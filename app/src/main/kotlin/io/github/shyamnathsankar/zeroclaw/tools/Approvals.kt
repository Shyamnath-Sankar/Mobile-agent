package io.github.shyamnathsankar.zeroclaw.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.shyamnathsankar.zeroclaw.MainActivity
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** A sensitive action the agent wants to take, waiting for the user's decision. */
data class ApprovalRequest(val id: String, val title: String, val detail: String)

/**
 * Human-in-the-loop gate for risky agent actions (form submits, logins, payments, destructive
 * commands). The request shows as a dialog in the app and as a notification with Allow / Deny.
 * No answer within the timeout counts as Deny.
 */
object Approvals {
    private const val CHANNEL_ID = "agent_approvals"
    private const val NOTIFICATION_ID = 7
    private const val TIMEOUT_MS = 120_000L

    private val _pending = MutableStateFlow<ApprovalRequest?>(null)
    val pending: StateFlow<ApprovalRequest?> = _pending.asStateFlow()

    private var waiter: Pair<String, CompletableDeferred<Boolean>>? = null

    /** Suspends until the user allows or denies; false on timeout. One request at a time. */
    suspend fun request(context: Context, title: String, detail: String): Boolean {
        val id = UUID.randomUUID().toString()
        val decision = CompletableDeferred<Boolean>()
        synchronized(this) {
            waiter?.second?.complete(false)
            waiter = id to decision
            _pending.value = ApprovalRequest(id, title, detail)
        }
        notify(context.applicationContext, id, title, detail)
        val allowed = withTimeoutOrNull(TIMEOUT_MS) { decision.await() } ?: false
        synchronized(this) {
            if (waiter?.first == id) {
                waiter = null
                _pending.value = null
            }
        }
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        return allowed
    }

    fun resolve(context: Context, id: String, allowed: Boolean) {
        synchronized(this) {
            val w = waiter ?: return
            if (w.first != id) return
            w.second.complete(allowed)
        }
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun notify(context: Context, id: String, title: String, detail: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Your agent asks before submitting forms, signing in, paying or deleting files"
            },
        )
        fun action(allow: Boolean) = PendingIntent.getBroadcast(
            context,
            if (allow) 1 else 2,
            Intent(context, ApprovalReceiver::class.java)
                .putExtra(ApprovalReceiver.EXTRA_ID, id)
                .putExtra(ApprovalReceiver.EXTRA_ALLOW, allow),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Agent asks: $title")
            .setContentText(detail)
            .setStyle(android.app.Notification.BigTextStyle().bigText(detail))
            .setContentIntent(open)
            .addAction(android.app.Notification.Action.Builder(null, "Allow", action(true)).build())
            .addAction(android.app.Notification.Action.Builder(null, "Deny", action(false)).build())
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }
}

/** Handles Allow / Deny taps on the approval notification. */
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        Approvals.resolve(context, id, intent.getBooleanExtra(EXTRA_ALLOW, false))
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_ALLOW = "allow"
    }
}
