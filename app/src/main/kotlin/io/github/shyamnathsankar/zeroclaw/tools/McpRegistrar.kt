package io.github.shyamnathsankar.zeroclaw.tools

import android.content.Context
import io.github.shyamnathsankar.zeroclaw.GatewayAuth
import io.github.shyamnathsankar.zeroclaw.ZeroClawRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Makes sure ZeroClaw knows about the app's tools, through the gateway config API (the same calls the
 * dashboard makes): an `android` MCP server pointing at [McpServer], a `phone` bundle containing it,
 * and that bundle granted to every agent. Idempotent; runs each time the gateway comes up, so agents
 * created later get the tools after the next start.
 */
object McpRegistrar {
    private const val SERVER = "android"
    private const val BUNDLE = "phone"
    private const val TOOL_TIMEOUT_SECS = 330 // covers a 300 s command or a 120 s approval wait

    private lateinit var appContext: Context

    private val _status = MutableStateFlow("Not registered yet")
    /** Human-readable registration state for the App tab. */
    val status: StateFlow<String> = _status.asStateFlow()

    /** Blocking; call from a background thread while the gateway is up. */
    fun ensure(context: Context) {
        appContext = context.applicationContext
        runCatching { ensureInner(context) }.onFailure {
            _status.value = "Error: ${it.message?.take(120)}"
            throw it
        }
    }

    private fun ensureInner(context: Context) {
        val url = McpServer.url(context)
        if (prop("mcp.servers.$SERVER.url") == null) {
            request("POST", "/api/config/map-key?path=mcp.servers&key=$SERVER")
        }
        if (prop("mcp.servers.$SERVER.url") != url || prop("mcp.servers.$SERVER.transport") != "http") {
            patch(
                JSONArray()
                    .put(replace("mcp.servers.$SERVER.transport", "http"))
                    .put(replace("mcp.servers.$SERVER.url", url))
                    .put(replace("mcp.servers.$SERVER.tool_timeout_secs", TOOL_TIMEOUT_SECS)),
            )
        }
        if (!listValue(prop("mcp_bundles.$BUNDLE.servers")).contains(SERVER)) {
            request("POST", "/api/config/map-key?path=mcp_bundles&key=$BUNDLE")
            patch(JSONArray().put(replace("mcp_bundles.$BUNDLE.servers", JSONArray().put(SERVER))))
        }

        val entries = JSONObject(request("GET", "/api/config/list?prefix=agents.")).optJSONArray("entries") ?: JSONArray()
        val ops = JSONArray()
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val path = e.optString("path")
            val alias = Regex("""^agents\.([^.]+)\.mcp_bundles$""").find(path)?.groupValues?.get(1) ?: continue
            val bundles = listValue(e.optString("value"))
            if (BUNDLE !in bundles) ops.put(replace("agents.$alias.mcp_bundles", JSONArray(bundles + BUNDLE)))
        }
        if (ops.length() > 0) {
            patch(ops)
            ZeroClawRuntime.appLog("[app] phone tools granted to ${ops.length()} more agent(s); start a new conversation to use them")
        }
        val aliases = (0 until entries.length()).mapNotNull {
            Regex("""^agents\.([^.]+)\.mcp_bundles$""").find(entries.getJSONObject(it).optString("path"))?.groupValues?.get(1)
        }
        aliases.forEach { AgentNotes.ensure(context, it) }
        _status.value = if (aliases.isEmpty()) "Ready (no agents yet)" else "Ready for ${aliases.size} agent(s): ${aliases.joinToString()}"
    }

    private fun replace(path: String, value: Any) = JSONObject().put("op", "replace").put("path", path).put("value", value)

    private fun patch(ops: JSONArray) = request("PATCH", "/api/config", ops.toString())

    /** A property's value as a string, or null when the path does not exist. */
    private fun prop(path: String): String? = runCatching {
        JSONObject(request("GET", "/api/config/prop?path=${java.net.URLEncoder.encode(path, "UTF-8")}")).optString("value")
    }.getOrNull()

    /** Parses the gateway's string rendering of a string list ("[\"a\",\"b\"]" or "<unset>"). */
    private fun listValue(value: String?): List<String> {
        if (value == null || !value.trimStart().startsWith("[")) return emptyList()
        val arr = runCatching { JSONArray(value) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /**
     * Minimal HTTP/1.1 client over a loopback socket. HttpURLConnection does not reliably allow PATCH,
     * which the gateway needs to create an MCP server entry atomically.
     */
    private fun request(method: String, path: String, body: String? = null): String {
        java.net.Socket(ZeroClawRuntime.HOST, ZeroClawRuntime.PORT).use { socket ->
            socket.soTimeout = 15_000
            val payload = body?.toByteArray() ?: ByteArray(0)
            val head = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: ${ZeroClawRuntime.HOST}:${ZeroClawRuntime.PORT}\r\n")
                GatewayAuth.token(appContext)?.let { append("Authorization: Bearer $it\r\n") }
                if (body != null) append("Content-Type: application/json\r\n")
                append("Content-Length: ${payload.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out = socket.getOutputStream()
            out.write(head.toByteArray())
            out.write(payload)
            out.flush()

            val raw = socket.getInputStream().readBytes()
            val split = indexOf(raw, "\r\n\r\n".toByteArray())
            require(split >= 0) { "$method $path: malformed response" }
            val headers = String(raw, 0, split).split("\r\n")
            val code = headers.first().split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            val chunked = headers.any { it.lowercase().startsWith("transfer-encoding:") && it.lowercase().contains("chunked") }
            val rest = raw.copyOfRange(split + 4, raw.size)
            val text = String(if (chunked) dechunk(rest) else rest)
            if (code !in 200..299) error("$method $path -> HTTP $code: ${text.take(200)}")
            return text
        }
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    private fun dechunk(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            val lineEnd = indexOf(data.copyOfRange(i, data.size), "\r\n".toByteArray())
            if (lineEnd < 0) break
            val size = String(data, i, lineEnd).substringBefore(';').trim().toIntOrNull(16) ?: break
            i += lineEnd + 2
            if (size == 0) break
            out.write(data, i, minOf(size, data.size - i))
            i += size + 2
        }
        return out.toByteArray()
    }
}
