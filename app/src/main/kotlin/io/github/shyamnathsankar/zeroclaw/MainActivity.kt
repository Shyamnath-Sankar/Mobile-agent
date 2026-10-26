package io.github.shyamnathsankar.zeroclaw

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.shyamnathsankar.zeroclaw.ui.ZeroClawApp

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* service runs either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZeroClawApp(onStart = ::startAgent, onStop = ::stopAgent)
        }
        // Start the agent as soon as the app opens, like any normal app.
        if (savedInstanceState == null && !ZeroClawRuntime.running.value) startAgent()
    }

    private fun startAgent() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ContextCompat.startForegroundService(this, ZeroClawService.startIntent(this))
    }

    private fun stopAgent() {
        startService(ZeroClawService.stopIntent(this))
    }
}
