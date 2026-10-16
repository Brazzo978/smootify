// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app.radio

import android.telephony.TelephonyManager
import org.junit.Assert.*
import org.junit.Test

class SimSwitchPolicyTest {
    private fun sim(id: Int, rat: Int, level: Int?, at: Long, service: Boolean = true,
                    call: RadioCallState = RadioCallState.IDLE, roaming: Boolean = false,
                    rsrp: Int? = null, active: Boolean = true) = SimRadioSnapshot(
        subscriptionId = id, slotIndex = id - 1, carrierName = "SIM$id",
        registeredRat = rat, signalLevel = level, inService = service, roaming = roaming,
        callState = call, lteRsrpDbm = rsrp, active = active, updatedAtElapsedMs = at,
    )
    private fun snapshot(first: SimRadioSnapshot, second: SimRadioSnapshot? = null,
                         default: Int = 1, permission: Boolean = true) =
        RadioSnapshot(permission, default, listOfNotNull(first, second))

    @Test fun transientTwoGDoesNotSwitch() {
        val policy = SimSwitchPolicy(SimSwitchPolicy.Config(enabled = true))
        val poor = sim(1, TelephonyManager.NETWORK_TYPE_EDGE, 1, 1000)
        val good = sim(2, TelephonyManager.NETWORK_TYPE_LTE, 3, 1000)
        assertNull(policy.evaluate(snapshot(poor, good), 1000).candidateSubscriptionId)
        assertNull(policy.evaluate(snapshot(poor, good), 7000).candidateSubscriptionId)
        val recovered = poor.copy(registeredRat = TelephonyManager.NETWORK_TYPE_LTE, signalLevel = 3)
        assertNull(policy.evaluate(snapshot(recovered, good), 7500).candidateSubscriptionId)
    }

    @Test fun sustainedTwoGToLteAfterUrgentWindow() {
        val policy = SimSwitchPolicy(SimSwitchPolicy.Config(enabled = true))
        val current = sim(1, TelephonyManager.NETWORK_TYPE_EDGE, 1, 1000)
        val other = sim(2, TelephonyManager.NETWORK_TYPE_LTE, 3, 1000)
        assertEquals(8000L, policy.evaluate(snapshot(current, other), 1000).remainingMs)
        assertNull(policy.evaluate(snapshot(current, other), 8999).candidateSubscriptionId)
        assertEquals(2, policy.evaluate(snapshot(current, other), 9000).candidateSubscriptionId)
    }

    @Test fun poorLteNeedsSustainedComparableSignalAdvantage() {
        val policy = SimSwitchPolicy(SimSwitchPolicy.Config(enabled = true))
        val current = sim(1, TelephonyManager.NETWORK_TYPE_LTE, 2, 1000, rsrp = -113)
        val other = sim(2, TelephonyManager.NETWORK_TYPE_LTE, 3, 1000, rsrp = -100)
        assertNull(policy.evaluate(snapshot(current, other), 1000).candidateSubscriptionId)
        assertEquals(2, policy.evaluate(snapshot(current, other), 21000).candidateSubscriptionId)
    }

    @Test fun noSecondSimOrRedactedDataBlocks() {
        val policy = SimSwitchPolicy(SimSwitchPolicy.Config(enabled = true))
        val current = sim(1, TelephonyManager.NETWORK_TYPE_EDGE, 1, 1000)
        val good = sim(2, TelephonyManager.NETWORK_TYPE_LTE, 3, 1000)
        assertNull(policy.evaluate(snapshot(current), 1000).candidateSubscriptionId)
        assertNull(policy.evaluate(snapshot(current, good, permission = false), 1000).candidateSubscriptionId)
        assertNull(policy.evaluate(snapshot(current, good.copy(signalLevel = null)), 1000).candidateSubscriptionId)
        assertNull(policy.evaluate(snapshot(current, good.copy(roaming = true)), 1000).candidateSubscriptionId)
        assertNull(policy.evaluate(snapshot(current, good.copy(active = false)), 1000).candidateSubscriptionId)
    }

    @Test fun activeOrUnknownCallBlocks() {
        val policy = SimSwitchPolicy(SimSwitchPolicy.Config(enabled = true))
        val current = sim(1, TelephonyManager.NETWORK_TYPE_EDGE, 1, 1000)
        val good = sim(2, TelephonyManager.NETWORK_TYPE_LTE, 3, 1000)
        assertNull(policy.evaluate(snapshot(current.copy(callState = RadioCallState.ACTIVE), good), 1000).candidateSubscriptionId)
        assertNull(policy.evaluate(snapshot(current, good.copy(callState = RadioCallState.UNKNOWN)), 1000).candidateSubscriptionId)
    }

