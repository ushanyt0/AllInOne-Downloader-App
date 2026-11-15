package com.shancodex.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class DownloadKeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel("download_status", "Download status", NotificationManager.IMPORTANCE_LOW)
            )
            val notification = Notification.Builder(this, "download_status")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("All-in-One Downloader")
                .setContentText("Download running in background")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            startForeground(7301, notification)
        } else {
            @Suppress("DEPRECATION")
            val notification = Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("All-in-One Downloader")
                .setContentText("Download running in background")
                .setOngoing(true)
                .build()
            startForeground(7301, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null
}
