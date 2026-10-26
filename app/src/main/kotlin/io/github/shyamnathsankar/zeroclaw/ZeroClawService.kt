package io.github.shyamnathsankar.zeroclaw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import io.github.shyamnathsankar.zeroclaw.background.AgentController
import io.github.shyamnathsankar.zeroclaw.background.AgentController.Hold
import io.github.shyamnathsankar.zeroclaw.background.AgentPrefs

/**
 * User-visible foreground service (type specialUse) with two modes:
 *  - [MODE_GRACE]: started as the user leaves the app; keeps the agent alive for
 *    [AgentPrefs.GRACE_MINUTES] so in-flight work can finish, then stops by itself.
 *  - [MODE_ALWAYS_ON]: opt-in persistent agent (e.g. chat channels), until the user stops it.
 * Both show a notification with a Stop action. Scheduled jobs do not use this service; they run
 * in [io.github.shyamnathsankar.zeroclaw.background.ScheduledRunWorker].
 */
class ZeroClawService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var mode: String? = null
    private val endGrace = Runnable { finish() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finish()
                return START_NOT_STICKY
            }
            ACTION_END_GRACE -> {
                if (mode == MODE_GRACE) finish() else if (mode == null) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_AGENT -> {
                AgentController.userStop(this)
                finish()
                return START_NOT_STICKY
            }
        }

        val requested = intent?.getStringExtra(EXTRA_MODE) ?: MODE_GRACE
        // Always-on already covers everything a grace period would.
        if (mode == MODE_ALWAYS_ON && requested == MODE_GRACE) return START_NOT_STICKY

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(requested),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when started too late from the background.
            ZeroClawRuntime.appLog("[app] could not start foreground service: ${e.message}")
            if (requested == MODE_GRACE && mode != MODE_GRACE) AgentController.release(this, Hold.Grace)
            if (mode == null) stopSelf()
            return START_NOT_STICKY
        }

        handler.removeCallbacks(endGrace)
        if (requested == MODE_GRACE) {
            AgentController.acquire(this, Hold.Grace)
            handler.postDelayed(endGrace, AgentPrefs.GRACE_MINUTES * 60_000)
        } else {
            AgentController.acquire(this, Hold.AlwaysOn)
            AgentController.release(this, Hold.Grace)
        }
        mode = requested
        return START_NOT_STICKY
    }

    // specialUse has no system time limit, but stop cleanly if the system ever asks.
    override fun onTimeout(startId: Int, fgsType: Int) = finish()

    override fun onDestroy() {
        handler.removeCallbacks(endGrace)
        releaseHolds()
        super.onDestroy()
    }

    private fun finish() {
        handler.removeCallbacks(endGrace)
        releaseHolds()
        mode = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseHolds() {
        when (mode) {
            MODE_GRACE -> AgentController.release(this, Hold.Grace)
            MODE_ALWAYS_ON -> AgentController.release(this, Hold.AlwaysOn)
        }
    }

    private fun buildNotification(mode: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agent activity", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while your agent is working in the background"
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ZeroClawService::class.java).setAction(ACTION_STOP_AGENT), PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, text) = if (mode == MODE_ALWAYS_ON) {
            "Agent is on" to "Listening for messages and running schedules"
        } else {
            "Agent is finishing up" to "Stops in ${AgentPrefs.GRACE_MINUTES} minutes to save battery"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "agent_activity"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_MODE = "mode"
        private const val ACTION_STOP = "io.github.shyamnathsankar.zeroclaw.STOP_SERVICE"
        private const val ACTION_END_GRACE = "io.github.shyamnathsankar.zeroclaw.END_GRACE"
        private const val ACTION_STOP_AGENT = "io.github.shyamnathsankar.zeroclaw.STOP_AGENT"
        const val MODE_GRACE = "grace"
        const val MODE_ALWAYS_ON = "always_on"

        fun startIntent(context: Context, mode: String) =
            Intent(context, ZeroClawService::class.java).putExtra(EXTRA_MODE, mode)

        /** Ends the service whatever its mode (the agent itself is stopped by [AgentController]). */
        fun stopIntent(context: Context) = Intent(context, ZeroClawService::class.java).setAction(ACTION_STOP)

        /** Ends a grace period early because the user came back to the app. */
        fun endGraceIntent(context: Context) = Intent(context, ZeroClawService::class.java).setAction(ACTION_END_GRACE)
    }
}
