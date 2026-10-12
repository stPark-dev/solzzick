package com.zzick.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 일괄 편집이 진행되는 동안 앱 프로세스를 살려두는 포그라운드 서비스.
 * 실제 일은 BatchProcessor가 하고, 이 서비스는 알림을 띄우고 수명만 맡는다.
 */
class BatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "일괄 편집", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("솔찍")
            .setContentText("영상을 처리하는 중이에요")
            .setOngoing(true)
            .build()
        // Android 15+는 미디어 처리 전용 타입, 그 이전은 데이터 동기화 타입
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(NOTIFICATION_ID, notification, type)
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "batch"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"

        /** 테스트·UI 확인용: 서비스가 포그라운드로 떠 있는지. */
        @Volatile var running = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BatchService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BatchService::class.java).setAction(ACTION_STOP))
        }
    }
}
