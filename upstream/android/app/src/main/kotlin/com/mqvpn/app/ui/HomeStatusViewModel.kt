// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mqvpn.app.data.DemoSettings
import com.mqvpn.app.data.SettingsRepository
import com.mqvpn.app.radio.RadioCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Passive home summaries. Collecting these flows never starts radio observation. */
@HiltViewModel
class HomeStatusViewModel @Inject constructor(
    repository: SettingsRepository,
    val radioCoordinator: RadioCoordinator,
) : ViewModel() {
    val settings: StateFlow<DemoSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val radioOptions = radioCoordinator.options
    val radioState = radioCoordinator.state
    val radioSnapshot = radioCoordinator.radio
    val shizukuState = radioCoordinator.shizuku
}
