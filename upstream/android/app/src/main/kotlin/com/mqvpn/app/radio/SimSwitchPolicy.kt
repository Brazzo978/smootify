// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app.radio

/** Decision engine only. The caller owns the platform switch and acknowledges its result. */
class SimSwitchPolicy(private val config: Config = Config()) {
    data class Config(
        val enabled: Boolean = false,
        val candidateStableMs: Long = 20_000,
        val urgentStableMs: Long = 8_000,
        val minHoldMs: Long = 120_000,
        val maxSampleAgeMs: Long = 30_000,
        val allowRoaming: Boolean = false,
    )

    data class SwitchDecision(
        val candidateSubscriptionId: Int? = null,
        val reason: String,
        val remainingMs: Long = 0,
    )

    private var candidate: Int? = null
    private var candidateSinceMs = 0L
    private var lastSwitchMs: Long? = null
    private var observedDefault: Int? = null

    fun onSwitchConfirmed(newDefaultSubscriptionId: Int, atElapsedMs: Long) {
        observedDefault = newDefaultSubscriptionId
        lastSwitchMs = atElapsedMs
        clearCandidate()
    }

    fun onExternalDataSubscriptionChanged(newDefaultSubscriptionId: Int) {
        observedDefault = newDefaultSubscriptionId
        lastSwitchMs = null
        clearCandidate()
    }

    fun evaluate(snapshot: RadioSnapshot, nowElapsedMs: Long): SwitchDecision {
        fun reject(reason: String): SwitchDecision { clearCandidate(); return SwitchDecision(reason = reason) }
        if (!config.enabled) return reject("disabled")
        if (!snapshot.permissionGranted) return reject("phone permission missing")
        val currentId = snapshot.defaultDataSubscriptionId
        if (currentId < 0) return reject("default data SIM unknown")
        if (observedDefault != null && observedDefault != currentId) onExternalDataSubscriptionChanged(currentId)
        observedDefault = currentId
        val sims = snapshot.subscriptions.filter { it.active }
        val current = sims.singleOrNull { it.subscriptionId == currentId }
            ?: return reject("default data SIM unavailable")
        if (sims.size < 2) return reject("no second active SIM")
        if (sims.any { it.callState != RadioCallState.IDLE }) return reject("call state active or unknown")
        if (sims.any { it.updatedAtElapsedMs <= 0 || nowElapsedMs < it.updatedAtElapsedMs ||
                    nowElapsedMs - it.updatedAtElapsedMs > config.maxSampleAgeMs }) return reject("radio reading stale")
        if (current.inService == null || (current.inService == true && current.signalLevel == null))
            return reject("current radio data unavailable")
        val alternatives = sims.filter { it.subscriptionId != currentId && it.inService == true &&
            it.signalLevel != null && it.signalLevel >= 2 && it.roaming != null &&
            (config.allowRoaming || it.roaming == false) }
        val best = alternatives.mapNotNull { alternative ->
            val urgent = current.inService == false || (current.signalLevel ?: 4) <= 1
            val betterGeneration = current.inService == false ||
                (current.generation == 2 && alternative.generation >= 4)
            val healthyRadioRecovery = current.inService == true && current.generation >= 4 &&
                alternative.generation >= 4 && (current.signalLevel ?: 4) <= 1 &&
                (alternative.signalLevel ?: 0) >= 2
            val sameRatSignalAdvantage = current.inService == true && current.generation >= 4 &&
                alternative.registeredRat == current.registeredRat &&
                ((alternative.signalLevel ?: 0) - (current.signalLevel ?: 0) >= 2 ||
                (current.registeredRat == android.telephony.TelephonyManager.NETWORK_TYPE_LTE &&
                    current.lteRsrpDbm != null && alternative.lteRsrpDbm != null &&
                    alternative.lteRsrpDbm - current.lteRsrpDbm >= 10))
            val betterSignal = healthyRadioRecovery || sameRatSignalAdvantage
            if (betterGeneration || betterSignal) alternative to urgent else null
        }.maxWithOrNull(compareBy<Pair<SimRadioSnapshot, Boolean>> { it.first.generation }
            .thenBy { it.first.signalLevel ?: -1 }) ?: return reject("no better healthy SIM")
        val target = best.first.subscriptionId
        if (candidate != target) { candidate = target; candidateSinceMs = nowElapsedMs }
        val stableRequired = if (best.second) config.urgentStableMs else config.candidateStableMs
        val stableRemaining = (stableRequired - (nowElapsedMs - candidateSinceMs)).coerceAtLeast(0)
        val holdRemaining = lastSwitchMs?.let { (config.minHoldMs - (nowElapsedMs - it)).coerceAtLeast(0) } ?: 0L
        val remaining = maxOf(stableRemaining, holdRemaining)
        return if (remaining > 0) SwitchDecision(reason = "candidate stabilizing or hold", remainingMs = remaining)
        else SwitchDecision(candidateSubscriptionId = target, reason = "better serving radio")
    }

    private fun clearCandidate() { candidate = null; candidateSinceMs = 0L }
}



