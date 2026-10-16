// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app.radio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.mqvpn.app.simcontrol.ShizukuSimBridge
import com.mqvpn.app.simcontrol.ShizukuSimState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import javax.inject.Inject
import javax.inject.Singleton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AutoSimOptions(val enabled: Boolean = false, val independent: Boolean = true,
    val idleSeconds: Int = 120, val stableSeconds: Int = 20,
    val urgentSeconds: Int = 8, val minimumHoldSeconds: Int = 120, val allowRoaming: Boolean = false)
data class RadioControlState(val vpnSession: Boolean = false, val standaloneRunning: Boolean = false,
    val idlePaused: Boolean = false, val switching: Boolean = false,
    val message: String = "Automatic selection off.", val events: List<String> = emptyList())

/** Owns observation while the screen, VPN session, or independent service is active. */
@Singleton
class RadioCoordinator @Inject constructor(@ApplicationContext private val context: Context,
    private val monitor: RadioMonitor, private val bridge: ShizukuSimBridge) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("auto-data-sim", Context.MODE_PRIVATE)
    private val power = context.getSystemService(PowerManager::class.java)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _options = MutableStateFlow(AutoSimOptions(enabled = prefs.getBoolean("enabled", false),
        independent = prefs.getBoolean("independent", true), idleSeconds = prefs.getInt("idle", 120).coerceIn(30, 3600),
        stableSeconds = prefs.getInt("stable", 20), urgentSeconds = prefs.getInt("urgent", 8),
        minimumHoldSeconds = prefs.getInt("hold", 120), allowRoaming = prefs.getBoolean("roaming", false)))
    val options = _options.asStateFlow()
    private val _state = MutableStateFlow(RadioControlState())
    val state = _state.asStateFlow()
    val radio = monitor.snapshots
    val shizuku = bridge.state
    private var uiOwners = 0
    private var session = false
    private var standaloneRunning = false
    private var powerReceiverRegistered = false
    private var lastTrafficBytes: Long? = null
    private var lastTrafficAtMs = SystemClock.elapsedRealtime()
    private var trafficWindowStartMs = lastTrafficAtMs
    private var trafficWindowBytes = 0L
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                lastTrafficAtMs = SystemClock.elapsedRealtime()
                trafficWindowStartMs = lastTrafficAtMs
                trafficWindowBytes = 0
            }
            wake.trySend(Unit)
        }
    }
    private var ticker: Job? = null
    private var policy = newPolicy()
    private var observedDefault: Int? = null
    private var retryAfterElapsedMs = 0L
    private var lastSwitchEpochMs = prefs.getLong("last_switch_epoch", 0)
    private var lastSwitchSubId = prefs.getInt("last_switch_sub", -1)

    private fun newPolicy() = _options.value.let { SimSwitchPolicy(SimSwitchPolicy.Config(
        enabled = it.enabled, candidateStableMs = it.stableSeconds * 1000L,
        urgentStableMs = it.urgentSeconds * 1000L, minHoldMs = it.minimumHoldSeconds * 1000L,
        allowRoaming = it.allowRoaming)) }

    fun attachUi() { scope.launch { uiOwners++; reconcile() } }
    fun detachUi() { scope.launch { uiOwners = (uiOwners - 1).coerceAtLeast(0); reconcile() } }
    fun startSession() { scope.launch { session = true; reconcile() } }
    fun stopSession() { scope.launch { session = false; reconcile() } }
    fun standaloneServiceStarted() { scope.launch { standaloneRunning = true; reconcile() } }
    fun standaloneServiceStopped() { scope.launch { standaloneRunning = false; reconcile() } }
    fun stopFromNotification() { saveOptions(options.value.copy(enabled = false)) }
    fun refresh() { scope.launch { reconcile(); monitor.refresh(); tick() } }
    fun requestShizukuPermission() {
        runCatching { bridge.requestPermission() }.onFailure { message("Shizuku: " + it.message) }
    }

    /** Foreground start is allowed only from a deliberate UI enable or mode change. */
    fun saveOptions(value: AutoSimOptions, explicitUiAction: Boolean = false) {
        require(value.stableSeconds in 5..120 && value.urgentSeconds in 3..60 &&
            value.minimumHoldSeconds in 30..900 && value.idleSeconds in 30..3600)
        val granted = radio.value.permissionGranted && shizuku.value == ShizukuSimState.Ready
        if (value.enabled && !options.value.enabled && !granted) {
            message("Phone permission and authorized Shizuku are required to enable this.")
            return
        }
        val previous = options.value
        _options.value = value
        prefs.edit().putBoolean("enabled", value.enabled)
            .putBoolean("independent", value.independent).putInt("idle", value.idleSeconds)
            .putInt("stable", value.stableSeconds).putInt("urgent", value.urgentSeconds)
            .putInt("hold", value.minimumHoldSeconds).putBoolean("roaming", value.allowRoaming).apply()
        policy = newPolicy()
        seedCooldown()
        val standaloneWanted = value.enabled && value.independent
        if (!standaloneWanted && previous.enabled && previous.independent) {
            context.stopService(Intent(context, RadioControlService::class.java))
        }
        if (standaloneWanted && explicitUiAction && !standaloneRunning) startStandaloneFromUi()
        event(when {
            !value.enabled -> "Automatic selection disabled."
            value.independent -> "Automatic selection enabled even without VPN."
            else -> "Automatic selection enabled for MQVPN sessions."
        })
        scope.launch { reconcile() }
    }

    fun startStandaloneFromUi() {
        if (!options.value.enabled || !options.value.independent) return
        try {
            context.startForegroundService(Intent(context, RadioControlService::class.java))
        } catch (e: Exception) {
            message("Independent monitor did not start: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    fun switchManually(target: Int) { scope.launch { switchTo(target, automatic = false) } }

    private fun reconcile() {
        _state.update { it.copy(vpnSession = session, standaloneRunning = standaloneRunning) }
        val needed = uiOwners > 0 || (session && options.value.enabled) ||
            (standaloneRunning && options.value.enabled && options.value.independent) || state.value.switching
        if (needed) {
            monitor.start()
            bridge.start()
            registerPowerReceiver()
            if (ticker?.isActive != true) {
                lastTrafficAtMs = SystemClock.elapsedRealtime()
                trafficWindowStartMs = lastTrafficAtMs
                trafficWindowBytes = 0
                ticker = scope.launch { tickerLoop() }
            }
        } else {
            ticker?.cancel()
            ticker = null
            unregisterPowerReceiver()
            monitor.stop()
            bridge.stop()
            _state.update { it.copy(idlePaused = false) }
            message(if (options.value.enabled) "Automatic selection waiting for the independent monitor or an MQVPN session."
                else "Automatic selection off.")
        }
    }

    private fun registerPowerReceiver() {
        if (powerReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(context, powerReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        powerReceiverRegistered = true
    }

    private fun unregisterPowerReceiver() {
        if (!powerReceiverRegistered) return
        context.unregisterReceiver(powerReceiver)
        powerReceiverRegistered = false
    }

    private suspend fun tickerLoop() = coroutineScope {
        val passiveUpdates = launch {
            radio.drop(1).collect { wake.trySend(Unit) }
        }
        try {
            while (isActive) {
                val paused = shouldPauseForIdle()
                if (paused != state.value.idlePaused) {
                    Log.i("MqvpnRadio", if (paused) "Idle: radio polling paused" else "Active: radio polling resumed")
                    if (paused) {
                        policy = newPolicy()
                        seedCooldown()
                    }
                }
                _state.update { it.copy(idlePaused = paused) }
                if (paused) {
                    message("Phone idle: radio checks paused. Android may delay the next switch.")
                    while (wake.tryReceive().isSuccess) { }
                    if (!shouldPauseForIdle()) continue
                    withTimeoutOrNull(60_000) { wake.receive() }
                    continue
                }
                monitor.refresh()
                tick()
                delay(2_000)
            }
        } finally {
            passiveUpdates.cancel()
        }
    }

    private fun shouldPauseForIdle(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx >= 0 || tx >= 0) {
            val total = rx.coerceAtLeast(0) + tx.coerceAtLeast(0)
            val previous = lastTrafficBytes
            if (previous != null && total >= previous) trafficWindowBytes += total - previous
            lastTrafficBytes = total
            val windowMs = now - trafficWindowStartMs
            if (windowMs >= 10_000L ||
                (state.value.idlePaused && trafficWindowBytes >= 16_384L)) {
                val meaningfulBytes = 16_384L * ((windowMs + 9_999L) / 10_000L).coerceAtLeast(1)
                if (trafficWindowBytes >= meaningfulBytes) lastTrafficAtMs = now
                trafficWindowStartMs = now
                trafficWindowBytes = 0
            }
        }
        return power.isDeviceIdleMode ||
            (!power.isInteractive && now - lastTrafficAtMs >= options.value.idleSeconds * 1_000L)
    }
    private fun seedCooldown() {
        if (lastSwitchSubId >= 0 && lastSwitchEpochMs > 0) {
            val age = (System.currentTimeMillis() - lastSwitchEpochMs).coerceAtLeast(0)
            policy.onSwitchConfirmed(lastSwitchSubId, SystemClock.elapsedRealtime() - age)
        }
    }
    private suspend fun tick() {
        if (state.value.switching) return
        val snapshot = radio.value
        val now = SystemClock.elapsedRealtime()
        val current = snapshot.defaultDataSubscriptionId
        if (current >= 0 && observedDefault != current) {
            if (observedDefault != null || (lastSwitchSubId >= 0 && lastSwitchSubId != current)) {
                recordSwitch(current)
                event("Data SIM changed externally: ${label(current)}. Waiting for the minimum interval before another switch.")
            } else seedCooldown()
            observedDefault = current
        }
        if (!options.value.enabled) { message("Automatic selection is off. You can inspect radio status or choose a SIM manually."); return }
        if (!session && !(options.value.independent && standaloneRunning)) {
            message("Automatic selection waiting for the independent monitor or an MQVPN session.")
            return
        }
        if (shizuku.value != ShizukuSimState.Ready) { bridge.start(); message("Automatic selection paused: Shizuku not ready."); return }
        if (now < retryAfterElapsedMs) { message("Previous switch unconfirmed: retrying in ${(retryAfterElapsedMs-now+999)/1000} s."); return }
        val decision = policy.evaluate(snapshot, now)
        if (decision.candidateSubscriptionId != null) switchTo(decision.candidateSubscriptionId, automatic = true)
        else message(reasonText(decision.reason) + if (decision.remainingMs > 0) " · ${(decision.remainingMs + 999) / 1000} s" else "")
    }
    private suspend fun switchTo(target: Int, automatic: Boolean) {
        if (state.value.switching) return
        if (shizuku.value != ShizukuSimState.Ready) { message("Authorize and start Shizuku before switching."); return }
        val snapshot = radio.value
        if (snapshot.subscriptions.none { it.subscriptionId == target && it.active }) { message("SIM inactive or unavailable."); return }
        if (snapshot.subscriptions.any { it.callState == RadioCallState.ACTIVE }) { message("Switching paused during a call."); return }
        if (target == snapshot.defaultDataSubscriptionId) { message("${label(target)} is already the default data SIM."); return }
        _state.update { it.copy(switching = true, message = "Switching data SIM to ${label(target)}…") }
        event("${if (automatic) "Automatic" else "Manual"}: requested data SIM ${label(target)}.")
        try {
            bridge.setDefaultDataSubId(target)
            withTimeout(20_000) {
                while (bridge.getDefaultDataSubId() != target) delay(500)
            }
            recordSwitch(target); observedDefault = target; monitor.refresh()
            event("Confirmed data SIM ${label(target)}. Waiting for Android to make its Internet path available.")
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            retryAfterElapsedMs = SystemClock.elapsedRealtime() + 60_000
            event("Switch not confirmed in time. Check the data SIM in Android settings.")
        } catch (e: Exception) {
            retryAfterElapsedMs = SystemClock.elapsedRealtime() + 60_000
            event("Switch failed: ${e.message}")
        } finally {
            _state.update { it.copy(switching = false) }; reconcile()
        }
    }
    private fun recordSwitch(target: Int) {
        lastSwitchEpochMs = System.currentTimeMillis(); lastSwitchSubId = target
        prefs.edit().putLong("last_switch_epoch", lastSwitchEpochMs).putInt("last_switch_sub", target).apply()
        policy.onSwitchConfirmed(target, SystemClock.elapsedRealtime())
    }
    private fun label(id: Int) = radio.value.subscriptions.firstOrNull { it.subscriptionId == id }
        ?.let { "SIM ${it.slotIndex + 1} ${it.carrierName}" } ?: "subscription $id"
    private fun message(text: String) { _state.update { it.copy(message = text) } }
    private fun event(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(Date())
        _state.update { it.copy(message = text, events = (listOf("$time · $text") + it.events).take(30)) }
    }
}
private fun reasonText(reason: String): String = when (reason) {
    "disabled" -> "Automatic selection off"
    "phone permission missing" -> "Phone permission missing"
    "default data SIM unknown", "default data SIM unavailable" -> "Default data SIM unavailable"
    "no second active SIM" -> "No second active SIM"
    "call state active or unknown" -> "Call in progress or call status unavailable"
    "radio reading stale" -> "Waiting for updated radio measurements"
    "current radio data unavailable" -> "Current SIM measurements unavailable"
    "no better healthy SIM" -> "Keeping current SIM: no clearly better alternative"
    "candidate stabilizing or hold" -> "Advantage not yet confirmed or minimum interval not reached"
    else -> reason
}
