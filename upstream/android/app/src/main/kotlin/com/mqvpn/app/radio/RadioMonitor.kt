// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app.radio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthTdscdma
import android.telephony.CellSignalStrengthWcdma
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only radio observation. Call start/stop from the owner's lifecycle and refresh after
 * READ_PHONE_STATE permission changes. Missing or redacted readings stay unknown.
 */
@Singleton
class RadioMonitor @Inject constructor(@ApplicationContext private val context: Context) {
    private val subscriptions = context.getSystemService(SubscriptionManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val _snapshots = MutableStateFlow(RadioSnapshot())
    val snapshots = _snapshots.asStateFlow()

    private data class Registration(
        val manager: TelephonyManager,
        val modern: TelephonyCallback? = null,
        val legacy: PhoneStateListener? = null,
        var signal: SignalStrength? = null,
        var service: ServiceState? = null,
        var displayOverride: Int? = null,
        var call: RadioCallState = RadioCallState.UNKNOWN,
        var signalAtMs: Long = 0,
        var serviceAtMs: Long = 0,
    )

    private val registrations = mutableMapOf<Int, Registration>()
    private var started = false
    private var subscriptionListenerRegistered = false
    private val subscriptionsListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() { refresh() }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        refresh()
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        if (subscriptionListenerRegistered) {
            try { subscriptions.removeOnSubscriptionsChangedListener(subscriptionsListener) }
            catch (_: SecurityException) { }
            subscriptionListenerRegistered = false
        }
        registrations.keys.toList().forEach(::unregister)
        _snapshots.value = RadioSnapshot()
    }

    @Synchronized
    fun refresh() {
        if (!started) return
        if (!hasPermission()) {
            if (subscriptionListenerRegistered) {
                try { subscriptions.removeOnSubscriptionsChangedListener(subscriptionsListener) }
                catch (_: SecurityException) { }
                subscriptionListenerRegistered = false
            }
            registrations.keys.toList().forEach(::unregister)
            _snapshots.value = RadioSnapshot()
            return
        }
        if (!subscriptionListenerRegistered) {
            subscriptionListenerRegistered = true
            try { subscriptions.addOnSubscriptionsChangedListener(subscriptionsListener) }
            catch (_: SecurityException) { subscriptionListenerRegistered = false }
        }
        val infos = try { subscriptions.activeSubscriptionInfoList.orEmpty() }
        catch (_: SecurityException) { emptyList() }
        val ids = infos.map { it.subscriptionId }.toSet()
        registrations.keys.filter { it !in ids }.toList().forEach(::unregister)
        infos.forEach { info ->
            if (info.subscriptionId !in registrations) register(info.subscriptionId)
        }
        val now = SystemClock.elapsedRealtime()
        val rows = infos.map { info ->
            val reg = registrations[info.subscriptionId]
            val manager = reg?.manager
            // Never infer data NR from an NSA display icon or a cached NR measurement.
            val rat = safe { manager?.dataNetworkType } ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
            val signal = reg?.signal
            val service = reg?.service
            val lte = if (Build.VERSION.SDK_INT >= 29)
                signal?.cellSignalStrengths?.filterIsInstance<CellSignalStrengthLte>()?.firstOrNull() else null
            val nr = if (Build.VERSION.SDK_INT >= 29)
                signal?.cellSignalStrengths?.filterIsInstance<CellSignalStrengthNr>()?.firstOrNull() else null
            val servingCell = servingCell(rat, signal)
            val level = if (Build.VERSION.SDK_INT >= 29)
                servingCell?.level?.takeIf { it in 0..4 } else signal?.level?.takeIf { it in 0..4 }
            val roaming = service?.roaming
            val currentCall = reg?.call?.takeIf { it != RadioCallState.UNKNOWN }
                ?: safe { manager?.callState }?.let(::callState) ?: RadioCallState.UNKNOWN
            SimRadioSnapshot(
                subscriptionId = info.subscriptionId,
                slotIndex = info.simSlotIndex,
                carrierName = info.carrierName?.toString().orEmpty(),
                registeredRat = rat,
                displayOverride = reg?.displayOverride,
                signalLevel = level,
                dbm = servingCell?.dbm?.takeIf { it in -160..-30 },
                lteRsrpDbm = lte?.rsrp?.takeIf { it in -150..-40 },
                nrSsRsrpDbm = nr?.ssRsrp?.takeIf { it in -150..-40 },
                inService = service?.state?.let { it == ServiceState.STATE_IN_SERVICE },
                roaming = roaming,
                callState = currentCall,
                updatedAtElapsedMs = if (service?.state == ServiceState.STATE_IN_SERVICE)
                    (reg?.signalAtMs ?: 0L) else (reg?.serviceAtMs ?: 0L),
            )
        }
        _snapshots.value = RadioSnapshot(
            permissionGranted = true,
            defaultDataSubscriptionId = SubscriptionManager.getDefaultDataSubscriptionId(),
            subscriptions = rows,
            sampledAtElapsedMs = now,
        )
    }

    private fun hasPermission() = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED

    private inline fun <T> safe(block: () -> T): T? =
        try { block() } catch (_: SecurityException) { null } catch (_: UnsupportedOperationException) { null }

