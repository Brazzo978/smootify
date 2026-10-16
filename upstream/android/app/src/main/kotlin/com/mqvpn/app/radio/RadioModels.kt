// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app.radio

import android.telephony.TelephonyManager

enum class RadioCallState { UNKNOWN, IDLE, ACTIVE }

/** Display override is informational; only registeredRat is used for decisions. */
data class SimRadioSnapshot(
    val subscriptionId: Int,
    val slotIndex: Int,
    val carrierName: String,
    val registeredRat: Int = TelephonyManager.NETWORK_TYPE_UNKNOWN,
    val displayOverride: Int? = null,
    val signalLevel: Int? = null,
    val dbm: Int? = null,
    val lteRsrpDbm: Int? = null,
    val nrSsRsrpDbm: Int? = null,
    val inService: Boolean? = null,
    val roaming: Boolean? = null,
    val callState: RadioCallState = RadioCallState.UNKNOWN,
    val active: Boolean = true,
    val updatedAtElapsedMs: Long = 0L,
) {
    val generation: Int get() = when (registeredRat) {
        TelephonyManager.NETWORK_TYPE_NR -> 5
        TelephonyManager.NETWORK_TYPE_LTE -> 4
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> 3
        TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN, TelephonyManager.NETWORK_TYPE_GSM -> 2
        else -> 0
    }
}

data class RadioSnapshot(
    val permissionGranted: Boolean = false,
    val defaultDataSubscriptionId: Int = -1,
    val subscriptions: List<SimRadioSnapshot> = emptyList(),
    val sampledAtElapsedMs: Long = 0L,
)
