// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors
package com.mqvpn.app.data

import org.json.JSONObject
import java.io.IOException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The companion listens inside the encrypted VPN. Never bind/protect this socket to a WAN. */
object ServerControlClient {
    fun request(settings: DemoSettings, desired: JSONObject? = null): JSONObject {
        require(settings.authKey.isNotBlank()) { "Auth Key missing" }
        require(settings.controlPort in 1..65535) { "Invalid control port" }
        val body = JSONObject().put("version", 1).put("op", if (desired == null) "get" else "set")
            .put("ts", System.currentTimeMillis() / 1000)
            .put("nonce", UUID.randomUUID().toString().replace("-", ""))
        if (desired != null) body.put("desired", desired)
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(settings.authKey.trim().toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = hmac.doFinal(canonical(body).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        body.put("mac", signature)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(settings.controlAddress.trim(), settings.controlPort), 6000)
            socket.soTimeout = 10000
            socket.getOutputStream().write((canonical(body) + "\n").toByteArray(Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val line = StringBuilder()
            while (line.length < 131072) {
                val c = reader.read()
                if (c < 0 || c == 10) break
                line.append(c.toChar())
            }
            if (line.isEmpty() || line.length >= 131072) throw IOException("Empty or oversized control response")
            val response = JSONObject(line.toString())
            check(response.optBoolean("ok")) { response.optString("error", "The server rejected the request") }
            return response
        }
    }

    internal fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> error("Unsupported JSON type")
    }

    fun schedulerWire(value: String): String = when (value) {
        "MIN_RTT" -> "minrtt"
        "WLB" -> "wlb"
        "WLB_UDP_PIN" -> "wlb_udp_pin"
        "BACKUP_FEC" -> "backup_fec"
        else -> error("Unsupported scheduler")
    }
    fun schedulerSetting(value: String): String = when (value) {
        "minrtt" -> "MIN_RTT"
        "wlb" -> "WLB"
        "wlb_udp_pin" -> "WLB_UDP_PIN"
        "backup_fec" -> "BACKUP_FEC"
        else -> error("Unsupported server scheduler: $value")
    }
}
