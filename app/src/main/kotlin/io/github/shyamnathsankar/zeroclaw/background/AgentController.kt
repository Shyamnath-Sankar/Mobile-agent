package io.github.shyamnathsankar.zeroclaw.background

import android.content.Context
import io.github.shyamnathsankar.zeroclaw.GatewayAuth
import io.github.shyamnathsankar.zeroclaw.ZeroClawRuntime
import io.github.shyamnathsankar.zeroclaw.ZeroClawService
import io.github.shyamnathsankar.zeroclaw.tools.McpRegistrar
import io.github.shyamnathsankar.zeroclaw.tools.McpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Decides when the agent process runs. Each reason to run is a [Hold]; the daemon starts with the
 * first hold and stops when the last one is released, so it never runs without a purpose:
 *
 *  - [Hold.Ui]       the app is on screen
 *  - [Hold.Grace]    the user just left; a short, visible foreground service lets work finish
 *  - [Hold.Worker]   a scheduled job is due (WorkManager / exact alarm)
 *  - [Hold.AlwaysOn] the user opted in to a persistent agent (e.g. chat channels)
 *
 * Before stopping, the next scheduled job is read from the gateway so a wake-up can be booked.
 */
object AgentController {
    enum class Hold { Ui, Grace, Worker, AlwaysOn }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val holds = mutableSetOf<Hold>()
    private var syncLoop: Job? = null

    /** Set when the user presses Stop; opening the app then does not restart the agent by itself. */
    @Volatile var userStopped = false
        private set

    @Synchronized
    fun acquire(context: Context, hold: Hold) {
        val app = context.applicationContext
        if (hold == Hold.Ui && userStopped) return
        holds += hold
        scope.launch { McpServer.ensureStarted(app) }
        scope.launch { ZeroClawRuntime.startDaemon(app) }
        if (syncLoop?.isActive != true) {
            syncLoop = scope.launch {
                var registeredFor: Long? = null
                while (isActive) {
                    if (!ZeroClawRuntime.ready.value) {
                        delay(1_000)
                        continue
                    }
                    // Each fresh daemon: make sure ZeroClaw knows the phone tools, then keep schedules booked.
                    val started = ZeroClawRuntime.startedAt.value
                    if (started != null && started != registeredFor) {
                        if (GatewayAuth.ensureToken(app) == null) {
                            delay(5_000)
                            continue
                        }
                        runCatching { McpRegistrar.ensure(app) }
                            .onFailure { ZeroClawRuntime.appLog("[app] could not register phone tools: ${it.message}") }
                        registeredFor = started
                    }
                    runCatching { AgentScheduler.syncFromGateway(app) }
                    delay(60_000)
                }
            }
        }
    }

    @Synchronized
    fun release(context: Context, hold: Hold) {
        if (!holds.remove(hold) || holds.isNotEmpty()) return
        stopWhenIdle(context.applicationContext)
    }

    @Synchronized
    fun isHeld(hold: Hold) = hold in holds

    /** User pressed Start. */
    fun userStart(context: Context) {
        userStopped = false
        acquire(context, Hold.Ui)
    }

    /** User pressed Stop: end every mode, including always-on. */
    fun userStop(context: Context) {
        val app = context.applicationContext
        userStopped = true
        AgentPrefs.setAlwaysOn(app, false)
        app.startService(ZeroClawService.stopIntent(app))
        synchronized(this) { holds.clear() }
        stopWhenIdle(app)
    }

    private fun stopWhenIdle(app: Context) {
        scope.launch {
            // Book the next wake-up while the gateway is still up, then stop if nothing re-acquired.
            if (ZeroClawRuntime.ready.value) {
                runCatching { AgentScheduler.syncFromGateway(app) }
                    .onFailure { ZeroClawRuntime.appLog("[app] could not read schedules: ${it.message}") }
            }
            synchronized(this@AgentController) {
                if (holds.isEmpty()) {
                    syncLoop?.cancel()
                    ZeroClawRuntime.stopDaemon()
                }
            }
        }
    }
}
