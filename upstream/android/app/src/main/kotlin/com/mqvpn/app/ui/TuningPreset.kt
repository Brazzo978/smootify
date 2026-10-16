// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui

import com.mqvpn.app.data.DemoSettings
import com.mqvpn.sdk.core.model.MqvpnConfig

/** Simple client-side starting points; neither changes the server until explicit sync. */
enum class TuningPreset(val label: String, val scheduler: MqvpnConfig.Scheduler) {
    SPEED("Speed", MqvpnConfig.Scheduler.WLB),
    STABILITY("Stability", MqvpnConfig.Scheduler.MIN_RTT);

    fun applyTo(settings: DemoSettings): DemoSettings = settings.copy(
        scheduler = scheduler.name,
        multipathEnabled = true,
        hybridEnabled = false,
        reorderEnabled = false,
        serverMtu = 1280,
    )

    companion object {
        fun of(settings: DemoSettings): TuningPreset? {
            if (!settings.multipathEnabled || settings.hybridEnabled ||
                settings.reorderEnabled || settings.serverMtu != 1280) return null
            return entries.firstOrNull { it.scheduler.name == settings.scheduler }
        }
    }
}
