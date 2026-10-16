// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app

import com.mqvpn.app.data.DemoSettings
import com.mqvpn.app.ui.TuningPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TuningPresetTest {
    @Test fun presets_change_scheduler_but_keep_access_and_connections() {
        val original = DemoSettings(
            serverAddress = "example.test", authKey = "secret",
            congestionControl = "CUBIC",
            cellularPathEnabled = false, wifiPathEnabled = true,
            excludedInterfaces = listOf("wlan1"),
            hybridEnabled = true, reorderEnabled = true, serverMtu = 1400,
        )
        val speed = TuningPreset.SPEED.applyTo(original)
        assertEquals("WLB", speed.scheduler)
        assertEquals(TuningPreset.SPEED, TuningPreset.of(speed))
        assertEquals("CUBIC", speed.congestionControl)
        assertEquals("secret", speed.authKey)
        assertEquals(listOf("wlan1"), speed.excludedInterfaces)
        assertEquals(false, speed.cellularPathEnabled)
        assertEquals(false, speed.hybridEnabled)
        assertEquals(false, speed.reorderEnabled)
        assertEquals(1280, speed.serverMtu)

        val stability = TuningPreset.STABILITY.applyTo(speed)
        assertEquals("MIN_RTT", stability.scheduler)
        assertEquals(TuningPreset.STABILITY, TuningPreset.of(stability))
    }

    @Test fun advanced_change_is_custom() {
        assertNull(TuningPreset.of(DemoSettings(hybridEnabled = true)))
        assertNull(TuningPreset.of(DemoSettings(reorderEnabled = true)))
        assertNull(TuningPreset.of(DemoSettings(serverMtu = 1400)))
        assertNull(TuningPreset.of(DemoSettings(scheduler = "WLB_UDP_PIN")))
    }
}
