package io.github.shyamnathsankar.zeroclaw.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.shyamnathsankar.zeroclaw.ui.theme.Zc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One-time consent to the privacy notice, and which privacy screen is showing. */
object PrivacyUi {
    private const val PREFS = "privacy"
    private const val KEY_ACCEPTED = "accepted_v1"

    private val _showPolicy = MutableStateFlow(false)
    val showPolicy: StateFlow<Boolean> = _showPolicy.asStateFlow()

    private val _accepted = MutableStateFlow<Boolean?>(null)
    val accepted: StateFlow<Boolean?> = _accepted.asStateFlow()

    fun load(context: Context) {
        if (_accepted.value == null) {
            _accepted.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACCEPTED, false)
        }
    }

    fun accept(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACCEPTED, true).apply()
        _accepted.value = true
    }

    fun openPolicy() {
        _showPolicy.value = true
    }

    fun closePolicy() {
        _showPolicy.value = false
    }
}

/** First launch: what the app does with data, before anything else. */
@Composable
fun OnboardingScreen(onAccept: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Zc.Sage) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            BrandMark()
            Spacer(Modifier.height(36.dp))
            Text("Before you", style = MaterialTheme.typography.displayMedium, color = Zc.Ink)
            Text(
                "start.",
                style = MaterialTheme.typography.displayMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                color = Zc.Ink,
            )
            Spacer(Modifier.height(20.dp))
            Point(Zc.Mint, "Your agent runs on this phone.", "Settings, chats, memory and files stay here. No account, no ads, no tracking.")
            Point(Zc.Lilac, "Messages go to the AI you choose.", "What you send, plus files you attach and tool results, goes to the provider you set up (OpenAI, Anthropic, Gemini, OpenRouter…) with your own API key.")
            Point(Zc.Butter, "You stay in control.", "The agent asks before it submits forms, signs in, pays or deletes files. You type passwords yourself.")
            Point(Zc.Apricot, "Always visible.", "When it works in the background you'll see a notification you can stop at any time.")
            Spacer(Modifier.height(20.dp))
            Hairline()
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Agree and continue", style = MaterialTheme.typography.bodyLarge, color = Zc.Ink)
                    Text(
                        "Read the privacy policy",
                        style = MaterialTheme.typography.labelMedium,
                        color = Zc.Forest,
                        modifier = Modifier.padding(top = 4.dp).clickable { PrivacyUi.openPolicy() },
                    )
                }
                CircleArrowButton(onClick = onAccept, contentDescription = "Agree and continue")
            }
        }
    }
}

@Composable
private fun Point(color: androidx.compose.ui.graphics.Color, title: String, body: String) {
    Row(Modifier.padding(vertical = 10.dp)) {
        Pill(" ", color)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Zc.Ink)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Zc.InkSoft)
        }
    }
}

/** The full privacy policy, rendered from the bundled `privacy-policy.md`. */
@Composable
fun PrivacyPolicyScreen() {
    val context = LocalContext.current
    BackHandler { PrivacyUi.closePolicy() }
    val lines = remember {
        runCatching { context.assets.open("privacy-policy.md").bufferedReader().readLines() }.getOrDefault(emptyList())
    }
    Surface(Modifier.fillMaxSize(), color = Zc.Sage) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(Modifier.weight(1f))
                OutlinePill("Close", onClick = { PrivacyUi.closePolicy() })
            }
            Spacer(Modifier.height(16.dp))
            lines.forEach { line ->
                when {
                    line.startsWith("# ") -> Text(line.removePrefix("# "), style = MaterialTheme.typography.displaySmall, color = Zc.Ink)
                    line.startsWith("## ") -> {
                        Spacer(Modifier.height(10.dp))
                        Text(line.removePrefix("## "), style = MaterialTheme.typography.headlineMedium, color = Zc.Ink)
                    }
                    line.startsWith("- ") -> Row {
                        Text("•  ", style = MaterialTheme.typography.bodyMedium, color = Zc.Ink)
                        Text(inlineBold(line.removePrefix("- ")), style = MaterialTheme.typography.bodyMedium, color = Zc.InkSoft)
                    }
                    line.isBlank() -> Spacer(Modifier.height(4.dp))
                    else -> Text(inlineBold(line), style = MaterialTheme.typography.bodyMedium, color = Zc.InkSoft)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Renders `**bold**` spans; everything else is plain text. */
private fun inlineBold(text: String): AnnotatedString = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1) {
            pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Zc.Ink))
            append(part)
            pop()
        } else {
            append(part)
        }
    }
}
