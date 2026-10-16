// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app.ui

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mqvpn.app.data.SettingsRepository
import com.mqvpn.app.iperf.*
import com.mqvpn.sdk.core.MqvpnManager
import com.mqvpn.sdk.core.model.MqvpnState
import com.mqvpn.sdk.core.model.PathInfo
import com.mqvpn.sdk.network.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Counts deltas, including counter resets and paths appearing during the run. */
data class TestPathTotal(val handle: Long, val iface: String, val label: String,
    val txBytes: Long = 0, val rxBytes: Long = 0, val rttMs: Long = 0,
    val status: Int = 0, val present: Boolean = true, val resets: Int = 0)

class TestPathCounter(initial: List<PathInfo>, private val labels: Map<String, String>) {
    private val previous = initial.associateBy { it.handle }.toMutableMap()
    private val totals = initial.associate { p -> p.handle to TestPathTotal(p.handle, p.iface,
        labels[p.iface] ?: p.iface, rttMs = p.srttMs, status = p.status) }.toMutableMap()
    fun sample(paths: List<PathInfo>): List<TestPathTotal> {
        val present = paths.map { it.handle }.toSet()
        totals.replaceAll { handle, value -> value.copy(present = handle in present) }
        paths.forEach { p ->
            val old = previous[p.handle]
            val t = totals[p.handle] ?: TestPathTotal(p.handle, p.iface, labels[p.iface] ?: p.iface)
            val reset = old != null && (p.bytesTx < old.bytesTx || p.bytesRx < old.bytesRx)
            fun delta(now: Long, before: Long?) = if (before == null || now < before) now.coerceAtLeast(0) else now - before
            totals[p.handle] = t.copy(txBytes = t.txBytes + delta(p.bytesTx, old?.bytesTx),
                rxBytes = t.rxBytes + delta(p.bytesRx, old?.bytesRx), rttMs = p.srttMs,
                status = p.status, present = true, resets = t.resets + if (reset) 1 else 0)
            previous[p.handle] = p
        }
        return totals.values.toList()
    }
}

data class SavedIperfRun(val title: String, val report: String, val chart: BandwidthHistoryState = BandwidthHistoryState())
data class IperfUiState(val running: Boolean = false, val message: String = "Ready to test.",
    val elapsedSeconds: Long = 0, val result: IperfResult? = null,
    val activeProtocol: IperfProtocol? = null, val activeDirection: IperfDirection? = null,
    val liveMbps: Double? = null,
    val intervals: List<IperfInterval> = emptyList(),
    val resultsByProtocol: Map<IperfProtocol, IperfResult> = emptyMap(),
    val chart: BandwidthHistoryState = BandwidthHistoryState(),
    val totals: List<TestPathTotal> = emptyList(), val history: List<SavedIperfRun> = emptyList())

