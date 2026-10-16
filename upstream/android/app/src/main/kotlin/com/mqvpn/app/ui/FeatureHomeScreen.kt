// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mqvpn.app.radio.SimRadioSnapshot
import com.mqvpn.app.simcontrol.ShizukuSimState
import com.mqvpn.sdk.core.model.MqvpnState
import kotlinx.coroutines.launch

private val SUBTABS = listOf("Status", "Options", "Requirements")

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FeatureHomeScreen(
    viewModel: MqvpnViewModel,
    onOpenSettings: () -> Unit,
    onOpenIperf: () -> Unit,
    onOpenServer: () -> Unit,
    onOpenRadio: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenCompatibility: () -> Unit,
    onOpenTuning: () -> Unit,
    onOpenConnections: () -> Unit,
    onOpenProfile: () -> Unit,
    initialFeature: Int = 0,
    homeStatus: HomeStatusViewModel = hiltViewModel(),
) {
    val pager = rememberPagerState(initialPage = initialFeature.coerceIn(0, 1), pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val simVisible = pager.currentPage == 0

    // Observe the radios only while the SIM feature is foregrounded. The
    // independent service and an active VPN retain their own owners.
    LifecycleStartEffect(homeStatus.radioCoordinator, simVisible) {
        if (simVisible) homeStatus.radioCoordinator.attachUi()
        onStopOrDispose {
            if (simVisible) homeStatus.radioCoordinator.detachUi()
        }
    }

    Scaffold(topBar = {
        Column {
            TopAppBar(
                title = { Text("MQVPN") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
            TabRow(selectedTabIndex = pager.currentPage) {
                listOf("SIM Switcher", "Seamless VPN").forEachIndexed { index, label ->
                    Tab(
                        selected = pager.currentPage == index,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        text = { Text(label) },
                    )
                }
            }
        }
    }) { padding ->
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { index ->
            if (index == 0) {
                SimFeaturePage(
                    homeStatus = homeStatus,
                    active = simVisible,
                    onOpenRadio = onOpenRadio,
                )
            } else {
                VpnFeaturePage(
                    viewModel = viewModel,
                    homeStatus = homeStatus,
                    onOpenSettings = onOpenSettings,
                    onOpenIperf = onOpenIperf,
                    onOpenServer = onOpenServer,
                    onOpenRadio = onOpenRadio,
                    onOpenDiagnostics = onOpenDiagnostics,
                    onOpenSetup = onOpenSetup,
                    onOpenCompatibility = onOpenCompatibility,
                    onOpenTuning = onOpenTuning,
                    onOpenConnections = onOpenConnections,
                    onOpenProfile = onOpenProfile,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SimFeaturePage(
    homeStatus: HomeStatusViewModel,
    active: Boolean,
    onOpenRadio: () -> Unit,
) {
    val sections = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val options by homeStatus.radioOptions.collectAsStateWithLifecycle()
    val control by homeStatus.radioState.collectAsStateWithLifecycle()
    val radio by homeStatus.radioSnapshot.collectAsStateWithLifecycle()
    val shizuku by homeStatus.shizukuState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    Column(Modifier.fillMaxSize()) {
        SectionTabs(sections.currentPage) { target ->
            scope.launch { sections.animateScrollToPage(target) }
        }
        HorizontalPager(state = sections, modifier = Modifier.fillMaxSize()) { section ->
            when (section) {
            0 -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("SIM Switcher", style = MaterialTheme.typography.headlineSmall)
                Text("The best available data SIM, even without VPN when you enable the independent monitor.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Automatic selection", style = MaterialTheme.typography.titleMedium)
                        Text(when {
                            !options.enabled -> "Off"
                            control.switching -> "Switching SIM"
                            options.independent && control.standaloneRunning -> "Active even without VPN"
                            options.independent -> "Independent monitor waiting"
                            control.vpnSession -> "Active during VPN"
                            else -> "Waiting for VPN"
                        }, style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary)
                        if (options.enabled) Text(control.message,
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { scope.launch { sections.animateScrollToPage(1) } }) { Text("Manage SIM Switcher") }
                    }
                }
                val current = radio.subscriptions.firstOrNull {
                    it.subscriptionId == radio.defaultDataSubscriptionId
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Current data SIM", style = MaterialTheme.typography.titleMedium)
                        if (current == null) {
                            Text(if (radio.permissionGranted) "Data SIM unavailable" else "Phone permission required")
                        } else {
                            Text("SIM ${current.slotIndex + 1} · ${current.carrierName}",
                                style = MaterialTheme.typography.titleLarge)
                            Text(simSummary(current), style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = { scope.launch { sections.animateScrollToPage(2) } }) { Text("Check requirements") }
                    }
                }
                Text("Data SIM selection works independently of the VPN connection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            1 -> RadioScreen(
                onNavigateUp = { scope.launch { sections.animateScrollToPage(0) } },
                embedded = true,
                active = active && sections.currentPage == 1 && section == 1,
            )
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("SIM Switcher requirements", style = MaterialTheme.typography.headlineSmall)
                BatteryAccessCard()
                Requirement("Phone permission",
                    if (radio.permissionGranted) "Granted" else "Needs permission",
                    "Needed to read SIMs and radio quality.", radio.permissionGranted)
                Requirement("Two active SIMs",
                    if (radio.subscriptions.count { it.active } >= 2) "Available" else "Needs checking",
                    "Automatic switching requires another usable data SIM.",
                    radio.subscriptions.count { it.active } >= 2)
                Requirement("Shizuku",
                    if (shizuku == ShizukuSimState.Ready) "Ready" else "Needs setup",
                    "Allows MQVPN to change the default data SIM.",
                    shizuku == ShizukuSimState.Ready)
                Requirement("Notifications",
                    if (notificationsAllowed) "Allowed" else "Needs permission",
                    "Needed to see the independent monitor on Android 13 and later.",
                    notificationsAllowed)
                TextButton(onClick = { scope.launch { sections.animateScrollToPage(1) } }) { Text("Open options and permissions") }
                TextButton(onClick = onOpenRadio) { Text("Open full SIM page") }
            }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VpnFeaturePage(
    viewModel: MqvpnViewModel,
    homeStatus: HomeStatusViewModel,
    onOpenSettings: () -> Unit,
    onOpenIperf: () -> Unit,
    onOpenServer: () -> Unit,
    onOpenRadio: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenCompatibility: () -> Unit,
    onOpenTuning: () -> Unit,
    onOpenConnections: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val sections = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val settings by homeStatus.settings.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        SectionTabs(sections.currentPage) { target ->
            scope.launch { sections.animateScrollToPage(target) }
        }
        HorizontalPager(state = sections, modifier = Modifier.fillMaxSize()) { section ->
            when (section) {
            0 -> DashboardScreen(
                viewModel = viewModel,
                onOpenSettings = onOpenSettings,
                onOpenIperf = onOpenIperf,
                onOpenConnections = onOpenConnections,
                onOpenServer = onOpenServer,
                onOpenRadio = onOpenRadio,
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenSetup = onOpenSetup,
                onOpenCompatibility = onOpenCompatibility,
                embedded = true,
                homeStatus = homeStatus,
            )
            1 -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Your tunnel", style = MaterialTheme.typography.headlineSmall)
                Text("Choose networks, measure the connection, and set its behavior.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val selectedNetworks = listOfNotNull(
                    if (settings?.wifiPathEnabled == true) "Wi-Fi" else null,
                    if (settings?.cellularPathEnabled == true) "Mobile" else null,
                    if (settings?.ethernetPathEnabled == true) "Ethernet" else null,
                    if (settings?.bluetoothPathEnabled == true) "Bluetooth" else null,
                ).joinToString(" · ")
                TunnelMenuCard("Connections", selectedNetworks.ifBlank { "Choose networks to use" },
                    "Select tunnel paths", Icons.Filled.Wifi, onOpenConnections)
                TunnelMenuCard("Speed test", "TCP and UDP with iperf",
                    "Live gauge and each network's contribution", Icons.Filled.Speed, onOpenIperf)
                val mode = settings?.let { TuningPreset.of(it)?.label } ?: "Custom"
                TunnelMenuCard("Tunnel mode", mode,
                    "Simple presets and advanced settings", Icons.Filled.Tune, onOpenTuning)
                FeatureAction("Import server profile", "Scan the VPS QR code or paste a profile", onOpenProfile)
                FeatureAction("Server and access", "Address and access key", onOpenServer)
                FeatureAction("Android Auto",
                    if (settings?.excludeAndroidAutoFromVpn == true) "Excluded from VPN" else "Check compatibility",
                    onOpenCompatibility)
                BatteryAccessCard()
                FeatureAction("Guided setup", "Review initial setup steps", onOpenSetup)
                FeatureAction("Diagnostics", "Detailed network status", onOpenDiagnostics)
            }
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Seamless VPN requirements", style = MaterialTheme.typography.headlineSmall)
                BatteryAccessCard()
                Requirement("Server",
                    if (settings?.hostValid() == true && settings?.portValid() == true) "Configured" else "Needs setup",
                    "MQVPN server address and port.",
                    settings?.hostValid() == true && settings?.portValid() == true)
                Requirement("Access",
                    if (settings?.authKey?.isNotBlank() == true) "Key set" else "Key missing",
                    "The key must match the server's key.",
                    settings?.authKey?.isNotBlank() == true)
                Requirement("VPN permission",
                    if (vpnState is MqvpnState.Connected) "Granted for this session" else "Checked when connecting",
                    "Android asks for permission when you tap Connect.",
                    vpnState is MqvpnState.Connected)
                Requirement("MTU",
                    if (settings?.serverMtu == 1280) "1280" else "Set to 1280",
                    "The mobile profile uses MTU 1280.",
                    settings?.serverMtu == 1280)
                Requirement("Shizuku", "Not required",
                    "Only needed for automatic SIM switching, not for VPN.",
                    true)
                TextButton(onClick = onOpenSetup) { Text("Open guided setup") }
            }
            }
        }
    }
}

@Composable
private fun SectionTabs(selected: Int, onSelect: (Int) -> Unit) {
    TabRow(selectedTabIndex = selected) {
        SUBTABS.forEachIndexed { index, label ->
            Tab(selected = selected == index, onClick = { onSelect(index) },
                text = { Text(label) })
        }
    }
}

@Composable
private fun TunnelMenuCard(title: String, status: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun FeatureAction(title: String, detail: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onClick) { Text("Open") }
        }
    }
}

@Composable
private fun Requirement(title: String, status: String, detail: String, ready: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.labelLarge,
                color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Text(detail, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun simSummary(sim: SimRadioSnapshot): String {
    val technology = when {
        sim.registeredRat == TelephonyManager.NETWORK_TYPE_IWLAN -> "VoWiFi / IWLAN"
        sim.generation > 0 -> "${sim.generation}G"
        else -> "Technology unavailable"
    }
    val signal = sim.signalLevel?.let { "signal $it/4" } ?: "signal unavailable"
    return "$technology · $signal"
}
