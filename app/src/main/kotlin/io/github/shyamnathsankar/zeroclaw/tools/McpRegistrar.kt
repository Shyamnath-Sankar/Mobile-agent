package io.github.shyamnathsankar.zeroclaw.tools

import android.content.Context
import io.github.shyamnathsankar.zeroclaw.GatewayAuth
import io.github.shyamnathsankar.zeroclaw.ZeroClawRuntime
import java.net.HttpURLConnection
import java.net.URL
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

    /** Blocking; call from a background thread while the gateway is up. */
    fun ensure(context: Context) {
        appContext = context.applicationContext
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
        if (ops.length() > 0) patch(ops)
        ZeroClawRuntime.appLog("[app] phone tools registered with ZeroClaw (${ops.length()} agent grant(s) added)")
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

    private fun request(method: String, path: String, body: String? = null): String {
        val conn = URL("http://${ZeroClawRuntime.HOST}:${ZeroClawRuntime.PORT}$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        GatewayAuth.authorize(appContext, conn)
        conn.connectTimeout = 3_000
        conn.readTimeout = 15_000
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("$method $path -> HTTP $code: ${text.take(200)}")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
