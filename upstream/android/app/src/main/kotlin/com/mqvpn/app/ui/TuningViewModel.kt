// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors
package com.mqvpn.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mqvpn.app.data.DemoSettings
import com.mqvpn.app.data.ServerControlClient
import com.mqvpn.app.data.SettingsRepository
import com.mqvpn.app.service.MyVpnService
import com.mqvpn.sdk.core.MqvpnManager
import com.mqvpn.sdk.core.model.MqvpnState
import com.mqvpn.sdk.network.NetworkMonitor
import com.mqvpn.sdk.network.NetworkPath
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Retains unsaved, non-secret UI drafts while navigating between destinations. */
@Singleton
class TuningDraftStore @Inject constructor() {
    var tuning: DemoSettings? = null
    var connections: DemoSettings? = null
}

@HiltViewModel
class TuningViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SettingsRepository,
    private val manager: MqvpnManager,
    private val draftStore: TuningDraftStore,
) : ViewModel() {
    private val _loaded = MutableStateFlow<DemoSettings?>(null)
    val loaded = _loaded.asStateFlow()
    private val _available = MutableStateFlow<List<NetworkPath>>(emptyList())
    val available = _available.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow("Connect the tunnel to read or sync the server.")
    val message = _message.asStateFlow()
    private val _remote = MutableStateFlow<JSONObject?>(null)
    val remote = _remote.asStateFlow()
    val vpnState = manager.vpnState
    /** Native path state, distinct from networks currently visible to Android. */
    val paths = manager.paths

    init {
        refreshNetworks()
        viewModelScope.launch { repository.settings.collect { _loaded.value = it } }
    }

    fun tuningDraftSnapshot(): DemoSettings? = draftStore.tuning
    fun connectionsDraftSnapshot(): DemoSettings? = draftStore.connections
    fun retainTuningDraft(draft: DemoSettings) {
        draftStore.tuning = draft.copy(authKey = "", serverAddress = "", tlsServerName = "")
    }
    fun retainConnectionsDraft(draft: DemoSettings) {
        draftStore.connections = draft.copy(authKey = "", serverAddress = "", tlsServerName = "")
    }
    fun clearTuningDraft() { draftStore.tuning = null }
    fun clearConnectionsDraft() { draftStore.connections = null }

    fun refreshNetworks() {
        _available.value = NetworkMonitor(context).snapshotPaths().sortedBy { it.name }
    }

    private fun mergeTuning(latest: DemoSettings, draft: DemoSettings) = latest.copy(
        scheduler = draft.scheduler,
        congestionControl = draft.congestionControl,
        multipathEnabled = draft.multipathEnabled,
        hybridEnabled = draft.hybridEnabled,
        hybridTcpMode = draft.hybridTcpMode,
        reorderEnabled = draft.reorderEnabled,
        reorderProfile = draft.reorderProfile,
        reorderPorts = draft.reorderPorts,
        serverMtu = draft.serverMtu,
        controlAddress = draft.controlAddress,
        controlPort = draft.controlPort,
    )

    private fun mergeConnections(latest: DemoSettings, draft: DemoSettings) = latest.copy(
        multipathEnabled = draft.multipathEnabled,
        cellularPathEnabled = draft.cellularPathEnabled,
        wifiPathEnabled = draft.wifiPathEnabled,
        ethernetPathEnabled = draft.ethernetPathEnabled,
        bluetoothPathEnabled = draft.bluetoothPathEnabled,
        excludedSubscriptionIds = draft.excludedSubscriptionIds.distinct().sorted(),
        excludedInterfaces = draft.excludedInterfaces.distinct().sorted(),
    )

    private fun validate(draft: DemoSettings, reconnect: Boolean) {
        require(draft.cellularPathEnabled || draft.wifiPathEnabled ||
            draft.ethernetPathEnabled || draft.bluetoothPathEnabled) {
            "Select at least one connection type."
        }
        require(draft.serverMtu in 1280..9000) { "MTU: enter a value from 1280 to 9000." }
        if (reconnect) require(draft.isValid()) { "Set up server and Auth Key first." }
    }

    private fun action(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _message.value = "Operation incomplete: ${e.message}" }
            finally { _busy.value = false }
        }
    }

    fun readServer(draft: DemoSettings) = action {
        check(repository.settings.first().serverControlAllowed) { "This profile cannot administer the server." }
        check(manager.vpnState.value is MqvpnState.Connected) { "Connect the tunnel first." }
        _remote.value = withContext(Dispatchers.IO) { ServerControlClient.request(draft) }
        _message.value = "Values read from server. Tap Import to copy them into the form."
    }

    /** Saves only tuning fields over the latest repository value, preserving credentials and paths. */
    fun save(draft: DemoSettings, reconnect: Boolean) = action {
        val previous = repository.settings.first()
        val target = mergeTuning(previous, draft)
        validate(target, reconnect)
        saveClient(target, previous, reconnect, "Client engine") { mergeTuning(it, draft) }
    }

    fun savePreset(preset: TuningPreset, reconnect: Boolean) = action {
        val previous = repository.settings.first()
        val target = preset.applyTo(previous)
        validate(target, reconnect)
        saveClient(target, previous, reconnect, "Preset ${preset.label}") { preset.applyTo(it) }
    }

    /** Saves only path selection; other settings may have changed on another screen. */
    fun saveConnections(draft: DemoSettings, reconnect: Boolean) = action {
        val previous = repository.settings.first()
        val target = mergeConnections(previous, draft)
        validate(target, reconnect)
        saveClient(target, previous, reconnect, "Connections") { mergeConnections(it, draft) }
    }

    private suspend fun saveClient(
        target: DemoSettings,
        previous: DemoSettings,
        reconnect: Boolean,
        label: String,
        mergeLatest: (DemoSettings) -> DemoSettings,
    ) {
        if (reconnect) {
            check(manager.vpnState.value is MqvpnState.Connected) { "Connect the tunnel first." }
            try {
                reconnect(target)
                repository.save(mergeLatest(repository.settings.first()))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                runCatching { reconnect(previous) }
                throw IllegalStateException("${e.message}. Previous settings kept.", e)
            }
            _message.value = "$label applied: tunnel reconnected. Server values unchanged."
        } else {
            repository.save(mergeLatest(repository.settings.first()))
            _message.value = "$label saved on the phone. It will be used on the next connection; server unchanged."
        }
    }

    private suspend fun reconnect(draft: DemoSettings, serverRestart: Boolean = false) {
        check(manager.prepareVpn() == null) { "Open the tunnel and grant Android VPN permission first." }
        manager.disconnectAndAwait()
        if (serverRestart) delay(3500)
        manager.connect(draft.toMqvpnConfig(), MyVpnService::class.java)
        withTimeout(30000) {
            manager.vpnState.first { it is MqvpnState.Connected || it is MqvpnState.Error }
        }.let { check(it is MqvpnState.Connected) { "Tunnel did not reconnect: $it" } }
    }

    fun sync(draft: DemoSettings) = action {
        val previous = repository.settings.first()
        check(previous.serverControlAllowed) { "This profile cannot administer the server." }
        val target = mergeTuning(previous, draft)
        validate(target, reconnect = true)
        check(manager.vpnState.value is MqvpnState.Connected) { "Connect the tunnel with current values first." }
        _message.value = "Sending authenticated configuration to server..."
        val desired = JSONObject()
            .put("scheduler", ServerControlClient.schedulerWire(target.scheduler))
            .put("cc", target.congestionControl.lowercase())
            .put("mtu", target.serverMtu)
        val accepted = try {
            withContext(Dispatchers.IO) { ServerControlClient.request(target, desired) }
        } catch (e: IOException) {
            // A lost SET reply is ambiguous when the server restarts. Confirm with GET.
            _message.value = "SET response interrupted: reconnecting and checking actual status..."
            null
        }
        if (accepted != null) _remote.value = accepted
        try {
            _message.value = "Configuration sent. Reconnecting and checking server values..."
            reconnect(target, accepted?.optBoolean("requiresReconnect", true) ?: true)
            var last: Exception? = null
            repeat(5) {
                try {
                    val status = withContext(Dispatchers.IO) { ServerControlClient.request(target) }
                    _remote.value = status
                    val applied = status.optJSONObject("applied")
                    check(status.optString("status") == "applied" && applied != null &&
                        applied.optString("scheduler") == desired.getString("scheduler") &&
                        applied.optString("cc") == desired.getString("cc") &&
                        applied.optInt("mtu") == target.serverMtu) {
                        "Values not confirmed by server: ${status.optString("status")}"
                    }
                    // Credentials and connection choices may have changed while the VPS restarted.
                    repository.save(mergeTuning(repository.settings.first(), draft))
                    _message.value = "Sync verified: client and server use ${desired.getString("scheduler")} / ${desired.getString("cc")}; server MTU ${target.serverMtu}."
                    return@action
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { last = e; delay(1500) }
            }
            error("Configuration sent, but final verification failed: ${last?.message}")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            runCatching { reconnect(previous) }
            throw IllegalStateException("${e.message}. Previous client values kept; read the server again before retrying.", e)
        }
    }
}
