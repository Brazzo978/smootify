// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileImportScreen(
    onNavigateUp: () -> Unit,
    incomingPayload: String? = null,
    onPayloadConsumed: () -> Unit = {},
    vm: ProfileImportViewModel = hiltViewModel(),
    onImported: () -> Unit = onNavigateUp,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pasted by remember { mutableStateOf("") }
    var revealInput by remember { mutableStateOf(false) }

    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(vm::preview)
    }
    fun launchScan() {
        scan.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan an Smootify VPS profile QR code")
                .setBeepEnabled(false),
        )
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) launchScan() else vm.showError("Camera permission is needed to scan a QR code.")
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.previewFile(uri)
    }

    LaunchedEffect(incomingPayload) {
        if (incomingPayload != null) {
            vm.preview(incomingPayload)
            onPayloadConsumed()
        }
    }
    BackHandler(enabled = state.saving) {}
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Import VPS profile") },
            navigationIcon = {
                IconButton(onClick = onNavigateUp, enabled = !state.saving) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
        )
    }) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Move your VPS connection to this phone", style = MaterialTheme.typography.headlineSmall)
            Text("Scan the QR code from the VPS setup, paste its link, or open a profile file. Review the details before importing. The VPN will not connect automatically.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        launchScan()
                    } else cameraPermission.launch(Manifest.permission.CAMERA)
                }, enabled = !state.saving) { Text("Scan QR") }
                OutlinedButton(onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    enabled = !state.saving) { Text("Open file") }
            }
            OutlinedTextField(
                value = pasted,
                onValueChange = { pasted = it.take(24 * 1024 + 1) },
                label = { Text("Paste profile link or JSON") },
                supportingText = { Text("The pasted profile may contain a secret key. Keep it private.") },
                visualTransformation = if (revealInput) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { revealInput = !revealInput }) {
                        Text(if (revealInput) "Hide" else "Show")
                    }
                },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { vm.preview(pasted); pasted = ""; revealInput = false },
                enabled = pasted.isNotBlank() && !state.saving) {
                Text("Preview pasted profile")
            }
            state.profile?.let { profile ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Profile preview", style = MaterialTheme.typography.titleLarge)
                        Text(profile.name, style = MaterialTheme.typography.titleMedium)
                        Text("Server: " + profile.serverAddress + ":" + profile.serverPort)
                        Text("TLS name: " + (profile.tlsServerName.ifEmpty { "server address" }))
                        Text("Authentication key: ••••••••")
                        Text("MTU: " + profile.mtu)
                        Text("Control endpoint: " + profile.controlAddress + ":" + profile.controlPort)
                        Text("iperf endpoint: " + profile.iperfHost + ":" + profile.iperfPort)
                        Text("Server management: " + if (profile.serverControlAllowed) "available" else "not included in this profile")
                        if (profile.insecure) Text("TLS certificate verification disabled",
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                Button(onClick = vm::save, enabled = !state.saving && !state.saved) {
                    Text("Import profile")
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.saving) CircularProgressIndicator()
            if (state.saved) {
                Text("Profile imported. Connect from the dashboard when you are ready.",
                    color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onImported) { Text("Done") }
            }
        }
    }
}
