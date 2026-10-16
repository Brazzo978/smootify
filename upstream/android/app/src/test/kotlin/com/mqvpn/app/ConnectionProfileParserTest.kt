// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app

import com.mqvpn.app.data.ConnectionProfileParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class ConnectionProfileParserTest {
    private fun payload(): JSONObject = JSONObject()
        .put("version", 1)
        .put("name", "Example user")
        .put("serverAddress", "vpn.example.org")
        .put("serverPort", 443)
        .put("tlsServerName", "vpn.example.org")
        .put("authKey", "FAKE_TEST_PSK_ONLY")
        .put("mtu", 1280)
        .put("controlAddress", "10.88.0.1")
        .put("controlPort", 9443)
        .put("iperfHost", "10.88.0.1")
        .put("iperfPort", 5201)

    private fun link(json: JSONObject): String =
        "mqvpn://profile?data=" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8))

    @Test
    fun parsesQrLinkAndIgnoresFutureKeys() {
        val input = payload().put("futureField", "ignored")
        val parsed = ConnectionProfileParser.parse(link(input))
        assertEquals("vpn.example.org", parsed.serverAddress)
        assertEquals("FAKE_TEST_PSK_ONLY", parsed.authKey)
        assertEquals(1280, parsed.mtu)
        assertFalse(parsed.serverControlAllowed)
        assertFalse(parsed.insecure)
    }

    @Test
    fun explicitCapabilityAndTlsSettingAreBoolean() {
        val parsed = ConnectionProfileParser.parse(
            payload().put("serverControlAllowed", true).put("insecure", true).toString(),
        )
        assertTrue(parsed.serverControlAllowed)
        assertTrue(parsed.insecure)
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionProfileParser.parse(payload().put("insecure", "true").toString())
        }
    }

    @Test
    fun rejectsMalformedVersionMtuAndPorts() {
        for (input in listOf(
            "not a profile",
            "mqvpn://profile?data=%%%",
            payload().put("version", 2).toString(),
            payload().put("mtu", 1400).toString(),
            payload().put("serverPort", 0).toString(),
            payload().put("controlPort", 65536).toString(),
            payload().put("iperfPort", "5201").toString(),
            payload().put("serverAddress", "evil/path").toString(),
            payload().put("serverAddress", "1:2:3:4:5:6:7:8:9").toString(),
            payload().put("serverAddress", "2001:db8:::1").toString(),
            link(payload()).replace("://profile", "://user@profile"),
            link(payload()).replace("://profile", "://profile:443"),
            payload().put("authKey", "").toString(),
            payload().put("authKey", "x".repeat(256)).toString(),
            payload().put("authKey", "é".repeat(128)).toString(),
            payload().put("authKey", "user:FAKE_TEST_PSK_ONLY").toString(),
        )) {
            assertThrows(IllegalArgumentException::class.java) { ConnectionProfileParser.parse(input) }
        }
    }

    @Test
    fun rejectsOversizeDecodedJson() {
        val oversized = payload().put("ignored", "x".repeat(ConnectionProfileParser.MAX_JSON_BYTES))
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionProfileParser.parse(link(oversized))
        }
    }
    @Test
    fun qrImageRoundTripPreservesTheUserProfile() {
        val uri = link(payload())
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
            uri, com.google.zxing.BarcodeFormat.QR_CODE, 800, 800)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) 0xFF000000.toInt()
            else 0xFFFFFFFF.toInt()
        }
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(
            com.google.zxing.RGBLuminanceSource(matrix.width, matrix.height, pixels)))
        val scanned = com.google.zxing.qrcode.QRCodeReader().decode(bitmap).text
        val profile = ConnectionProfileParser.parse(scanned)
        assertEquals("vpn.example.org", profile.serverAddress)
        assertEquals("FAKE_TEST_PSK_ONLY", profile.authKey)
        assertFalse(profile.serverControlAllowed)
    }

}


