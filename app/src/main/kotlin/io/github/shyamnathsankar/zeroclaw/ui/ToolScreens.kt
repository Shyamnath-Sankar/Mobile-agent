package io.github.shyamnathsankar.zeroclaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.shyamnathsankar.zeroclaw.tools.AgentBrowser
import io.github.shyamnathsankar.zeroclaw.tools.Approvals
import io.github.shyamnathsankar.zeroclaw.tools.Shell
import io.github.shyamnathsankar.zeroclaw.tools.TerminalLog
import io.github.shyamnathsankar.zeroclaw.ui.theme.Zc
import kotlinx.coroutines.launch

/** Live view of the agent's browser. The user can also browse by hand. */
@Composable
fun BrowserScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AgentBrowser.state.collectAsState()
    var address by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.url) { if (state.url.isNotEmpty() && state.url != "about:blank") address = state.url }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 20.dp)) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            BrandMark()
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Browser", style = MaterialTheme.typography.displaySmall, color = Zc.Ink, modifier = Modifier.weight(1f))
                Pill(if (state.loading) "Loading" else "Live", if (state.loading) Zc.Butter else Zc.Mint)
            }
            Text(
                "Watch your agent browse. It asks before it submits, signs in or pays.",
                style = MaterialTheme.typography.bodyMedium,
                color = Zc.InkSoft,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    placeholder = { Text("Search or type a URL") },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { scope.launch { AgentBrowser.navigate(context, toUrl(address)) } }),
                    colors = fieldColors(),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                CircleArrowButton(
                    onClick = { scope.launch { AgentBrowser.navigate(context, toUrl(address)) } },
                    contentDescription = "Go",
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .padding(bottom = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Zc.Paper),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> AgentBrowser.view(ctx) },
                // The WebView outlives this screen (the agent keeps using it), so only detach it.
                onRelease = { AgentBrowser.detach() },
            )
        }
    }
}

private fun toUrl(input: String): String {
    val t = input.trim()
    return when {
        t.startsWith("http://") || t.startsWith("https://") -> t
        t.contains('.') && !t.contains(' ') -> "https://$t"
        else -> "https://duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(t, "UTF-8")
    }
}

/** A command console on the phone's built-in shell, also showing what the agent ran. */
@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by TerminalLog.entries.collectAsState()
    var command by rememberSaveable { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex) }

    fun submit() {
        val cmd = command.trim()
        if (cmd.isEmpty() || running) return
        running = true
        scope.launch {
            val outcome = Shell.run(context, cmd, Shell.DEFAULT_TIMEOUT_S)
            TerminalLog.record(cmd, outcome, byAgent = false)
            command = ""
            running = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 24.dp, vertical = 20.dp)) {
        BrandMark()
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Terminal", style = MaterialTheme.typography.displaySmall, color = Zc.Ink, modifier = Modifier.weight(1f))
            OutlinePill("Clear", onClick = { TerminalLog.clear() })
        }
        Text(
            "Android's built-in shell, sandboxed to the agent workspace. Commands the agent runs show up here too.",
            style = MaterialTheme.typography.bodyMedium,
            color = Zc.InkSoft,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Zc.Ink)
                .padding(14.dp),
        ) {
            if (entries.isEmpty()) {
                item {
                    Text(
                        "$ ls -la\n\nTry a command, e.g. ls, cat, grep, find, date, df -h",
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Zc.Mint,
                    )
                }
            }
            items(entries) { e ->
                Column {
                    Text(
                        (if (e.byAgent) "agent $ " else "$ ") + e.command,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = if (e.byAgent) Zc.Lilac else Zc.Mint,
                    )
                    if (e.output.isNotBlank()) {
                        Text(e.output.trimEnd(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = Zc.Sage)
                    }
                    Text("[${e.status}]", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Zc.Muted)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                placeholder = { Text("Command", fontFamily = FontFamily.Monospace) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                shape = RoundedCornerShape(20.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                colors = fieldColors(),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            CircleArrowButton(onClick = ::submit, loading = running, contentDescription = "Run")
        }
    }
}

/** Shown over every screen while the agent waits for the user's decision. */
@Composable
fun ApprovalDialog() {
    val context = LocalContext.current
    val request by Approvals.pending.collectAsState()
    val r = request ?: return
    AlertDialog(
        onDismissRequest = { },
        containerColor = Zc.Sage,
        shape = RoundedCornerShape(28.dp),
        title = { Text(r.title, style = MaterialTheme.typography.headlineMedium, color = Zc.Ink) },
        text = { Text(r.detail, style = MaterialTheme.typography.bodyMedium, color = Zc.InkSoft) },
        confirmButton = {
            TextButton(onClick = { Approvals.resolve(context, r.id, true) }) {
                Text("Allow", style = MaterialTheme.typography.labelLarge, color = Zc.Forest)
            }
        },
        dismissButton = {
            TextButton(onClick = { Approvals.resolve(context, r.id, false) }) {
                Text("Deny", style = MaterialTheme.typography.labelLarge, color = Zc.Error)
            }
        },
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Zc.Ink,
    unfocusedBorderColor = Zc.Hairline,
    focusedContainerColor = Zc.Paper,
    unfocusedContainerColor = Zc.Paper,
)
