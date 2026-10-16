// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors
package com.mqvpn.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

/** One shared flow for both features and onboarding; no settings change until user consent. */
@Composable
fun BatteryAccessCard() {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var exempt by remember(context) { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    var error by remember { mutableStateOf<String?>(null) }
    val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    LifecycleResumeEffect(context) {
        exempt = power.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    fun open(candidates: List<Intent>) {
        error = if (openFirstBatteryActivity(context, candidates)) null
            else "Could not open settings. Go to Android Settings > Apps > MQVPN > Battery."
    }
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Battery and background", style = MaterialTheme.typography.titleMedium)
            Text(if (exempt) "Android battery optimization disabled" else "Battery optimization still active",
                style = MaterialTheme.typography.bodyLarge,
                color = if (exempt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Text("Allow MQVPN to keep working with the screen off, for both SIM Switcher and the VPN tunnel.")
            if (!exempt) Button(onClick = {
                open(listOf(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails,
                ))
            }) { Text("Allow unrestricted battery use") }
            OutlinedButton(onClick = {
                open(listOf(appDetails, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)))
            }) { Text("Open app battery settings") }
            if (samsung) {
                Text("Samsung: also check Battery > Background usage limits > Never sleeping apps. Add MQVPN if available. The app cannot check this Samsung list.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    open(listOf(
                        Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")
                            .setPackage("com.samsung.android.lool").putExtra("activity_type", 2),
                        Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")
                            .setPackage("com.samsung.android.sm").putExtra("activity_type", 2),
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails,
                    ))
                }) { Text("Open Samsung Never sleeping apps") }
            }
            Text("The idle pause set in SIM Switcher remains active. This button does not keep the phone awake.",
                style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun openFirstBatteryActivity(context: Context, candidates: List<Intent>): Boolean {
    for (intent in candidates) {
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Vendor activity absent on this firmware: try the public Android page.
        } catch (_: SecurityException) {
            // Vendor activity not exported: try the public Android page.
        }
    }
    return false
}