    @Test fun cooldownAndStaleReadingsBlock() {
        val policy = SimSwitchPolicy(SimSwitchPolicy.Config(enabled = true))
        val current = sim(1, TelephonyManager.NETWORK_TYPE_EDGE, 1, 1000)
        val good = sim(2, TelephonyManager.NETWORK_TYPE_LTE, 3, 1000)
        policy.onSwitchConfirmed(1, 1000)
        assertNull(policy.evaluate(snapshot(current, good), 9000).candidateSubscriptionId)
        assertEquals(2, policy.evaluate(snapshot(current.copy(updatedAtElapsedMs = 110000),
            good.copy(updatedAtElapsedMs = 110000)), 121000).candidateSubscriptionId)
        assertNull(policy.evaluate(snapshot(current, good), 122000).candidateSubscriptionId)
    }

    @Test fun outOfServiceWithoutSignalCanRecover() {
        val policy = SimSwitchPolicy(SimSwitchPolicy.Config(enabled = true))
        val current = sim(1, TelephonyManager.NETWORK_TYPE_UNKNOWN, null, 1000, service = false)
        val other = sim(2, TelephonyManager.NETWORK_TYPE_LTE, 3, 1000)
        assertNull(policy.evaluate(snapshot(current, other), 1000).candidateSubscriptionId)
        assertEquals(2, policy.evaluate(snapshot(current, other), 9000).candidateSubscriptionId)
    }

    @Test fun oneBarToTwoBarsIsUrgent() {
        val policy = SimSwitchPolicy(SimSwitchPolicy.Config(enabled = true))
        val current = sim(1, TelephonyManager.NETWORK_TYPE_LTE, 1, 1000)
        val other = sim(2, TelephonyManager.NETWORK_TYPE_LTE, 2, 1000)
        assertNull(policy.evaluate(snapshot(current, other), 1000).candidateSubscriptionId)
        assertEquals(2, policy.evaluate(snapshot(current, other), 9000).candidateSubscriptionId)
    }

    @Test fun unknownServingRatDoesNotCountAsTwoG() {
        val policy = SimSwitchPolicy(SimSwitchPolicy.Config(enabled = true))
        val unknown = sim(1, TelephonyManager.NETWORK_TYPE_UNKNOWN, 1, 1000)
        val lte = sim(2, TelephonyManager.NETWORK_TYPE_LTE, 3, 1000)
        assertNull(policy.evaluate(snapshot(unknown, lte), 1000).candidateSubscriptionId)
        assertNull(policy.evaluate(snapshot(unknown, lte), 21000).candidateSubscriptionId)
    }

    @Test fun poorLteCanRecoverToHealthyServingNr() {
        val policy = SimSwitchPolicy(SimSwitchPolicy.Config(enabled = true))
        val lte = sim(1, TelephonyManager.NETWORK_TYPE_LTE, 1, 1000)
        val nr = sim(2, TelephonyManager.NETWORK_TYPE_NR, 2, 1000)
        assertNull(policy.evaluate(snapshot(lte, nr), 1000).candidateSubscriptionId)
        assertEquals(2, policy.evaluate(snapshot(lte, nr), 9000).candidateSubscriptionId)
    }

    @Test fun smallCrossRatBarDifferenceDoesNotTrigger() {
        val policy = SimSwitchPolicy(SimSwitchPolicy.Config(enabled = true))
        val lte = sim(1, TelephonyManager.NETWORK_TYPE_LTE, 2, 1000)
        val nr = sim(2, TelephonyManager.NETWORK_TYPE_NR, 3, 1000)
        assertNull(policy.evaluate(snapshot(lte, nr), 21000).candidateSubscriptionId)
    }
    @Test fun disabledByDefault() {
        val current = sim(1, TelephonyManager.NETWORK_TYPE_EDGE, 1, 1000)
        val other = sim(2, TelephonyManager.NETWORK_TYPE_LTE, 3, 1000)
        assertEquals("disabled", SimSwitchPolicy().evaluate(snapshot(current, other), 9000).reason)
    }
    @Test fun nsaIconCannotUpgradeServingLte() {
        val policy = SimSwitchPolicy(SimSwitchPolicy.Config(enabled = true))
        val current = sim(1, TelephonyManager.NETWORK_TYPE_LTE, 3, 1000)
        val other = sim(2, TelephonyManager.NETWORK_TYPE_LTE, 3, 1000)
            .copy(displayOverride = 5, nrSsRsrpDbm = -75)
        assertNull(policy.evaluate(snapshot(current, other), 21000).candidateSubscriptionId)
    }
}





