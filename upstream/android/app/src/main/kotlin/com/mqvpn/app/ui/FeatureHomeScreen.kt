// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mqvpn.app.radio.SimRadioSnapshot
import com.mqvpn.app.simcontrol.ShizukuSimState
import kotlinx.coroutines.launch

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

    // The active VPN and standalone monitor have their own owners. The UI only
    // observes the radio while the SIM dashboard is in the foreground.
    LifecycleStartEffect(homeStatus.radioCoordinator, simVisible) {
        if (simVisible) homeStatus.radioCoordinator.attachUi()
        onStopOrDispose {
            if (simVisible) homeStatus.radioCoordinator.detachUi()
        }
    }

    Scaffold(topBar = {
        Column {
            TopAppBar(
                title = { Text("Smootify", style = MaterialTheme.typography.titleLarge) },
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
        ) { page ->
            if (page == 0) {
                SmootifySimDashboard(homeStatus, onOpenRadio)
            } else {
                SmootifyVpnDashboard(
                    viewModel = viewModel,
                    homeStatus = homeStatus,
                    onOpenSettings = onOpenSettings,
                    onOpenIperf = onOpenIperf,
                    onOpenServer = onOpenServer,
                    onOpenRadio = onOpenRadio,
                    onOpenDiagnostics = onOpenDiagnostics,
                    onOpenSetup = onOpenSetup,
                    onOpenCompatibility = onOpenCompatibility,
                    onOpenConnections = onOpenConnections,
                )
            }
        }
    }
}

@Composable
private fun SmootifySimDashboard(
    homeStatus: HomeStatusViewModel,
    onOpenRadio: () -> Unit,
) {
    val options by homeStatus.radioOptions.collectAsStateWithLifecycle()
    val control by homeStatus.radioState.collectAsStateWithLifecycle()
    val radio by homeStatus.radioSnapshot.collectAsStateWithLifecycle()
    val shizuku by homeStatus.shizukuState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val current = radio.subscriptions.firstOrNull {
        it.subscriptionId == radio.defaultDataSubscriptionId
    }
    val stateLabel = when {
        !options.enabled -> "Automatic switching is off"
        control.switching -> "Switching data SIM"
        options.independent && control.standaloneRunning -> "Monitoring independently"
        options.independent -> "Monitor waiting"
        control.vpnSession -> "Monitoring during VPN"
        else -> "Waiting for VPN"
    }
    val statusColor = if (options.enabled && (control.standaloneRunning || control.vpnSession)) {
        MaterialTheme.colorScheme.primary
    } else MaterialTheme.colorScheme.tertiary

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("SIM Switcher", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Your mobile data at a glance",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(12.dp).background(statusColor, CircleShape),
                    )
                    Text(
                        "AUTOMATIC SELECTION",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Text(stateLabel, style = MaterialTheme.typography.headlineSmall)
                if (options.enabled) {
                    Text(
                        control.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Text(
                    if (options.independent) "Can run while the VPN is disconnected" else "Uses the VPN session when connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Button(onClick = onOpenRadio, modifier = Modifier.fillMaxWidth()) {
                    Text("SIM settings")
                }
            }
        }

        Text("Mobile networks", style = MaterialTheme.typography.titleLarge)
        if (radio.subscriptions.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    if (radio.permissionGranted) "No active SIM information is available yet."
                    else "Allow phone access in SIM settings to see your networks.",
                    modifier = Modifier.padding(18.dp),
                )
            }
        } else {
            radio.subscriptions.forEach { sim ->
                val isData = sim.subscriptionId == radio.defaultDataSubscriptionId
                SmootifySimCard(sim, isData)
            }
        }

        val needsAttention = buildList {
            if (!radio.permissionGranted) add("Phone access")
            if (radio.subscriptions.count { it.active } < 2) add("Two active SIMs")
            if (shizuku != ShizukuSimState.Ready) add("Shizuku")
            if (options.independent && !notificationsAllowed) add("Notifications")
        }
        if (needsAttention.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Check before switching", style = MaterialTheme.typography.titleMedium)
                    Text(
                        needsAttention.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        "Open SIM settings to review access and switching requirements.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
        Text(
            "Switching the data SIM may briefly interrupt mobile traffic. VPN connectivity is a separate feature.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SmootifySimCard(sim: SimRadioSnapshot, isData: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isData) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("SIM ${sim.slotIndex + 1}", style = MaterialTheme.typography.titleMedium)
                if (isData) Text("DATA", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(sim.carrierName, style = MaterialTheme.typography.titleLarge)
            Text(
                simSummary(sim),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!sim.active) Text("Inactive", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SmootifyVpnDashboard(
    viewModel: MqvpnViewModel,
    homeStatus: HomeStatusViewModel,
    onOpenSettings: () -> Unit,
    onOpenIperf: () -> Unit,
    onOpenServer: () -> Unit,
    onOpenRadio: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenCompatibility: () -> Unit,
    onOpenConnections: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Seamless VPN", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Secure connection and live paths",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
        }
        DashboardScreen(
            viewModel = viewModel,
            onOpenSettings = onOpenSettings,
            onOpenIperf = onOpenIperf,
            onOpenServer = onOpenServer,
            onOpenRadio = onOpenRadio,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenSetup = onOpenSetup,
            onOpenCompatibility = onOpenCompatibility,
            onOpenConnections = onOpenConnections,
            embedded = true,
            homeStatus = homeStatus,
        )
    }
}

private fun simSummary(sim: SimRadioSnapshot): String {
    val technology = when {
        sim.registeredRat == TelephonyManager.NETWORK_TYPE_IWLAN -> "VoWiFi / IWLAN"
        sim.generation > 0 -> "${sim.generation}G"
        else -> "Technology unavailable"
    }
    val signal = sim.signalLevel?.let { "Signal $it/4" } ?: "Signal unavailable"
    return "$technology · $signal"
}
