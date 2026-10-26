package io.github.shyamnathsankar.zeroclaw.ui

import android.app.ActivityManager
import android.os.Process
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.shyamnathsankar.zeroclaw.ZeroClawRuntime
import io.github.shyamnathsankar.zeroclaw.reports.AiReports
import io.github.shyamnathsankar.zeroclaw.ui.theme.Zc
import kotlinx.coroutines.launch

/** Typical idle footprint of the agent process when it cannot be measured yet. */
private const val TYPICAL_AGENT_MB = "45–80"

/** Explains the cost of always-on before turning it on, using live measurements when available. */
@Composable
fun AlwaysOnDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val agentMb = remember { ZeroClawRuntime.daemonMemoryMb() }
    val appMb = remember {
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            am.getProcessMemoryInfo(intArrayOf(Process.myPid())).first().totalPss / 1024
        }.getOrNull()
    }
    val memoryLine = if (agentMb != null) {
        "about $agentMb MB of RAM for the agent right now" + (appMb?.let { " (plus about $it MB for the app while it is open)" } ?: "")
    } else {
        "about $TYPICAL_AGENT_MB MB of RAM for the agent, more during long tasks"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Zc.Sage,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Keep the agent always on?", style = MaterialTheme.typography.headlineMedium, color = Zc.Ink) },
        text = {
            Column {
                Text(
                    "Your agent will keep running in the background with a notification, so chat channels " +
                        "(Telegram, Discord, email) can reach it any time.",
                    style = MaterialTheme.typography.bodyMedium, color = Zc.InkSoft,
                )
                Spacer(Modifier.height(12.dp))
                Text("• Memory: $memoryLine.", style = MaterialTheme.typography.bodyMedium, color = Zc.Ink)
                Text(
                    "• Battery: it keeps a network connection open, so expect noticeably more drain than the default.",
                    style = MaterialTheme.typography.bodyMedium, color = Zc.Ink,
                )
                Text(
                    "• Android may still stop it when memory is low; set Battery to Unrestricted if that happens.",
                    style = MaterialTheme.typography.bodyMedium, color = Zc.Ink,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "You don't need this for scheduled jobs: they wake the agent on their own.",
                    style = MaterialTheme.typography.bodySmall, color = Zc.Muted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Turn on", style = MaterialTheme.typography.labelLarge, color = Zc.Forest) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now", style = MaterialTheme.typography.labelLarge, color = Zc.Ink) }
        },
    )
}

/** In-app reporting of offensive AI output (required by Play's AI-Generated Content policy). */
@Composable
fun ReportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var response by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf<String?>(null) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Zc.Ink,
        unfocusedBorderColor = Zc.Hairline,
        focusedContainerColor = Zc.Paper,
        unfocusedContainerColor = Zc.Paper,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Zc.Sage,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Report an AI response", style = MaterialTheme.typography.headlineMedium, color = Zc.Ink) },
        text = {
            Column {
                if (sent != null) {
                    Text(sent!!, style = MaterialTheme.typography.bodyMedium, color = Zc.Ink)
                } else {
                    Text(
                        "Paste the response and tell us what was wrong. Reports are used to improve safety filtering.",
                        style = MaterialTheme.typography.bodyMedium, color = Zc.InkSoft,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = response, onValueChange = { response = it },
                        label = { Text("The AI response") }, minLines = 3, shape = RoundedCornerShape(16.dp),
                        colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = reason, onValueChange = { reason = it },
                        label = { Text("What's wrong with it?") }, minLines = 2, shape = RoundedCornerShape(16.dp),
                        colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (sent == null) {
                TextButton(
                    enabled = response.isNotBlank() || reason.isNotBlank(),
                    onClick = { scope.launch { sent = AiReports.submit(context, response, reason) } },
                ) { Text("Send report", style = MaterialTheme.typography.labelLarge, color = Zc.Error) }
            } else {
                TextButton(onClick = onDismiss) { Text("Done", style = MaterialTheme.typography.labelLarge, color = Zc.Ink) }
            }
        },
        dismissButton = {
            if (sent == null) TextButton(onClick = onDismiss) { Text("Cancel", style = MaterialTheme.typography.labelLarge, color = Zc.Ink) }
        },
    )
}
