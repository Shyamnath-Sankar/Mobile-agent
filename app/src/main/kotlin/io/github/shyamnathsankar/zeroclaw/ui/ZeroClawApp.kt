package io.github.shyamnathsankar.zeroclaw.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.compose.ui.viewinterop.AndroidView
import io.github.shyamnathsankar.zeroclaw.ZeroClawRuntime

private enum class Tab(val label: String) { Dashboard("Dashboard"), Logs("Logs"), Control("Control") }

@Composable
fun ZeroClawApp(onStart: () -> Unit, onStop: () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        var tab by rememberSaveable { mutableStateOf(Tab.Control) }
        val ready by ZeroClawRuntime.ready.collectAsState()

        // Jump to the dashboard the first time the gateway comes up.
        LaunchedEffect(ready) { if (ready) tab = Tab.Dashboard }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {
                                Icon(
                                    when (t) {
                                        Tab.Dashboard -> Icons.Filled.Home
                                        Tab.Logs -> Icons.Filled.Info
                                        Tab.Control -> Icons.Filled.Settings
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
                when (tab) {
                    Tab.Dashboard -> DashboardScreen()
                    Tab.Logs -> LogsScreen()
                    Tab.Control -> ControlScreen(onStart, onStop)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DashboardScreen() {
    val ready by ZeroClawRuntime.ready.collectAsState()
    val running by ZeroClawRuntime.running.collectAsState()
    var reloadKey by remember { mutableIntStateOf(0) }

    if (!ready) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(
                if (running) "Starting ZeroClaw…" else "ZeroClaw is not running.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (running) "Waiting for the gateway on port ${ZeroClawRuntime.PORT}. Check the Logs tab if this takes long."
                else "Open the Control tab and press Start.",
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        val context = LocalContext.current
        val webView = remember { webViewInfo() }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "ZeroClaw dashboard",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
            )
            OutlinedButton(onClick = { openInBrowser(context) }) { Text("Open in Chrome") }
            OutlinedButton(onClick = { reloadKey++ }) { Text("Reload") }
        }
        if (webView.tooOld) {
            Text(
                "Your WebView (${webView.label}) is older than the dashboard needs (Chrome 111+). " +
                    "Update \"Android System WebView\" in Play Store, or use Open in Chrome.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        androidx.compose.runtime.key(reloadKey) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val host = request.url.host
                                if (host == ZeroClawRuntime.HOST || host == "localhost") return false
                                // Anything off-device opens in the user's browser, never inside this WebView.
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                                return true
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = Unit
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
                    }
                },
                onRelease = { it.destroy() },
            )
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
private fun ControlScreen(onStart: () -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    val running by ZeroClawRuntime.running.collectAsState()
    val ready by ZeroClawRuntime.ready.collectAsState()
    var args by rememberSaveable { mutableStateOf("--help") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ZeroClaw Mobile", style = MaterialTheme.typography.headlineSmall)
        Text(
            when {
                ready -> "Status: running, gateway ready"
                running -> "Status: starting…"
                else -> "Status: stopped"
            },
        )
        Text(ZeroClawRuntime.describeBinary(context), style = MaterialTheme.typography.bodySmall)
        val webView = remember { webViewInfo() }
        Text("WebView: ${webView.label}", style = MaterialTheme.typography.bodySmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = !running) { Text("Start agent") }
            Button(
                onClick = onStop,
                enabled = running,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Stop") }
        }

        Text("Troubleshooting: run a one-off command", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = args,
            onValueChange = { args = it },
            label = { Text("zeroclaw <args>") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { openInBrowser(context) }, enabled = ready) { Text("Open dashboard in Chrome") }
        OutlinedButton(onClick = {
            ZeroClawRuntime.runOnce(context, args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() })
        }) { Text("Run once (output in Logs)") }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ZeroClaw logs", text))
}

private class WebViewInfo(val label: String, val tooOld: Boolean)

/** The dashboard is built with Tailwind v4, which needs Chrome/WebView 111+. */
private fun webViewInfo(): WebViewInfo {
    val pkg = WebView.getCurrentWebViewPackage()
    val version = pkg?.versionName ?: return WebViewInfo("unknown", false)
    val major = version.substringBefore('.').toIntOrNull()
    return WebViewInfo("${pkg.packageName} $version", major != null && major < 111)
}

private fun openInBrowser(context: Context) {
    CustomTabsIntent.Builder().build().launchUrl(context, ZeroClawRuntime.DASHBOARD_URL.toUri())
}
