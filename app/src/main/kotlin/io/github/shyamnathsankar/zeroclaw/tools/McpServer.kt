package io.github.shyamnathsankar.zeroclaw.tools

import android.content.Context
import io.github.shyamnathsankar.zeroclaw.ZeroClawRuntime
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's MCP server (Streamable HTTP, JSON responses) on loopback. ZeroClaw connects to it as the
 * `android` MCP server, so the agent can use [Tools]. Requests must carry the per-install token in
 * the URL, which keeps other apps on the phone from driving the browser or shell.
 */
object McpServer {
    const val PORT = 42618
    private const val PROTOCOL_VERSION = "2025-06-18"
    private const val MAX_BODY = 1 * 1024 * 1024

    @Volatile private var socket: ServerSocket? = null
    private lateinit var app: Context

    fun url(context: Context) = "http://127.0.0.1:$PORT/mcp?token=${token(context)}"

    @Synchronized
    fun ensureStarted(context: Context) {
        app = context.applicationContext
        if (socket?.isClosed == false) return
        val server = runCatching { ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1")) }.getOrElse {
            ZeroClawRuntime.appLog("[app] MCP server could not start: ${it.message}")
            return
        }
        socket = server
        thread(name = "mcp-accept", isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(name = "mcp-conn", isDaemon = true) { runCatching { handle(client) } }
            }
        }
        ZeroClawRuntime.appLog("[app] tools server listening on 127.0.0.1:$PORT")
    }

    private fun handle(client: Socket) = client.use { s ->
        s.soTimeout = 30_000
        val input = BufferedInputStream(s.getInputStream())
        val requestLine = readLine(input) ?: return
        val (method, target) = requestLine.split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val out = s.getOutputStream()
        fun respond(code: Int, status: String, body: String = "", contentType: String = "application/json") {
            val bytes = body.toByteArray()
            out.write(
                ("HTTP/1.1 $code $status\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
            out.write(bytes)
            out.flush()
        }

        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val token = query.split('&').firstOrNull { it.startsWith("token=") }?.substringAfter('=')
        if (path != "/mcp") return respond(404, "Not Found", "{}")
        if (token != token(app)) return respond(401, "Unauthorized", "{}")
        if (method == "GET") return respond(405, "Method Not Allowed", "")
        if (method == "DELETE") return respond(200, "OK", "")
        if (method != "POST") return respond(405, "Method Not Allowed", "")

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0 || length > MAX_BODY) return respond(400, "Bad Request", "{}")
        val body = String(readExactly(input, length))

        val parsed = runCatching { org.json.JSONTokener(body).nextValue() }.getOrNull()
            ?: return respond(400, "Bad Request", error(null, -32700, "Parse error").toString())
        val messages = if (parsed is JSONArray) (0 until parsed.length()).map { parsed.getJSONObject(it) } else listOf(parsed as JSONObject)
        val responses = messages.mapNotNull { dispatch(it) }
        when {
            responses.isEmpty() -> respond(202, "Accepted", "")
            parsed is JSONArray -> respond(200, "OK", JSONArray(responses).toString())
            else -> respond(200, "OK", responses.first().toString())
        }
    }

    /** Handles one JSON-RPC message; null for notifications. */
    private fun dispatch(msg: JSONObject): JSONObject? {
        if (!msg.has("id")) return null
        val id = msg.get("id")
        val params = msg.optJSONObject("params") ?: JSONObject()
        return when (msg.optString("method")) {
            "initialize" -> result(
                id,
                JSONObject()
                    .put("protocolVersion", params.optString("protocolVersion").ifEmpty { PROTOCOL_VERSION })
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", "zeroclaw-mobile-android").put("version", "1"))
                    .put(
                        "instructions",
                        "Tools that act on the user's phone: a visible web browser, web page fetching and a sandboxed shell. " +
                            "Sensitive actions (form submits, sign-ins, payments, deleting files) pause for the user's approval.",
                    ),
            )
            "ping" -> result(id, JSONObject())
            "tools/list" -> result(
                id,
                JSONObject().put(
                    "tools",
                    JSONArray(
                        Tools.all.map {
                            JSONObject().put("name", it.name).put("description", it.description).put("inputSchema", it.inputSchema)
                        },
                    ),
                ),
            )
            "tools/call" -> {
                val tool = Tools.find(params.optString("name"))
                    ?: return error(id, -32602, "Unknown tool: ${params.optString("name")}")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                ZeroClawRuntime.appLog("[tool] ${tool.name} ${args.toString().take(160)}")
                val r = runCatching { runBlocking { tool.call(app, args) } }
                    .getOrElse { ToolResult("Tool failed: ${it.message}", isError = true) }
                result(
                    id,
                    JSONObject()
                        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", r.text)))
                        .put("isError", r.isError),
                )
            }
            else -> error(id, -32601, "Method not found")
        }
    }

    private fun result(id: Any, result: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)

    private fun error(id: Any?, code: Int, message: String) = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))

    private fun token(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("mcp", Context.MODE_PRIVATE)
        prefs.getString("token", null)?.let { return it }
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val t = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("token", t).apply()
        return t
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(c.toChar())
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return buf
    }
}
