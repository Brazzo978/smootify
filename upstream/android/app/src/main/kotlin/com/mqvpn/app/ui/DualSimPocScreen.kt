// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mqvpn.app.data.DemoSettings
import com.mqvpn.app.data.SettingsRepository
import com.mqvpn.sdk.native_.NativeBridge
import com.mqvpn.sdk.network.NetworkMonitor
import com.mqvpn.sdk.network.NetworkPath
import com.mqvpn.sdk.network.PathType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.net.*
import javax.inject.Inject
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Application-payload bytes measured by this diagnostic, not VPN/wire counters. */
data class SimProbeResult(
    val handle: Long,
    val label: String,
    val iface: String,
    val imsOnly: Boolean,
    val status: String = "Ready",
    val publicIp: String = "—",
    val httpsOk: Int = 0,
    val udpOk: Int = 0,
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val elapsedMs: Long = 0,
    val error: String? = null,
)

@HiltViewModel
class DualSimPocViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    repository: SettingsRepository,
) : ViewModel() {
    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DemoSettings())
    private val _paths = MutableStateFlow<List<SimProbeResult>>(emptyList())
    val paths = _paths.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _message = MutableStateFlow("This test runs outside the tunnel and does not require a VPS.")
    val message = _message.asStateFlow()
    private val _native = MutableStateFlow("Checking MQVPN engine…")
    val native = _native.asStateFlow()
    private var job: Job? = null

    init {
        refresh()
        viewModelScope.launch(Dispatchers.IO) {
            _native.value = runCatching {
                val cfg = NativeBridge.configNew()
                check(cfg != 0L) { "configNew returned null" }
                try { check(NativeBridge.configSetMultipath(cfg, true) == 0) { "Multipath could not be initialized" } }
                finally { NativeBridge.configFree(cfg) }
                "MQVPN engine 0.17.0 loaded · multipath available"
            }.getOrElse { "MQVPN engine error: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    private fun snapshot(): List<NetworkPath> = NetworkMonitor(context).snapshotPaths()
        .filter { it.type == PathType.CELLULAR }
        .distinctBy { it.network.networkHandle }
        .sortedBy { it.name }

    private fun row(path: NetworkPath): SimProbeResult {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(path.network)
        return SimProbeResult(
            handle = path.network.networkHandle,
            label = path.displayName,
            iface = path.interfaceName ?: cm.getLinkProperties(path.network)?.interfaceName ?: path.name,
            imsOnly = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true,
        )
    }

    fun refresh() {
        if (_running.value) return
        runCatching { snapshot().map(::row) }
            .onSuccess { _paths.value = it; _message.value = "${it.size} cellular paths visible to the app. Test without a VPS available." }
            .onFailure { _message.value = "Could not read networks: ${it.message}" }
    }

    private fun update(handle: Long, change: (SimProbeResult) -> SimProbeResult) {
        _paths.update { list -> list.map { if (it.handle == handle) change(it) else it } }
    }

    fun startTest() {
        if (_running.value) return
        job = viewModelScope.launch {
            _running.value = true
            try {
                val available = snapshot()
                _paths.value = available.map(::row)
                if (available.isEmpty()) {
                    _message.value = "No cellular path available. Check mobile data and APN profiles."
                    return@launch
                }
                _message.value = "Sending HTTPS and UDP DNS requests separately over each cellular network…"
                coroutineScope {
                    available.map { path -> async(Dispatchers.IO) { testPath(path) } }.awaitAll()
                }
                val results = _paths.value
                val working = results.count { it.httpsOk > 0 }
                _message.value = if (working >= 2) "PoC succeeded: $working cellular paths responded over HTTPS in the same test."
                    else "Test finished: $working working HTTPS paths out of ${available.size}."
            } catch (e: CancellationException) {
                _message.value = "Test stopped."
                throw e
            } catch (e: Exception) {
                _message.value = "Test fallito: ${e.message}"
            } finally { _running.value = false }
        }
    }

    fun stopTest() { job?.cancel() }

    private suspend fun testPath(path: NetworkPath) {
        val handle = path.network.networkHandle
        update(handle) { it.copy(status = "Test in progress", error = null) }
        repeat(3) {
            currentCoroutineContext().ensureActive()
            val started = System.nanoTime()
            try {
                val request = "GET /cdn-cgi/trace HTTP/1.1\r\nHost: one.one.one.one\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
                Socket().use { raw ->
                    path.network.bindSocket(raw)
                    raw.connect(InetSocketAddress("1.1.1.1", 443), 5000)
                    raw.soTimeout = 5000
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    (factory.createSocket(raw, "one.one.one.one", 443, true) as SSLSocket).use { tls ->
                        tls.soTimeout = 5000
                        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                        tls.startHandshake()
                        tls.outputStream.write(request)
                        update(handle) { it.copy(txBytes = it.txBytes + request.size) }
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(2048)
                        while (bytes.size() < 16384) {
                            val n = tls.inputStream.read(buffer)
                            if (n < 0) break
                            bytes.write(buffer, 0, n)
                            update(handle) { it.copy(rxBytes = it.rxBytes + n) }
                        }
                        val body = bytes.toString("US-ASCII")
                        check(body.startsWith("HTTP/1.1 200")) { "Unexpected HTTP response" }
                        val ip = body.lineSequence().firstOrNull { it.startsWith("ip=") }?.removePrefix("ip=")?.trim()
                            ?: error("Exit IP not returned")
                        update(handle) { it.copy(httpsOk = it.httpsOk + 1, publicIp = ip,
                            elapsedMs = (System.nanoTime() - started) / 1_000_000, error = null) }
                    }
                }
            } catch (e: Exception) {
                update(handle) { it.copy(error = "HTTPS: ${e.message ?: e.javaClass.simpleName}") }
            }
            delay(500)
        }
        currentCoroutineContext().ensureActive()
        try {
            val query = byteArrayOf(0x51,0x29,1,0,0,1,0,0,0,0,0,0,7,101,120,97,109,112,108,101,3,99,111,109,0,0,1,0,1)
            DatagramSocket().use { socket ->
                path.network.bindSocket(socket)
                socket.soTimeout = 5000
                socket.connect(InetAddress.getByName("1.1.1.1"), 53)
                socket.send(DatagramPacket(query, query.size))
                update(handle) { it.copy(txBytes = it.txBytes + query.size) }
                val packet = DatagramPacket(ByteArray(1500), 1500)
                socket.receive(packet)
                check(packet.length >= 12 && packet.data[0] == query[0] && packet.data[1] == query[1] && (packet.data[2].toInt() and 0x80) != 0) { "Unexpected DNS response" }
                update(handle) { it.copy(udpOk = it.udpOk + 1, rxBytes = it.rxBytes + packet.length) }
            }
        } catch (e: Exception) {
            update(handle) { it.copy(error = listOfNotNull(it.error, "UDP: ${e.message}").joinToString("; ")) }
        }
        update(handle) { it.copy(status = if (it.httpsOk > 0) "Internet verified" else "Connection not verified") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DualSimPocScreen(
    onOpenTunnel: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTuning: () -> Unit,
    onOpenIperf: () -> Unit,
    viewModel: DualSimPocViewModel = hiltViewModel(),
) {
    val rows by viewModel.paths.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val native by viewModel.native.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val phonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refresh() }
    Scaffold(topBar = { TopAppBar(title = { Text("Cellular diagnostics") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Check mobile networks", style = MaterialTheme.typography.headlineSmall)
            Text(native, style = MaterialTheme.typography.bodySmall)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Server MQVPN", style = MaterialTheme.typography.titleMedium)
                    Text(if (settings.serverAddress.isBlank()) "Not configured · you can still test both SIMs" else "${settings.serverAddress}:${settings.serverPort}")
                    Button(onClick = onOpenSettings, enabled = !running, modifier = Modifier.fillMaxWidth()) { Text("Server and password") }
                    OutlinedButton(onClick = onOpenTunnel, enabled = !running && settings.serverAddress.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Open MQVPN tunnel and counters") }
                }
            }
            OutlinedButton(onClick = onOpenIperf, modifier = Modifier.fillMaxWidth()) { Text("Test iperf TCP / UDP") }
            TextButton(onClick = onOpenTuning, modifier = Modifier.fillMaxWidth()) { Text("Aggregation, scheduler, and server") }
            Text("Test SIMs without a VPS", style = MaterialTheme.typography.titleMedium)
            Text("HTTPS and UDP DNS on each path. Bytes below are test payload; traffic to the MQVPN server appears in the tunnel view.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (running) viewModel.stopTest() else viewModel.startTest() }) { Text(if (running) "Stop test" else "Test both SIMs") }
                OutlinedButton(onClick = { viewModel.refresh() }, enabled = !running) { Text("Refresh") }
            }
            Text(message, color = if (rows.count { it.httpsOk > 0 } >= 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            rows.forEach { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(row.label, style = MaterialTheme.typography.titleMedium)
                        Text("${row.iface} · ${if (row.imsOnly) "IMS path" else "Internet path"}", style = MaterialTheme.typography.bodySmall)
                        Text(row.status, color = if (row.httpsOk > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text("Exit IP: ${row.publicIp}")
                        Text("HTTPS ${row.httpsOk}/3 · UDP DNS ${row.udpOk}/1 · last request ${row.elapsedMs} ms")
                        Text("Payload TX ${row.txBytes} B  ·  RX ${row.rxBytes} B")
                        row.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                TextButton(onClick = { phonePermission.launch(Manifest.permission.READ_PHONE_STATE) }, enabled = !running) { Text("Allow SIM identification") }
            }
            Text("PoC for two cellular networks. The tunnel needs an MQVPN VPS reachable over UDP and the same Auth Key on the server and in the app.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
        }
    }
}
