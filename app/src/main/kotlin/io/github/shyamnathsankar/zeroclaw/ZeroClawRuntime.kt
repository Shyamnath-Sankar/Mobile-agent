package io.github.shyamnathsankar.zeroclaw

import android.content.Context
import android.os.Build
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns the ZeroClaw child process.
 *
 * The agent ships as `libzeroclaw.so` (an ELF executable packaged in jniLibs, extracted to
 * nativeLibraryDir by the installer) and is launched as `zeroclaw daemon` bound to loopback.
 * Nothing is downloaded or executed from writable storage.
 */
object ZeroClawRuntime {
    const val HOST = "127.0.0.1"
    const val PORT = 42617
    const val DASHBOARD_URL = "http://$HOST:$PORT/"

    private const val MAX_LOG_LINES = 2000

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** True once the gateway port accepts connections. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var daemon: Process? = null

    fun binary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libzeroclaw.so")

    fun describeBinary(context: Context): String {
        val f = binary(context)
        val abis = Build.SUPPORTED_ABIS.joinToString()
        return if (f.exists()) {
            "libzeroclaw.so present (${f.length() / 1024 / 1024} MB), executable=${f.canExecute()}, device ABIs: $abis"
        } else {
            "libzeroclaw.so NOT found in ${f.parent} (device ABIs: $abis). ZeroClaw only ships arm64-v8a."
        }
    }

    @Synchronized
    fun startDaemon(context: Context) {
        if (daemon?.isAlive == true) return
        val app = context.applicationContext
        prepare(app)
        log("[app] starting: zeroclaw daemon --host $HOST -p $PORT")
        val process = launch(app, listOf("daemon", "--host", HOST, "-p", PORT.toString())) ?: return
        daemon = process
        _running.value = true
        _ready.value = false

        thread(name = "zeroclaw-port-probe", isDaemon = true) {
            while (daemon === process && process.isAlive) {
                if (portOpen()) {
                    _ready.value = true
                    log("[app] gateway is up at $DASHBOARD_URL")
                    return@thread
                }
                Thread.sleep(500)
            }
        }
        thread(name = "zeroclaw-wait", isDaemon = true) {
            val code = process.waitFor()
            synchronized(this) { if (daemon === process) daemon = null }
            _running.value = false
            _ready.value = false
            log("[app] zeroclaw daemon exited with code $code")
        }
    }

    @Synchronized
    fun stopDaemon() {
        val p = daemon ?: return
        log("[app] stopping zeroclaw daemon")
        p.destroy()
        thread(isDaemon = true) {
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    /** Runs one-off CLI args (for troubleshooting, e.g. `--help` or `config list`). */
    fun runOnce(context: Context, args: List<String>) {
        val app = context.applicationContext
        prepare(app)
        log("[app] running: zeroclaw ${args.joinToString(" ")}")
        val process = launch(app, args) ?: return
        thread(name = "zeroclaw-oneshot", isDaemon = true) {
            val code = process.waitFor()
            log("[app] exited with code $code")
        }
    }

    fun clearLogs() = _logs.update { emptyList() }

    private fun prepare(app: Context) {
        WebAssets.ensureExtracted(app)
        for (dir in listOf(homeDir(app), dataDir(app), app.cacheDir)) dir.mkdirs()
    }

    private fun homeDir(app: Context) = File(app.filesDir, "home")
    private fun dataDir(app: Context) = File(app.filesDir, "zeroclaw")

    private fun launch(app: Context, args: List<String>): Process? {
        val bin = binary(app)
        if (!bin.exists()) {
            log("[app] ERROR: ${describeBinary(app)}")
            return null
        }
        val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
            .directory(homeDir(app))
            .redirectErrorStream(true)
        pb.environment().apply {
            put("HOME", homeDir(app).absolutePath)
            put("TMPDIR", app.cacheDir.absolutePath)
            put("ZEROCLAW_CONFIG_DIR", dataDir(app).absolutePath)
            put("ZEROCLAW_gateway__web_dist_dir", WebAssets.distDir(app).absolutePath)
        }
        return try {
            val process = pb.start()
            thread(name = "zeroclaw-log", isDaemon = true) {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach(::log) }
            }
            process
        } catch (e: Exception) {
            log("[app] ERROR: failed to start process: $e")
            null
        }
    }

    private fun portOpen(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(HOST, PORT), 300) }
        true
    } catch (_: Exception) {
        false
    }

    private fun log(line: String) {
        _logs.update { (it + line).takeLast(MAX_LOG_LINES) }
    }
}
