// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mqvpn.app.data.ConnectionProfile
import com.mqvpn.app.data.ConnectionProfileParser
import com.mqvpn.app.data.SettingsRepository
import com.mqvpn.app.data.SetupMode
import com.mqvpn.app.data.SetupPreferences
import com.mqvpn.sdk.core.MqvpnManager
import com.mqvpn.sdk.core.model.MqvpnState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import javax.inject.Inject

data class ProfileImportState(
    val profile: ConnectionProfile? = null,
    val error: String? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
)

@HiltViewModel
class ProfileImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SettingsRepository,
    private val manager: MqvpnManager,
    private val setupPreferences: SetupPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileImportState())
    val state = _state.asStateFlow()

    fun showError(message: String) { _state.update { it.copy(error = message) } }

    fun preview(text: String) {
        if (_state.value.saving) return
        _state.value = try {
            ProfileImportState(profile = ConnectionProfileParser.parse(text))
        } catch (e: IllegalArgumentException) {
            ProfileImportState(error = e.message ?: "Invalid profile.")
        }
    }

    fun previewFile(uri: Uri) {
        if (_state.value.saving) return
        viewModelScope.launch {
            try {
                val source = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val buffer = ByteArray(24 * 1024 + 1)
                        var total = 0
                        while (total < buffer.size) {
                            val count = stream.read(buffer, total, buffer.size - total)
                            if (count <= 0) break
                            total += count
                        }
                        require(total <= 24 * 1024) { "Profile file is too large." }
                        StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(buffer, 0, total)).toString()
                    } ?: throw IllegalArgumentException("Could not open the profile file.")
                }
                preview(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ProfileImportState(error = if (e is IllegalArgumentException)
                    e.message else "Could not read the profile file.")
            }
        }
    }

    fun save() {
        val profile = _state.value.profile ?: return
        if (_state.value.saving || _state.value.saved) return
        val vpnState = manager.vpnState.value
        if (vpnState !is MqvpnState.Disconnected && vpnState !is MqvpnState.Error) {
            _state.update { it.copy(error = "Disconnect the VPN before importing a profile.") }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val latest = repository.settings.first()
                val currentState = manager.vpnState.value
                if (currentState !is MqvpnState.Disconnected && currentState !is MqvpnState.Error) {
                    _state.update { it.copy(saving = false,
                        error = "Disconnect the VPN before importing a profile.") }
                    return@launch
                }
                repository.save(latest.copy(
                    serverAddress = profile.serverAddress,
                    serverPort = profile.serverPort,
                    tlsServerName = profile.tlsServerName,
                    authKey = profile.authKey,
                    insecure = profile.insecure,
                    controlAddress = profile.controlAddress,
                    controlPort = profile.controlPort,
                    serverMtu = profile.mtu,
                    serverControlAllowed = profile.serverControlAllowed,
                ))
                val prefs = context.getSharedPreferences("iperf-options", Context.MODE_PRIVATE)
                val endpointSaved = prefs.edit()
                    .putString("host", profile.iperfHost)
                    .putInt("port", profile.iperfPort)
                    .commit()
                if (!endpointSaved) {
                    _state.update { it.copy(saving = false,
                        error = "VPN settings were saved, but the iperf endpoint could not be saved. Try again.") }
                    return@launch
                }
                setupPreferences.markCompleted(SetupMode.VPN_ONLY)
                _state.update { it.copy(saving = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(saving = false, error = "Could not save the profile. Try again.") }
            }
        }
    }
}



