// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors
package com.mqvpn.app.ui

import android.content.Intent
import android.os.Bundle
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
                var requestedHomeFeature by rememberSaveable { mutableIntStateOf(-1) }
                val completed by setupPreferences.completed.collectAsStateWithLifecycle()
                val state by vm.vpnState.collectAsStateWithLifecycle()
                val entry by nav.currentBackStackEntryAsState()
                LaunchedEffect(pendingProfilePayload, entry?.destination?.route) {
                    if (pendingProfilePayload != null && entry?.destination?.route != "profile") {
                        nav.navigate("profile") { launchSingleTop = true }
                    }
                }
                LaunchedEffect(completed, state, pendingProfilePayload, entry?.destination?.route) {
                    if (!completed && pendingProfilePayload == null && state is MqvpnState.Disconnected && entry?.destination?.route == "dashboard") {
                        nav.navigate("setup") { launchSingleTop = true }
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
                            onOpenSettings = { nav.navigate("hub") }, onOpenIperf = { nav.navigate("iperf") },
                            onOpenServer = { nav.navigate("settings") }, onOpenRadio = { nav.navigate("radio") },
                            onOpenDiagnostics = { nav.navigate("poc") }, onOpenSetup = { nav.navigate("setup") },
                            onOpenCompatibility = { nav.navigate("compatibility") },
                            onOpenTuning = { nav.navigate("tuning") },
                            onOpenConnections = { nav.navigate("connections") },
                            onOpenProfile = { nav.navigate("profile") },
                            initialFeature = if (requestedHomeFeature >= 0) requestedHomeFeature else if (setupPreferences.lastMode == SetupMode.VPN_ONLY) 1 else 0)
                    }
                    composable("hub") {
                        SettingsHubScreen(onNavigateUp = { nav.navigateUp() },
                            onOpenServer = { nav.navigate("settings") }, onOpenTuning = { nav.navigate("tuning") },
                            onOpenRadio = { nav.navigate("radio") }, onOpenCompatibility = { nav.navigate("compatibility") },
                            onOpenSetup = { nav.navigate("setup") }, onOpenDiagnostics = { nav.navigate("poc") },
                            onOpenConnections = { nav.navigate("connections") },
                            onOpenProfile = { nav.navigate("profile") })
                    }
                    composable("setup") {
                        SetupScreen(onComplete = showSetupHome, onCancel = showSetupHome,
                            onOpenRadio = { nav.navigate("radio") })
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
                            onOpenSettings = { nav.navigate("settings") }, onOpenTuning = { nav.navigate("tuning") },
                            onOpenIperf = { nav.navigate("iperf") })
                    }
                    composable("iperf") {
                        IperfScreen(iperfVm, onNavigateUp = { nav.navigateUp() }, onOpenTunnel = showHome,
                            onOpenTuning = { nav.navigate("connections") })
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





