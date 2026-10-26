package io.github.shyamnathsankar.zeroclaw.background

import io.github.shyamnathsankar.zeroclaw.ZeroClawRuntime
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import org.json.JSONObject

/** One scheduled job as reported by the ZeroClaw gateway (`GET /api/cron`). */
data class CronJobInfo(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val nextRunMs: Long,
    val lastRunMs: Long?,
)

/** Minimal client for the loopback gateway. Blocking; call from a background thread. */
object GatewayClient {

    fun cronJobs(): List<CronJobInfo> {
        val body = get("/api/cron")
        val jobs = JSONObject(body).optJSONArray("jobs") ?: return emptyList()
        return (0 until jobs.length()).mapNotNull { i ->
            val j = jobs.getJSONObject(i)
            val next = parseTime(j.optString("next_run")) ?: return@mapNotNull null
            CronJobInfo(
                id = j.optString("id"),
                name = j.optString("name").takeIf { it.isNotEmpty() && it != "null" } ?: j.optString("expression"),
                enabled = j.optBoolean("enabled", true),
                nextRunMs = next,
                lastRunMs = if (j.isNull("last_run")) null else parseTime(j.optString("last_run")),
            )
        }
    }

    private fun parseTime(s: String?): Long? =
        s?.takeIf { it.isNotEmpty() && it != "null" }?.let { t ->
            runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
        }

    private fun get(path: String): String {
        val conn = URL("http://${ZeroClawRuntime.HOST}:${ZeroClawRuntime.PORT}$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000
        conn.readTimeout = 10_000
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("GET $path -> HTTP $code: ${text.take(200)}")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
