package com.mqvpn.app.ui

import com.mqvpn.sdk.core.model.PathInfo
import org.junit.Assert.*
import org.junit.Test

class TestPathCounterTest {
    private fun path(id: Long, tx: Long, rx: Long) = PathInfo(id, 1, "sim$id", tx, rx, 40)
    @Test fun excludesTrafficBeforeTestAndCountsBothPaths() {
        val c = TestPathCounter(listOf(path(1, 1000, 2000), path(2, 3000, 4000)), mapOf("sim1" to "Ho"))
        val totals = c.sample(listOf(path(1, 1500, 2700), path(2, 3900, 5100)))
        assertEquals(listOf(500L, 900L), totals.map { it.txBytes })
        assertEquals(listOf(700L, 1100L), totals.map { it.rxBytes })
        assertEquals("Ho", totals.first().label)
    }
    @Test fun retainsRemovedPathAndCountsNewPathFromZero() {
        val c = TestPathCounter(listOf(path(1, 100, 200)), emptyMap())
        c.sample(listOf(path(1, 150, 250)))
        val totals = c.sample(listOf(path(2, 600, 700)))
        assertFalse(totals.first().present)
        assertEquals(50, totals.first().txBytes)
        assertEquals(600, totals.last().txBytes)
    }
    @Test fun counterResetNeverProducesNegativeTotals() {
        val c = TestPathCounter(listOf(path(1, 100, 200)), emptyMap())
        c.sample(listOf(path(1, 200, 400)))
        val t = c.sample(listOf(path(1, 10, 20))).single()
        assertEquals(110, t.txBytes)
        assertEquals(220, t.rxBytes)
        assertEquals(1, t.resets)
    }

    @Test fun wifiPlusOneSimDoesNotQualifyAsDualSim() {
        val a = path(1, 0, 0).copy(iface = "sim1-1405")
        val b = path(2, 0, 0).copy(iface = "wifi-1422")
        assertNotNull(dualSimTestError(listOf(a, b)))
        assertNull(dualSimTestError(listOf(a, b.copy(iface = "sim2-1424"))))
    }
    @Test fun duplicateNetworksFromSameSimDoNotQualify() {
        assertNotNull(dualSimTestError(listOf(path(1, 0, 0).copy(iface = "sim1-100"), path(2, 0, 0).copy(iface = "sim1-101"))))
    }
}
