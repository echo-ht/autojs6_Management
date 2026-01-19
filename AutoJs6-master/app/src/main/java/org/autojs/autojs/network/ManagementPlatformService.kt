package org.autojs.autojs.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.autojs.autojs.core.pref.Pref
import org.autojs.autojs6.R

class ManagementPlatformService : Service() {

    companion object {
        private const val CHANNEL_ID = "management_platform_service"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Management Platform Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)

            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("AutoJs6 管理服务")
                .setContentText("正在保持与管理平台的连接")
                .setSmallIcon(R.drawable.autojs6_material)
                .build()
            runCatching { startForeground(NOTIFICATION_ID, notification) }
                .onFailure { stopSelf(); return }
        } else {
            val notification = Notification.Builder(this)
                .setContentTitle("AutoJs6 管理服务")
                .setContentText("正在保持与管理平台的连接")
                .setSmallIcon(R.drawable.autojs6_material)
                .build()
            runCatching { startForeground(NOTIFICATION_ID, notification) }
                .onFailure { stopSelf(); return }
        }

        if (!hasValidConfig()) {
            stopSelf()
            return
        }

        ManagementPlatformClient.connectIfConfigured()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasValidConfig()) {
            stopSelf()
            return START_NOT_STICKY
        }

        ManagementPlatformClient.connectIfConfigured()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun hasValidConfig(): Boolean {
        val address = Pref.getManagementPlatformServerAddress().trim()
        val secret = Pref.getManagementPlatformSecret().trim()
        return address.isNotEmpty() && secret.isNotEmpty()
    }
}
