package io.github.shyamnathsankar.zeroclaw.background

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.shyamnathsankar.zeroclaw.ZeroClawService

/** Exact-alarm wake-ups, reboots, app updates and exact-alarm permission changes. */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_WAKE -> AgentScheduler.runNow(context)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AgentScheduler.restore(context)
                // The user opted in to an always-on agent; bring it back after a reboot or update.
                AgentPrefs.load(context)
                if (AgentPrefs.alwaysOn.value) {
                    runCatching {
                        ContextCompat.startForegroundService(
                            context,
                            ZeroClawService.startIntent(context, ZeroClawService.MODE_ALWAYS_ON),
                        )
                    }
                }
            }
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> AgentScheduler.rebook(context)
        }
    }

    companion object {
        const val ACTION_WAKE = "io.github.shyamnathsankar.zeroclaw.WAKE"
    }
}
