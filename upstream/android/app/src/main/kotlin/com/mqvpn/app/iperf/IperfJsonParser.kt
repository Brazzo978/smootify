// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.iperf

import org.json.JSONObject

/** Final receiver summary is authoritative; streamed intervals only drive the live gauge. */
internal object IperfJsonParser {
    sealed interface StreamEvent {
        data class Interval(val sample: IperfInterval) : StreamEvent
        data class End(val data: JSONObject) : StreamEvent
        data class Error(val message: String) : StreamEvent
        data object Other : StreamEvent
    }

    /** ESnet iperf3 3.18 --json-stream emits one {event,data} object per line. */
    fun streamEvent(line: String, direction: IperfDirection): StreamEvent {
        val root = try { JSONObject(line) } catch (_: Exception) { return StreamEvent.Other }
        val data = root.optJSONObject("data")
        return when (root.optString("event")) {
            "interval" -> data?.let { interval(it, direction) }?.let { StreamEvent.Interval(it) } ?: StreamEvent.Other
            "end" -> data?.let { StreamEvent.End(it) } ?: StreamEvent.Other
            "error" -> StreamEvent.Error((root.opt("data") as? String)?.takeIf { it.isNotBlank() }
                ?: data?.optString("error")?.takeIf { it.isNotBlank() }
                ?: "iperf3 reported an error")
            else -> StreamEvent.Other
        }
    }

    private fun interval(data: JSONObject, direction: IperfDirection): IperfInterval? {
        val expectedSender = direction == IperfDirection.UP
        // iperf supplies an aggregate `sum` for parallel streams. Never add it to
        // `streams`; those are the same bytes, and doing so doubles the gauge.
        val aggregate = data.optJSONObject("sum") ?: data.optJSONObject(
            if (expectedSender) "sum_sent" else "sum_received")
        if (aggregate != null && (!aggregate.has("sender") || aggregate.optBoolean("sender") == expectedSender)) {
            return aggregate.toInterval(expectedSender)
        }
        val streams = data.optJSONArray("streams") ?: return null
        var bytes = 0L
        var bps = 0.0
        var end = 0.0
        var omitted = true
        var seen = 0
        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            if (s.has("sender") && s.optBoolean("sender") != expectedSender) continue
            val sample = s.toInterval(expectedSender) ?: continue
            bytes = safeAdd(bytes, sample.bytes) ?: return null
            bps += sample.mbps * 1_000_000.0
            end = maxOf(end, sample.elapsedSeconds)
            omitted = omitted && sample.omitted
            seen++
        }
        return if (seen > 0 && bps.isFinite())
            IperfInterval(end, bps / 1_000_000.0, bytes, omitted, expectedSender) else null
    }

    private fun JSONObject.toInterval(expectedSender: Boolean): IperfInterval? {
        val bps = finiteNumber("bits_per_second")?.takeIf { it >= 0.0 } ?: return null
        val end = finiteNumber("end")?.takeIf { it >= 0.0 } ?: return null
        val bytes = nonnegativeLong("bytes") ?: return null
        return IperfInterval(end, bps / 1_000_000.0, bytes, optBoolean("omitted"), expectedSender)
    }

    fun parseStreamFinal(end: JSONObject?, streamError: String?, protocol: IperfProtocol,
        exitCode: Int, raw: String): IperfResult {
        if (end == null) return IperfResult(error = streamError ?: "iperf3 returned no final receiver summary",
            exitCode = exitCode, rawJson = raw)
        val root = JSONObject().put("end", end)
        if (streamError != null) root.put("error", streamError)
        return parse(root.toString(), protocol, exitCode).copy(rawJson = raw)
    }

    fun parse(raw: String, protocol: IperfProtocol, exitCode: Int): IperfResult {
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return IperfResult(error = "iperf3 returned invalid JSON", exitCode = exitCode, rawJson = raw)
        }
        val iperfError = root.optString("error").takeIf { it.isNotBlank() }
        val end = root.optJSONObject("end")
        val sent = end?.optJSONObject("sum_sent")
        val received = end?.optJSONObject("sum_received")
        val udpSum = end?.optJSONObject("sum")
        val receiver = received ?: if (protocol == IperfProtocol.UDP)
            udpSum?.takeIf { it.has("sender") && !it.optBoolean("sender") } else null
        val bps = receiver?.finiteNumber("bits_per_second")?.takeIf { it >= 0.0 }
        val failure = when {
            iperfError != null -> iperfError
            exitCode != 0 -> "iperf3 exited with code $exitCode"
            end == null || bps == null -> "iperf3 returned no final receiver summary"
            else -> null
        }
        return IperfResult(
            receiverMbps = if (failure == null) bps!! / 1_000_000.0 else null,
            txBytes = sent?.nonnegativeLong("bytes"),
            rxBytes = receiver?.nonnegativeLong("bytes"),
            udpLossPercent = if (protocol == IperfProtocol.UDP) receiver?.finiteNumber("lost_percent") else null,
            udpJitterMs = if (protocol == IperfProtocol.UDP) receiver?.finiteNumber("jitter_ms") else null,
            error = failure,
            exitCode = exitCode,
            rawJson = raw,
        )
    }

    private fun safeAdd(a: Long, b: Long): Long? = if (Long.MAX_VALUE - a < b) null else a + b
    private fun JSONObject.finiteNumber(key: String): Double? =
        opt(key)?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() }
    private fun JSONObject.nonnegativeLong(key: String): Long? =
        opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }
}


