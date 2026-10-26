package io.github.shyamnathsankar.zeroclaw.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.core.net.toUri
import io.github.shyamnathsankar.zeroclaw.BuildConfig
import io.github.shyamnathsankar.zeroclaw.GatewayAuth
import io.github.shyamnathsankar.zeroclaw.ZeroClawRuntime
import io.github.shyamnathsankar.zeroclaw.background.AgentPrefs
import io.github.shyamnathsankar.zeroclaw.background.AgentScheduler
import io.github.shyamnathsankar.zeroclaw.ui.theme.Zc
import io.github.shyamnathsankar.zeroclaw.ui.theme.ZcTheme
import kotlinx.coroutines.delay

private enum class Tab(val label: String, val icon: ImageVector) {
    Agent("Agent", Icons.Outlined.Home),
    Browser("Browser", Icons.Outlined.Search),
    Terminal("Shell", Icons.Outlined.Build),
    Logs("Logs", Icons.Outlined.List),
    App("App", Icons.Outlined.Settings),
}

@Composable
fun ZeroClawApp(onStart: () -> Unit, onStop: () -> Unit, onAlwaysOnChange: (Boolean) -> Unit) {
    ZcTheme {
        val context = LocalContext.current
        var tab by rememberSaveable { mutableStateOf(Tab.Agent) }
        val gatewayUp by ZeroClawRuntime.ready.collectAsState()
        val token by GatewayAuth.paired.collectAsState()
        // The dashboard needs the gateway up and the app paired (pairing stays on for security).
        val ready = gatewayUp && token != null
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
            containerColor = Zc.Sage,
            // Screens handle the status bar themselves; the bottom bar handles the navigation bar.
            contentWindowInsets = WindowInsets(0),
            bottomBar = { BottomTabs(tab) { tab = it } },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (dashboardCreated) DashboardWebView(webView, token, Modifier.fillMaxSize())

                when {
                    tab == Tab.Agent && !ready -> Surface(Modifier.fillMaxSize(), color = Zc.Sage) {
                        StartupScreen(onStart = onStart, onShowLogs = { tab = Tab.Logs })
                    }
                    tab == Tab.Browser -> Surface(Modifier.fillMaxSize(), color = Zc.Sage) { BrowserScreen() }
                    tab == Tab.Terminal -> Surface(Modifier.fillMaxSize(), color = Zc.Sage) { TerminalScreen() }
                    tab == Tab.Logs -> Surface(Modifier.fillMaxSize(), color = Zc.Sage) { LogsScreen() }
                    tab == Tab.App -> Surface(Modifier.fillMaxSize(), color = Zc.Sage) {
                        AppScreen(onStart, onStop, onAlwaysOnChange, onReloadDashboard = { webView.value?.reload() })
                    }
                    else -> Unit
                }
            }
        }
        ApprovalDialog()
    }
}

@Composable
private fun BottomTabs(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Zc.Sage).navigationBarsPadding()) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { t ->
                val active = t == selected
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(if (active) Zc.Ink else Zc.Sage)
                        .clickable { onSelect(t) }
                        .padding(horizontal = if (active) 16.dp else 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(t.icon, contentDescription = t.label, tint = if (active) Zc.Sage else Zc.Ink, modifier = Modifier.size(20.dp))
                    if (active) {
                        Spacer(Modifier.width(8.dp))
                        Text(t.label, style = MaterialTheme.typography.labelLarge, color = Zc.Sage)
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DashboardWebView(holder: MutableState<WebView?>, token: String?, modifier: Modifier) {
    val currentToken by rememberUpdatedState(token)
    AndroidView(
        modifier = modifier.statusBarsPadding(),
        factory = { ctx ->
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            WebView(ctx).apply {
                // Compose gives AndroidViews WRAP_CONTENT by default; a WebView that wraps its content
                // reports a broken CSS viewport height, which collapses the dashboard's vh-sized layout.
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(Zc.Sage.toArgb())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.textZoom = 100
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(false)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        // Hand the dashboard the app's gateway token (it reads localStorage.zeroclaw_token);
                        // reload once if it was missing or stale, so the user never sees a pairing screen.
                        val t = currentToken ?: return
                        if (!url.startsWith(ZeroClawRuntime.DASHBOARD_URL)) return
                        view.evaluateJavascript(
                            "(function(t){if(localStorage.getItem('zeroclaw_token')!==t){localStorage.setItem('zeroclaw_token',t);return 'set';}return 'ok';})(${org.json.JSONObject.quote(t)})",
                        ) { result -> if (result?.contains("set") == true) view.reload() }
                    }

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
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(Modifier.align(Alignment.Start))
        Spacer(Modifier.weight(0.5f))
        Text("Your agent", style = MaterialTheme.typography.displayMedium, color = Zc.Ink, textAlign = TextAlign.Center)
        Text(
            "on device.",
            style = MaterialTheme.typography.displayMedium.copy(fontStyle = FontStyle.Italic),
            color = Zc.Ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Private, local-first AI that runs right\non your phone. Bring your own model.",
            style = MaterialTheme.typography.bodyLarge,
            color = Zc.InkSoft,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Text("✱", fontSize = 40.sp, color = Zc.Ink)
        Text("∼ ∽", fontSize = 22.sp, color = Zc.Ink)
        Spacer(Modifier.weight(1f))
        CapabilityCluster(Modifier.align(Alignment.CenterHorizontally), size = 88.dp)
        Spacer(Modifier.height(28.dp))
        Hairline()
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (running) "Waking up your agent," else "Your agent is resting.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Zc.Ink,
                )
                Text(
                    if (running) "this takes a few seconds." else "Tap to start it.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Zc.InkSoft,
                )
                if (!running) {
                    Text(
                        "View logs",
                        style = MaterialTheme.typography.labelMedium,
                        color = Zc.Forest,
                        modifier = Modifier.padding(top = 6.dp).clickable(onClick = onShowLogs),
                    )
                }
            }
            CircleArrowButton(onClick = onStart, loading = running, contentDescription = "Start agent")
        }
    }
}

@Composable
private fun LogsScreen() {
    val context = LocalContext.current
    val logs by ZeroClawRuntime.logs.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex) }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp)) {
        BrandMark()
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Logs", style = MaterialTheme.typography.displaySmall, color = Zc.Ink, modifier = Modifier.weight(1f))
            Pill("${logs.size} lines", Zc.Mint)
        }
        Text("Everything your agent prints, live.", style = MaterialTheme.typography.bodyMedium, color = Zc.InkSoft)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinePill("Copy all", onClick = { copyToClipboard(context, logs.joinToString("\n")) })
            OutlinePill("Clear", onClick = { ZeroClawRuntime.clearLogs() })
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
                .background(Zc.Paper)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            items(logs) { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = if (line.startsWith("[app] ERROR") || line.contains(" ERROR ")) Zc.Error else Zc.InkSoft,
                )
            }
        }
    }
}

