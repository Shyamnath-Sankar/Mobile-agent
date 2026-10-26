package io.github.shyamnathsankar.zeroclaw.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import io.github.shyamnathsankar.zeroclaw.ZeroClawRuntime
import java.util.concurrent.TimeUnit

/**
 * Books a wake-up for the next ZeroClaw cron job. ZeroClaw's own scheduler stays the source of truth;
 * Android only has to make sure the agent is running when a job is due.
 *
 * Default: WorkManager with a network constraint (batched by the OS, battery friendly, may run a bit late
 * in Doze). Optional: an exact alarm when the user enabled exact timing and granted SCHEDULE_EXACT_ALARM.
 */
object AgentScheduler {
    private const val WORK_SCHEDULED = "zc-scheduled-run"
    private const val WORK_NOW = "zc-run-now"
    private const val ALARM_REQUEST = 42

    private var lastBooked: Pair<Long?, Boolean>? = null

    /** Reads cron jobs from the running gateway and books the earliest enabled one. Blocking. */
    fun syncFromGateway(context: Context) {
        val next = GatewayClient.cronJobs().filter { it.enabled }.minOfOrNull { it.nextRunMs }
        book(context, next)
    }

    @Synchronized
    fun book(context: Context, at: Long?) {
        val app = context.applicationContext
        AgentPrefs.load(app)
        val exact = useExact(app)
        if (lastBooked == (at to exact)) return
        lastBooked = at to exact
        AgentPrefs.setNextWakeAt(app, at)

        val wm = WorkManager.getInstance(app)
        val am = app.getSystemService(AlarmManager::class.java)
        am.cancel(alarmIntent(app))
        wm.cancelUniqueWork(WORK_SCHEDULED)
        if (at == null) return

        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(app))
        } else {
            val request = OneTimeWorkRequestBuilder<ScheduledRunWorker>()
                .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniqueWork(WORK_SCHEDULED, ExistingWorkPolicy.REPLACE, request)
        }
        ZeroClawRuntime.appLog("[app] next scheduled wake-up booked (${if (exact) "exact alarm" else "WorkManager"})")
    }

    /** Called by the exact alarm, on boot, or when a booked time has already passed. */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ScheduledRunWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** After a reboot or app update: re-book the last known wake-up. */
    fun restore(context: Context) {
        AgentPrefs.load(context)
        val at = AgentPrefs.nextWakeAt.value ?: return
        lastBooked = null
        if (at <= System.currentTimeMillis()) runNow(context) else book(context, at)
    }

    /** Re-book with the current exact/inexact preference. */
    fun rebook(context: Context) {
        AgentPrefs.load(context)
        lastBooked = null
        book(context, AgentPrefs.nextWakeAt.value)
    }

    fun canUseExactAlarms(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun useExact(context: Context) = AgentPrefs.exactTiming.value && canUseExactAlarms(context)

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST,
        Intent(context, WakeReceiver::class.java).setAction(WakeReceiver.ACTION_WAKE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
