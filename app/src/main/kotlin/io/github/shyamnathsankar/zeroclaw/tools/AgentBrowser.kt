package io.github.shyamnathsankar.zeroclaw.tools

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener

/** What the Browser tab shows about the agent's page. */
data class BrowserState(val url: String = "", val title: String = "", val loading: Boolean = false)

/**
 * The agent's own browser: one WebView, driven only through evaluateJavascript (no JavaScript
 * interface is added, as Play requires for untrusted content). The Browser tab shows it live.
 */
object AgentBrowser {
    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    private var webView: WebView? = null
    // Lets the WebView use the Activity (for dropdowns and dialogs) while it is on screen,
    // and fall back to the application context when the agent drives it in the background.
    private var contextWrapper: MutableContextWrapper? = null
    private var pageLoad: CompletableDeferred<Unit>? = null

    /** The shared WebView; create on the main thread. Detached from any previous parent. */
    @SuppressLint("SetJavaScriptEnabled")
    fun view(context: Context): WebView {
        val wrapper = contextWrapper ?: MutableContextWrapper(context.applicationContext).also { contextWrapper = it }
        if (context is Activity) wrapper.baseContext = context
        webView?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        return WebView(wrapper).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.textZoom = 100
            // Keep sign-ins: cookies persist on disk, and third-party cookies are allowed because many
            // login flows (SSO, embedded auth) depend on them.
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Stay on the open web: no intent://, market:// or local-network pages.
                    return UrlGuard.check(request.url.toString()) != null
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    _state.value = BrowserState(url, view.title.orEmpty(), loading = true)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    _state.value = BrowserState(url, view.title.orEmpty(), loading = false)
                    pageLoad?.complete(Unit)
                    CookieManager.getInstance().flush()
                }
            }
            loadUrl("about:blank")
            webView = this
        }
    }

    /** Called when the Browser tab goes away: drop the Activity reference. */
    fun detach() {
        webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        contextWrapper?.let { it.baseContext = it.applicationContext }
    }

    suspend fun navigate(context: Context, url: String): ToolResult {
        val target = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        withContext(Dispatchers.IO) { UrlGuard.checkResolved(target) }?.let { return ToolResult(it, isError = true) }
        val loaded = withContext(Dispatchers.Main) {
            val wv = view(context)
            val done = CompletableDeferred<Unit>().also { pageLoad = it }
            wv.loadUrl(target)
            done
        }
        val ok = withTimeoutOrNull(30_000) { loaded.await() } != null
        val s = state.value
        return ToolResult(
            (if (ok) "Loaded" else "Still loading after 30s") + ": ${s.title}\n${s.url}\n" +
                "Call browser_snapshot to read the page and get numbered elements.",
        )
    }

    suspend fun snapshot(context: Context, maxChars: Int): ToolResult {
        val json = eval(context, SNAPSHOT_JS) ?: return ToolResult("No page loaded", isError = true)
        val o = JSONObject(json)
        val sb = StringBuilder()
        sb.append("Title: ").append(o.optString("title")).append('\n')
        sb.append("URL: ").append(o.optString("url")).append("\n\n")
        sb.append(o.optString("text").take(maxChars)).append("\n\nInteractive elements:\n")
        val els = o.optJSONArray("elements")
        if (els != null) {
            for (i in 0 until els.length()) {
                val e = els.getJSONObject(i)
                sb.append('[').append(e.optInt("n")).append("] ").append(e.optString("tag"))
                e.optString("type").takeIf { it.isNotEmpty() }?.let { sb.append('(').append(it).append(')') }
                sb.append(' ').append(e.optString("label").take(80))
                e.optString("href").takeIf { it.isNotEmpty() }?.let { sb.append(" -> ").append(it.take(120)) }
                if (e.optBoolean("sensitive")) sb.append("  [needs approval]")
                sb.append('\n')
            }
        }
        return ToolResult(sb.toString())
    }

    suspend fun click(context: Context, n: Int): ToolResult {
        val info = describe(context, n) ?: return ToolResult("No element [$n]. Call browser_snapshot first.", isError = true)
        if (info.optBoolean("sensitive") &&
            !Approvals.request(context, "Click \"${info.optString("label").take(60)}\"", "On ${state.value.url}\nThis looks like a submit, sign-in or payment action.")
        ) {
            return ToolResult("The user denied this click.", isError = true)
        }
        eval(context, "(function(){var e=document.querySelector('[data-zc-n=\"$n\"]');if(!e)return 'missing';e.scrollIntoView({block:'center'});e.click();return 'ok';})()")
        delay(1_500)
        return ToolResult("Clicked [$n]. Now at: ${state.value.title}\n${state.value.url}")
    }

    suspend fun type(context: Context, n: Int, text: String, submit: Boolean): ToolResult {
        val info = describe(context, n) ?: return ToolResult("No element [$n]. Call browser_snapshot first.", isError = true)
        val sensitiveField = info.optBoolean("secret")
        if ((sensitiveField || submit) &&
            !Approvals.request(
                context,
                if (submit) "Type and submit" else "Fill a sensitive field",
                "Field: ${info.optString("label").take(60)}\nOn ${state.value.url}" +
                    if (sensitiveField) "\n(The agent will not show what it types into this field.)" else "\nText: ${text.take(200)}",
            )
        ) {
            return ToolResult("The user denied this.", isError = true)
        }
        val quoted = JSONObject.quote(text)
        eval(
            context,
            """(function(){var e=document.querySelector('[data-zc-n="$n"]');if(!e)return 'missing';
            e.focus();var p=Object.getPrototypeOf(e);var d=Object.getOwnPropertyDescriptor(p,'value');
            if(d&&d.set){d.set.call(e,$quoted);}else if(e.isContentEditable){e.textContent=$quoted;}else{e.value=$quoted;}
            e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
            ${if (submit) "if(e.form){e.form.requestSubmit?e.form.requestSubmit():e.form.submit();}else{e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));}" else ""}
            return 'ok';})()""",
        )
        if (submit) delay(1_500)
        return ToolResult("Typed into [$n]" + if (submit) " and submitted. Now at: ${state.value.url}" else ".")
    }

    suspend fun scroll(context: Context, direction: String): ToolResult {
        val js = when (direction) {
            "up" -> "window.scrollBy(0,-window.innerHeight*0.8)"
            "top" -> "window.scrollTo(0,0)"
            "bottom" -> "window.scrollTo(0,document.body.scrollHeight)"
            else -> "window.scrollBy(0,window.innerHeight*0.8)"
        }
        eval(context, "$js;'ok'")
        return ToolResult("Scrolled $direction.")
    }

    suspend fun back(context: Context): ToolResult = withContext(Dispatchers.Main) {
        val wv = view(context)
        if (wv.canGoBack()) {
            wv.goBack()
            ToolResult("Went back.")
        } else {
            ToolResult("No previous page.", isError = true)
        }
    }

    /** Waits until [text] is visible, [selector] matches, or (with neither) the page has finished loading. */
    suspend fun waitFor(context: Context, text: String?, selector: String?, timeoutSeconds: Int): ToolResult {
        val check = when {
            !text.isNullOrBlank() -> "document.body&&document.body.innerText.indexOf(${JSONObject.quote(text)})>=0"
            !selector.isNullOrBlank() -> "!!document.querySelector(${JSONObject.quote(selector)})"
            else -> "document.readyState==='complete'"
        }
        val deadline = System.currentTimeMillis() + timeoutSeconds.coerceIn(1, 60) * 1000L
        while (System.currentTimeMillis() < deadline) {
            val ok = runCatching { eval(context, "(function(){try{return ($check)?'yes':'no'}catch(e){return 'no'}})()") }.getOrNull()
            if (ok == "yes") return ToolResult("Ready: ${state.value.title}\n${state.value.url}")
            delay(500)
        }
        return ToolResult("Timed out after ${timeoutSeconds}s waiting" + (text?.let { " for \"$it\"" } ?: selector?.let { " for $it" } ?: ""), isError = true)
    }

    /** Captures the visible page as a JPEG (max 1024 px wide) so vision models can look at it. */
    suspend fun screenshot(context: Context): ToolResult = withContext(Dispatchers.Main) {
        val wv = view(context)
        if (wv.width == 0 || wv.height == 0) {
            // Not on screen: lay it out at a phone-sized viewport so it can still be drawn.
            val dm = context.resources.displayMetrics
            wv.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(dm.widthPixels, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(dm.heightPixels, android.view.View.MeasureSpec.EXACTLY),
            )
            wv.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
        val bitmap = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
        wv.draw(android.graphics.Canvas(bitmap))
        val scale = minOf(1f, 1024f / bitmap.width)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        ToolResult("Screenshot of ${state.value.url} (${scaled.width}x${scaled.height}).", imageJpegBase64 = b64)
    }

    /**
     * Clears cookies, cache, history and site storage of the agent browser. The dashboard's own storage
     * (loopback origin) is kept so the app stays signed in to the agent.
     */
    suspend fun clearData(context: Context) = withContext(Dispatchers.Main) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        android.webkit.WebStorage.getInstance().getOrigins { origins ->
            origins?.values?.forEach { o ->
                val origin = (o as? android.webkit.WebStorage.Origin)?.origin ?: return@forEach
                if (!origin.contains("127.0.0.1") && !origin.contains("localhost")) {
                    android.webkit.WebStorage.getInstance().deleteOrigin(origin)
                }
            }
        }
        view(context).apply {
            clearCache(true)
            clearHistory()
            loadUrl("about:blank")
        }
    }

    private suspend fun describe(context: Context, n: Int): JSONObject? {
        val json = eval(context, "(function(){var e=document.querySelector('[data-zc-n=\"$n\"]');if(!e)return null;return JSON.stringify({label:e.getAttribute('data-zc-label')||'',sensitive:e.getAttribute('data-zc-sensitive')==='1',secret:e.getAttribute('data-zc-secret')==='1'});})()")
        return json?.let { JSONObject(it) }
    }

    /** Runs JS and returns its string result (JSON-decoded), or null. */
    private suspend fun eval(context: Context, js: String): String? {
        val result = CompletableDeferred<String?>()
        withContext(Dispatchers.Main) {
            view(context).evaluateJavascript(js) { raw -> result.complete(raw) }
        }
        val raw = withTimeoutOrNull(15_000) { result.await() } ?: return null
        val value = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        return (value as? String)
    }

    /**
     * Collects visible text and numbers interactive elements (data-zc-n). Marks elements that submit
     * forms, sign in, pay or delete as sensitive, and password/card/OTP fields as secret.
     */
    private val SNAPSHOT_JS = """
        (function(){
          var risky=/(pay|buy|purchase|checkout|order|sign ?in|log ?in|login|submit|confirm|subscribe|delete|remove|transfer|send|place order|donate|book now)/i;
          var secretRe=/(password|passcode|card|cvv|cvc|iban|otp|one.time|pin|security code)/i;
          var sel='a[href],button,input:not([type=hidden]),select,textarea,[role=button],[role=link],[contenteditable=true],[onclick]';
          var nodes=document.querySelectorAll(sel),out=[],n=0;
          for(var i=0;i<nodes.length&&out.length<150;i++){
            var e=nodes[i];var r=e.getBoundingClientRect();
            if(r.width===0&&r.height===0)continue;
            var cs=getComputedStyle(e);if(cs.visibility==='hidden'||cs.display==='none')continue;
            n++;e.setAttribute('data-zc-n',n);
            var type=(e.getAttribute('type')||'').toLowerCase();
            var label=(e.getAttribute('aria-label')||e.innerText||e.value||e.getAttribute('placeholder')||e.getAttribute('name')||e.getAttribute('title')||e.getAttribute('alt')||'').replace(/\s+/g,' ').trim();
            var form=e.form||e.closest('form');
            var hasPassword=form&&form.querySelector('input[type=password]');
            var isSubmit=type==='submit'||(e.tagName==='BUTTON'&&form&&(!type||type==='submit'));
            var href=e.getAttribute('href')||'';
            var sensitive=(isSubmit&&(hasPassword||risky.test(label)))||(e.tagName!=='INPUT'&&risky.test(label))||/(checkout|payment|signin|login|oauth)/i.test(href);
            var ac=(e.getAttribute('autocomplete')||'');
            var secret=type==='password'||/cc-/.test(ac)||secretRe.test((e.getAttribute('name')||'')+' '+label);
            e.setAttribute('data-zc-label',label.slice(0,120));
            e.setAttribute('data-zc-sensitive',sensitive?'1':'0');
            e.setAttribute('data-zc-secret',secret?'1':'0');
            out.push({n:n,tag:e.tagName.toLowerCase(),type:type,label:secret&&e.value?label.replace(e.value,'***'):label.slice(0,120),href:href.slice(0,200),sensitive:!!sensitive});
          }
          var text=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n');
          return JSON.stringify({title:document.title,url:location.href,text:text.slice(0,20000),elements:out});
        })()
    """.trimIndent()
}
