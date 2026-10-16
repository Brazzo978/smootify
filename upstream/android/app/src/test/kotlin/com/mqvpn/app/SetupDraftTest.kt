// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app

import com.mqvpn.app.data.ConnectionProfile
import com.mqvpn.app.data.DemoSettings
import com.mqvpn.app.data.SetupMode
import com.mqvpn.app.ui.SetupDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupDraftTest {
    private fun sampleProfile(serverControlAllowed: Boolean = false) = ConnectionProfile(
        name = "Test VPS",
        serverAddress = "vpn.example.org",
        serverPort = 8443,
        tlsServerName = "tls.example.org",
        authKey = "TEST_PSK",
        mtu = 1280,
        controlAddress = "10.88.0.7",
        controlPort = 9450,
        iperfHost = "10.88.0.8",
        iperfPort = 5202,
        insecure = true,
        serverControlAllowed = serverControlAllowed,
    )

    @Test
    fun scanStagesCompleteProfileWithoutAdvancingOrCompletingWizard() {
        val start = SetupDraft(loaded = true, step = 1, mode = SetupMode.VPN_AND_SIM,
            wifi = false, mobile = true, ethernet = false, excludeAndroidAuto = false)
        val profile = sampleProfile()
        val staged = start.withProfile(profile)

        assertEquals(profile, staged.importedProfile)
        assertEquals(profile.serverAddress, staged.serverAddress)
        assertEquals("8443", staged.serverPort)
        assertEquals(profile.authKey, staged.authKey)
        assertEquals(profile.tlsServerName, staged.tlsServerName)
        assertTrue(staged.insecure)
        assertEquals(profile.iperfHost, staged.importedProfile?.iperfHost)
        assertEquals(profile.iperfPort, staged.importedProfile?.iperfPort)
        assertEquals(start.mode, staged.mode)
        assertEquals(start.step, staged.step)
        assertEquals(start.wifi, staged.wifi)
        assertEquals(start.mobile, staged.mobile)
        assertEquals(start.ethernet, staged.ethernet)
        assertEquals(start.excludeAndroidAuto, staged.excludeAndroidAuto)
        assertFalse(staged.saving)
        assertFalse(staged.finished)
    }

    @Test
    fun saveMappingPreservesUnrelatedLatestSettingsAndProfileCapabilities() {
        val latest = DemoSettings(
            killSwitch = true, scheduler = "ROUND_ROBIN", congestionControl = "CUBIC",
            hybridEnabled = true, bluetoothPathEnabled = true,
            excludedSubscriptionIds = listOf(5), excludedInterfaces = listOf("rmnet0"),
            controlAddress = "stale.example.org", controlPort = 9443,
            serverControlAllowed = true, serverMtu = 1400,
        )
        val staged = SetupDraft(mode = SetupMode.VPN_ONLY,
            wifi = false, mobile = true, ethernet = false,
            excludeAndroidAuto = true).withProfile(sampleProfile())
        val saved = staged.serverSettings(latest)

        assertEquals(staged.serverAddress, saved.serverAddress)
        assertEquals(8443, saved.serverPort)
        assertEquals("TEST_PSK", saved.authKey)
        assertEquals("tls.example.org", saved.tlsServerName)
        assertTrue(saved.insecure)
        assertFalse(saved.wifiPathEnabled)
        assertTrue(saved.cellularPathEnabled)
        assertFalse(saved.ethernetPathEnabled)
        assertTrue(saved.excludeAndroidAutoFromVpn)
        assertEquals("10.88.0.7", saved.controlAddress)
        assertEquals(9450, saved.controlPort)
        assertEquals(1280, saved.serverMtu)
        assertFalse(saved.serverControlAllowed)
        assertTrue(saved.killSwitch)
        assertEquals("ROUND_ROBIN", saved.scheduler)
        assertEquals("CUBIC", saved.congestionControl)
        assertTrue(saved.hybridEnabled)
        assertTrue(saved.bluetoothPathEnabled)
        assertEquals(listOf(5), saved.excludedSubscriptionIds)
        assertEquals(listOf("rmnet0"), saved.excludedInterfaces)
        assertTrue(staged.withProfile(sampleProfile(serverControlAllowed = true))
            .serverSettings(latest).serverControlAllowed)
    }

    @Test
    fun simOnlyIgnoresStagedVpnProfileAtSave() {
        val latest = DemoSettings(serverAddress = "existing.example.org", authKey = "OLD",
            controlAddress = "control.example.org", serverControlAllowed = true)
        val staged = SetupDraft(mode = SetupMode.SIM_ONLY).withProfile(sampleProfile())

        assertEquals(latest, staged.serverSettings(latest))
    }

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
