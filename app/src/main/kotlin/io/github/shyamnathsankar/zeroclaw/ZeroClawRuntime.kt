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

    /** Wall-clock time the current daemon started, or null when stopped. */
    private val _startedAt = MutableStateFlow<Long?>(null)
    val startedAt: StateFlow<Long?> = _startedAt.asStateFlow()

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
        killOrphans()
        log("[app] starting: zeroclaw daemon --host $HOST -p $PORT")
        val process = launch(app, listOf("daemon", "--host", HOST, "-p", PORT.toString())) ?: return
        daemon = process
        _startedAt.value = System.currentTimeMillis()
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
            _startedAt.value = null
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

    /** Waits until the gateway accepts connections; false on timeout or if the daemon died. */
    suspend fun awaitReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (_ready.value) return true
            if (!_running.value) return false
            kotlinx.coroutines.delay(250)
        }
        return _ready.value
    }

    /**
     * A daemon left over from a previous app process still holds the port and the config lock,
     * so a new one would fail to start. Only processes of this app's own uid can be signalled.
     */
    private fun killOrphans() {
        val myUid = android.os.Process.myUid()
        val myPid = android.os.Process.myPid()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            if (pid == myPid) return@forEach
            val cmdline = runCatching { File(dir, "cmdline").readText() }.getOrNull() ?: return@forEach
            if (!cmdline.contains("libzeroclaw.so")) return@forEach
            val uid = runCatching {
                File(dir, "status").readLines().first { it.startsWith("Uid:") }.split(Regex("\\s+"))[1].toInt()
            }.getOrNull()
            if (uid == myUid) {
                log("[app] stopping leftover zeroclaw process $pid")
                android.os.Process.killProcess(pid)
            }
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

    fun appLog(line: String) = log(line)

    fun clearLogs() = _logs.update { emptyList() }

    private fun prepare(app: Context) {
        WebAssets.ensureExtracted(app)
        for (dir in listOf(homeDir(app), dataDir(app), app.cacheDir)) dir.mkdirs()
        ensureSecretKey(File(dataDir(app), ".secret_key"))
    }

    /**
     * ZeroClaw encrypts saved secrets (API keys) with a key in `<config dir>/.secret_key`. It creates that
     * file by writing a temp file and hard-linking it into place, but Android does not let apps create
     * hard links, so every secret save failed ("Failed to encrypt ..."). Provision the key ourselves in
     * ZeroClaw's format: 32 random bytes as 64 lowercase hex chars, mode 0600, created exclusively.
     */
    private fun ensureSecretKey(file: File) {
        if (file.exists()) return
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val hex = key.joinToString("") { "%02x".format(it) }.toByteArray()
        val fd = try {
            android.system.Os.open(
                file.absolutePath,
                android.system.OsConstants.O_CREAT or android.system.OsConstants.O_EXCL or android.system.OsConstants.O_WRONLY,
                384, // 0600
            )
        } catch (e: android.system.ErrnoException) {
            if (e.errno == android.system.OsConstants.EEXIST) return // created concurrently
            log("[app] could not create secret key: ${e.message}")
            return
        }
        try {
            android.system.Os.write(fd, hex, 0, hex.size)
            android.system.Os.fsync(fd)
        } finally {
            android.system.Os.close(fd)
        }
        log("[app] created ZeroClaw secret key")
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
            // Single-user device, loopback-only gateway: skip the pairing step so the dashboard opens directly.
            put("ZEROCLAW_gateway__require_pairing", "false")
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
