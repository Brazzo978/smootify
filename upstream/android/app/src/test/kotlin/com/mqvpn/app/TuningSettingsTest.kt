// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors
package com.mqvpn.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.mqvpn.app.data.DemoSettings
import com.mqvpn.app.data.SettingsRepository
import com.mqvpn.sdk.core.model.MqvpnConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TuningSettingsTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            return transform(data.value).also { data.value = it }
        }
    }
    @Test fun `advanced preferences round trip without losing credentials or selection`() = runTest {
        val repo = SettingsRepository(MemoryStore())
        val input = DemoSettings(serverAddress = "vpn.example.test", authKey = "test-only-key", insecure = true,
            scheduler = "WLB_UDP_PIN", congestionControl = "CUBIC", multipathEnabled = false,
            cellularPathEnabled = false, wifiPathEnabled = false, ethernetPathEnabled = true,
            bluetoothPathEnabled = true, excludedSubscriptionIds = listOf(2,6),
            excludedInterfaces = listOf("eth0","wlan1"), controlAddress = "10.88.0.1", controlPort = 9450, serverMtu = 1400)
        repo.save(input)
        assertEquals(input, repo.settings.first())
    }
    @Test fun `motor and selected networks reach the SDK configuration`() {
        val input = DemoSettings(serverAddress = "vpn.example.test", authKey = "test-only-key", scheduler = "WLB",
            congestionControl = "BBR", cellularPathEnabled = false, wifiPathEnabled = true,
            ethernetPathEnabled = false, excludedSubscriptionIds = listOf(6), excludedInterfaces = listOf("wlan1"))
        val config = input.toMqvpnConfig()
        assertEquals(MqvpnConfig.Scheduler.WLB, config.scheduler)
        assertEquals(MqvpnConfig.CongestionControl.BBR, config.congestionControl)
        assertFalse(config.cellularPathEnabled);assertTrue(config.wifiPathEnabled);assertFalse(config.ethernetPathEnabled)
        assertEquals(listOf(6), config.excludedSubscriptionIds)
        assertEquals(listOf("wlan1"), config.excludedInterfaces)
    }
    @Test fun `unknown motor preferences fall back to supported defaults`() {
        assertEquals(MqvpnConfig.Scheduler.MIN_RTT, DemoSettings(scheduler = "obsolete").schedulerEnum())
        assertEquals(MqvpnConfig.CongestionControl.BBR2, DemoSettings(congestionControl = "obsolete").congestionControlEnum())
    }
}
