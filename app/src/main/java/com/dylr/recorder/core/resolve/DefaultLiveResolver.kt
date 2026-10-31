package com.dylr.recorder.core.resolve

import android.content.Context
import com.dylr.recorder.core.LiveResolver
import com.dylr.recorder.core.LiveSource
import com.dylr.recorder.core.RoomStatus
import com.dylr.recorder.core.StreamType
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 主动 + 被动双通道解析器。
 * 主动：拿到 web_rid 后先向直播间接口要 [FULL_HD1 > HD1 > SD1 > SD2] 的最高清晰度档位（原画/蓝光优先）。
 * 被动：接口拿不到地址（字段缺失 / 空 / 异常 / 风控）才回落到 WebView 三通道抓流，且抓流命中后
 * 仍会给 1.8s 收集窗口，用 [LiveUrlInterceptor.score] 在同一容器内按清晰度挑更好的候选。
 * 房间号只用于文件名与历史记录，提不出来绝不阻断录制。
 */
class DefaultLiveResolver(context: Context) : LiveResolver {

    private val appContext: Context = context.applicationContext

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override suspend fun resolve(input: String): LiveSource {
        val text = input.trim()
        if (text.isEmpty()) throw IOException("解析失败：输入内容为空")
        val target = resolveTarget(text)
        // 主动通道：已知 web_rid 就先要最高清晰度，成功即返回（roomId 就用该 web_rid）
        target.roomIdHint?.let { rid ->
            if (isNumericRoomId(rid)) apiSource(rid)?.let { return it }
        }
        val capture = captureStream(target)
        val captured = capture.url
        if (captured.isNullOrBlank()) {
            // 被动路径没抓到流：若页面线索给了 web_rid，再用主动通道试一次
            val clueRid = capture.clues?.webRids?.firstOrNull()
            if (clueRid != null && clueRid != target.roomIdHint && isNumericRoomId(clueRid)) {
                apiSource(clueRid)?.let { return it }
            }
            throw IOException(timeoutMessage(target, capture))
        }
        val playable = bestPlayableUrl(captured)
        return LiveSource(
            roomId = fallbackRoomId(target, capture),
            url = playable,
            type = streamTypeOf(playable)
        )
    }

