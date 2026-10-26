package io.github.shyamnathsankar.zeroclaw.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import io.github.shyamnathsankar.zeroclaw.BuildConfig
import io.github.shyamnathsankar.zeroclaw.ZeroClawRuntime

private enum class Tab(val label: String) { Agent("Agent"), Logs("Logs"), App("App") }

// Matches the ZeroClaw dashboard's dark surface so the native chrome blends in.
private val DashboardBackground = Color(0xFF0E0F13)

private val AppColors = darkColorScheme(
    primary = Color(0xFF22D3EE),
    onPrimary = Color(0xFF041014),
    background = DashboardBackground,
    surface = DashboardBackground,
    surfaceContainer = Color(0xFF16181E),
)

@Composable
fun ZeroClawApp(onStart: () -> Unit, onStop: () -> Unit) {
    MaterialTheme(colorScheme = AppColors) {
        val context = LocalContext.current
        var tab by rememberSaveable { mutableStateOf(Tab.Agent) }
        val ready by ZeroClawRuntime.ready.collectAsState()
        val webView = remember { mutableStateOf<WebView?>(null) }
        // The WebView is created the first time the gateway is up and then kept alive across tabs.
        var dashboardCreated by remember { mutableStateOf(false) }

        LaunchedEffect(ready) {
            if (!ready) return@LaunchedEffect
            if (dashboardCreated) webView.value?.reload() else dashboardCreated = true
            tab = Tab.Agent
        }

        BackHandler(enabled = tab != Tab.Agent) { tab = Tab.Agent }
        BackHandler(enabled = tab == Tab.Agent) {
            val w = webView.value
            if (ready && w != null && w.canGoBack()) w.goBack() else (context as? Activity)?.moveTaskToBack(true)
        }

        Scaffold(
            containerColor = DashboardBackground,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {
                                Icon(
                                    when (t) {
                                        Tab.Agent -> Icons.Filled.Home
                                        Tab.Logs -> Icons.Filled.Info
                                        Tab.App -> Icons.Filled.Settings
                                    },
                                    contentDescription = t.label,
                                )
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (dashboardCreated) DashboardWebView(webView, Modifier.fillMaxSize())

                when {
                    tab == Tab.Agent && !ready -> Surface(Modifier.fillMaxSize()) {
                        StartupScreen(onStart = onStart, onShowLogs = { tab = Tab.Logs })
                    }
                    tab == Tab.Logs -> Surface(Modifier.fillMaxSize()) { LogsScreen() }
                    tab == Tab.App -> Surface(Modifier.fillMaxSize()) {
                        AppScreen(onStart, onStop, onReloadDashboard = { webView.value?.reload() })
                    }
                    else -> Unit
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DashboardWebView(holder: MutableState<WebView?>, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            WebView(ctx).apply {
                // Compose gives AndroidViews WRAP_CONTENT by default; a WebView that wraps its content
                // reports a broken CSS viewport height, which collapses the dashboard's vh-sized layout.
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(DashboardBackground.toArgb())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.textZoom = 100
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val host = request.url.host
                        if (host == ZeroClawRuntime.HOST || host == "localhost") return false
                        // Anything off-device opens in the user's browser, never inside this WebView.
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                        if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            ZeroClawRuntime.appLog("[web] ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                        }
                        return true
                    }
                }
                loadUrl(ZeroClawRuntime.DASHBOARD_URL)
                holder.value = this
            }
        },
        onRelease = {
            holder.value = null
            it.destroy()
        },
    )
}

@Composable
private fun StartupScreen(onStart: () -> Unit, onShowLogs: () -> Unit) {
    val running by ZeroClawRuntime.running.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ZeroClaw", style = MaterialTheme.typography.headlineMedium)
        if (running) {
            CircularProgressIndicator()
            Text("Starting your agent…")
        } else {
            Text("The agent is not running.")
            Button(onClick = onStart) { Text("Start agent") }
            OutlinedButton(onClick = onShowLogs) { Text("View logs") }
        }
    }
}

@Composable
private fun LogsScreen() {
    val context = LocalContext.current
    val logs by ZeroClawRuntime.logs.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { copyToClipboard(context, logs.joinToString("\n")) }) { Text("Copy all") }
            OutlinedButton(onClick = { ZeroClawRuntime.clearLogs() }) { Text("Clear") }
            Text("${logs.size} lines", modifier = Modifier.align(Alignment.CenterVertically))
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            items(logs) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
private fun AppScreen(onStart: () -> Unit, onStop: () -> Unit, onReloadDashboard: () -> Unit) {
    val context = LocalContext.current
    val running by ZeroClawRuntime.running.collectAsState()
    val ready by ZeroClawRuntime.ready.collectAsState()
    var args by rememberSaveable { mutableStateOf("--help") }
    val webViewLabel = remember { webViewLabel() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ZeroClaw Mobile", style = MaterialTheme.typography.headlineSmall)
        Text(
            when {
                ready -> "Agent: running"
                running -> "Agent: starting…"
                else -> "Agent: stopped"
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = !running) { Text("Start") }
            Button(
                onClick = onStop,
                enabled = running,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Stop") }
        }

        Text("Dashboard", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onReloadDashboard, enabled = ready) { Text("Reload") }
            OutlinedButton(onClick = { openInBrowser(context) }, enabled = ready) { Text("Open in Chrome") }
        }

        Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
        Text(ZeroClawRuntime.describeBinary(context), style = MaterialTheme.typography.bodySmall)
        Text("WebView: $webViewLabel", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = args,
            onValueChange = { args = it },
            label = { Text("zeroclaw <args>") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = {
            ZeroClawRuntime.runOnce(context, args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() })
        }) { Text("Run once (output in Logs)") }
    }
}

private fun webViewLabel(): String {
    val pkg = WebView.getCurrentWebViewPackage() ?: return "unknown"
    return "${pkg.packageName} ${pkg.versionName}"
}

private fun openInBrowser(context: Context) {
    CustomTabsIntent.Builder().build().launchUrl(context, ZeroClawRuntime.DASHBOARD_URL.toUri())
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ZeroClaw logs", text))
}
