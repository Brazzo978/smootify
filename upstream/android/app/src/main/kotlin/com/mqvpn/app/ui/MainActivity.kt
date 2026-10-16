// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors
package com.mqvpn.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mqvpn.app.data.SetupMode
import com.mqvpn.app.data.SetupPreferences
import com.mqvpn.app.ui.theme.MqvpnTheme
import com.mqvpn.sdk.core.model.MqvpnState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var setupPreferences: SetupPreferences
    private var pendingProfilePayload by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingProfilePayload = intent?.dataString
        enableEdgeToEdge()
        setContent {
            MqvpnTheme {
                // Keep manager-owning view models activity scoped across navigation.
                val vm: MqvpnViewModel = hiltViewModel()
                val iperfVm: IperfViewModel = hiltViewModel()
                val nav = rememberNavController()
                fun openScreen(route: String) {
                    if (nav.currentDestination?.route == route) return
                    if (!nav.popBackStack(route, inclusive = false)) {
                        nav.navigate(route) { launchSingleTop = true }
                    }
                }
                var setupDismissed by rememberSaveable { mutableStateOf(false) }
                var requestedHomeFeature by rememberSaveable { mutableIntStateOf(-1) }
                val completed by setupPreferences.completed.collectAsStateWithLifecycle()
                val state by vm.vpnState.collectAsStateWithLifecycle()
                val entry by nav.currentBackStackEntryAsState()
                LaunchedEffect(pendingProfilePayload, entry?.destination?.route) {
                    if (pendingProfilePayload != null && entry?.destination?.route != "profile") {
                        openScreen("profile")
                    }
                }
                LaunchedEffect(completed, setupDismissed, state, pendingProfilePayload, entry?.destination?.route) {
                    if (!completed && !setupDismissed && pendingProfilePayload == null && state is MqvpnState.Disconnected && entry?.destination?.route == "dashboard") {
                        openScreen("setup")
                    }
                }
                val navigateHome: () -> Unit = {
                    nav.navigate("dashboard") { popUpTo("dashboard") { inclusive = true }; launchSingleTop = true }
                }
                val showHome: () -> Unit = { requestedHomeFeature = 1; navigateHome() }
                val showSetupHome: () -> Unit = { requestedHomeFeature = -1; navigateHome() }
                NavHost(nav, startDestination = "dashboard") {
                    composable("dashboard") {
                        FeatureHomeScreen(vm,
                            onOpenSettings = { openScreen("hub") }, onOpenIperf = { openScreen("iperf") },
                            onOpenServer = { openScreen("settings") }, onOpenRadio = { openScreen("radio") },
                            onOpenDiagnostics = { openScreen("poc") }, onOpenSetup = { openScreen("setup") },
                            onOpenCompatibility = { openScreen("compatibility") },
                            onOpenTuning = { openScreen("tuning") },
                            onOpenConnections = { openScreen("connections") },
                            onOpenProfile = { openScreen("profile") },
                            initialFeature = if (requestedHomeFeature >= 0) requestedHomeFeature else if (setupPreferences.lastMode == SetupMode.VPN_ONLY) 1 else 0)
                    }
                    composable("hub") {
                        SettingsHubScreen(onNavigateUp = { nav.navigateUp() },
                            onOpenServer = { openScreen("settings") }, onOpenTuning = { openScreen("tuning") },
                            onOpenRadio = { openScreen("radio") }, onOpenCompatibility = { openScreen("compatibility") },
                            onOpenSetup = { openScreen("setup") }, onOpenDiagnostics = { openScreen("poc") },
                            onOpenConnections = { openScreen("connections") },
                            onOpenProfile = { openScreen("profile") })
                    }
                    composable("setup") {
                        val leaveSetup = { setupDismissed = true; showSetupHome() }
                        BackHandler(onBack = leaveSetup)
                        SetupScreen(onComplete = leaveSetup, onCancel = leaveSetup,
                            onOpenRadio = { openScreen("radio") })
                    }
                    composable("profile") {
                        ProfileImportScreen(onNavigateUp = { nav.navigateUp() }, onImported = showHome,
                            incomingPayload = pendingProfilePayload,
                            onPayloadConsumed = {
                                pendingProfilePayload = null
                                intent?.setData(null)
                            })
                    }
                    composable("compatibility") { CompatibilityScreen(onNavigateUp = { nav.navigateUp() }) }
                    composable("poc") {
                        DualSimPocScreen(onOpenTunnel = showHome,
                            onOpenSettings = { openScreen("settings") }, onOpenTuning = { openScreen("tuning") },
                            onOpenIperf = { openScreen("iperf") })
                    }
                    composable("iperf") {
                        IperfScreen(iperfVm, onNavigateUp = { nav.navigateUp() }, onOpenTunnel = showHome,
                            onOpenTuning = { openScreen("connections") })
                    }
                    composable("connections") { ConnectionsScreen(onNavigateUp = { nav.navigateUp() }) }
                    composable("tuning") { TuningScreen(onNavigateUp = { nav.navigateUp() }) }
                    composable("radio") { RadioScreen(onNavigateUp = { nav.navigateUp() }) }
                    composable("settings") { SettingsScreen(onNavigateUp = { nav.navigateUp() }) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingProfilePayload = intent.dataString
    }

}





