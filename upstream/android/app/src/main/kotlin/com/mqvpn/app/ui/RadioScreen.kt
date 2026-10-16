// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import com.mqvpn.app.radio.*
import com.mqvpn.app.simcontrol.ShizukuSimState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class RadioViewModel @Inject constructor(val coordinator: RadioCoordinator) : ViewModel()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioScreen(onNavigateUp: () -> Unit, vm: RadioViewModel = hiltViewModel(), embedded: Boolean = false, active: Boolean = true) {
    var showCriteria by rememberSaveable { mutableStateOf(false) }
    var showRadioDetails by rememberSaveable { mutableStateOf(false) }
    val c = vm.coordinator
    LifecycleStartEffect(c, active) {
        if (active) c.attachUi()
        onStopOrDispose { if (active) c.detachUi() }
    }
    val radio by c.radio.collectAsStateWithLifecycle()
    val control by c.state.collectAsStateWithLifecycle()
    val options by c.options.collectAsStateWithLifecycle()
    val shizuku by c.shizuku.collectAsStateWithLifecycle()
    var stable by rememberSaveable { mutableStateOf(options.stableSeconds.toString()) }
    var urgent by rememberSaveable { mutableStateOf(options.urgentSeconds.toString()) }
    var hold by rememberSaveable { mutableStateOf(options.minimumHoldSeconds.toString()) }
    var roaming by rememberSaveable { mutableStateOf(options.allowRoaming) }
    var idle by rememberSaveable { mutableStateOf(options.idleSeconds.toString()) }
    val context = LocalContext.current
    val phonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { c.refresh() }
    var notificationsGranted by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    var pendingOptions by remember { mutableStateOf<AutoSimOptions?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsGranted = granted
        pendingOptions?.let { c.saveOptions(it, explicitUiAction = true) }
        pendingOptions = null
    }
    fun saveFromUi(next: AutoSimOptions) {
        if (next.enabled && next.independent && !notificationsGranted && Build.VERSION.SDK_INT >= 33) {
            pendingOptions = next
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else c.saveOptions(next, explicitUiAction = true)
    }
    val ready = shizuku == ShizukuSimState.Ready
    val valid = (stable.toIntOrNull() ?: 0) in 5..120 && (urgent.toIntOrNull() ?: 0) in 3..60 && (hold.toIntOrNull() ?: 0) in 30..900 && (idle.toIntOrNull() ?: 0) in 30..3600
    Scaffold(topBar = { if (!embedded) TopAppBar(title = { Text("SIM Switcher") }, navigationIcon = {
        IconButton(onClick = onNavigateUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BatteryAccessCard()
            Text("Choose the best mobile network", style = MaterialTheme.typography.titleLarge)
            Text("Switch the data SIM when another network is clearly better. This also works with VPN disconnected.")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Shizuku: ${shizukuText(shizuku)}", style = MaterialTheme.typography.titleMedium)
                    Text("Only SIM switching requires Shizuku. Aggregation and iperf tests work without it.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")); context.startActivity(intent) }) { Text(if (shizuku == ShizukuSimState.NotInstalled) "Download Shizuku" else "Open Shizuku") }
                        Button(onClick = c::requestShizukuPermission, enabled = shizuku == ShizukuSimState.NoPermission) { Text("Authorize MQVPN") }
                    }
                    TextButton(onClick = c::refresh, enabled = !control.switching) { Text("Refresh radio and Shizuku status") }
                    if (!radio.permissionGranted) Button(onClick = { phonePermission.launch(Manifest.permission.READ_PHONE_STATE) }) { Text("Allow SIM access") }
                }
            }
            LabeledSwitchRow("Automatic data SIM selection", options.enabled,
                { saveFromUi(options.copy(enabled = it)) }, !control.switching && (options.enabled || (ready && radio.permissionGranted)))
            LabeledSwitchRow("Active even without VPN", options.independent,
                { saveFromUi(options.copy(independent = it)) }, !control.switching)
            Text(
                when {
                    options.enabled && options.independent && control.standaloneRunning ->
                        "Independent monitor active · persistent notification"
                    options.enabled && options.independent ->
                        "Independent monitor starting or stopped · check notification"
                    options.enabled && control.vpnSession -> "MQVPN session active · automatic selection active"
                    options.enabled -> "VPN only · no automatic switching now"
                    else -> "Automatic selection off"
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (options.enabled && options.independent && !notificationsGranted && Build.VERSION.SDK_INT >= 33) {
                Text("Notifications not allowed: Android may hide the monitor notice. You can allow them in app settings.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (options.enabled && options.independent && !control.standaloneRunning) {
                OutlinedButton(onClick = c::startStandaloneFromUi) { Text("Start independent monitor") }
            }
            Text("Checks pause when the phone is idle. You can stop the monitor from its notification.",
                style = MaterialTheme.typography.bodySmall)
            if (control.switching) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(control.message, style = MaterialTheme.typography.bodyLarge)
            radio.subscriptions.forEach { sim ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("SIM ${sim.slotIndex + 1} · ${sim.carrierName}" + if (sim.subscriptionId == radio.defaultDataSubscriptionId) " · DATA" else "", style = MaterialTheme.typography.titleMedium)
                        Text("${ratText(sim.registeredRat)} · ${when (sim.inService) { true -> "In service"; false -> "Out of service"; null -> "Service status unavailable" }}")
                        Text("Level ${sim.signalLevel?.let { "$it/4" } ?: "—"} · signal ${sim.dbm?.let { "$it dBm" } ?: "—"}")
                        if (showRadioDetails) {
                        sim.lteRsrpDbm?.let { Text("RSRP LTE: $it dBm") }
                        sim.nrSsRsrpDbm?.let { Text("NR SS-RSRP: $it dBm · a measurement, not proof of a 5G data connection", style = MaterialTheme.typography.bodySmall) }
                        if (sim.displayOverride != null && sim.displayOverride != 0) Text("Additional network indicator: ${sim.displayOverride}. The decision uses the registered technology.", style = MaterialTheme.typography.bodySmall)
                        Text("Calls: ${when(sim.callState) { RadioCallState.IDLE -> "none"; RadioCallState.ACTIVE -> "in progress"; else -> "status unavailable" }} · roaming ${sim.roaming?.let { if (it) "yes" else "no" } ?: "—"}", style = MaterialTheme.typography.bodySmall)
                        val age = if (sim.updatedAtElapsedMs > 0) (SystemClock.elapsedRealtime() - sim.updatedAtElapsedMs).coerceAtLeast(0) / 1000 else null
                        Text(age?.let { "Last useful measurement: $it s ago" } ?: "Waiting for radio measurements", style = MaterialTheme.typography.bodySmall)
                        }
                        if (sim.subscriptionId != radio.defaultDataSubscriptionId) OutlinedButton(
                            onClick = { c.switchManually(sim.subscriptionId) }, enabled = ready && !control.switching && sim.active) { Text("Use SIM ${sim.slotIndex + 1} for data") }
                    }
                }
            }
            if (radio.subscriptions.isEmpty()) Text("No SIM data available. Grant phone permission and refresh status.")
            TextButton(onClick = { showRadioDetails = !showRadioDetails }) {
                Text(if (showRadioDetails) "Hide radio details" else "Show radio details")
            }
            OutlinedButton(onClick = { showCriteria = !showCriteria }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showCriteria) "Close advanced criteria" else "Criteria and power saving")
            }
            if (showCriteria) {
            HorizontalDivider()
            Text("Criteria and power saving", style = MaterialTheme.typography.titleLarge)
            Text("Switch to a SIM in service with better signal, or from 2G to 4G/5G. The 5G icon alone does not decide switching. RSSI and RSRP are not equivalent across technologies.", style = MaterialTheme.typography.bodySmall)
            SecondsField("Stable advantage before switching (5–120 s)", stable, { stable = it }, !control.switching)
            SecondsField("Wait when current SIM is weak or out of service (3–60 s)", urgent, { urgent = it }, !control.switching)
            SecondsField("Minimum time between switches (30–900 s)", hold, { hold = it }, !control.switching)
            SecondsField("Screen-off idle time before pause (30–3600 s)", idle, { idle = it }, !control.switching)
            Text("Device traffic is used as an estimate: small keepalives do not count as activity. The pause does not wake the phone or start Internet tests.", style = MaterialTheme.typography.bodySmall)
            LabeledSwitchRow("Allow automatic selection of a roaming SIM", roaming, { roaming = it }, !control.switching)
            Button(onClick = { c.saveOptions(options.copy(stableSeconds = stable.toInt(), urgentSeconds = urgent.toInt(),
                minimumHoldSeconds = hold.toInt(), idleSeconds = idle.toInt(), allowRoaming = roaming)) }, enabled = valid && !control.switching,
                modifier = Modifier.fillMaxWidth()) { Text("Save radio criteria") }
            Text("Missing or stale measurements and active calls block automatic selection. Radio setup can delay the new path: switching the default SIM does not guarantee uninterrupted traffic.", style = MaterialTheme.typography.bodySmall)
            if (control.events.isNotEmpty()) {
                HorizontalDivider(); Text("Switches and reasons", style = MaterialTheme.typography.titleMedium)
                control.events.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
@Composable
private fun SecondsField(label: String, value: String, change: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, enabled = enabled,
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}
private fun shizukuText(state: ShizukuSimState): String = when (state) {
    ShizukuSimState.Stopped -> "not connected"
    ShizukuSimState.NotInstalled -> "needs installation"
    ShizukuSimState.NotRunning -> "needs starting"
    ShizukuSimState.NoPermission -> "permission required"
    ShizukuSimState.Connecting -> "connecting…"
    ShizukuSimState.Ready -> "ready"
    ShizukuSimState.Dead -> "out of service · refresh status"
    is ShizukuSimState.Error -> state.message
}
private fun ratText(rat: Int): String = when (rat) {
    TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
    TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
    TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"
    TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"
    TelephonyManager.NETWORK_TYPE_GSM -> "2G GSM"
    TelephonyManager.NETWORK_TYPE_UMTS -> "3G UMTS"
    TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP -> "3G HSPA"
    TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
    TelephonyManager.NETWORK_TYPE_UNKNOWN -> "Technology unavailable"
    else -> "Mobile network (type $rat)"
}
