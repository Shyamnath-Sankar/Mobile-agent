package io.github.shyamnathsankar.zeroclaw.reports

import android.content.Context
import io.github.shyamnathsankar.zeroclaw.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Stores user reports of offensive AI output and sends them to the developer's endpoint
 * (BuildConfig.REPORT_URL, set with the Gradle property `zcReportUrl`). Reports that cannot be sent
 * are kept and retried with the next report.
 */
object AiReports {
    private fun file(context: Context) = File(context.filesDir, "reports/ai_reports.jsonl").also { it.parentFile?.mkdirs() }
    private fun pending(context: Context) = File(context.filesDir, "reports/pending.jsonl")

    /** Returns a message for the user. */
    suspend fun submit(context: Context, response: String, reason: String): String = withContext(Dispatchers.IO) {
        val report = JSONObject()
            .put("time", System.currentTimeMillis())
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("response", response.take(20_000))
            .put("reason", reason.take(4_000))
            .toString()
        file(context).appendText(report + "\n")
        if (BuildConfig.REPORT_URL.isBlank()) {
            pending(context).appendText(report + "\n")
            return@withContext "Thanks, your report was saved."
        }
        val queue = (runCatching { pending(context).readLines() }.getOrDefault(emptyList()) + report).filter { it.isNotBlank() }
        val failed = queue.filterNot { post(it) }
        pending(context).writeText(failed.joinToString("") { it + "\n" })
        if (failed.isEmpty()) "Thanks, your report was sent." else "Thanks, your report was saved and will be sent later."
    }

    private fun post(json: String): Boolean = runCatching {
        val conn = URL(BuildConfig.REPORT_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(json.toByteArray()) }
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        ok
    }.getOrDefault(false)
}
