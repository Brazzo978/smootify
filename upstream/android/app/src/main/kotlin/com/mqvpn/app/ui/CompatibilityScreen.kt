// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors
package com.mqvpn.app.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mqvpn.app.data.SettingsRepository
import com.mqvpn.app.service.MyVpnService
import com.mqvpn.sdk.core.MqvpnManager
import com.mqvpn.sdk.core.model.MqvpnState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@HiltViewModel
class CompatibilityViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val manager: MqvpnManager,
    @ApplicationContext context: Context,
) : ViewModel() {
    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val vpnState = manager.vpnState
    val installed = try { context.packageManager.getApplicationInfo("com.google.android.projection.gearhead", 0); true }
        catch (_: PackageManager.NameNotFoundException) { false }
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun save(enabled: Boolean, reconnect: Boolean) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val current = repository.settings.first()
                repository.save(current.copy(excludeAndroidAutoFromVpn = enabled))
                if (reconnect && (manager.vpnState.value is MqvpnState.Connected || manager.vpnState.value is MqvpnState.Reconnecting)) {
                    manager.disconnectAndAwait()
                    manager.connect(repository.settings.first().toMqvpnConfig(), MyVpnService::class.java)
                    _message.value = "Setting saved. Smootify is reconnecting the tunnel with the new configuration."
                } else {
                    _message.value = if (manager.vpnState.value is MqvpnState.Disconnected || manager.vpnState.value is MqvpnState.Error)
                        "Setting saved; it will apply on the next connection."
                    else "Setting saved. Reconnect Smootify to apply it to the current tunnel."
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _message.value = "Impossibile completare l'operazione: ${e.message}" }
            finally { _busy.value = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompatibilityScreen(onNavigateUp: () -> Unit, vm: CompatibilityViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.vpnState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(settings) { if (enabled == null && settings != null) enabled = settings!!.excludeAndroidAutoFromVpn }
    BackHandler(enabled = busy) {}
    val active = state is MqvpnState.Connected || state is MqvpnState.Reconnecting
    Scaffold(topBar = { TopAppBar(title = { Text("Android Auto") }, navigationIcon = {
        IconButton(onClick = onNavigateUp, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Connection to your car", style = MaterialTheme.typography.headlineSmall)
            Text("Wireless Android Auto needs to communicate directly with your car. This option lets it use the phone's regular network.")
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(when { settings == null -> "Loading…"; enabled != settings!!.excludeAndroidAutoFromVpn -> "Unsaved change"; enabled == true -> "Compatibility enabled in settings"; else -> "Compatibility not enabled" }, style = MaterialTheme.typography.titleLarge)
                    LabeledSwitchRow("Exclude Android Auto from VPN", enabled == true,
                        { enabled = it }, enabled = !busy && settings != null)
                    Text("Android Auto traffic will stay outside the tunnel. Other apps will continue using Smootify.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(if (vm.installed) "Android Auto detected on this phone" else "Android Auto is not installed or is not visible on this device.", style = MaterialTheme.typography.bodyMedium)
            if (active) Text("Changing this exclusion requires reconnecting the tunnel; active connections may be interrupted.", color = MaterialTheme.colorScheme.tertiary)
            Button(onClick = { vm.save(enabled == true, reconnect = active) },
                enabled = settings != null && !busy && state !is MqvpnState.Connecting,
                modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Applying…" else if (active) "Save and reconnect Smootify" else "Save compatibility setting")
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            HorizontalDivider()
            Text("How to test it", style = MaterialTheme.typography.titleMedium)
            Text("1. Enable the exclusion and save.\n2. Connect Smootify, or tap Save and reconnect.\n3. Reconnect wireless Android Auto to your car.")
            Text("If Android blocks all connections outside the VPN in system settings, it may also block the excluded app. This exclusion does not change that Android setting.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
