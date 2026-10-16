// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.simcontrol

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.IBinder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

sealed interface ShizukuSimState {
    data object Stopped : ShizukuSimState
    data object NotInstalled : ShizukuSimState
    data object NotRunning : ShizukuSimState
    data object NoPermission : ShizukuSimState
    data object Connecting : ShizukuSimState
    data object Ready : ShizukuSimState
    data object Dead : ShizukuSimState
    data class Error(val message: String) : ShizukuSimState
}

class SimControlException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** App-side owner of the short-lived Shizuku UserService connection. */
@Singleton
class ShizukuSimBridge @Inject constructor(@ApplicationContext private val context: Context) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, SimSwitchUserService::class.java.name),
    ).daemon(false).processNameSuffix("sim_control").version(1)
    private val _state = MutableStateFlow<ShizukuSimState>(ShizukuSimState.Stopped)
    val state: StateFlow<ShizukuSimState> = _state.asStateFlow()

    private var started = false
    private var binding = false
    private var boundRequested = false
    private var service: ISimSwitchService? = null
    private var serviceBinder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        clearService()
        if (isStarted()) _state.value = ShizukuSimState.Dead
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ ->
        refresh()
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!isStarted()) return
            if (!binder.pingBinder()) {
                clearService()
                _state.value = ShizukuSimState.Dead
                return
            }
            val recipient = IBinder.DeathRecipient {
                clearService()
                if (isStarted()) _state.value = ShizukuSimState.Dead
            }
            try {
                binder.linkToDeath(recipient, 0)
                synchronized(lock) {
                    if (!started) {
                        binder.unlinkToDeath(recipient, 0)
                        return
                    }
                    serviceBinder = binder
                    deathRecipient = recipient
                    service = ISimSwitchService.Stub.asInterface(binder)
                    _state.value = ShizukuSimState.Ready
                }
            } catch (e: Exception) {
                clearService()
                if (isStarted()) _state.value = ShizukuSimState.Error("UserService connection failed: " + e.message)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            clearService()
            if (isStarted()) _state.value = ShizukuSimState.Dead
        }
    }

    /** Register for Shizuku state and bind if permission is already granted. Never prompts. */
    fun start() {
        val firstStart = synchronized(lock) {
            if (started) false else {
                started = true
                true
            }
        }
        if (firstStart) {
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionResult)
        }
        refresh()
    }

    /** Unbind and remove the privileged process. Safe to call repeatedly. */
    fun stop() {
        val wasBinding = synchronized(lock) {
            started = false
            val old = boundRequested
            boundRequested = false
            binding = false
            old
        }
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        clearService()
        _state.value = ShizukuSimState.Stopped
        if (wasBinding) {
            scope.launch {
                runCatching { Shizuku.unbindUserService(args, connection, true) }
            }
        }
    }

    /** Call only from an explicit user action; Shizuku shows its own permission prompt. */
    fun requestPermission() {
        if (!isStarted()) throw SimControlException("SIM control is stopped")
        if (!Shizuku.pingBinder()) {
            refresh()
            throw SimControlException("Shizuku is not running")
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            refresh()
            return
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            _state.value = ShizukuSimState.NoPermission
            throw SimControlException("Shizuku permission was denied; grant it in Shizuku")
        }
        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
    }

    suspend fun getDefaultDataSubId(): Int = remoteCall {
        val id = this.getDefaultDataSubId()
        if (id < 0) throw SimControlException("No valid default data subscription")
        id
    }

    /** Returns an immediate readback, which can precede completion of the modem handover. */
    suspend fun setDefaultDataSubId(targetSubId: Int): Int {
        if (targetSubId < 0 || targetSubId == Int.MAX_VALUE) {
            throw SimControlException("Invalid subscription ID: $targetSubId")
        }
        return remoteCall { this.setDefaultDataSubId(targetSubId) }
    }

    private suspend fun remoteCall(call: ISimSwitchService.() -> Int): Int =
        withTimeout(REMOTE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val current = synchronized(lock) {
                    if (started && _state.value == ShizukuSimState.Ready) service else null
                } ?: throw SimControlException("Shizuku SIM control is not ready")
                try {
                    current.call()
                } catch (e: DeadObjectException) {
                    clearService()
                    _state.value = ShizukuSimState.Dead
                    throw SimControlException("Shizuku UserService disconnected", e)
                } catch (e: Exception) {
                    throw SimControlException("SIM control failed: " + (e.message ?: e.javaClass.simpleName), e)
                }
            }
        }

    private fun refresh() {
        if (!isStarted()) return
        if (!Shizuku.pingBinder()) {
            _state.value = if (managerInstalled()) ShizukuSimState.NotRunning else ShizukuSimState.NotInstalled
            return
        }
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) {
            _state.value = ShizukuSimState.Error("Shizuku API 13 or newer is required")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            _state.value = ShizukuSimState.NoPermission
            return
        }
        val needsBind = synchronized(lock) {
            if (!started || binding) false else {
                binding = true
                boundRequested = true
                true
            }
        }
        if (!needsBind) return
        _state.value = ShizukuSimState.Connecting
        scope.launch {
            try {
                Shizuku.bindUserService(args, connection)
            } catch (e: Exception) {
                synchronized(lock) {
                    binding = false
                    boundRequested = false
                }
                if (isStarted()) _state.value = ShizukuSimState.Error(
                    "Could not bind Shizuku UserService: " + (e.message ?: e.javaClass.simpleName),
                )
            }
        }
    }

    private fun clearService() {
        synchronized(lock) {
            val binder = serviceBinder
            val recipient = deathRecipient
            if (binder != null && recipient != null) {
                runCatching { binder.unlinkToDeath(recipient, 0) }
            }
            serviceBinder = null
            deathRecipient = null
            service = null
            binding = false
        }
    }

    private fun isStarted(): Boolean = synchronized(lock) { started }

    @Suppress("DEPRECATION")
    private fun managerInstalled(): Boolean = try {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 6104
        const val REMOTE_TIMEOUT_MS = 5_000L
    }
}
