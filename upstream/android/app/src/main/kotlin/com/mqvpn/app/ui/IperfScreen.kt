// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mqvpn.app.iperf.*
import com.mqvpn.sdk.core.model.MqvpnState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IperfScreen(vm: IperfViewModel, onNavigateUp: () -> Unit, onOpenTunnel: () -> Unit, onOpenTuning: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val vpnState by vm.vpnState.collectAsStateWithLifecycle()
    val paths by vm.paths.collectAsStateWithLifecycle()
    val saved = remember { vm.savedOptions() }
    var protocol by rememberSaveable { mutableStateOf("TCP + UDP") }
    var direction by rememberSaveable { mutableStateOf("Download") }
    var duration by rememberSaveable { mutableStateOf(saved.durationSeconds.toString()) }
    var streams by rememberSaveable { mutableStateOf(saved.streams.toString()) }
    var udpMbps by rememberSaveable { mutableStateOf(saved.udpTargetMbps.toInt().toString()) }
    var host by rememberSaveable { mutableStateOf(saved.host) }
    var port by rememberSaveable { mutableStateOf(saved.port.toString()) }
    var requireTwo by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var historyExpanded by rememberSaveable { mutableStateOf(false) }
    var expandedHistory by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(ui.running, view) {
        val old = view.keepScreenOn
        if (ui.running) view.keepScreenOn = true
        onDispose { view.keepScreenOn = old }
    }
    val connected = vpnState is MqvpnState.Connected
    val active = paths.count { it.status == 1 }
    val simCount = paths.filter { it.status == 1 }.mapNotNull { simIdentity(it.iface) }.distinct().size
    val simError = if (requireTwo) dualSimTestError(paths) else null
    val udpValue = udpMbps.replace(',', '.').toDoubleOrNull()
    val inputValid = IperfOptions(host = host.trim(), port = port.toIntOrNull() ?: 0,
        durationSeconds = duration.toIntOrNull() ?: 0, streams = streams.toIntOrNull() ?: 0,
        udpTargetMbps = udpValue ?: 0.0).validationError() == null
    val testDirection = if (direction == "Download") IperfDirection.DOWN else IperfDirection.UP
    val chartLabels = ui.totals.associate { it.iface to it.label }
    fun share(report: String) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "MQVPN · iperf test")
            putExtra(Intent.EXTRA_TEXT, report)
        }, "Share result"))
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Speed test MQVPN") }, navigationIcon = {
        IconButton(onClick = onNavigateUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(if (connected) "Tunnel connected · $active active paths" else "Tunnel not connected",
                        style = MaterialTheme.typography.titleSmall)
                    Text("$simCount SIM · Android MTU 1280 · test over MQVPN tunnel",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!connected || active < 2) OutlinedButton(onClick = onOpenTunnel, enabled = !ui.running) {
                Text("Open tunnel and paths")
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    IperfGauge(ui.activeProtocol ?: if (protocol == "UDP") IperfProtocol.UDP else IperfProtocol.TCP,
                        ui.running, ui.intervals, ui.result?.takeIf { it.valid }?.receiverMbps)
                    Text(if (ui.running) "${ui.activeProtocol ?: "Test"} · ${ui.elapsedSeconds} s · real iperf samples"
                        else ui.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
            ChoiceRow("Protocol", listOf("TCP + UDP", "TCP", "UDP"), protocol, !ui.running) { protocol = it }
            ChoiceRow("Direction", listOf("Download", "Upload"), direction, !ui.running) { direction = it }
            if (ui.running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(ui.message, style = MaterialTheme.typography.bodySmall)
                FilledTonalButton(onClick = vm::stop, modifier = Modifier.fillMaxWidth()) { Text("Stop test") }
            } else {
                Button(onClick = { vm.start(IperfOptions(host = host.trim(), port = port.toInt(),
                    protocol = if (protocol == "UDP") IperfProtocol.UDP else IperfProtocol.TCP,
                    direction = testDirection, durationSeconds = duration.toInt(),
                    streams = streams.toInt(), udpTargetMbps = udpValue!!),
                    protocol == "TCP + UDP", requireTwo) },
                    enabled = connected && simError == null && inputValid,
                    modifier = Modifier.fillMaxWidth()) { Text("Start speed test") }
                if (ui.result != null && ui.result?.valid != true)
                    Text(ui.message, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
            }
            if (ui.chart.samples.size > 1) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Connection contributions", style = MaterialTheme.typography.titleMedium)
                        Text(if ((ui.activeDirection ?: testDirection) == IperfDirection.DOWN) "Download · RX by path" else "Upload · TX by path",
                            style = MaterialTheme.typography.bodySmall)
                        IperfPathChart(ui.chart, ui.activeDirection ?: testDirection, chartLabels)
                    }
                }
            }
            if (ui.resultsByProtocol.isNotEmpty()) {
                Text("Measured results", style = MaterialTheme.typography.titleMedium)
                listOf(IperfProtocol.TCP, IperfProtocol.UDP).forEach { kind ->
                    ui.resultsByProtocol[kind]?.let { result ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("$kind · ${if (result.valid) "completed" else "incomplete"}",
                                    style = MaterialTheme.typography.titleSmall)
                                if (result.valid) Text("${number(result.receiverMbps)} Mbit/s received",
                                    style = MaterialTheme.typography.headlineSmall)
                                result.udpLossPercent?.let { Text("Loss ${number(it)} % · jitter ${number(result.udpJitterMs)} ms") }
                                result.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
                if (ui.history.isNotEmpty()) OutlinedButton(onClick = { share(ui.history.first().report) }) {
                    Text("Share latest result")
                }
            }
            TextButton(onClick = { advanced = !advanced }, enabled = !ui.running) {
                Text(if (advanced) "Hide advanced options" else "Advanced options · $host:$port")
            }
            AnimatedVisibility(advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceRow("Duration per protocol", listOf("10", "20", "30", "60"), duration, !ui.running) { duration = it }
                    ChoiceRow("Parallel streams", listOf("1", "4", "8"), streams, !ui.running) { streams = it }
                    if (protocol != "TCP") {
                        OutlinedTextField(value = udpMbps, onValueChange = { udpMbps = it }, enabled = !ui.running,
                            label = { Text("Total UDP load (Mbit/s)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                        Text("UDP load is split across streams. Loss is shown only in the final result.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(value = host, onValueChange = { host = it }, enabled = !ui.running,
                        label = { Text("iperf server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = port, onValueChange = { port = it }, enabled = !ui.running,
                        label = { Text("Port") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = requireTwo, onCheckedChange = { requireTwo = it }, enabled = !ui.running)
                        Text("Only two SIMs", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (simError != null && connected) {
                        Text(simError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onOpenTuning, enabled = !ui.running) { Text("Configure connections") }
                    }
                    Text("TCP uses available bandwidth; UDP uses the selected load. The test uses mobile data.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (ui.totals.isNotEmpty()) {
                Text("Tunnel traffic during test", style = MaterialTheme.typography.titleMedium)
                ui.totals.forEach { p ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(p.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("TX ${testBytes(p.txBytes)} · RX ${testBytes(p.rxBytes)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Includes overhead and traffic from other apps. The gauge uses only iperf intervals.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (ui.history.isNotEmpty()) {
                HorizontalDivider()
                TextButton(onClick = { historyExpanded = !historyExpanded }) {
                    Text(if (historyExpanded) "Hide history" else "History (${ui.history.size})")
                }
                AnimatedVisibility(historyExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ui.history.forEach { h ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp)) {
                                    TextButton(onClick = { expandedHistory = if (expandedHistory == h.title) null else h.title }) {
                                        Text(h.title)
                                    }
                                    if (expandedHistory == h.title) {
                                        Text(h.report.substringBefore("--- iperf JSON ---"), style = MaterialTheme.typography.bodySmall)
                                        if (h.chart.samples.isNotEmpty()) IperfPathChart(h.chart, if (h.title.contains("download")) IperfDirection.DOWN else IperfDirection.UP, chartLabels)
                                        OutlinedButton(onClick = { share(h.report) }) { Text("Share") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ChoiceRow(label: String, choices: List<String>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { value -> FilterChip(selected = value == selected, onClick = { onSelect(value) },
                enabled = enabled, label = { Text(value) }) }
        }
    }
}
private fun number(n: Double?) = n?.let { String.format(Locale.ITALY, "%.2f", it) } ?: "—"
private fun testBytes(n: Long) = when {
    n >= 1_000_000 -> "${number(n / 1_000_000.0)} MB"
    n >= 1000 -> "${number(n / 1000.0)} kB"
    else -> "$n B"
}

