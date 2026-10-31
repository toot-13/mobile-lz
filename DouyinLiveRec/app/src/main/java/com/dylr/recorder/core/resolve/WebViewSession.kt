package com.dylr.recorder.core.resolve

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

/**
 * 无界面 WebView 会话。所有 WebView 操作都在主线程（Handler(Looper.getMainLooper())）。
 *
 * 三条互相独立的抓流通道，命中任一即成功，判定与优先级完全统一：
 * 1) shouldInterceptRequest（WebView 自己发起的子资源请求，含 iframe / MSE 前的真实请求，与 UA 无关）；
 * 2) 注入 JS 包裹 window.fetch / XMLHttpRequest.prototype.open；
 * 3) 每 800ms poll 一次 window.__dylrUrls（JS 用 performance 资源列表补捞）。
 * 另有页面线索通道：同一次 poll 里收割页面内出现的直播链接 / web_rid / room_id，
 * 交给调用方的 [cluePlanner] 决定是否二次跳转到真正的直播页（整个会话最多跳 MAX_PAGE_REDIRECTS 次）。
 */
class WebViewSession(
    context: Context,
    private val userAgent: String = USER_AGENT
) {

    private val appContext: Context = context.applicationContext

    /** 页面内挖到的直播线索：只放直播页 URL 与 web_rid / room_id，绝不收 modal_id 这类猜测值。 */
    class PageClues(
        val pageUrl: String?,
        val liveUrls: List<String>,
        val webRids: List<String>,
        val roomIds: List<String>
    ) {
        fun isEmpty(): Boolean = liveUrls.isEmpty() && webRids.isEmpty() && roomIds.isEmpty()

        fun summary(): String =
            "直播链接 ${liveUrls.size} 个 / web_rid ${webRids.size} 个 / room_id ${roomIds.size} 个"
    }

    /**
     * 抓流策略：页面线索决策器、候选收集窗口时长、窗口结束时的“最优候选”选择器。
     * 收集窗口只在首次命中后才开始计时，用来把更好的候选（如 flv 原画）也收进来。
     */
    class CaptureOptions(
        val onClues: ((PageClues) -> String?)? = null,
        val bestCandidate: (() -> String?)? = null,
        val collectWindowMs: Long = 1800L
    )

    companion object {
        const val USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** 移动端 UA：抖音短链在桌面 UA 下常跳 App 引导页，移动 UA 更容易落到真正的直播 H5。 */
        const val MOBILE_USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        const val DOUYIN_LIVE_HOME: String = "https://live.douyin.com/"

        private const val POLL_INTERVAL_MS = 800L
        private const val MAX_PAGE_REDIRECTS = 1

        /** 收集窗口与总超时之间留的安全余量：保证窗口结算一定早于 withTimeoutOrNull 到期。 */
        private const val TIMEOUT_SAFETY_MS = 500L

        /**
         * 注入脚本：保存 __dylrUrls 列表，包裹 fetch 与 XMLHttpRequest.open，
         * 每 1000ms 用 performance.getEntriesByType('resource') 补捞一次；__dylrHooked 保证幂等。
         */
        private const val INJECT_JS: String = """(function () {
  if (window.__dylrHooked) { return; }
  window.__dylrHooked = true;
  window.__dylrUrls = window.__dylrUrls || [];
  function keep(u) {
    try {
      if (!u || typeof u !== 'string') { return; }
      var low = u.toLowerCase();
      if (low.indexOf('.flv') < 0 && low.indexOf('.m3u8') < 0) { return; }
      if (window.__dylrUrls.indexOf(u) < 0) { window.__dylrUrls.push(u); }
    } catch (e) {}
  }
  try {
    var rawFetch = window.fetch;
    if (rawFetch) {
      window.fetch = function (input, init) {
        try { keep(typeof input === 'string' ? input : (input && input.url)); } catch (e) {}
        return rawFetch.apply(this, arguments);
      };
    }
  } catch (e) {}
  try {
    var rawOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (method, url) {
      try { keep(url); } catch (e) {}
      return rawOpen.apply(this, arguments);
    };
  } catch (e) {}
  try {
    setInterval(function () {
      try {
        if (!window.performance || !performance.getEntriesByType) { return; }
        var list = performance.getEntriesByType('resource');
        for (var i = 0; i < list.length; i++) { keep(list[i].name); }
      } catch (e) {}
    }, 1000);
  } catch (e) {}
})();"""

        /** 收割脚本：把钩子记录的 URL 用 | 拼成字符串返回，交给轮询逐条投喂。 */
        private const val HARVEST_JS: String =
            "(function () { try { var a = window.__dylrUrls || []; return a.join('|'); } catch (e) { return ''; } })()"

        /**
         * 页面线索脚本：扫 location.href、a[href]、页面 HTML，收集
         * live.douyin.com/<数字> 链接、web_rid=<数字>、room_id=<数字>。
         * 返回 3 元素数组（JSON），HTML 全量扫描按 1.5s 节流以控制开销。
         */
        private const val CLUE_JS: String = """(function () {
  try {
    var urls = [], rids = [], roomIds = [], seen = {};
    function push(list, key, v) {
      if (!v) { return; }
      var k = key + ':' + v;
      if (seen[k]) { return; }
      seen[k] = 1;
      if (list.length < 8) { list.push(v); }
    }
    function digits(s) { return String(s).replace(/[^0-9]/g, ''); }
    function scanText(t) {
      if (!t) { return; }
      var s = String(t);
      var m = s.match(/live[.]douyin[.]com[/][0-9]{3,}/ig) || [];
      for (var i = 0; i < m.length; i++) {
        var d1 = digits(m[i]);
        if (d1) { push(urls, 'u', 'https://live.douyin.com/' + d1); }
      }
      var w = s.match(/web_rid=[0-9]{3,}/ig) || [];
      for (var j = 0; j < w.length; j++) {
        var d2 = digits(w[j]);
        if (d2) { push(rids, 'w', d2); }
      }
      var r = s.match(/room_id=[0-9]{3,}/ig) || [];
      for (var k2 = 0; k2 < r.length; k2++) {
        var d3 = digits(r[k2]);
        if (d3) { push(roomIds, 'r', d3); }
      }
    }
    scanText(location.href);
    var anchors = document.querySelectorAll ? document.querySelectorAll('a[href]') : [];
    for (var a = 0; a < anchors.length; a++) {
      var href = anchors[a].href;
      if (href && String(href).indexOf('live.douyin.com/') >= 0) { scanText(href); }
    }
    var now = new Date().getTime();
    if (!window.__dylrHeavyAt || now - window.__dylrHeavyAt > 1500) {
      window.__dylrHeavyAt = now;
      var html = document.documentElement ? document.documentElement.innerHTML : '';
      if (html && html.length < 400000) { scanText(html); }
    }
    return [urls.join('|'), rids.join('|'), roomIds.join('|')];
  } catch (e) { return ['', '', '']; }
})();"""

        /** 全局唯一的 CookieManager：probe 与 WebView 共用同一份登录态。 */
        fun cookieStore(): CookieManager = CookieManager.getInstance()

        fun cookieFor(url: String): String = try {
            cookieStore().getCookie(url) ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 轮询专用 Handler：销毁时对它 removeCallbacksAndMessages(null)，不会误伤别的会话。 */
    private val pollHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var hitSink: HitSink? = null

    @Volatile
    private var capture: CompletableDeferred<String?>? = null

    @Volatile
    private var destroyed: Boolean = false

    /** 最终落地页 URL（WebViewClient 回调在主线程写，抓流结束后由调用方读一次）。 */
    @Volatile
    private var pageUrl: String? = null

    /** 三条通道累计收到的不同候选数：只用于失败诊断，不参与判定。 */
    @Volatile
    private var candidateTotal: Int = 0

    /** 页面线索：最近一次收割结果（主线程写，失败诊断读一次）。 */
    @Volatile
    private var clues: PageClues? = null

    /** 线索决策器：拿到页面线索后问调用方“是否要跳转到某个直播页”，返回 null 表示不跳。 */
    @Volatile
    private var cluePlanner: ((PageClues) -> String?)? = null

    /** 本会话剩余可用的二次跳转次数（整会话最多 MAX_PAGE_REDIRECTS 次，杜绝无限跳）。 */
    @Volatile
    private var redirectLeft: Int = MAX_PAGE_REDIRECTS

    private var openedUrl: String? = null

    /** 收集窗口：首次命中后开始计时，窗口结束用 windowSelector 选最优候选。 */
    @Volatile
    private var windowSelector: (() -> String?)? = null

    @Volatile
    private var windowMs: Long = 0L

    @Volatile
    private var captureDeadline: Long = 0L

    @Volatile
    private var graceArmed: Boolean = false

    private var firstAccepted: String? = null

    /** 只在主线程访问的去重集合：避免同一线索重复触发决策/重复跳转。 */
    private val clueSeen = HashSet<String>()

    fun lastPageUrl(): String? = pageUrl

    fun candidateCount(): Int = candidateTotal

    fun lastClues(): PageClues? = clues

    private val pollTask: Runnable = object : Runnable {
        override fun run() {
            val wv = webView
            val sink = hitSink
            val done = capture
            if (wv == null || sink == null || done == null || !done.isActive) return
            try {
                wv.evaluateJavascript(HARVEST_JS) { raw ->
                    runOnMain { sink.offerAll(decodeJsList(raw)) }
                }
            } catch (_: Throwable) {
            }
            try {
                wv.evaluateJavascript(CLUE_JS) { raw ->
                    runOnMain { handleClues(parseClues(raw)) }
                }
            } catch (_: Throwable) {
            }
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    init {
        try {
            cookieStore().setAcceptCookie(true)
        } catch (_: Throwable) {
            // CookieManager 在极端情况下不可用，降级为无 cookie 抓流
        }
    }

    /**
     * 在主线程创建 WebView 并打开 [url]，任一通道抓到满足 [matcher] 的流地址即返回，超时返回 null。
     * [CaptureOptions.onClues] 在主线程回调，返回非空 URL 时本会话跳转一次到该直播页；
     * 首条候选命中后进入 options.collectWindowMs 的收集窗口，窗口结束时用 options.bestCandidate 选最优。
     * WebView 初始化/加载失败转为带中文信息的 IOException。
     */
    suspend fun openAndCapture(
        url: String,
        matcher: (String) -> Boolean,
        timeoutMs: Long = 20000L,
        options: CaptureOptions = CaptureOptions()
    ): String? {
        val hit = CompletableDeferred<String?>()
        mainHandler.post {
            try {
                createWebView(url, matcher, hit, options, timeoutMs)
            } catch (io: IOException) {
                hit.completeExceptionally(io)
            } catch (t: Throwable) {
                hit.completeExceptionally(
                    IOException("解析失败：WebView 初始化异常（${t.message ?: t.javaClass.simpleName}）", t)
                )
            }
        }
        return try {
            withTimeoutOrNull(timeoutMs) { hit.await() }
        } catch (c: CancellationException) {
            throw c
        } catch (io: IOException) {
            throw io
        } catch (t: Throwable) {
            throw IOException("解析失败：${t.message ?: "WebView 加载失败"}", t)
        } finally {
            destroy()
        }
    }

    /** 释放 WebView 并停掉轮询；重复调用安全。 */
    fun destroy() {
        destroyed = true
        pollHandler.removeCallbacksAndMessages(null)
        cluePlanner = null
        windowSelector = null
        hitSink?.close()
        // 结算未完成的 deferred：让已排队的投递/回调因 !hit.isActive 直接早退；
        // 对已完成的 deferred 再 complete(null) 返回 false 且不抛，所以这里幂等。
        capture?.complete(null)
        capture = null
        hitSink = null
        val wv = webView ?: return
        webView = null
        runOnMain {
            try {
                wv.stopLoading()
                wv.webViewClient = WebViewClient()
                wv.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(
        url: String,
        matcher: (String) -> Boolean,
        hit: CompletableDeferred<String?>,
        options: CaptureOptions,
        timeoutMs: Long
    ) {
        if (destroyed) {
            hit.completeExceptionally(IOException("解析失败：WebView 会话已销毁"))
            return
        }
        val sink = HitSink(
            matcher = matcher,
            hit = hit,
            onNewCandidate = { candidateTotal++ },
            onAccepted = { url -> onCandidateAccepted(url) }
        )
        // WebView 必须在主线程创建；部分 OEM 上 application context 构造即抛，交由 newWebView 兜底
        val wv = newWebView()
        webView = wv
        hitSink = sink
        capture = hit
        openedUrl = url
        cluePlanner = options.onClues
        windowSelector = options.bestCandidate
        windowMs = options.collectWindowMs
        captureDeadline = System.currentTimeMillis() + timeoutMs
        try {
            val cookies = cookieStore()
            cookies.setAcceptCookie(true)
            cookies.setAcceptThirdPartyCookies(wv, true)
        } catch (_: Throwable) {
        }
        val settings: WebSettings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.userAgentString = userAgent
        // 允许自动播放，否则 h5 播放器不会发起真正的流请求
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = LiveUrlInterceptor(
            onHit = { streamUrl -> runOnMain { sink.offer(streamUrl) } },
            onPageReady = { page ->
                // onPageStarted/onPageFinished 均在主线程：记录含重定向的最终落地页 URL
                recordPage(page)
                try {
                    page?.evaluateJavascript(INJECT_JS, null)
                } catch (_: Throwable) {
                }
            }
        )
        wv.loadUrl(url, mapOf("User-Agent" to userAgent))
        // 通道 3：轮询收割 JS 钩子；与 shouldInterceptRequest 并行，命中任一即完成
        pollHandler.removeCallbacksAndMessages(null)
        pollHandler.postDelayed(pollTask, POLL_INTERVAL_MS)
    }

    /** 页面线索到达（主线程）：先去重，再问线索决策器，最多跳转 MAX_PAGE_REDIRECTS 次。 */
    private fun handleClues(incoming: PageClues?) {
        if (incoming == null || incoming.isEmpty()) return
        clues = incoming
        var fresh = false
        for (u in incoming.liveUrls) if (clueSeen.add("u:" + u)) fresh = true
        for (w in incoming.webRids) if (clueSeen.add("w:" + w)) fresh = true
        for (r in incoming.roomIds) if (clueSeen.add("r:" + r)) fresh = true
        if (!fresh) return
        val planner = cluePlanner ?: return
        if (redirectLeft <= 0) return
        val next = try {
            planner(incoming)
        } catch (_: Throwable) {
            null
        } ?: return
        val current = webView ?: return
        if (next == openedUrl || next == pageUrl) return
        redirectLeft -= 1
        try {
            current.loadUrl(next, mapOf("User-Agent" to userAgent))
        } catch (_: Throwable) {
        }
    }

    /** 部分 OEM 用 application context 直接 new WebView 会抛，套一层主题包装再试一次，两次都失败才抛中文异常。 */
    private fun newWebView(): WebView {
        var failure: Throwable? = null
        try {
            return WebView(appContext)
        } catch (t: Throwable) {
            failure = t
        }
        val themed = ContextThemeWrapper(appContext, android.R.style.Theme_DeviceDefault)
        return try {
            WebView(themed)
        } catch (t: Throwable) {
            throw IOException(
                "解析失败：WebView 初始化异常（${t.message ?: failure?.message ?: "无法创建 WebView"}）", t
            )
        }
    }

    /**
     * 三条通道唯一的投喂口：只在主线程调用（shouldInterceptRequest 经 runOnMain，轮询本就在主线程），
     * 因此 matcher 不需要线程安全，重复 URL 只喂一次。
     */
    private class HitSink(
        private val matcher: (String) -> Boolean,
        private val hit: CompletableDeferred<String?>,
        private val onNewCandidate: () -> Unit,
        private val onAccepted: (String) -> Unit
    ) {
        private val seen = HashSet<String>()

        /** 由 destroy() 写（调用方线程），主线程只读 → volatile 保证可见性。 */
        @Volatile
        private var closed = false

        fun close() {
            closed = true
        }

        fun offerAll(urls: List<String>) {
            for (url in urls) offer(url)
        }

        fun offer(url: String) {
            if (closed) return
            if (!hit.isActive) return
            if (!seen.add(url)) return
            try {
                onNewCandidate()
            } catch (_: Throwable) {
            }
            val accepted = try {
                matcher(url)
            } catch (_: Throwable) {
                false
            }
            // 命中后不立刻收工：交给会话的收集窗口，窗口结束时用 bestCandidate 选最优
            if (accepted) onAccepted(url)
        }
    }

    /** 解析线索脚本的 JSON 数组返回值；不是数组就当作没有线索。 */
    private fun parseClues(raw: String?): PageClues? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            val array = JSONArray(raw)
            PageClues(
                pageUrl = pageUrl,
                liveUrls = splitList(array.optString(0, "")),
                webRids = splitList(array.optString(1, "")),
                roomIds = splitList(array.optString(2, ""))
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun splitList(text: String): List<String> =
        text.split('|').map { it.trim() }.filter { it.isNotEmpty() }

    /** deferred 结束后不再自我重排，销毁/超时再 removeCallbacksAndMessages(null)。 */
    private fun decodeJsList(raw: String?): List<String> {
        val text = decodeJsString(raw)
        if (text.isEmpty()) return emptyList()
        return splitList(text)
    }

    /** evaluateJavascript 回调给的是 JSON 字面量（带外层引号、内部转义），这里还原成普通字符串。 */
    private fun decodeJsString(raw: String?): String {
        if (raw == null) return ""
        val text = raw.trim()
        if (text.isEmpty() || text == "null") return ""
        if (!text.startsWith("\"")) return text
        val builder = StringBuilder()
        var index = 1
        while (index < text.length) {
            val ch = text[index]
            if (ch == '"') break
            if (ch == '\\' && index + 1 < text.length) {
                val next = text[index + 1]
                when (next) {
                    'n' -> builder.append('\n')
                    't' -> builder.append('\t')
                    'r' -> builder.append('\r')
                    '"' -> builder.append('"')
                    '\\' -> builder.append('\\')
                    '/' -> builder.append('/')
                    'u' -> {
                        if (index + 5 < text.length) {
                            val code = text.substring(index + 2, index + 6).toIntOrNull(16)
                            if (code != null) builder.append(code.toChar())
                            index += 4
                        }
                    }
                    else -> builder.append(next)
                }
                index += 2
                continue
            }
            builder.append(ch)
            index++
        }
        return builder.toString()
    }

    /** 收集窗口结束：用调用方提供的选择器挑最优候选，没有选择器就退回第一条命中。 */
    private val graceTask: Runnable = object : Runnable {
        override fun run() {
            finishCapture()
        }
    }

    private fun finishCapture() {
        val done = capture ?: return
        if (!done.isActive) return
        val best = try {
            windowSelector?.invoke()
        } catch (_: Throwable) {
            null
        } ?: firstAccepted
        done.complete(best)
    }

    /**
     * 首条候选命中（主线程）：不立刻收工，先开一个收集窗口把更好的候选收进来。
     * 窗口时长会被剩余超时时间夹住，保证窗口一定在 withTimeoutOrNull 到期前结算。
     */
    private fun onCandidateAccepted(url: String) {
        if (firstAccepted == null) firstAccepted = url
        if (graceArmed) return
        graceArmed = true
        val budget = captureDeadline - System.currentTimeMillis() - TIMEOUT_SAFETY_MS
        val wait = minOf(windowMs, budget)
        if (wait <= 0L) {
            finishCapture()
        } else {
            pollHandler.postDelayed(graceTask, wait)
        }
    }

    private fun recordPage(page: WebView?) {
        try {
            val url = page?.url
            if (!url.isNullOrBlank()) pageUrl = url
        } catch (_: Throwable) {
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }
}