@Composable
private fun AppScreen(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAlwaysOnChange: (Boolean) -> Unit,
    onReloadDashboard: () -> Unit,
) {
    val context = LocalContext.current
    val running by ZeroClawRuntime.running.collectAsState()
    val ready by ZeroClawRuntime.ready.collectAsState()
    val startedAt by ZeroClawRuntime.startedAt.collectAsState()
    var args by rememberSaveable { mutableStateOf("--help") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var memory by remember { mutableFloatStateOf(0f) }
    var storage by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            memory = memoryInUse(context)
            storage = storageInUse(context)
            delay(5_000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        BrandMark()
        Spacer(Modifier.height(32.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(
                when {
                    ready -> "● Running"
                    running -> "● Starting"
                    else -> "○ Stopped"
                },
                if (ready) Zc.Lilac else if (running) Zc.Butter else Zc.Paper,
            )
            Spacer(Modifier.width(12.dp))
            Text("Agent status", style = MaterialTheme.typography.bodyMedium, color = Zc.Ink)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            startedAt?.let { formatUptime(now - it) } ?: "Offline",
            style = MaterialTheme.typography.displayLarge,
            color = Zc.Ink,
        )
        Text(
            if (startedAt != null) "Uptime of your on-device agent" else "Start the agent to bring it online",
            style = MaterialTheme.typography.bodyLarge,
            color = Zc.InkSoft,
        )

        Spacer(Modifier.height(32.dp))
        UsageBar("Device memory in use", memory, Zc.Lilac)
        Spacer(Modifier.height(18.dp))
        UsageBar("Storage in use", storage, Zc.Forest)

        Spacer(Modifier.height(28.dp))
        Hairline()
        if (running) {
            ActionRow("Stop agent", "Stops it now. Scheduled jobs still wake it up.", onClick = onStop)
        } else {
            ActionRow("Start agent", "Runs while the app is open", onClick = onStart)
        }
        Hairline()
        ActionRow("Reload dashboard", "Refresh the in-app dashboard", onClick = onReloadDashboard, enabled = ready)
        Hairline()
        var reporting by remember { mutableStateOf(false) }
        ActionRow(
            "Report an AI response",
            "Flag harmful or offensive output so it can be reviewed",
            onClick = { reporting = true },
        )
        Hairline()
        if (reporting) ReportDialog(onDismiss = { reporting = false })

        Spacer(Modifier.height(28.dp))
        BackgroundSection(onAlwaysOnChange)

        Spacer(Modifier.height(28.dp))
        Text("Diagnostics", style = MaterialTheme.typography.headlineMedium, color = Zc.Ink)
        Spacer(Modifier.height(8.dp))
        Text("App version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = Zc.InkSoft)
        val agentMb = remember(now) { ZeroClawRuntime.daemonMemoryMb() }
        Text(
            "Agent memory: " + (agentMb?.let { "$it MB" } ?: "not running"),
            style = MaterialTheme.typography.bodySmall,
            color = Zc.InkSoft,
        )
        Text(ZeroClawRuntime.describeBinary(context), style = MaterialTheme.typography.bodySmall, color = Zc.InkSoft)
        Text("WebView: ${remember { webViewLabel() }}", style = MaterialTheme.typography.bodySmall, color = Zc.InkSoft)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = args,
                onValueChange = { args = it },
                label = { Text("zeroclaw <args>") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Zc.Ink,
                    unfocusedBorderColor = Zc.Hairline,
                    focusedContainerColor = Zc.Paper,
                    unfocusedContainerColor = Zc.Paper,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            CircleArrowButton(
                onClick = {
                    ZeroClawRuntime.runOnce(context, args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() })
                },
                contentDescription = "Run command",
            )
        }
        Text(
            "Output appears in Logs.",
            style = MaterialTheme.typography.bodySmall,
            color = Zc.Muted,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

private fun formatUptime(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "<1m"
        minutes < 60 -> "${minutes}m"
        minutes < 24 * 60 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes / (24 * 60)}d ${(minutes / 60) % 24}h"
    }
}

private fun memoryInUse(context: Context): Float {
    val am = context.getSystemService(ActivityManager::class.java)
    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    return if (info.totalMem > 0) 1f - info.availMem.toFloat() / info.totalMem else 0f
}

private fun storageInUse(context: Context): Float {
    val stat = StatFs(context.filesDir.absolutePath)
    return if (stat.totalBytes > 0) 1f - stat.availableBytes.toFloat() / stat.totalBytes else 0f
}

private fun webViewLabel(): String {
    val pkg = WebView.getCurrentWebViewPackage() ?: return "unknown"
    return "${pkg.packageName} ${pkg.versionName}"
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ZeroClaw logs", text))
}

/** How the agent runs when the app is closed: schedules, exact timing, always-on, battery. */
@Composable
private fun BackgroundSection(onAlwaysOnChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val alwaysOn by AgentPrefs.alwaysOn.collectAsState()
    val exact by AgentPrefs.exactTiming.collectAsState()
    val nextWake by AgentPrefs.nextWakeAt.collectAsState()
    // Re-read system permission state whenever the screen is shown again.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val canExact = remember(refresh) { AgentScheduler.canUseExactAlarms(context) }
    val unrestricted = remember(refresh) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }

    Text("Background", style = MaterialTheme.typography.headlineMedium, color = Zc.Ink)
    Text(
        "The agent sleeps when you're not using it and wakes up for scheduled jobs, so it barely touches your battery.",
        style = MaterialTheme.typography.bodyMedium,
        color = Zc.InkSoft,
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Pill(if (nextWake != null) "Next job" else "No jobs", if (nextWake != null) Zc.Mint else Zc.Paper)
        Spacer(Modifier.width(12.dp))
        Text(
            nextWake?.let { formatWhen(it) } ?: "Create schedules in the dashboard (Automations)",
            style = MaterialTheme.typography.bodyMedium,
            color = Zc.Ink,
        )
    }
    Spacer(Modifier.height(8.dp))
    Hairline()
    ToggleRow(
        title = "Exact timing",
        subtitle = when {
            exact && !canExact -> "Allow \"Alarms & reminders\" for this app to turn this on"
            exact -> "Jobs run on the minute, even in Doze"
            else -> "Off: Android batches wake-ups to save battery, jobs may run a few minutes late"
        },
        checked = exact && canExact,
        onCheckedChange = { on ->
            AgentPrefs.setExactTiming(context, on)
            if (on && !canExact && Build.VERSION.SDK_INT >= 31) {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()),
                )
            }
            AgentScheduler.rebook(context)
        },
    )
    Hairline()
    var confirmAlwaysOn by remember { mutableStateOf(false) }
    ToggleRow(
        title = "Always on",
        subtitle = "Keeps the agent running with a notification, for chat channels like Telegram. Uses more battery.",
        checked = alwaysOn,
        onCheckedChange = { on -> if (on) confirmAlwaysOn = true else onAlwaysOnChange(false) },
    )
    if (confirmAlwaysOn) {
        AlwaysOnDialog(
            onConfirm = {
                confirmAlwaysOn = false
                onAlwaysOnChange(true)
            },
            onDismiss = { confirmAlwaysOn = false },
        )
    }
    Hairline()
    ActionRow(
        "Battery settings",
        if (unrestricted) "Unrestricted: background work is never paused" else "Optimized (recommended). Set to Unrestricted only if always-on gets stopped by your phone",
        onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        diagonal = true,
    )
    Hairline()
}

private fun formatWhen(at: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val time = java.time.Instant.ofEpochMilli(at).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val pattern = when (time.toLocalDate()) {
        today -> "'Today' HH:mm"
        today.plusDays(1) -> "'Tomorrow' HH:mm"
        else -> "EEE d MMM, HH:mm"
    }
    return time.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
}