    override suspend fun probe(roomId: String): RoomStatus = withContext(Dispatchers.IO) {
        val rid = roomId.trim()
        // 只有纯数字 web_rid 才能查接口；退化值（v_xxx / room_xxx）一律不请求、也不编造在播信息
        if (!isNumericRoomId(rid)) return@withContext RoomStatus(roomId = roomId, isLive = false)
        val body = try {
            requestEnter(rid)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        if (body.isNullOrBlank()) return@withContext RoomStatus(roomId = roomId, isLive = false)
        parseRoomStatus(roomId, body)
    }

    /**
     * probe 与「主动要最高清晰度」共用的唯一 enter 请求实现（含 cookie 预热），不重复 coding。
     * 任何网络/风控异常都返回 null，由调用方决定降级。
     */
    private suspend fun requestEnter(webRid: String): String? = withContext(Dispatchers.IO) {
        try {
            ensureCookieJar()
            val cookie = WebViewSession.cookieFor(LIVE_HOME)
            val builder = Request.Builder()
                .url(ENTER_API + ENTER_QUERY + webRid)
                .header("User-Agent", WebViewSession.MOBILE_USER_AGENT)
                .header("Referer", LIVE_HOME)
                .header("Accept", "application/json, text/plain, */*")
                .get()
            if (cookie.isNotBlank()) builder.header("Cookie", cookie)
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) null else body
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 主动通道：从 data.data[0].stream_url 取最高清晰度地址。
     * FLV 优先（与被动路径的容器优先级一致），其次 HLS；都取不到就返回 null，让调用方回落 WebView 抓流。
     */
    private suspend fun apiSource(webRid: String): LiveSource? {
        val body = requestEnter(webRid) ?: return null
        val streamUrl = try {
            JSONObject(body)
                .optJSONObject("data")
                ?.optJSONArray("data")
                ?.optJSONObject(0)
                ?.optJSONObject("stream_url")
        } catch (_: Throwable) {
            null
        } ?: return null
        pickFlv(streamUrl)?.let { url ->
            return LiveSource(roomId = webRid, url = url, type = StreamType.FLV)
        }
        pickHls(streamUrl)?.let { url ->
            return LiveSource(roomId = webRid, url = bestPlayableUrl(url), type = StreamType.HLS)
        }
        return null
    }

    /** flv_pull_url 是对象，键名大小写不统一：FULL_HD1 > HD1 > SD1 > SD2，取第一个非空。 */
    private fun pickFlv(streamUrl: JSONObject): String? =
        pickByKeyPriority(streamUrl.optJSONObject("flv_pull_url"), FLV_KEY_PRIORITY)

    /** hls_pull_url_map 通常是对象；hls_pull_url 可能是对象也可能是字符串，两种都支持。 */
    private fun pickHls(streamUrl: JSONObject): String? {
        pickByKeyPriority(streamUrl.optJSONObject("hls_pull_url_map"), HLS_KEY_PRIORITY)?.let { return it }
        return when (val raw = streamUrl.opt("hls_pull_url")) {
            is String -> raw.trim().takeIf { it.startsWith("http", true) }
            is JSONObject -> pickByKeyPriority(raw, HLS_KEY_PRIORITY)
            else -> null
        }
    }

    /** 按键名优先级做大小写无关匹配；优先级键全缺时，退而取对象里第一个 http 值。 */
    private fun pickByKeyPriority(holder: JSONObject?, priority: List<String>): String? {
        if (holder == null) return null
        val names = holder.keys().asSequence().toList()
        for (key in priority) {
            val actual = names.firstOrNull { it.equals(key, ignoreCase = true) } ?: continue
            val value = holder.optString(actual, "").trim()
            if (value.startsWith("http", true)) return value
        }
        for (name in names) {
            val value = holder.optString(name, "").trim()
            if (value.startsWith("http", true)) return value
        }
        return null
    }

    /** HLS 若是 master playlist：在带 avc1 的变体里挑 BANDWIDTH 最高的；只有 hvc1 时退回第一条。 */
    private suspend fun bestPlayableUrl(url: String): String =
        if (streamTypeOf(url) == StreamType.HLS) preferBestHlsVariant(url) else url

    private suspend fun preferBestHlsVariant(url: String): String = withContext(Dispatchers.IO) {
        try {
            val text = fetchText(url) ?: return@withContext url
            if (!text.contains(HLS_STREAM_INF)) return@withContext url
            val variants = parseVariants(url, text)
            if (variants.isEmpty()) return@withContext url
            val avc = variants.filter { it.codecs.contains("avc1", ignoreCase = true) }
            val pool = if (avc.isEmpty()) variants else avc
            pool.maxByOrNull { it.bandwidth }?.url ?: url
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            url
        }
    }

    private class Variant(val bandwidth: Long, val codecs: String, val url: String)

    /** 解析 master playlist：每个 #EXT-X-STREAM-INF 后面第一条非 # 行就是变体地址。 */
    private fun parseVariants(baseUrl: String, text: String): List<Variant> {
        val variants = mutableListOf<Variant>()
        var bandwidth = -1L
        var codecs = ""
        var waiting = false
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith(HLS_STREAM_INF)) {
                bandwidth = BANDWIDTH_ATTR.find(trimmed)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1L
                codecs = CODECS_ATTR.find(trimmed)?.groupValues?.getOrNull(1) ?: ""
                waiting = true
                continue
            }
            if (waiting && !trimmed.startsWith("#")) {
                variants.add(Variant(bandwidth, codecs, resolveRelative(baseUrl, trimmed)))
                waiting = false
            }
        }
        return variants
    }

    private fun resolveRelative(baseUrl: String, ref: String): String {
        if (ref.startsWith("http", ignoreCase = true)) return ref
        return try {
            java.net.URI(baseUrl).resolve(ref).toString()
        } catch (_: Throwable) {
            ref
        }
    }

