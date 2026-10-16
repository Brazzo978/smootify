// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mqvpn.app.data.ConnectionProfile
import com.mqvpn.app.data.ConnectionProfileParser
import com.mqvpn.app.data.DemoSettings
import com.mqvpn.app.data.SettingsRepository
import com.mqvpn.app.data.SetupMode
import com.mqvpn.app.data.SetupPreferences
import com.mqvpn.app.radio.RadioCoordinator
import com.mqvpn.sdk.core.MqvpnManager
import com.mqvpn.sdk.core.model.MqvpnState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    val importedProfile: ConnectionProfile? = null,
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

    fun withProfile(profile: ConnectionProfile): SetupDraft = copy(
        serverAddress = profile.serverAddress,
        serverPort = profile.serverPort.toString(),
        authKey = profile.authKey,
        tlsServerName = profile.tlsServerName,
        insecure = profile.insecure,
        importedProfile = profile,
        error = null,
    )

    fun serverSettings(latest: DemoSettings): DemoSettings {
        if (!usesVpn) return latest
        return latest.copy(
            serverAddress = serverAddress.trim(),
            serverPort = serverPort.toInt(),
            authKey = authKey.trim(),
            tlsServerName = tlsServerName.trim(),
            insecure = insecure,
            wifiPathEnabled = wifi,
            cellularPathEnabled = mobile,
            ethernetPathEnabled = ethernet,
            excludeAndroidAutoFromVpn = excludeAndroidAuto,
            controlAddress = importedProfile?.controlAddress ?: latest.controlAddress,
            controlPort = importedProfile?.controlPort ?: latest.controlPort,
            serverMtu = importedProfile?.mtu ?: latest.serverMtu,
            serverControlAllowed = importedProfile?.serverControlAllowed ?: latest.serverControlAllowed,
        )
    }
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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
            else {
                val changed = change(current)
                val credentialsChanged = changed.serverAddress != current.serverAddress ||
                    changed.serverPort != current.serverPort || changed.authKey != current.authKey ||
                    changed.tlsServerName != current.tlsServerName || changed.insecure != current.insecure
                changed.copy(importedProfile = if (credentialsChanged) null else changed.importedProfile,
                    error = null)
            }
        }
    }

    fun importProfile(text: String) {
        val current = _draft.value
        if (!current.loaded || current.saving || current.finished) return
        val profile = try {
            ConnectionProfileParser.parse(text)
        } catch (_: Exception) {
            showProfileError("Invalid QR profile. Scan a valid server QR code or use advanced setup.")
            return
        }
        _draft.update { if (!it.loaded || it.saving || it.finished) it else it.withProfile(profile) }
    }

    fun showProfileError(error: String) {
        _draft.update { if (!it.loaded || it.saving || it.finished) it else it.copy(error = error) }
    }

    fun next() {
        val current = _draft.value
        if (!current.loaded || current.saving) return
        val error = when {
            current.step == 1 && current.usesVpn && !current.validServer ->
                "Scan your VPS profile QR code or enter server details in Advanced setup."
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
            _draft.update { it.copy(step = 1, error = "Scan your VPS profile QR code or complete Advanced setup.") }
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
                    repository.save(current.serverSettings(latest))
                    current.importedProfile?.let { profile ->
                        check(context.getSharedPreferences("iperf-options", Context.MODE_PRIVATE)
                            .edit().putString("host", profile.iperfHost)
                            .putInt("port", profile.iperfPort).commit()) {
                            "Could not save iperf endpoint."
                        }
                    }
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