@HiltViewModel
class IperfViewModel @Inject constructor(@ApplicationContext private val context: Context,
    private val manager: MqvpnManager, private val runner: IperfRunner,
    private val repository: SettingsRepository) : ViewModel() {
    private val _ui = MutableStateFlow(IperfUiState())
    val ui = _ui.asStateFlow()
    val vpnState = manager.vpnState
    val paths = manager.paths
    private var job: Job? = null
    private var stopReason: String? = null
    private val historyFile get() = File(context.filesDir, "iperf-history.json")
    private val prefs = context.getSharedPreferences("iperf-options", Context.MODE_PRIVATE)
    fun savedOptions() = IperfOptions(host = prefs.getString("host", "10.88.0.1") ?: "10.88.0.1",
        port = prefs.getInt("port", 5201), durationSeconds = prefs.getInt("duration", 20),
        streams = prefs.getInt("streams", 4), udpTargetMbps = prefs.getFloat("udp", 10f).toDouble())
    init {
        viewModelScope.launch(Dispatchers.IO) {
            val history = runCatching {
                val a = JSONArray(historyFile.readText())
                (0 until a.length()).map { a.getJSONObject(it).let { SavedIperfRun(it.getString("title"), it.getString("report"), readChart(it.optJSONArray("chart"))) } }
            }.getOrDefault(emptyList())
            _ui.update { it.copy(history = history) }
        }
    }
    fun stop() { stopReason = "Test stopped by user."; job?.cancel() }
    fun start(options: IperfOptions, bothProtocols: Boolean, requireTwoPaths: Boolean) {
        if (_ui.value.running) return
        if (manager.vpnState.value !is MqvpnState.Connected) {
            _ui.update { it.copy(message = "Connect MQVPN first.") }; return
        }
        if (requireTwoPaths) dualSimTestError(manager.paths.value)?.let { error ->
            _ui.update { it.copy(message = error) }; return
        }
        stopReason = null
        _ui.update { it.copy(running = true, result = null, resultsByProtocol = emptyMap(),
            activeProtocol = null, activeDirection = null, liveMbps = null, intervals = emptyList(),
            totals = emptyList(), elapsedSeconds = 0) }
        job = viewModelScope.launch {
            try {
                val protocols = if (bothProtocols) listOf(IperfProtocol.TCP, IperfProtocol.UDP) else listOf(options.protocol)
                for (protocol in protocols) {
                    ensureActive()
                    if (manager.vpnState.value !is MqvpnState.Connected) error("Tunnel not connected.")
                    if (requireTwoPaths) dualSimTestError(manager.paths.value)?.let { error(it) }
                    runOne(options.copy(protocol = protocol), requireTwoPaths)
                    if (bothProtocols && protocol != protocols.last()) {
                        if (_ui.value.result?.valid != true) {
                            _ui.update { it.copy(message = "Waiting for server before the next protocol…") }
                            delay(12000)
                        } else delay(2000)
                    }
                }
            } catch (e: CancellationException) {
                _ui.update { it.copy(message = stopReason ?: "Test stopped.") }; throw e
            } catch (e: Exception) {
                _ui.update { it.copy(message = "Test incomplete: ${e.message}") }
            } finally {
                _ui.update { it.copy(running = false) }
            }
        }
    }
    private suspend fun runOne(options: IperfOptions, simOnly: Boolean) = coroutineScope {
        prefs.edit().putString("host", options.host).putInt("port", options.port)
            .putInt("duration", options.durationSeconds).putInt("streams", options.streams)
            .putFloat("udp", options.udpTargetMbps.toFloat()).apply()
        val settings = repository.settings.first()
        val labels = withContext(Dispatchers.IO) { runCatching {
            NetworkMonitor(context).snapshotPaths().associate { it.name to it.displayName }
        }.getOrDefault(emptyMap()) }
        val counter = TestPathCounter(manager.paths.value, labels)
        val chart = BandwidthHistory()
        chart.onTick(manager.paths.value, System.nanoTime())
        val started = SystemClock.elapsedRealtime()
        _ui.update { it.copy(message = "${options.protocol} · starting…", result = null,
            activeProtocol = options.protocol, activeDirection = options.direction,
            liveMbps = null, intervals = emptyList(),
            elapsedSeconds = 0, chart = BandwidthHistoryState(), totals = counter.sample(manager.paths.value)) }
        val monitor = launch {
            while (isActive) {
                delay(1000)
                val points = chart.onTick(manager.paths.value, System.nanoTime())
                _ui.update { it.copy(elapsedSeconds = (SystemClock.elapsedRealtime() - started) / 1000,
                    chart = BandwidthHistoryState(points, chart.ifaceSlots()),
                    totals = counter.sample(manager.paths.value)) }
            }
        }
        val guard = launch {
            val reason = combine(manager.vpnState, manager.paths) { state, paths ->
                when {
                    state !is MqvpnState.Connected -> "Tunnel disconnected during test: measurement stopped."
                    simOnly && paths.any { it.status == 1 && simIdentity(it.iface) == null } ->
                        "A non-cellular path joined: SIM-only test stopped."
                    else -> null
                }
            }.filterNotNull().first()
            stopReason = reason
            job?.cancel()
        }
        var result: IperfResult? = null
        try {
            result = runner.run(options,
                onProgress = { text -> _ui.update { it.copy(message = "${options.protocol} · $text") } },
                onInterval = { sample -> if (!sample.omitted) _ui.update { state ->
                    state.copy(liveMbps = sample.mbps,
                        intervals = (state.intervals + sample).takeLast(90))
                } })
        } catch (e: CancellationException) {
            result = IperfResult(error = stopReason ?: "Test stopped.")
            throw e
        } finally {
            // Persist cancellations too, and always stop observers.
            withContext(NonCancellable) {
                monitor.cancelAndJoin(); guard.cancelAndJoin()
                delay(500)
                val totals = counter.sample(manager.paths.value)
                val finished = result ?: IperfResult(error = "Test incomplete.")
                val date = SimpleDateFormat("dd/MM HH:mm:ss", Locale.ITALY).format(Date())
                val title = "$date · ${options.protocol} ${if (options.direction == IperfDirection.DOWN) "download" else "upload"}"
                val report = buildString {
                    appendLine("MQVPN iperf3 · $title")
                    appendLine("${options.host}:${options.port} · ${options.durationSeconds} s · ${options.streams} streams · Android MTU 1280")
                    appendLine("Scheduler ${settings.scheduler} · CC ${settings.congestionControl} · Hybrid ${settings.hybridEnabled}")
                    if (options.protocol == IperfProtocol.UDP) appendLine("UDP offered: ${options.udpTargetMbps} Mbit/s total")
                    appendLine(if (finished.valid) "Completed: ${finished.receiverMbps} Mbit/s received" else "INCOMPLETE: ${finished.error ?: "no result"}")
                    finished.udpLossPercent?.let { appendLine("UDP loss: $it % · jitter ${finished.udpJitterMs} ms") }
                    totals.forEach { appendLine("${it.label}: TX ${it.txBytes} B / RX ${it.rxBytes} B · RTT ${it.rttMs} ms · ${pathStatusName(it.status)} · present ${it.present} · resets ${it.resets}") }
                    appendLine("Per-path counters: all tunnel traffic during the test, including overhead and other apps.")
                    appendLine("Paths with over 16 kB in the test direction: " + totals.count { (if (options.direction == IperfDirection.DOWN) it.rxBytes else it.txBytes) >= 16384 })
                    appendLine("--- iperf JSON ---")
                    appendLine(finished.rawJson ?: "No JSON result")
                }
                val entry = SavedIperfRun(title, report, _ui.value.chart)
                _ui.update { it.copy(result = finished,
                    resultsByProtocol = it.resultsByProtocol + (options.protocol to finished),
                    liveMbps = if (finished.valid) finished.receiverMbps else it.liveMbps,
                    totals = totals,
                    message = if (finished.valid) "${options.protocol} completed." else "${options.protocol}: ${finished.error ?: "invalid result"}",
                    history = (listOf(entry) + it.history).take(12)) }
                withContext(Dispatchers.IO) { runCatching {
                    val a = JSONArray(); _ui.value.history.forEach { a.put(JSONObject().put("title", it.title).put("report", it.report).put("chart", writeChart(it.chart))) }
                    val tmp = File(context.filesDir, "iperf-history.tmp")
                    tmp.writeText(a.toString()); check(tmp.renameTo(historyFile))
                } }
            }
        }
    }
}

