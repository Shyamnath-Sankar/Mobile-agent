package io.github.shyamnathsankar.zeroclaw

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.shyamnathsankar.zeroclaw.background.AgentController
import io.github.shyamnathsankar.zeroclaw.background.AgentController.Hold
import io.github.shyamnathsankar.zeroclaw.background.AgentPrefs
import io.github.shyamnathsankar.zeroclaw.files.Outbox
import io.github.shyamnathsankar.zeroclaw.tools.Handoff
import io.github.shyamnathsankar.zeroclaw.ui.AppNav
import io.github.shyamnathsankar.zeroclaw.ui.ZeroClawApp
import io.github.shyamnathsankar.zeroclaw.ui.theme.Appearance
import io.github.shyamnathsankar.zeroclaw.ui.theme.Zc

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the agent works either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentPrefs.load(this)
        Appearance.load(this)
        Outbox.load(this)
        // Transparent bars; icon colour follows the light/dark palette (ZeroClawApp updates it on change).
        val bars = if (Zc.dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            ZeroClawApp(
                onStart = { AgentController.userStart(this) },
                onStop = { AgentController.userStop(this) },
                onAlwaysOnChange = ::setAlwaysOn,
            )
        }
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private var stoppedAt = 0L

    override fun onStop() {
        super.onStop()
        stoppedAt = System.currentTimeMillis()
    }

    override fun onRestart() {
        super.onRestart()
        // Coming back to the app after a while opens the Agent tab. Quick trips to a settings screen or a file
        // picker keep the current tab, and so does a browser hand-off the agent is waiting on.
        val away = System.currentTimeMillis() - stoppedAt
        if (away > 30_000 && Handoff.pending.value == null) AppNav.showAgent()
    }

    override fun onResume() {
        super.onResume()
        // The app is on screen: the agent runs without any foreground service.
        AgentController.acquire(this, Hold.Ui)
        if (AgentController.isHeld(Hold.Grace)) startService(ZeroClawService.endGraceIntent(this))
        if (AgentPrefs.alwaysOn.value && !AgentController.isHeld(Hold.AlwaysOn)) {
            ContextCompat.startForegroundService(this, ZeroClawService.startIntent(this, ZeroClawService.MODE_ALWAYS_ON))
        }
    }

    override fun onPause() {
        super.onPause()
        android.webkit.CookieManager.getInstance().flush()
        if (isChangingConfigurations) return
        // Leaving the app: hand over to a short, visible grace period so in-flight work can finish.
        // Started from onPause because the app still counts as foreground here.
        val needsGrace = ZeroClawRuntime.running.value &&
            !AgentController.isHeld(Hold.AlwaysOn) &&
            !AgentController.userStopped
        if (needsGrace) {
            AgentController.acquire(this, Hold.Grace)
            ContextCompat.startForegroundService(this, ZeroClawService.startIntent(this, ZeroClawService.MODE_GRACE))
        }
        AgentController.release(this, Hold.Ui)
    }

    companion object {
        /** Set by the browser hand-off notification; the Browser tab opens on its own while a hand-off is pending. */
        const val EXTRA_OPEN_BROWSER = "open_browser"
    }

    private fun setAlwaysOn(enabled: Boolean) {
        AgentPrefs.setAlwaysOn(this, enabled)
        if (enabled) {
            AgentController.userStart(this)
            ContextCompat.startForegroundService(this, ZeroClawService.startIntent(this, ZeroClawService.MODE_ALWAYS_ON))
        } else {
            startService(ZeroClawService.stopIntent(this))
        }
    }
}
