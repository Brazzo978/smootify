// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mqvpn.app.data.SetupMode
import com.mqvpn.app.simcontrol.ShizukuSimState

private val setupTitles = listOf("How do you want to use Smootify?", "VPN server", "Network paths", "Compatibility and permissions", "Summary")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    onOpenRadio: () -> Unit,
    vm: SetupViewModel = hiltViewModel(),
) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val shizuku by vm.radioCoordinator.shizuku.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val phoneGranted = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED
    var showAuth by remember { mutableStateOf(false) }

    LaunchedEffect(draft.finished) {
        if (draft.finished) onComplete()
    }
    BackHandler {
        if (!vm.draft.value.saving) {
            if (vm.draft.value.step > 0) vm.back() else if (vm.cancel()) onCancel()
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Setup · " + draft.displayedStep + "/" + draft.stepCount) },
            navigationIcon = {
                IconButton(onClick = {
                    if (draft.step > 0) vm.back() else if (vm.cancel()) onCancel()
                }, enabled = !draft.saving) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                TextButton(onClick = vm::skip, enabled = !draft.saving && !draft.finished) {
                    Text("Later")
                }
            },
        )
    }) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(setupTitles[draft.step], style = MaterialTheme.typography.headlineSmall)
            Text(
                when (draft.step) {
                    0 -> "Choose what to set up. You can change these options later."
                    1 -> "Enter your server details. The key stays hidden on screen."
                    2 -> "Choose which networks Smootify can use together when available."
                    3 -> if (draft.mode == SetupMode.SIM_ONLY) "Check SIM Switcher requirements." else "Check Android Auto and requirements for optional SIM switching."
                    else -> "Save the configuration. No connection or SIM change will start automatically."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!draft.loaded) {
                CircularProgressIndicator()
                draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } else {
                when (draft.step) {
                    0 -> ModeStep(draft, vm)
                    1 -> ServerStep(draft, vm, showAuth) { showAuth = !showAuth }
                    2 -> TransportStep(draft, vm)
                    3 -> CompatibilityStep(draft, vm, phoneGranted, shizuku, onOpenRadio)
                    4 -> ReviewStep(draft)
                }
                draft.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (draft.step > 0) {
                        TextButton(onClick = vm::back, enabled = !draft.saving) { Text("Back") }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(
                        onClick = if (draft.step == 4) vm::save else vm::next,
                        enabled = !draft.saving && !draft.finished,
                    ) {
                        Text(if (draft.step == 4) "Save configuration" else "Continue")
                    }
                }
                if (draft.saving) CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun ModeStep(draft: SetupDraft, vm: SetupViewModel) {
    val options = listOf(
        Triple(SetupMode.VPN_AND_SIM, "Both features", "Set up Seamless VPN and review SIM Switcher options."),
        Triple(SetupMode.VPN_ONLY, "Seamless VPN", "Set up VPN; existing SIM options remain unchanged."),
        Triple(SetupMode.SIM_ONLY, "SIM Switcher", "Check radio requirements without setting up VPN."),
    )
    options.forEach { (mode, title, description) ->
        Card(Modifier.fillMaxWidth().clickable { vm.edit { it.copy(mode = mode) } }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = draft.mode == mode, onClick = { vm.edit { it.copy(mode = mode) } })
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    Text("This choice does not automatically activate SIM switching, VPN, or Shizuku.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ServerStep(draft: SetupDraft, vm: SetupViewModel, showAuth: Boolean, toggleAuth: () -> Unit) {
    if (!draft.usesVpn) {
        Card(Modifier.fillMaxWidth()) {
            Text("SIM-only mode: saved server details remain unchanged.", Modifier.padding(16.dp))
        }
        return
    }
    OutlinedTextField(
        value = draft.serverAddress,
        onValueChange = { value -> vm.edit { it.copy(serverAddress = value) } },
        label = { Text("Server IP address or name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.serverPort,
        onValueChange = { value -> vm.edit { it.copy(serverPort = value) } },
        label = { Text("UDP port") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.authKey,
        onValueChange = { value -> vm.edit { it.copy(authKey = value) } },
        label = { Text("Authentication key") },
        singleLine = true,
        visualTransformation = if (showAuth) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = toggleAuth) { Text(if (showAuth) "Hide" else "Show") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.tlsServerName,
        onValueChange = { value -> vm.edit { it.copy(tlsServerName = value) } },
        label = { Text("TLS name (optional)") },
        supportingText = { Text("If the certificate name differs from the server address.") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = draft.insecure, onCheckedChange = { value -> vm.edit { it.copy(insecure = value) } })
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Accept unverified certificate", style = MaterialTheme.typography.titleSmall)
                Text("Use only when needed for a trusted server: disables TLS verification.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TransportStep(draft: SetupDraft, vm: SetupViewModel) {
    if (!draft.usesVpn) {
        Card(Modifier.fillMaxWidth()) {
            Text("SIM-only mode: no VPN path required. Existing selections remain unchanged.", Modifier.padding(16.dp))
        }
        return
    }
    TransportChoice("Wi-Fi", draft.wifi) { vm.edit { it.copy(wifi = !it.wifi) } }
    TransportChoice("Mobile network", draft.mobile) { vm.edit { it.copy(mobile = !it.mobile) } }
    TransportChoice("Ethernet", draft.ethernet) { vm.edit { it.copy(ethernet = !it.ethernet) } }
    Text("Select at least one path. Actual availability depends on the device's networks.",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TransportChoice(label: String, checked: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onClick() })
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun CompatibilityStep(
    draft: SetupDraft,
    vm: SetupViewModel,
    phoneGranted: Boolean,
    shizuku: ShizukuSimState,
    onOpenRadio: () -> Unit,
) {
    BatteryAccessCard()
    if (draft.usesVpn) Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Android Auto wireless", style = MaterialTheme.typography.titleMedium)
            Text("Recommended: exclude Android Auto from VPN to keep the wireless car connection.",
                style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = draft.excludeAndroidAuto,
                    onCheckedChange = { value -> vm.edit { it.copy(excludeAndroidAuto = value) } },
                    enabled = draft.usesVpn,
                )
                Spacer(Modifier.width(12.dp))
                Text(if (draft.excludeAndroidAuto) "Exclusion enabled" else "Exclusion disabled")
            }
            if (!draft.usesVpn) Text("In SIM-only mode, this VPN option remains unchanged.")
            Text("The new exclusion applies on the next VPN connection.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (draft.mode != SetupMode.VPN_ONLY) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Optional SIM switching", style = MaterialTheme.typography.titleMedium)
                Text(if (phoneGranted) "Phone permission: granted" else "Phone permission: needed",
                    style = MaterialTheme.typography.bodyMedium)
                Text("Shizuku: " + when (shizuku) {
                    ShizukuSimState.Ready -> "ready"
                    ShizukuSimState.NotInstalled -> "needs installation"
                    ShizukuSimState.NotRunning -> "needs starting"
                    ShizukuSimState.NoPermission -> "needs authorization"
                    else -> "check on the SIM screen"
                })
                Text("SIM switching can work without VPN. Enable it on the radio screen after checking permissions.")
                TextButton(onClick = onOpenRadio) { Text("Open SIM and permissions") }
            }
        }
    }
    if (draft.usesVpn) Text("VPN does not require Shizuku or phone permission.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ReviewStep(draft: SetupDraft) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Mode: " + when (draft.mode) {
                SetupMode.VPN_AND_SIM -> "VPN and SIM option"
                SetupMode.VPN_ONLY -> "VPN only"
                SetupMode.SIM_ONLY -> "SIM only"
            }, style = MaterialTheme.typography.titleMedium)
            if (draft.usesVpn) {
                Text("Server: " + draft.serverAddress.trim() + ":" + draft.serverPort)
                Text("Key: " + if (draft.authKey.isNotEmpty()) "set" else "missing")
                Text("Paths: " + listOfNotNull(
                    "Wi-Fi".takeIf { draft.wifi },
                    "mobile".takeIf { draft.mobile },
                    "Ethernet".takeIf { draft.ethernet },
                ).joinToString(", "))
                Text("Android Auto wireless: " + if (draft.excludeAndroidAuto) "excluded from VPN" else "not excluded")
                if (draft.insecure) Text("TLS certificate verification disabled",
                    color = MaterialTheme.colorScheme.error)
            } else {
                Text("Saved VPN details remain unchanged.")
            }
            Text("Automatic SIM selection remains optional; enable it on the radio screen.")
        }
    }
}