private fun writeChart(state: BandwidthHistoryState): JSONArray = JSONArray().apply {
    state.samples.forEach { s ->
        put(JSONObject().put("total", s.totalBps).put("paths", JSONObject(s.perPathBps))
            .put("tx", JSONObject(s.perPathTxBps)).put("rx", JSONObject(s.perPathRxBps)))
    }
}
private fun readChart(a: JSONArray?): BandwidthHistoryState {
    if (a == null) return BandwidthHistoryState()
    fun map(o: JSONObject?): Map<String, Long> = o?.keys()?.asSequence()?.associateWith { o.optLong(it) } ?: emptyMap()
    val samples = (0 until a.length()).map { i -> a.getJSONObject(i).let {
        BandwidthSample(it.optLong("total"), map(it.optJSONObject("paths")), map(it.optJSONObject("tx")), map(it.optJSONObject("rx")))
    } }
    return BandwidthHistoryState(samples, samples.flatMap { it.perPathBps.keys }.distinct().withIndex().associate { it.value to it.index })
}

internal fun simIdentity(iface: String): String? = Regex("^(sim[0-9]+|sub[0-9]+)-").find(iface)?.groupValues?.get(1)
internal fun dualSimTestError(paths: List<PathInfo>): String? {
    val active = paths.filter { it.status == 1 }
    if (active.any { simIdentity(it.iface) == null }) return "To test only SIMs, exclude Wi-Fi, Ethernet, and other paths in connection settings."
    if (active.mapNotNull { simIdentity(it.iface) }.distinct().size < 2) return "Two distinct active SIMs are required. Check paths and SIM identification permission."
    return null
}


