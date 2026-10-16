// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app

import com.mqvpn.app.data.SetupMode
import com.mqvpn.app.ui.SetupDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupDraftTest {
    @Test
    fun simOnlySkipsVpnStepsInBothDirections() {
        val start = SetupDraft(mode = SetupMode.SIM_ONLY)
        assertEquals(3, start.stepCount)
        assertEquals(1, start.displayedStep)
        assertEquals(3, start.nextStep())
        val compatibility = start.copy(step = start.nextStep())
        assertEquals(2, compatibility.displayedStep)
        assertEquals(0, compatibility.previousStep())
        assertEquals(4, compatibility.nextStep())
        assertEquals(3, compatibility.copy(step = 4).displayedStep)
    }

    @Test
    fun vpnRequiresServerCredentialsAndOneTransport() {
        val empty = SetupDraft(mode = SetupMode.VPN_ONLY)
        assertFalse(empty.validServer)
        assertTrue(empty.validTransport)
        assertTrue(empty.copy(serverAddress = "vpn.example.org", serverPort = "443", authKey = "secret").validServer)
        assertFalse(empty.copy(serverAddress = "vpn.example.org", serverPort = "65536", authKey = "secret").validServer)
        assertFalse(empty.copy(wifi = false, mobile = false, ethernet = false).validTransport)
        assertEquals(1, empty.nextStep())
        assertEquals(5, empty.stepCount)
    }
}
