// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app.data

import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Wire format shared by the VPS installer and the Android importer. */
object ConnectionProfileParser {
    const val MAX_JSON_BYTES = 16 * 1024
    private const val MAX_INPUT_CHARS = 24 * 1024
    private val encodedData = Regex("[A-Za-z0-9_-]+={0,2}")
    private val dnsLabel = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")
    private val ipv6Text = Regex("[0-9A-Fa-f:.]+")

    fun parse(input: String): ConnectionProfile {
        require(input.length <= MAX_INPUT_CHARS) { "Profile is too large." }
        val source = input.trim()
        val jsonBytes = if (source.startsWith("{")) {
            source.toByteArray(StandardCharsets.UTF_8)
        } else {
            val uri = try { URI(source) } catch (_: Exception) { null }
            require(uri?.scheme == "mqvpn" && uri.host == "profile" &&
                (uri.path.isNullOrEmpty() || uri.path == "/") &&
                uri.fragment == null && uri.rawUserInfo == null && uri.port == -1 && uri.rawQuery != null) { "Not an MQVPN profile link." }
            val query = uri.rawQuery!!
            require(query.startsWith("data=") && !query.contains('&')) { "Invalid profile link." }
            val encoded = query.removePrefix("data=")
            require(encoded.length <= MAX_INPUT_CHARS && encodedData.matches(encoded)) { "Invalid profile encoding." }
            try { Base64.getUrlDecoder().decode(encoded) }
            catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid profile encoding.") }
        }
        require(jsonBytes.size in 2..MAX_JSON_BYTES) { "Profile size must be at most 16 KiB." }
        val json = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(jsonBytes)).toString()
        } catch (_: Exception) { throw IllegalArgumentException("Profile text is not valid UTF-8.") }
        val objectValue = try {
            val tokens = JSONTokener(json)
            val value = tokens.nextValue()
            require(value is JSONObject)
            while (true) {
                val trailing = tokens.next()
                if (trailing == '\u0000') break
                require(trailing.isWhitespace())
            }
            value as JSONObject
        } catch (_: Exception) { throw IllegalArgumentException("Invalid profile JSON.") }

        require(integer(objectValue, "version") == 1) { "Unsupported profile version." }
        require(integer(objectValue, "mtu") == 1280) { "This Android build requires MTU 1280." }
        return ConnectionProfile(
            name = text(objectValue, "name", 80),
            serverAddress = host(text(objectValue, "serverAddress", 253), "serverAddress"),
            serverPort = port(objectValue, "serverPort"),
            tlsServerName = optionalText(objectValue, "tlsServerName", 253).let {
                if (it.isEmpty()) it else host(it, "tlsServerName")
            },
            authKey = text(objectValue, "authKey", 255).also {
                require(it.toByteArray(StandardCharsets.UTF_8).size <= 255) { "Authentication key is too long." }
                require(!it.contains(':') && it.none(Char::isWhitespace)) {
                    "authKey must contain the user PSK only."
                }
            },
            mtu = 1280,
            controlAddress = host(text(objectValue, "controlAddress", 253), "controlAddress"),
            controlPort = port(objectValue, "controlPort"),
            iperfHost = host(text(objectValue, "iperfHost", 253), "iperfHost"),
            iperfPort = port(objectValue, "iperfPort"),
            insecure = optionalBoolean(objectValue, "insecure", false),
            serverControlAllowed = optionalBoolean(objectValue, "serverControlAllowed", false),
        )
    }

    private fun text(json: JSONObject, key: String, max: Int): String {
        val value = json.opt(key)
        require(value is String && value.isNotBlank() && value.length <= max &&
            value == value.trim() && value.none { Character.isISOControl(it) }) { "Invalid $key." }
        return value
    }

    private fun optionalText(json: JSONObject, key: String, max: Int): String {
        val value = json.opt(key)
        require(value is String && value.length <= max && value == value.trim() &&
            value.none { Character.isISOControl(it) }) { "Invalid $key." }
        return value
    }

    private fun integer(json: JSONObject, key: String): Int {
        val value = json.opt(key)
        require(value is Number && value.toDouble().isFinite() &&
            value.toDouble() == value.toInt().toDouble()) { "Invalid $key." }
        return value.toInt()
    }

    private fun port(json: JSONObject, key: String): Int =
        integer(json, key).also { require(it in 1..65535) { "Invalid $key." } }

    private fun optionalBoolean(json: JSONObject, key: String, default: Boolean): Boolean {
        if (!json.has(key)) return default
        val value = json.opt(key)
        require(value is Boolean) { "Invalid $key." }
        return value
    }

    private fun host(value: String, key: String): String {
        val valid = if (':' in value) {
            ipv6Text.matches(value) && runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)
        } else {
            value.split('.').all { it.length in 1..63 && dnsLabel.matches(it) }
        }
        require(valid) { "Invalid $key." }
        return value
    }
}


