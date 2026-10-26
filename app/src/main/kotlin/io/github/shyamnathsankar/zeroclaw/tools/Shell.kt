package io.github.shyamnathsankar.zeroclaw.tools

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs shell commands with Android's built-in toybox shell (/system/bin/sh) inside the app's own
 * sandbox. Nothing is downloaded or installed: only the system binaries and files in the workspace.
 */
object Shell {
    const val MAX_OUTPUT_CHARS = 32_000
    const val DEFAULT_TIMEOUT_S = 60
    const val MAX_TIMEOUT_S = 300

    fun workspace(context: Context) = File(context.filesDir, "workspace").also { it.mkdirs() }

    /** Commands that delete, overwrite or reformat data need the user's approval first. */
    private val destructive = listOf(
        Regex("""(^|[;&|]\s*)rm\s"""),
        Regex("""(^|[;&|]\s*)rmdir\s"""),
        Regex("""(^|[;&|]\s*)(mv|dd|truncate|shred|mkfs\S*|chmod\s+-R|chown\s+-R)\b"""),
        Regex(""">\s*[^&|\s]"""),
        Regex("""\bfind\b.*\s-delete\b"""),
        Regex(""":\(\)\s*\{"""),
    )

    fun isDestructive(command: String) = destructive.any { it.containsMatchIn(command) }

    /**
     * The shell runs with the app's permissions, so it could read the agent's config, saved secrets and
     * the app's preferences. Commands that leave the workspace (absolute app-data paths, `..`, the
     * secret key) need the user's approval first.
     */
    private val leavesWorkspace = listOf(
        Regex("""\.\."""),
        Regex("""/data/(data|user|user_de)/"""),
        Regex("""\.secret_key|shared_prefs|files/zeroclaw|files/home|/proc/self"""),
        Regex("""(^|[\s;&|])cd\s+(/|~)"""),
    )

    fun leavesWorkspace(command: String) = leavesWorkspace.any { it.containsMatchIn(command) }

    data class Outcome(val exitCode: Int?, val output: String, val timedOut: Boolean)

    suspend fun run(context: Context, command: String, timeoutSeconds: Int, cwd: String? = null): Outcome =
        withContext(Dispatchers.IO) {
            val ws = workspace(context)
            val dir = cwd?.let { File(ws, it).canonicalFile }?.takeIf { it.path.startsWith(ws.canonicalPath) && it.isDirectory } ?: ws
            val pb = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(dir)
                .redirectErrorStream(true)
            pb.environment().apply {
                put("HOME", ws.absolutePath)
                put("TMPDIR", context.cacheDir.absolutePath)
                put("PATH", "/system/bin:/system/xbin")
                put("WORKSPACE", ws.absolutePath)
            }
            val process = pb.start()
            val out = StringBuilder()
            var dropped = 0
            val reader = thread(isDaemon = true) {
                process.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(out) {
                            // Keep the head and the tail; drop the middle of very long output.
                            if (out.length < MAX_OUTPUT_CHARS) out.append(buf, 0, n) else dropped += n
                        }
                    }
                }
            }
            val finished = process.waitFor(timeoutSeconds.coerceIn(1, MAX_TIMEOUT_S).toLong(), TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(2_000)
            val text = synchronized(out) {
                if (dropped > 0) "$out\n… [$dropped more characters truncated]" else out.toString()
            }
            Outcome(if (finished) process.exitValue() else null, text, timedOut = !finished)
        }
}