    /** 只取有上限的文本，避免把大响应整段读进内存。 */
    private suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", WebViewSession.MOBILE_USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                body.string().take(MAX_PLAYLIST_CHARS)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            null
        }
    }

    /** 一次解析的打开目标。 */
    private class OpenTarget(
        val openUrl: String,
        val landedUrl: String?,
        val roomIdHint: String?,
        val degradedCode: String?,
        val urlClues: UrlClues
    )

    /** 从 URL 里读到的线索：modalId 只用于命名与“是否像直播页”的判断，绝不用于拼直播页。 */
    private class UrlClues(
        val liveRoomId: String?,
        val roomId: String?,
        val modalId: String?
    )

    /** 一次抓流的结果：流地址（可能为空）、最终落地页 URL、三通道候选数、页面内线索。 */
    private class CaptureResult(
        val url: String?,
        val lastPageUrl: String?,
        val candidates: Int,
        val clues: WebViewSession.PageClues?
    )

    /**
     * 1) 输入里有房间号 → 打开规范直播页；
     * 2) 否则取出文本里第一个 http(s) URL，用移动 UA 跟随一次拿到 landed：
     *    能提到 web_rid / live.douyin.com/<id> 就打开规范直播页，提不到就原样打开 landed；
     * 3) 既没有房间号也没有 URL，才抛“无法识别的输入”。
     */
    private suspend fun resolveTarget(text: String): OpenTarget {
        RoomIdParser.parse(text)?.let { roomId ->
            return OpenTarget(
                openUrl = LIVE_HOME + roomId,
                landedUrl = null,
                roomIdHint = roomId,
                degradedCode = null,
                urlClues = UrlClues(liveRoomId = roomId, roomId = null, modalId = null)
            )
        }
        val rawUrl = firstHttpUrl(text)
            ?: throw IOException("无法识别的输入：请粘贴直播间链接、v.douyin.com 短链或纯数字房间号")
        val token = shortLinkToken(rawUrl)
        val landed = followRedirect(rawUrl) ?: rawUrl
        val landedClues = urlCluesOf(landed)
        val rawClues = urlCluesOf(rawUrl)
        // 只认明确直播页形状（live.douyin.com/<id> 或 web_rid=<id>）；modal_id / room_id 不参与拼页
        val hint = landedClues.liveRoomId ?: rawClues.liveRoomId
        val openUrl = if (hint != null && !landed.contains("live.douyin.com", true)) {
            LIVE_HOME + hint
        } else {
            landed
        }
        return OpenTarget(
            openUrl = openUrl,
            landedUrl = landed,
            roomIdHint = hint,
            degradedCode = token?.let { "v_" + it },
            urlClues = landedClues
        )
    }

    /** 页面线索 → 是否再跳一次（整会话由 WebViewSession 限制为最多一次）。 */
    private fun planRedirect(clues: WebViewSession.PageClues, target: OpenTarget): String? {
        val page = clues.pageUrl
        for (url in clues.liveUrls) {
            if (url != page && url != target.openUrl) return url
        }
        clues.webRids.firstOrNull()?.let { id ->
            val url = LIVE_HOME + id
            if (url != page && url != target.openUrl) return url
        }
        // room_id 是弱线索：只在当前页明显像直播页时才尝试，避免把 App 引导页硬拼成直播页
        if (!looksLikeLivePage(page)) return null
        clues.roomIds.firstOrNull()?.let { id ->
            val url = LIVE_HOME + id
            if (url != page && url != target.openUrl) return url
        }
        return null
    }

    /** 当前页是否“像直播页”：弱线索的准入判断 + 失败诊断的安慰提示。 */
    private fun looksLikeLivePage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.contains("live.douyin.com") ||
            lower.contains("webcast.amemv.com") ||
            lower.contains("/reflow") ||
            lower.contains("web_rid=") ||
            lower.contains("room_id=")
    }

    /** 房间号兜底链（保证非空，只用于文件名与历史记录）。 */
    private fun fallbackRoomId(target: OpenTarget, capture: CaptureResult): String {
        val clues = capture.clues
        val candidates = sequenceOf(
            target.roomIdHint,
            strictRoomId(capture.lastPageUrl),
            clues?.liveUrls?.firstOrNull()?.let { strictRoomId(it) },
            clues?.webRids?.firstOrNull(),
            clues?.roomIds?.firstOrNull(),
            strictRoomId(target.landedUrl),
            strictRoomId(target.openUrl),
            target.urlClues.roomId,
            target.degradedCode,
            target.urlClues.modalId,
            capture.lastPageUrl?.let { RoomIdParser.parse(it) },
            RoomIdParser.parse(target.openUrl)
        )
        return candidates.firstOrNull { !it.isNullOrBlank() } ?: ("room_" + System.currentTimeMillis())
    }

    /** 不做长数字猜测：只从明确的直播页 URL 形状里取 web_rid。 */
    private fun strictRoomId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        WEB_RID_URL.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        LIVE_ROOM_URL.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    /** URL 线索：liveRoomId 可拼直播页；roomId 是弱线索；modalId 只做命名与判断。 */
    private fun urlCluesOf(url: String?): UrlClues {
        if (url.isNullOrBlank()) return UrlClues(null, null, null)
        val roomId = ROOM_ID_URL.find(url)?.groupValues?.getOrNull(1)
            ?: REFLOW_ID.find(url)?.groupValues?.getOrNull(1)
        return UrlClues(
            liveRoomId = strictRoomId(url),
            roomId = roomId,
            modalId = MODAL_ID_URL.find(url)?.groupValues?.getOrNull(1)
        )
    }

    private suspend fun captureStream(target: OpenTarget): CaptureResult {
        // 两段式 UA：确定是 live.douyin.com 直播页时用桌面 UA（播放器档位更高），
        // 其余落地页（分享页 / App 引导页）继续用移动 UA，避免被强推下载引导。
        val ua = if (target.openUrl.contains("live.douyin.com", ignoreCase = true)) {
            WebViewSession.USER_AGENT
        } else {
            WebViewSession.MOBILE_USER_AGENT
        }
        val session = WebViewSession(appContext, ua)
        val watch = PreferredHit()
        val options = WebViewSession.CaptureOptions(
            onClues = { clues -> planRedirect(clues, target) },
            bestCandidate = { watch.best() },
            collectWindowMs = COLLECT_WINDOW_MS
        )
        val captured: String? = try {
            session.openAndCapture(
                url = target.openUrl,
                matcher = { url -> watch.accept(url) },
                timeoutMs = CAPTURE_TIMEOUT_MS,
                options = options
            )
        } catch (io: IOException) {
            val failed = CaptureResult(
                url = null,
                lastPageUrl = session.lastPageUrl(),
                candidates = session.candidateCount(),
                clues = session.lastClues()
            )
            throw IOException((io.message ?: "解析失败：WebView 加载失败") + diagnostics(target, failed), io)
        } finally {
            session.destroy()
        }
        return CaptureResult(
            url = captured ?: watch.best(),
            lastPageUrl = session.lastPageUrl(),
            candidates = session.candidateCount(),
            clues = session.lastClues()
        )
    }

    private suspend fun followRedirect(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", WebViewSession.MOBILE_USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                // followRedirects(true) 已自动跟随 302，最终请求 URL 就是落地页
                response.request.url.toString()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
    }

    private fun timeoutMessage(target: OpenTarget, capture: CaptureResult): String {
        val base = "解析超时：未捕获到流地址，可能未开播或需要人机验证"
        val appPage = capture.candidates == 0 &&
            !looksLikeLivePage(capture.lastPageUrl ?: target.openUrl)
        val hint = if (appPage) "；该链接可能只跳到了抖音 App 引导页，请改用直播间链接或房间号" else ""
        return base + hint + diagnostics(target, capture)
    }

    private fun diagnostics(target: OpenTarget, capture: CaptureResult): String {
        val clueText = capture.clues?.summary() ?: "无"
        return "（首跳落地 ${target.landedUrl ?: "-"}；打开 ${target.openUrl}；最终页面 ${capture.lastPageUrl ?: "-"}；页面线索 $clueText；三通道候选 ${capture.candidates} 个）"
    }

    /**
     * 被动路径的候选判定器：唯一打分函数是 [LiveUrlInterceptor.score]（flv 基准 10 > m3u8 基准 5，
     * 容器内再按 qualityRank 比清晰度）。accept() 对任何候选都返回 true，由 WebViewSession 的
     * 收集窗口在窗口结束时调用 best() 挑最高分——这样不会被播放器先请求的低码率流锁死。
     *
     * 线程契约：accept() 由 HitSink 在主线程串行调用（写）；best() 由 captureStream 在
     * openAndCapture 返回后于 IO 线程（以及主线程窗口结束时）读。用单个 @Volatile 引用持
     * 不可变快照（score+url），读到的要么是旧快照要么是新快照，不会撕裂、也不需要锁。
     */
    private class PreferredHit {

        private class Candidate(val score: Int, val url: String)

        @Volatile
        private var best: Candidate? = null

        fun accept(url: String): Boolean {
            val score = LiveUrlInterceptor.score(url)
            if (score <= 0) return false
            val current = best
            if (current == null || score > current.score) best = Candidate(score, url)
            return true
        }

        fun best(): String? = best?.url
    }

    private fun parseRoomStatus(roomId: String, body: String): RoomStatus {
        return try {
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: return RoomStatus(roomId = roomId, isLive = false)
            val rooms: JSONArray = data.optJSONArray("data")
                ?: return RoomStatus(roomId = roomId, isLive = false)
            if (rooms.length() == 0) return RoomStatus(roomId = roomId, isLive = false)
            val room = rooms.optJSONObject(0) ?: return RoomStatus(roomId = roomId, isLive = false)
            val owner = room.optJSONObject("owner")
            RoomStatus(
                roomId = roomId,
                isLive = room.optInt("status", 0) == 2,
                title = room.stringOrNull("title"),
                anchor = owner?.stringOrNull("nickname"),
                cover = owner?.firstUrlOf("avatar_thumb")
            )
        } catch (t: Throwable) {
            RoomStatus(roomId = roomId, isLive = false)
        }
    }

    /** 抖音接口里缺失字段常是 JSON null，optString 会返回字符串 "null"，这里统一归一化。 */
    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = optString(key, "").trim()
        return if (value.isEmpty() || value == "null") null else value
    }

    private fun JSONObject.firstUrlOf(objectKey: String): String? {
        val holder = optJSONObject(objectKey) ?: return null
        val urls = holder.optJSONArray("url_list") ?: return null
        if (urls.length() == 0) return null
        val value = urls.optString(0, "").trim()
        return if (value.isEmpty() || value == "null") null else value
    }

    /** 没有 ttwid 时先用首页请求把服务端下发的 cookie 写回 CookieManager，保证接口带全身份。 */
    private fun ensureCookieJar() {
        if (WebViewSession.cookieFor(LIVE_HOME).contains("ttwid")) return
        try {
            val request = Request.Builder()
                .url(LIVE_HOME)
                .header("User-Agent", WebViewSession.MOBILE_USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val headers = response.headers
                for (index in 0 until headers.size) {
                    if (!headers.name(index).equals("Set-Cookie", ignoreCase = true)) continue
                    val cookieLine = headers.value(index).substringBefore(';').trim()
                    if (cookieLine.isEmpty()) continue
                    try {
                        WebViewSession.cookieStore().setCookie(LIVE_HOME, cookieLine)
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun firstHttpUrl(text: String): String? {
        val match = HTTP_URL.find(text) ?: return null
        return match.value.trimEnd(
            ',', '.', ';', ':', '!', '?', ')', ']', '}', '"', '\'',
            '，', '。', '、', '；', '：', '！', '？', '”', '）', '】'
        )
    }

    private fun shortLinkToken(url: String): String? =
        SHORT_LINK.find(url)?.groupValues?.getOrNull(1)

    private fun isNumericRoomId(roomId: String): Boolean =
        roomId.length >= 3 && roomId.all { it.isDigit() }

    private fun streamTypeOf(url: String): StreamType {
        val lower = url.lowercase()
        return when {
            lower.contains(".flv") -> StreamType.FLV
            lower.contains(".m3u8") -> StreamType.HLS
            else -> StreamType.UNKNOWN
        }
    }

    companion object {
        private const val CAPTURE_TIMEOUT_MS = 20000L
        private const val COLLECT_WINDOW_MS = 1800L
        private const val MAX_PLAYLIST_CHARS = 65536
        private const val LIVE_HOME = "https://live.douyin.com/"
        private const val ENTER_API = "https://live.douyin.com/webcast/room/web/enter/"
        private const val HLS_STREAM_INF = "#EXT-X-STREAM-INF"
        private const val ENTER_QUERY =
            "?aid=6383&app_name=douyin_web&live_id=1&device_platform=web&language=zh-CN" +
                "&enter_from=web_live&cookie_enabled=true&browser_language=zh-CN" +
                "&browser_platform=Win32&browser_name=Mozilla&browser_version=5.0&web_rid="

        /** 清晰度档位优先级（大小写无关匹配）：FULL_HD1 > HD1 > SD1 > SD2。 */
        private val FLV_KEY_PRIORITY = listOf("FULL_HD1", "HD1", "SD1", "SD2")
        private val HLS_KEY_PRIORITY = listOf("FULL_HD1", "HD1", "SD1", "SD2")

        private val SHORT_LINK =
            Regex("""v\.douyin\.com/([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
        private val LIVE_ROOM_URL = Regex("""live\.douyin\.com/(\d{3,})""", RegexOption.IGNORE_CASE)
        private val WEB_RID_URL = Regex("""web_rid=(\d{3,})""", RegexOption.IGNORE_CASE)
        private val ROOM_ID_URL = Regex("""room_id=(\d{3,})""", RegexOption.IGNORE_CASE)
        private val MODAL_ID_URL = Regex("""modal_id=(\d{3,})""", RegexOption.IGNORE_CASE)
        private val REFLOW_ID = Regex("""reflow/(\d{3,})""", RegexOption.IGNORE_CASE)
        private val HTTP_URL =
            Regex("""https?://[^\s"'<>\[\]{}()（）【】，。、；：！？]+""", RegexOption.IGNORE_CASE)
        private val BANDWIDTH_ATTR = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
        private val CODECS_ATTR = Regex("CODECS=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    }
}
