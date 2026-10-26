package io.github.shyamnathsankar.zeroclaw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat

/** Foreground service (type dataSync) that keeps the ZeroClaw daemon alive while the app is backgrounded. */
class ZeroClawService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ZeroClawRuntime.stopDaemon()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        ZeroClawRuntime.startDaemon(this)
        return START_NOT_STICKY
    }

    // Android 15+ caps dataSync foreground services; stop cleanly when the system says time is up.
    override fun onTimeout(startId: Int, fgsType: Int) {
        ZeroClawRuntime.stopDaemon()
        stopSelf()
    }

    override fun onDestroy() {
        ZeroClawRuntime.stopDaemon()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agent running", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("ZeroClaw agent is running")
            .setContentText("Gateway on ${ZeroClawRuntime.HOST}:${ZeroClawRuntime.PORT}")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "agent_running"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "io.github.shyamnathsankar.zeroclaw.STOP"

        fun startIntent(context: Context) = Intent(context, ZeroClawService::class.java)
        fun stopIntent(context: Context) =
            Intent(context, ZeroClawService::class.java).setAction(ACTION_STOP)
    }
}
