// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.simcontrol

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import androidx.annotation.Keep
import java.lang.reflect.InvocationTargetException

/** Runs only as a Shizuku UserService. The Context constructor requires Shizuku API 13+. */
@Keep
class SimSwitchUserService : ISimSwitchService.Stub {
    private val appUid: Int

    // Older Shizuku versions instantiate this constructor. Fail closed on all API calls.
    @Keep
    constructor() : super() {
        appUid = -1
    }

    @Keep
    constructor(context: Context) : super() {
        appUid = context.applicationInfo.uid
    }

    private fun requireAppCaller() {
        if (appUid < 0 || Binder.getCallingUid() != appUid) {
            throw SecurityException("SIM control is restricted to the owning app")
        }
    }

    override fun getDefaultDataSubId(): Int {
        requireAppCaller()
        val result = try {
            invokeIsub("getDefaultDataSubId")
        } catch (_: NoSuchMethodException) {
            throw RemoteException("ISub#getDefaultDataSubId is unavailable on this firmware")
        }
        return result as? Int
            ?: throw RemoteException("ISub#getDefaultDataSubId returned no subscription ID")
    }

    override fun setDefaultDataSubId(targetSubId: Int): Int {
        requireAppCaller()
        if (targetSubId < 0 || targetSubId == Int.MAX_VALUE) {
            throw IllegalArgumentException("Invalid subscription ID: $targetSubId")
        }
        if (targetSubId !in activeSubIds()) {
            throw IllegalArgumentException("Subscription $targetSubId is not active")
        }
        if (getDefaultDataSubId() != targetSubId) {
            try {
                invokeIsub("setDefaultDataSubId", arrayOf(Int::class.javaPrimitiveType!!), targetSubId)
            } catch (_: NoSuchMethodException) {
                throw RemoteException("ISub#setDefaultDataSubId is unavailable on this firmware")
            }
        }
        // This may still read the old ID while the modem handover is pending.
        return getDefaultDataSubId()
    }

    private fun activeSubIds(): IntArray {
        val ids = try {
            invokeIsub("getActiveSubIdList", arrayOf(Boolean::class.javaPrimitiveType!!), true)
        } catch (_: NoSuchMethodException) {
            try {
                invokeIsub("getActiveSubIdList")
            } catch (_: NoSuchMethodException) {
                throw RemoteException("ISub#getActiveSubIdList is unavailable on this firmware")
            }
        }
        return ids as? IntArray
            ?: throw RemoteException("ISub#getActiveSubIdList is unavailable")
    }

    private fun invokeIsub(
        name: String,
        parameterTypes: Array<Class<*>> = emptyArray(),
        vararg args: Any,
    ): Any? {
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "isub") as? IBinder
                ?: throw RemoteException("Subscription service is unavailable")
            val isub = Class.forName("com.android.internal.telephony.ISub")
            val service = Class.forName("com.android.internal.telephony.ISub\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
                ?: throw RemoteException("Subscription service interface is unavailable")
            return isub.getMethod(name, *parameterTypes).invoke(service, *args)
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            if (cause is SecurityException) throw cause
            if (cause is RemoteException) throw cause
            throw RemoteException("ISub#$name failed: " + cause.javaClass.simpleName + ": " + cause.message)
        } catch (e: ClassNotFoundException) {
            throw RemoteException("ISub interface is unavailable on this firmware")
        } catch (e: IllegalAccessException) {
            throw RemoteException("ISub#$name is inaccessible on this firmware")
        }
    }

    override fun destroy() {
        // Reserved Shizuku lifecycle method. No work is retained in this process.
        System.exit(0)
    }
}
