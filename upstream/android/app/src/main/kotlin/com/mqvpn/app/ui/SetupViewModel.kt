// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mqvpn.app.data.DemoSettings
import com.mqvpn.app.data.SettingsRepository
import com.mqvpn.app.data.SetupMode
import com.mqvpn.app.data.SetupPreferences
import com.mqvpn.app.radio.RadioCoordinator
import com.mqvpn.sdk.core.MqvpnManager
import com.mqvpn.sdk.core.model.MqvpnState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupDraft(
    val loaded: Boolean = false,
    val step: Int = 0,
    val mode: SetupMode = SetupMode.VPN_AND_SIM,
    val serverAddress: String = "",
    val serverPort: String = "443",
    val authKey: String = "",
    val tlsServerName: String = "",
    val insecure: Boolean = false,
    val wifi: Boolean = true,
    val mobile: Boolean = true,
    val ethernet: Boolean = true,
    val excludeAndroidAuto: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
) {
    val usesVpn: Boolean get() = mode != SetupMode.SIM_ONLY
    val validServer: Boolean get() = serverAddress.trim().isNotEmpty() &&
        (serverPort.toIntOrNull() ?: 0) in 1..65535 && authKey.trim().isNotEmpty()
    val validTransport: Boolean get() = wifi || mobile || ethernet
    val stepCount: Int get() = if (usesVpn) 5 else 3
    val displayedStep: Int get() = if (usesVpn) step + 1 else when (step) { 0 -> 1; 3 -> 2; else -> 3 }
    fun nextStep(): Int = if (!usesVpn && step == 0) 3 else (step + 1).coerceAtMost(4)
    fun previousStep(): Int = if (!usesVpn && step == 3) 0 else (step - 1).coerceAtLeast(0)
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val preferences: SetupPreferences,
    private val manager: MqvpnManager,
    val radioCoordinator: RadioCoordinator,
) : ViewModel() {
    private val _draft = MutableStateFlow(SetupDraft())
    val draft = _draft.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val saved = repository.settings.first()
                val inferred = preferences.lastMode ?: when {
                    saved.serverAddress.isNotBlank() && radioCoordinator.options.value.enabled -> SetupMode.VPN_AND_SIM
                    saved.serverAddress.isNotBlank() -> SetupMode.VPN_ONLY
                    radioCoordinator.options.value.enabled -> SetupMode.SIM_ONLY
                    else -> SetupMode.VPN_AND_SIM
                }
                // Seed exactly once. Later DataStore emissions must not erase edits.
                _draft.update {
                    if (it.loaded) it else it.copy(
                        loaded = true,
                        mode = inferred,
                        serverAddress = saved.serverAddress,
                        serverPort = saved.serverPort.toString(),
                        authKey = saved.authKey,
                        tlsServerName = saved.tlsServerName,
                        insecure = saved.insecure,
                        wifi = saved.wifiPathEnabled,
                        mobile = saved.cellularPathEnabled,
                        ethernet = saved.ethernetPathEnabled,
                        // Recommend exclusion for a fresh setup, retain a saved preference.
                        excludeAndroidAuto = if (saved.serverAddress.isBlank()) true
                            else saved.excludeAndroidAutoFromVpn,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _draft.update { it.copy(error = "Could not read settings. Try again.") }
            }
        }
    }

    fun edit(change: (SetupDraft) -> SetupDraft) {
        _draft.update { current ->
            if (!current.loaded || current.saving || current.finished) current
            else change(current).copy(error = null)
        }
    }

    fun next() {
        val current = _draft.value
        if (!current.loaded || current.saving) return
        val error = when {
            current.step == 1 && current.usesVpn && !current.validServer ->
                "Enter a server address, port from 1 to 65535, and Auth Key."
            current.step == 2 && current.usesVpn && !current.validTransport ->
                "Select at least one VPN path."
            else -> null
        }
        if (error != null) _draft.update { it.copy(error = error) }
        else _draft.update { it.copy(step = it.nextStep(), error = null) }
    }

    fun back() {
        _draft.update { if (it.saving) it else it.copy(step = it.previousStep(), error = null) }
    }

    fun save() {
        val current = _draft.value
        if (!current.loaded || current.saving || current.finished) return
        if (current.step != 4) return
        if (current.usesVpn && !current.validServer) {
            _draft.update { it.copy(step = 1, error = "Complete the server address, port, and key.") }
            return
        }
        if (current.usesVpn && !current.validTransport) {
            _draft.update { it.copy(step = 2, error = "Select at least one VPN path.") }
            return
        }
        if (current.usesVpn && manager.vpnState.value !is MqvpnState.Disconnected &&
            manager.vpnState.value !is MqvpnState.Error) {
            _draft.update { it.copy(error = "VPN is active. Disconnect it from the dashboard before saving.") }
            return
        }
        _draft.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                if (current.usesVpn) {
                    val latest: DemoSettings = repository.settings.first()
                    repository.save(latest.copy(
                        serverAddress = current.serverAddress.trim(),
                        serverPort = current.serverPort.toInt(),
                        authKey = current.authKey.trim(),
                        tlsServerName = current.tlsServerName.trim(),
                        insecure = current.insecure,
                        wifiPathEnabled = current.wifi,
                        cellularPathEnabled = current.mobile,
                        ethernetPathEnabled = current.ethernet,
                        excludeAndroidAutoFromVpn = current.excludeAndroidAuto,
                    ))
                }
                preferences.markCompleted(current.mode)
                _draft.update { it.copy(saving = false, finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _draft.update { it.copy(saving = false, error = "Could not save. Try again.") }
            }
        }
    }

    fun cancel(): Boolean {
        val current = _draft.value
        if (current.saving || current.finished) return false
        return try {
            preferences.markSkipped()
            true
        } catch (_: Exception) {
            _draft.update { it.copy(error = "Could not save your choice. Try again.") }
            false
        }
    }

    fun skip() {
        val current = _draft.value
        if (current.saving || current.finished) return
        try {
            preferences.markSkipped()
            _draft.update { it.copy(finished = true, error = null) }
        } catch (_: Exception) {
            _draft.update { it.copy(error = "Could not save your choice. Try again.") }
        }
    }
}







