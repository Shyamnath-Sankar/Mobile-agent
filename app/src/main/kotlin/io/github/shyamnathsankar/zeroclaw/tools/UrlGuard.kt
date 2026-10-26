package io.github.shyamnathsankar.zeroclaw.tools

import android.net.Uri
import java.net.InetAddress

/**
 * Keeps agent web tools on the public internet. Blocks loopback and private networks so the agent
 * cannot reach the local gateway, this MCP server or devices on the user's LAN through a web tool.
 */
object UrlGuard {
    fun check(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return "Invalid URL"
        if (uri.scheme != "https" && uri.scheme != "http") return "Only http and https URLs are allowed"
        val host = uri.host?.lowercase() ?: return "URL has no host"
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return "Local addresses are blocked"
        val literal = host.trim('[', ']')
        val looksNumeric = literal.all { it.isDigit() || it == '.' } || literal.contains(':')
        if (looksNumeric) {
            val addr = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return "Invalid address"
            if (isPrivate(addr)) return "Private and local network addresses are blocked"
        }
        return null
    }

    /** Re-check after DNS resolution (call off the main thread). */
    fun checkResolved(url: String): String? {
        check(url)?.let { return it }
        val host = Uri.parse(url).host ?: return "URL has no host"
        val addrs = runCatching { InetAddress.getAllByName(host).toList() }.getOrElse { return "Could not resolve $host" }
        return if (addrs.any(::isPrivate)) "This host resolves to a private or local address" else null
    }

    private fun isPrivate(a: InetAddress) =
        a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress ||
            a.isMulticastAddress || (a.address.size == 16 && (a.address[0].toInt() and 0xfe) == 0xfc)
}
