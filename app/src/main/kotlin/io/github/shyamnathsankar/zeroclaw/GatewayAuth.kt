package io.github.shyamnathsankar.zeroclaw

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Keeps the gateway's pairing on (so other apps on the phone cannot use the agent) while the user never
 * sees a pairing code: the app mints a one-time code with the CLI (`gateway get-paircode`, which only works
 * for this app's own files), exchanges it at `POST /pair` for a bearer token and keeps that token in
 * app-private storage. The token is sent on the app's own API calls and handed to the dashboard.
 */
object GatewayAuth {
    private const val PREFS = "gateway_auth"
    private const val KEY_TOKEN = "token"

    @Volatile private var cached: String? = null

    /** The token once the app has paired with the current gateway (null until then). */
    private val _paired = MutableStateFlow<String?>(null)
    val paired: StateFlow<String?> = _paired.asStateFlow()

    fun token(context: Context): String? =
        cached ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null).also { cached = it }

    /** Called when the daemon stops: the next one must be checked again. */
    fun reset() {
        _paired.value = null
    }

    /** Returns a token the running gateway accepts, pairing again if needed. Blocking. */
    @Synchronized
    fun ensureToken(context: Context): String? {
        token(context)?.let {
            if (isAccepted(it)) {
                _paired.value = it
                return it
            }
        }
        val app = context.applicationContext
        val output = ZeroClawRuntime.runCapture(
            app,
            listOf("gateway", "get-paircode", "--new", "--host", ZeroClawRuntime.HOST, "--port", ZeroClawRuntime.PORT.toString(), "--json"),
        ) ?: return null
        val code = Regex("\"pairing_code\"\\s*:\\s*\"([^\"]+)\"").find(output)?.groupValues?.get(1)
        if (code == null) {
            ZeroClawRuntime.appLog("[app] could not get a pairing code: ${output.trim().take(200)}")
            return null
        }
        val conn = URL("http://${ZeroClawRuntime.HOST}:${ZeroClawRuntime.PORT}/pair").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("X-Pairing-Code", code)
        conn.connectTimeout = 3_000
        conn.readTimeout = 10_000
        val token = try {
            val ok = conn.responseCode in 200..299
            val body = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (!ok) {
                ZeroClawRuntime.appLog("[app] pairing failed: ${body.take(200)}")
                return null
            }
            JSONObject(body).optString("token").takeIf { it.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
        if (token != null) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TOKEN, token).apply()
            cached = token
            _paired.value = token
            ZeroClawRuntime.appLog("[app] paired with the agent gateway")
        }
        return token
    }

    private fun isAccepted(token: String): Boolean = runCatching {
        val conn = URL("http://${ZeroClawRuntime.HOST}:${ZeroClawRuntime.PORT}/api/cron").openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 3_000
        conn.readTimeout = 10_000
        try {
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    /** Adds the bearer token to an app → gateway request, if we have one. */
    fun authorize(context: Context, conn: HttpURLConnection) {
        token(context)?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
    }
}
