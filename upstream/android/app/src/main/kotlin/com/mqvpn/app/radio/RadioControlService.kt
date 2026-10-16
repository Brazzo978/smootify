// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.radio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mqvpn.app.R
import com.mqvpn.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** User-started monitor that can keep SIM policy active independently of the VPN service. */
@AndroidEntryPoint
class RadioControlService : Service() {
    @Inject lateinit var coordinator: RadioCoordinator

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Automatic data SIM selection", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i("MqvpnRadio", "Standalone monitor stopped from notification")
            coordinator.stopFromNotification()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val options = coordinator.options.value
        if (!options.enabled || !options.independent) {
            stopSelf()
            return START_NOT_STICKY
        }

        val stopIntent = Intent(this, RadioControlService::class.java).setAction(ACTION_STOP)
        val stopAction = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openAction = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle("Automatic data SIM selection")
            .setContentText("Active even without VPN; Android may delay checks in Doze mode.")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(openAction)
            .addAction(R.drawable.ic_vpn, "Stop", stopAction)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i("MqvpnRadio", "Standalone monitor foreground service started")
        coordinator.standaloneServiceStarted()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i("MqvpnRadio", "Standalone monitor foreground service destroyed")
        coordinator.standaloneServiceStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val ACTION_STOP = "com.mqvpn.app.radio.STOP"
        const val CHANNEL_ID = "mqvpn_radio_control"
        const val NOTIFICATION_ID = 2
    }
}
