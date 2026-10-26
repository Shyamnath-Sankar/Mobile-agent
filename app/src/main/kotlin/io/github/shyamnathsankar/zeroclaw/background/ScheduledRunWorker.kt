package io.github.shyamnathsankar.zeroclaw.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.shyamnathsankar.zeroclaw.GatewayAuth
import io.github.shyamnathsankar.zeroclaw.ZeroClawRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Starts the agent for due cron jobs, waits until they have finished (ZeroClaw writes `last_run`
 * when a run completes), books the next wake-up and lets the agent stop again.
 * Bounded to stay inside WorkManager's 10-minute execution window.
 */
class ScheduledRunWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        AgentPrefs.load(app)
        AgentController.acquire(app, AgentController.Hold.Worker)
        try {
            if (!waitForGateway()) {
                ZeroClawRuntime.appLog("[app] scheduled wake-up: agent did not start")
                return Result.retry()
            }
            withContext(Dispatchers.IO) { GatewayAuth.ensureToken(app) }
            val wokeAt = System.currentTimeMillis()
            val due = withContext(Dispatchers.IO) { GatewayClient.cronJobs(app) }
                .filter { it.enabled && it.nextRunMs <= wokeAt + DUE_SLACK_MS }
            ZeroClawRuntime.appLog("[app] scheduled wake-up: ${due.size} job(s) due")

            val deadline = wokeAt + MAX_RUN_MS
            var pending = due
            while (pending.isNotEmpty() && System.currentTimeMillis() < deadline) {
                delay(POLL_MS)
                val now = withContext(Dispatchers.IO) { GatewayClient.cronJobs(app) }.associateBy { it.id }
                pending = pending.filter { d ->
                    val job = now[d.id] ?: return@filter false // deleted after a one-shot run
                    val last = job.lastRunMs
                    last == null || last < d.nextRunMs
                }
            }
            if (pending.isNotEmpty()) {
                ZeroClawRuntime.appLog("[app] scheduled wake-up: ${pending.size} job(s) still running at the time limit")
            }
            withContext(Dispatchers.IO) { AgentScheduler.syncFromGateway(app) }
            return Result.success()
        } catch (e: Exception) {
            ZeroClawRuntime.appLog("[app] scheduled wake-up failed: ${e.message}")
            return Result.retry()
        } finally {
            AgentController.release(app, AgentController.Hold.Worker)
        }
    }

    private suspend fun waitForGateway(): Boolean {
        // startDaemon runs asynchronously; give it a moment to flip `running` before waiting on `ready`.
        repeat(20) {
            if (ZeroClawRuntime.running.value) return ZeroClawRuntime.awaitReady(STARTUP_TIMEOUT_MS)
            delay(250)
        }
        return false
    }

    private companion object {
        const val DUE_SLACK_MS = 60_000L
        const val MAX_RUN_MS = 9 * 60_000L
        const val POLL_MS = 10_000L
        const val STARTUP_TIMEOUT_MS = 90_000L
    }
}
