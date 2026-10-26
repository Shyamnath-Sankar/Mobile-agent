package io.github.shyamnathsankar.zeroclaw.tools

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Makes PDF, Word, Excel and PowerPoint files with JavaScript libraries that ship in the APK
 * (assets/docgen, staged by scripts/fetch-docgen.sh). They run in a hidden WebView that cannot reach
 * the network and has no JavaScript interface: the app only talks to it through evaluateJavascript.
 * This is the "JavaScript in a WebView" case Play allows; nothing is downloaded or executed natively.
 */
object DocEngine {
    private const val ENGINE_URL = "file:///android_asset/docgen/engine.html"
    private const val ASSET_PREFIX = "file:///android_asset/docgen/"
    private const val CHUNK = 256 * 1024
    private const val IDLE_MS = 120_000L

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var webView: WebView? = null
    private var loaded: CompletableDeferred<Unit>? = null
    private var idleJob: Job? = null

    /** Renders [spec] as [kind] ("pdf", "docx", "xlsx" or "pptx") and returns the file's bytes. */
    suspend fun render(context: Context, kind: String, spec: JSONObject): ByteArray = mutex.withLock {
        withTimeout(120_000) {
            val wv = ensure(context.applicationContext)
            val id = "job" + System.nanoTime()
            eval(wv, "ZC.start(${JSONObject.quote(id)},${JSONObject.quote(kind)},$spec)")
            var status: JSONObject
            do {
                status = JSONObject(eval(wv, "ZC.status(${JSONObject.quote(id)})") ?: "{}")
                val running = status.optString("state") == "running"
                if (running) delay(150)
            } while (running)
            try {
                when (status.optString("state")) {
                    "done" -> Unit
                    "error" -> error(status.optString("error").ifEmpty { "the document could not be made" })
                    else -> error("the document engine lost the job")
                }
                val length = status.getInt("length")
                val b64 = StringBuilder(length)
                var offset = 0
                while (offset < length) {
                    b64.append(eval(wv, "ZC.chunk(${JSONObject.quote(id)},$offset,$CHUNK)").orEmpty())
                    offset += CHUNK
                }
                Base64.decode(b64.toString(), Base64.DEFAULT)
            } finally {
                eval(wv, "ZC.drop(${JSONObject.quote(id)})")
                scheduleIdleRelease()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensure(app: Context): WebView {
        val (wv, ready) = withContext(Dispatchers.Main) {
            idleJob?.cancel()
            val existing = webView
            if (existing != null) {
                existing to loaded!!
            } else {
                val done = CompletableDeferred<Unit>()
                val created = WebView(app).apply {
                    settings.javaScriptEnabled = true
                    settings.blockNetworkLoads = true
                    settings.allowFileAccess = false // the APK's own assets stay readable
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url.toString()
                            // Only the bundled engine and its libraries; data: URLs never reach this callback.
                            return if (url.startsWith(ASSET_PREFIX)) null else WebResourceResponse("text/plain", "utf-8", null)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            done.complete(Unit)
                        }
                    }
                    loadUrl(ENGINE_URL)
                }
                webView = created
                loaded = done
                created to done
            }
        }
        withTimeout(20_000) { ready.await() }
        return wv
    }

    /** Frees the WebView (tens of MB) when no document has been made for a while. */
    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_MS)
            mutex.withLock {
                webView?.destroy()
                webView = null
                loaded = null
            }
        }
    }

    private suspend fun eval(wv: WebView, js: String): String? {
        val result = CompletableDeferred<String?>()
        withContext(Dispatchers.Main) { wv.evaluateJavascript(js) { raw -> result.complete(raw) } }
        val raw = withTimeout(30_000) { result.await() } ?: return null
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }
}
