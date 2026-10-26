package io.github.shyamnathsankar.zeroclaw.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.shyamnathsankar.zeroclaw.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** The agent asks the user to do something in the browser (usually signing in), then continues. */
data class HandoffRequest(val message: String)

/**
 * Hands the agent browser to the user: the Browser tab shows a banner with Done / Cancel while the user
 * signs in or solves a captcha themselves. The agent never sees or types the password.
 */
object Handoff {
    private const val CHANNEL_ID = "agent_handoff"
    private const val NOTIFICATION_ID = 8
    private const val TIMEOUT_MS = 10 * 60_000L

    private val _pending = MutableStateFlow<HandoffRequest?>(null)
    val pending: StateFlow<HandoffRequest?> = _pending.asStateFlow()

    private var waiter: CompletableDeferred<Boolean>? = null

    /** Suspends until the user taps Done (true) or Cancel / times out (false). */
    suspend fun request(context: Context, message: String): Boolean {
        val done = CompletableDeferred<Boolean>()
        synchronized(this) {
            waiter?.complete(false)
            waiter = done
            _pending.value = HandoffRequest(message)
        }
        notify(context.applicationContext, message)
        val result = withTimeoutOrNull(TIMEOUT_MS) { done.await() } ?: false
        synchronized(this) {
            if (waiter === done) {
                waiter = null
                _pending.value = null
            }
        }
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        return result
    }

    fun finish(done: Boolean) {
        synchronized(this) { waiter?.complete(done) }
    }

    private fun notify(context: Context, message: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Browser hand-offs", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Your agent asks you to sign in or finish something in its browser"
            },
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_BROWSER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Your agent needs you in the browser")
            .setContentText(message)
            .setStyle(android.app.Notification.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }
}
