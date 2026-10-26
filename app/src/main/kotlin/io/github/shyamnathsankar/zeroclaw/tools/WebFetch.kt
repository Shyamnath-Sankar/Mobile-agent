package io.github.shyamnathsankar.zeroclaw.tools

import android.text.Html
import java.net.HttpURLConnection
import java.net.URL

/** Downloads a public web page and returns readable text, for research without a browser. */
object WebFetch {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_REDIRECTS = 5

    /** Blocking; call on Dispatchers.IO. */
    fun fetch(url: String, maxChars: Int): ToolResult {
        var current = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        repeat(MAX_REDIRECTS + 1) {
            UrlGuard.checkResolved(current)?.let { return ToolResult(it, isError = true) }
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) ZeroClawMobile")
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.5")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return ToolResult("Redirect without location", isError = true)
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                val stream = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?: return ToolResult("HTTP $code with no body", isError = code !in 200..299)
                val bytes = stream.use { it.readNBytesCompat(MAX_BYTES) }
                val type = conn.contentType.orEmpty().lowercase()
                val charset = Regex("charset=([\\w-]+)").find(type)?.groupValues?.get(1) ?: "UTF-8"
                val body = runCatching { String(bytes, charset(charset)) }.getOrElse { String(bytes) }
                val text = if (type.contains("html") || body.trimStart().startsWith("<")) htmlToText(body) else body
                val header = "URL: $current\nHTTP $code\n\n"
                return ToolResult(header + text.take(maxChars), isError = code !in 200..299)
            } finally {
                conn.disconnect()
            }
        }
        return ToolResult("Too many redirects", isError = true)
    }

    private fun htmlToText(html: String): String {
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.trim()
        val cleaned = html
            .replace(Regex("<(script|style|noscript|svg|template)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<(br|/p|/div|/li|/h[1-6]|/tr|/section|/article)[^>]*>", RegexOption.IGNORE_CASE), "\n")
        val text = Html.fromHtml(cleaned, Html.FROM_HTML_MODE_COMPACT).toString()
            .replace(' ', ' ')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n\\s*\\n\\s*\\n+"), "\n\n")
            .trim()
        return if (title != null) "Title: $title\n\n$text" else text
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
