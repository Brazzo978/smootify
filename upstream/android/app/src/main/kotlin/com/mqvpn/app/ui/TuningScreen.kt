// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors
package com.mqvpn.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mqvpn.app.data.DemoSettings
import com.mqvpn.app.data.ServerControlClient
import com.mqvpn.sdk.core.model.MqvpnConfig
import com.mqvpn.sdk.core.model.MqvpnState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuningScreen(onNavigateUp: () -> Unit, viewModel: TuningViewModel = hiltViewModel()) {
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val remote by viewModel.remote.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    var seeded by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var scheduler by rememberSaveable { mutableStateOf("MIN_RTT") }
    var cc by rememberSaveable { mutableStateOf("BBR2") }
    var multipath by rememberSaveable { mutableStateOf(true) }
    var hybrid by rememberSaveable { mutableStateOf(false) }
    var hybridMode by rememberSaveable { mutableStateOf(MqvpnConfig.HybridTcpMode.AUTO.name) }
    var reorder by rememberSaveable { mutableStateOf(false) }
    var reorderProfile by rememberSaveable { mutableStateOf(MqvpnConfig.ReorderProfile.CELLULAR_BOND.name) }
    var reorderPorts by rememberSaveable { mutableStateOf("443") }
    var mtu by rememberSaveable { mutableStateOf("1280") }
    var controlAddress by rememberSaveable { mutableStateOf("10.88.0.1") }
    var controlPort by rememberSaveable { mutableStateOf("9443") }

    val current = loaded
    if (current != null && !seeded) {
        val source = viewModel.tuningDraftSnapshot() ?: current
        scheduler = source.scheduler
        cc = source.congestionControl
        multipath = source.multipathEnabled
        hybrid = source.hybridEnabled
        hybridMode = source.hybridTcpMode
        reorder = source.reorderEnabled
        reorderProfile = source.reorderProfile
        reorderPorts = source.reorderPorts
        mtu = source.serverMtu.toString()
        controlAddress = source.controlAddress
        controlPort = source.controlPort.toString()
        seeded = true
    }
    val draft = (current ?: DemoSettings()).copy(
        scheduler = scheduler,
        congestionControl = cc,
        multipathEnabled = multipath,
        hybridEnabled = hybrid,
        hybridTcpMode = hybridMode,
        reorderEnabled = reorder,
        reorderProfile = reorderProfile,
        reorderPorts = reorderPorts,
        serverMtu = mtu.toIntOrNull() ?: -1,
        controlAddress = controlAddress,
        controlPort = controlPort.toIntOrNull() ?: -1,
    )
    val preset = TuningPreset.of(draft)
    val editable = current != null && !busy
    val connected = vpnState is MqvpnState.Connected
    val serverControlAllowed = current?.serverControlAllowed == true
    val dirty = current != null && (
        current.scheduler != scheduler || current.congestionControl != cc ||
        current.multipathEnabled != multipath || current.hybridEnabled != hybrid ||
        current.hybridTcpMode != hybridMode || current.reorderEnabled != reorder ||
        current.reorderProfile != reorderProfile || current.reorderPorts != reorderPorts ||
        current.serverMtu.toString() != mtu || current.controlAddress != controlAddress ||
        current.controlPort.toString() != controlPort)
    SideEffect {
        if (seeded && current != null) {
            if (dirty) viewModel.retainTuningDraft(draft) else viewModel.clearTuningDraft()
        }
    }
    val serverSchedulers = remote?.optJSONObject("capabilities")?.optJSONArray("scheduler")?.let { values ->
        (0 until values.length()).map { values.getString(it) }.toSet()
    }
    val schedulerOptions = MqvpnConfig.Scheduler.entries.filter { option ->
        if (serverSchedulers != null) ServerControlClient.schedulerWire(option.name) in serverSchedulers
        else option != MqvpnConfig.Scheduler.BACKUP_FEC
    }

    fun choose(selected: TuningPreset) {
        val next = selected.applyTo(draft)
        scheduler = next.scheduler
        multipath = next.multipathEnabled
        hybrid = next.hybridEnabled
        reorder = next.reorderEnabled
        mtu = next.serverMtu.toString()
    }

    BackHandler(enabled = busy) {}
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Tunnel mode") },
            navigationIcon = {
                IconButton(onClick = onNavigateUp, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            Text("How to use networks", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Speed spreads traffic across available paths. Stability favors the path with lower latency.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Profile in form: ${preset?.label ?: "Custom"}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            PresetCard(
                label = "Speed",
                description = "Weighted load balancing (WLB) uses multiple paths when available.",
                selected = preset == TuningPreset.SPEED,
                enabled = editable,
                tint = MaterialTheme.colorScheme.primary,
                icon = Icons.Default.Bolt,
                onClick = { choose(TuningPreset.SPEED) },
            )
            PresetCard(
                label = "Stability",
                description = "Favors the lowest-RTT path (MinRTT), useful for jitter-sensitive traffic.",
                selected = preset == TuningPreset.STABILITY,
                enabled = editable,
                tint = MaterialTheme.colorScheme.tertiary,
                icon = Icons.Default.Shield,
                onClick = { choose(TuningPreset.STABILITY) },
            )
            Text(
                "Selecting a preset changes only this form: enables multipath, disables Hybrid and reordering, and resets the server MTU field to 1280. It keeps CC and network choices. The server changes only when you tap “Apply to server too”.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (dirty) Text("Changes not yet saved on the phone",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ viewModel.save(draft, false) }, enabled = editable && dirty,
                    modifier = Modifier.weight(1f)) { Text("Save client") }
                Button({ viewModel.save(draft, true) }, enabled = editable && dirty && connected,
                    modifier = Modifier.weight(1f)) { Text("Apply and reconnect") }
            }
            Button(onClick = { viewModel.sync(draft) }, enabled = editable && connected && serverControlAllowed,
                modifier = Modifier.fillMaxWidth()) { Text("Apply to server too") }
            if (current != null && !serverControlAllowed) Text(
                "This profile can use the VPN but cannot administer the server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Reconnects the tunnel and syncs scheduler, CC, and MTU on the VPS for all clients.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Saving or applying client settings does not change the VPS. The Android client always uses MTU 1280.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (expanded) "Hide advanced options" else "Show advanced options")
            }
            if (expanded) {
                Text("For advanced users", style = MaterialTheme.typography.titleLarge)
                LabeledSwitchRow("Use multiple networks together", multipath, { multipath = it }, editable)
                EnumDropdownField(
                    "Scheduler", schedulerOptions, draft.schedulerEnum(),
                    { item ->
                        when (item) {
                            MqvpnConfig.Scheduler.MIN_RTT -> "minrtt · lowest RTT"
                            MqvpnConfig.Scheduler.WLB -> "wlb · weighted balancing"
                            MqvpnConfig.Scheduler.WLB_UDP_PIN -> "wlb_udp_pin · UDP affinity"
                            MqvpnConfig.Scheduler.BACKUP_FEC -> "backup_fec · experimental"
                        }
                    }, { scheduler = it.name }, editable,
                )
                EnumDropdownField("Congestion control (CC)", listOf("BBR2", "BBR", "CUBIC"), cc,
                    { if (it == "BBR2") "BBR2 (bbr2)" else it }, { cc = it }, editable)
                Text("The scheduler chooses a path; CC controls the sending rate. Client and server can use different settings.",
                    style = MaterialTheme.typography.bodySmall)
                LabeledSwitchRow("Hybrid TCP", hybrid, { hybrid = it }, editable)
                if (hybrid) EnumDropdownField(
                    "Hybrid TCP mode", MqvpnConfig.HybridTcpMode.entries.toList(),
                    draft.hybridTcpModeEnum(), { it.name.lowercase() },
                    { hybridMode = it.name }, editable,
                )
                LabeledSwitchRow("UDP packet reordering", reorder, { reorder = it }, editable)
                if (reorder) {
                    EnumDropdownField(
                        "Reordering profile", MqvpnConfig.ReorderProfile.entries.toList(),
                        draft.reorderProfileEnum(), { it.name.lowercase().replace('_', ' ') },
                        { reorderProfile = it.name }, editable,
                    )
                    OutlinedTextField(reorderPorts, { reorderPorts = it },
                        label = { Text("UDP ports to reorder") },
                        supportingText = { Text("Comma-separated numbers, for example 443, 4500.") },
                        modifier = Modifier.fillMaxWidth(), enabled = editable)
                }
                HorizontalDivider()
                Text("VPS control", style = MaterialTheme.typography.titleLarge)
                Text("Authenticated requests travel through the tunnel. Syncing restarts MQVPN on the VPS and briefly interrupts other clients too.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(mtu, { mtu = it }, label = { Text("Server MTU") },
                    supportingText = { Text("1280–9000 on the VPS; Android always stays at 1280.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = editable, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(controlAddress, { controlAddress = it },
                    label = { Text("Server IP inside tunnel") },
                    enabled = editable, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(controlPort, { controlPort = it },
                    label = { Text("Control port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = editable, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ viewModel.readServer(draft) }, enabled = editable && connected && serverControlAllowed,
                        modifier = Modifier.weight(1f)) { Text("Read server") }
                    Button({ viewModel.sync(draft) }, enabled = editable && connected && serverControlAllowed,
                        modifier = Modifier.weight(1f)) { Text("Sync and reconnect") }
                }
                remote?.let { response ->
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Server status: ${response.optString("status", "unknown")}",
                                style = MaterialTheme.typography.titleMedium)
                            val desired = response.optJSONObject("desired")
                            val applied = response.optJSONObject("applied")
                            Text("Saved on VPS: ${desired?.optString("scheduler") ?: "?"} / ${desired?.optString("cc") ?: "?"} / MTU ${desired?.optInt("mtu") ?: "?"}")
                            Text("Last confirmed values: ${applied?.optString("scheduler") ?: "unconfirmed"} / ${applied?.optString("cc") ?: "?"} / MTU ${applied?.optInt("mtu") ?: "?"}")
                            Text("Observed scheduler: ${if (response.isNull("observedScheduler")) "unavailable" else response.optString("observedScheduler")}")
                            if (!response.isNull("error")) Text(response.optString("error"),
                                color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = {
                                val values = applied ?: desired
                                if (values != null) runCatching {
                                    scheduler = ServerControlClient.schedulerSetting(values.getString("scheduler"))
                                    cc = values.getString("cc").uppercase()
                                    mtu = values.getInt("mtu").toString()
                                }
                            }, enabled = editable) { Text("Import values into form") }
                        }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PresetCard(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    tint: Color,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) tint.copy(alpha = 0.16f)
                             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null, tint = tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.titleLarge)
                Text(description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Text("Selected", style = MaterialTheme.typography.labelMedium, color = tint)
        }
    }
}
