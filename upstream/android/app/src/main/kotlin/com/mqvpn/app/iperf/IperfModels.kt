// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.iperf

enum class IperfProtocol { TCP, UDP }

/** UP sends from the phone; DOWN asks the server to send to the phone. */
enum class IperfDirection { UP, DOWN }

data class IperfOptions(
    val host: String = "10.88.0.1",
    val port: Int = 5201,
    val protocol: IperfProtocol = IperfProtocol.TCP,
    val direction: IperfDirection = IperfDirection.DOWN,
    val durationSeconds: Int = 10,
    val streams: Int = 1,
    /** Total UDP target across all streams, in Mbit/s. */
    val udpTargetMbps: Double = 10.0,
) {
    fun validationError(): String? = when {
        host.isBlank() || host.length > 253 || !host.matches(Regex("[A-Za-z0-9.:-]+")) || host.startsWith('-') ->
            "Invalid server address"
        port !in 1..65535 -> "Port must be between 1 and 65535"
        durationSeconds !in setOf(10, 20, 30, 60) -> "Unsupported test duration"
        streams !in setOf(1, 4, 8) -> "Unsupported stream count"
        !udpTargetMbps.isFinite() || udpTargetMbps < 0.01 || udpTargetMbps > 10000.0 ->
            "UDP target must be between 0.01 and 10000 Mbit/s"
        else -> null
    }
}

data class IperfResult(
    val receiverMbps: Double? = null,
    val txBytes: Long? = null,
    val rxBytes: Long? = null,
    val udpLossPercent: Double? = null,
    val udpJitterMs: Double? = null,
    val error: String? = null,
    val exitCode: Int? = null,
    val rawJson: String? = null,
) {
    val valid: Boolean get() = error == null && exitCode == 0 && receiverMbps != null
}


/** One real iperf3 --json-stream interval; omitted samples are not test throughput. */
data class IperfInterval(
    val elapsedSeconds: Double,
    val mbps: Double,
    val bytes: Long,
    val omitted: Boolean,
    /** Upload intervals are sender-side; download intervals are receiver-side. */
    val sender: Boolean,
)
