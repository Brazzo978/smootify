// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class SetupMode { VPN_AND_SIM, VPN_ONLY, SIM_ONLY }

/** Only records onboarding; it never changes VPN or radio configuration. */
@Singleton
class SetupPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("mqvpn_setup", Context.MODE_PRIVATE)
    private val _completed = MutableStateFlow(prefs.getBoolean("completed", false))
    val completed = _completed.asStateFlow()

    val lastMode: SetupMode?
        get() = prefs.getString("mode", null)?.let { name ->
            SetupMode.entries.firstOrNull { it.name == name }
        }

    fun markCompleted(mode: SetupMode) {
        check(prefs.edit().putString("mode", mode.name).putBoolean("completed", true).commit()) {
            "Could not save setup completion."
        }
        _completed.value = true
    }

    fun markSkipped() {
        check(prefs.edit().putBoolean("completed", true).commit()) {
            "Could not save the choice to continue later."
        }
        _completed.value = true
    }
}