    private fun callState(state: Int): RadioCallState = when (state) {
        TelephonyManager.CALL_STATE_IDLE -> RadioCallState.IDLE
        TelephonyManager.CALL_STATE_OFFHOOK, TelephonyManager.CALL_STATE_RINGING -> RadioCallState.ACTIVE
        else -> RadioCallState.UNKNOWN
    }

    private fun register(id: Int) {
        val manager = telephony.createForSubscriptionId(id)
        if (Build.VERSION.SDK_INT >= 31) {
            val callback = object : TelephonyCallback(),
                TelephonyCallback.SignalStrengthsListener,
                TelephonyCallback.ServiceStateListener,
                TelephonyCallback.DisplayInfoListener,
                TelephonyCallback.CallStateListener {
                override fun onSignalStrengthsChanged(value: SignalStrength) {
                    registrations[id]?.signal = value
                    registrations[id]?.signalAtMs = signalTime(value, fromCallback = true)
                    refresh()
                }
                override fun onServiceStateChanged(value: ServiceState) {
                    registrations[id]?.service = value
                    registrations[id]?.serviceAtMs = SystemClock.elapsedRealtime()
                    refresh()
                }
                override fun onDisplayInfoChanged(value: TelephonyDisplayInfo) {
                    registrations[id]?.displayOverride = value.overrideNetworkType
                    refresh()
                }
                override fun onCallStateChanged(state: Int) {
                    registrations[id]?.call = callState(state)
                    refresh()
                }
            }
            registrations[id] = initialRegistration(manager).copy(modern = callback)
            try {
                if (Build.VERSION.SDK_INT >= 33) manager.registerTelephonyCallback(
                    TelephonyManager.INCLUDE_LOCATION_DATA_NONE, context.mainExecutor, callback)
                else manager.registerTelephonyCallback(context.mainExecutor, callback)
            }
            catch (_: SecurityException) { unregister(id) }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                override fun onSignalStrengthsChanged(value: SignalStrength) {
                    registrations[id]?.signal = value
                    registrations[id]?.signalAtMs = signalTime(value, fromCallback = true)
                    refresh()
                }
                override fun onServiceStateChanged(value: ServiceState) {
                    registrations[id]?.service = value
                    registrations[id]?.serviceAtMs = SystemClock.elapsedRealtime()
                    refresh()
                }
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    registrations[id]?.call = callState(state)
                    refresh()
                }
            }
            registrations[id] = initialRegistration(manager).copy(legacy = listener)
            try {
                @Suppress("DEPRECATION")
                manager.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                    PhoneStateListener.LISTEN_SERVICE_STATE or PhoneStateListener.LISTEN_CALL_STATE)
            } catch (_: SecurityException) { unregister(id) }
        }
    }

    private fun initialRegistration(manager: TelephonyManager): Registration {
        val signal = if (Build.VERSION.SDK_INT >= 28) safe { manager.signalStrength } else null
        val service = if (Build.VERSION.SDK_INT >= 33)
            safe { manager.getServiceState(TelephonyManager.INCLUDE_LOCATION_DATA_NONE) }
        else safe { manager.serviceState }
        return Registration(manager, signal = signal, service = service,
            call = safe { manager.callState }?.let(::callState) ?: RadioCallState.UNKNOWN,
            signalAtMs = if (signal != null) signalTime(signal, fromCallback = false) else 0L,
            serviceAtMs = if (service != null) SystemClock.elapsedRealtime() else 0L)
    }

    private fun signalTime(value: SignalStrength, fromCallback: Boolean): Long {
        val now = SystemClock.elapsedRealtime()
        if (Build.VERSION.SDK_INT >= 30) {
            val measured = value.timestampMillis
            if (measured in 1..now) return measured
            return if (fromCallback) now else 0L
        }
        // Pre-30 exposes no modem timestamp; callback arrival is the best available clock.
        return now
    }

    private fun servingCell(rat: Int, signal: SignalStrength?): CellSignalStrength? {
        if (Build.VERSION.SDK_INT < 29 || signal == null) return null
        val cells = signal.cellSignalStrengths
        return when (rat) {
            TelephonyManager.NETWORK_TYPE_NR -> cells.firstOrNull { it is CellSignalStrengthNr }
            TelephonyManager.NETWORK_TYPE_LTE -> cells.firstOrNull { it is CellSignalStrengthLte }
            TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GSM -> cells.firstOrNull { it is CellSignalStrengthGsm }
            TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD ->
                cells.firstOrNull { it is CellSignalStrengthCdma }
            TelephonyManager.NETWORK_TYPE_TD_SCDMA ->
                cells.firstOrNull { it is CellSignalStrengthTdscdma }
            TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSPAP ->
                cells.firstOrNull { it is CellSignalStrengthWcdma }
            else -> null
        }
    }
    private fun unregister(id: Int) {
        val reg = registrations.remove(id) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31 && reg.modern != null)
                reg.manager.unregisterTelephonyCallback(reg.modern)
            else if (reg.legacy != null) {
                @Suppress("DEPRECATION")
                reg.manager.listen(reg.legacy, PhoneStateListener.LISTEN_NONE)
            }
        } catch (_: SecurityException) { }
    }
}












