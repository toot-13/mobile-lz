package com.dylr.recorder.core.record

import android.content.Context
import android.webkit.CookieManager
import com.dylr.recorder.core.LiveSource
import com.dylr.recorder.core.Locator
import com.dylr.recorder.core.RecordConfig
import com.dylr.recorder.core.StreamType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 下载过程对外回报的事件。 */
sealed class DownloadEvent {
    /** 本次录制累计写下的字节数。 */
    data class Bytes(val totalBytes: Long) : DownloadEvent()

    /** 一个切片已落盘（关闭后回报）。 */
    data class DataSegment(val path: String, val index: Int) : DownloadEvent()

    /** 错误信息；fatal=true 表示不会重试（例如建不了文件），上层应把它当终止原因。 */
    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val fatal: Boolean = false
    ) : DownloadEvent()

    /** 下载循环结束（正常结束、被 stop()、或放弃重连）。 */
    object Stopped : DownloadEvent()
}

/** 不可重试的致命错误（例如根本建不了文件）。 */
private class FatalDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 把 LiveSource 的字节流稳定拉下来：顺序读 -> SegmentWriter 落盘，
 * 按时间/大小切片，断流退避重连并在必要时重新 resolve 拿新地址。
 * 一个实例只服务一次录制会话。
 */
class StreamDownloader(private val context: Context) {

    @Volatile
    private var stopped = false

    @Volatile
    private var activeResponse: Response? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** 外部停止：置位 + 关闭当前 response，让阻塞读立刻抛错返回。 */
    fun stop() {
        stopped = true
        val response = activeResponse
        activeResponse = null
        if (response != null) {
            try {
                response.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    suspend fun download(source: LiveSource, config: RecordConfig, onEvent: (DownloadEvent) -> Unit) {
        withContext(Dispatchers.IO) {
            transfer(source, config, onEvent)
        }
    }

    private suspend fun transfer(source: LiveSource, config: RecordConfig, onEvent: (DownloadEvent) -> Unit) {
        var current = source
        var index = 0
        var total = 0L
        var attempt = 0
        var consecutiveFailures = 0
        var writer: SegmentWriter? = null
        var sliceStartedAt = System.currentTimeMillis()
        var fatalAbort = false

        fun openSlice(): SegmentWriter {
            sliceStartedAt = System.currentTimeMillis()
            val created = try {
                SegmentWriter(context, config.roomId, index, extensionOf(current.type), maxBytesOf(config))
            } catch (io: IOException) {
                // 存储/权限问题，重试 10 次也没意义，直接结束
                throw FatalDownloadException(FATAL_CREATE_MESSAGE, io)
            }
            writer = created
            return created
        }

        fun emitSegment() {
            val finished = writer ?: return
            writer = null
            finished.close()
            if (finished.bytesWritten > 0L) {
                onEvent(DownloadEvent.DataSegment(finished.path, finished.index))
            }
        }

        try {
            while (!stopped) {
                var response: Response? = null
                var bytesThisRound = 0L
                var failure: Throwable? = null
                try {
                    val endpoints = endpointsOf(current, 0)
                    if (endpoints.isEmpty()) {
                        throw IOException(PLAYLIST_EMPTY_MESSAGE)
                    }
                    for (endpoint in endpoints) {
                        if (stopped) break
                        val opened = execute(endpoint)
                        response = opened
                        activeResponse = opened
                        val body = opened.body ?: throw IOException("响应体为空")
                        val input = body.source()
                        val buffer = ByteArray(BUFFER_SIZE)
                        var sink = writer ?: openSlice()
                        while (!stopped) {
                            val read = input.read(buffer, 0, buffer.size)
                            if (read == -1) break
                            if (read == 0) continue
                            val keepWriting = sink.write(buffer, 0, read)
                            total += read
                            bytesThisRound += read
                            onEvent(DownloadEvent.Bytes(total))
                            if (!keepWriting) {
                                emitSegment()
                                index++
                                sink = openSlice()
                            } else if (config.segmentMinutes > 0 &&
                                System.currentTimeMillis() - sliceStartedAt >= config.segmentMinutes * 60_000L
                            ) {
                                emitSegment()
                                index++
                                sink = openSlice()
                            }
                        }
                        closeQuietly(response)
                        response = null
                        activeResponse = null
                    }
                } catch (fatalError: FatalDownloadException) {
                    fatalAbort = true
                    onEvent(DownloadEvent.Error(fatalError.message ?: FATAL_CREATE_MESSAGE, fatalError, true))
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    failure = t
                    if (!stopped) onEvent(DownloadEvent.Error(messageOf(t), t))
                } finally {
                    closeQuietly(response)
                    activeResponse = null
                }

                emitSegment()
                if (stopped) break
                if (fatalAbort) break

                val reason = if (failure == null) "流已结束" else messageOf(failure)
                val failedRound = failure != null || bytesThisRound <= 0L
                if (failedRound) consecutiveFailures++ else consecutiveFailures = 0
                if (bytesThisRound > 0L) attempt = 0
                attempt++
                if (attempt > MAX_RECONNECT) {
                    onEvent(DownloadEvent.Error("重连已超过 " + MAX_RECONNECT + " 次，停止下载"))
                    break
                }
                val waitMs = backoffMillis(attempt)
                onEvent(DownloadEvent.Error(reason + "，" + waitMs + "ms 后进行第 " + attempt + " 次重连"))
                delay(waitMs)
                if (stopped) break

                // 按需重解析：连续失败够多，或错误看起来像地址失效，才重建解析链（WebView 很贵）
                if (consecutiveFailures >= RESOLVE_AFTER_FAILURES || looksLikeAddressInvalid(reason)) {
                    val fresh = tryResolve(config, onEvent)
                    if (fresh != null) {
                        current = fresh
                        consecutiveFailures = 0
                    }
                }
                index++
            }
        } finally {
            emitSegment()
            onEvent(DownloadEvent.Stopped)
        }
    }

    private suspend fun tryResolve(config: RecordConfig, onEvent: (DownloadEvent) -> Unit): LiveSource? {
        return try {
            Locator.resolver.resolve(config.roomId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            onEvent(DownloadEvent.Error("重新解析地址失败，沿用旧地址", t))
            null
        }
    }

    private fun looksLikeAddressInvalid(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        val lower = text.lowercase(Locale.US)
        return lower.contains("403") ||
            lower.contains("404") ||
            lower.contains("410") ||
            lower.contains("forbidden") ||
            lower.contains("not found")
    }

    /**
     * 一次连接要顺序拉取的地址列表。
     * FLV/未知类型 = 单个地址；HLS 先取播放列表，master playlist 递归解析 variant，最后拆成分片。
     */
    private fun endpointsOf(source: LiveSource, depth: Int): List<String> {
        if (source.type != StreamType.HLS) return listOf(source.url)
        // 走到这里说明解析器已认定它是播放列表：任何“拿不到分片”的分支都必须返回空列表，
        // 绝不能回落成 source.url —— 否则 m3u8 文本会被当媒体字节写进 .ts。
        val text = fetchText(source.url) ?: return emptyList()
        if (!text.contains("#EXTM3U")) return emptyList()
        if (text.contains("#EXT-X-STREAM-INF")) {
            // master playlist：#EXT-X-MEDIA（纯音频/字幕轨）忽略，只取一个 variant 播放列表
            if (depth >= MAX_PLAYLIST_DEPTH) return emptyList()
            val variant = firstVariantUrl(source.url, text)
            if (variant == null || variant == source.url) return emptyList()
            val next = LiveSource(
                roomId = source.roomId,
                url = variant,
                type = StreamType.HLS,
                title = source.title,
                anchor = source.anchor
            )
            return endpointsOf(next, depth + 1)
        }
        return playlistSegments(source.url, text)
    }

    /**
     * master playlist 里 #EXT-X-STREAM-INF 之后的第一条非 # 行就是 variant 地址。
     * 优先选 CODECS 含 avc1 的变体：HEVC(hvc1) 变体在不少机型上解析不出视频轨，
     * 会直接产出黑屏 mp4，从源头避开它。
     */
    private fun firstVariantUrl(playlistUrl: String, text: String): String? {
        val base = uriOf(playlistUrl)
        val urls = ArrayList<String>()
        val attributes = ArrayList<String>()
        var pending: String? = null
        for (line in text.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#")) {
                if (trimmed.startsWith("#EXT-X-STREAM-INF")) pending = trimmed
                continue
            }
            val current = pending
            if (current != null) {
                urls.add(resolveUrl(base, trimmed))
                attributes.add(current)
                pending = null
            }
        }
        if (urls.isEmpty()) return null
        for (i in urls.indices) {
            if (attributes[i].contains("avc1", ignoreCase = true)) return urls[i]
        }
        return urls[0]
    }

    private fun playlistSegments(playlistUrl: String, text: String): List<String> {
        val base = uriOf(playlistUrl)
        val segments = ArrayList<String>()
        for (line in text.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            segments.add(resolveUrl(base, trimmed))
        }
        return segments
    }

    private fun uriOf(url: String): URI? = try {
        URI(url)
    } catch (ignored: Throwable) {
        null
    }

    private fun resolveUrl(base: URI?, raw: String): String {
        if (base == null) return raw
        return try {
            base.resolve(raw).toString()
        } catch (ignored: Throwable) {
            raw
        }
    }

    private fun fetchText(url: String): String? {
        return try {
            val response = execute(url)
            try {
                val body = response.body ?: return null
                body.string()
            } finally {
                closeQuietly(response)
            }
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun execute(url: String): Response {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
        val cookie = cookieOf(url)
        if (!cookie.isNullOrEmpty()) builder.header("Cookie", cookie)
        val response = client.newCall(builder.build()).execute()
        val code = response.code
        if (!response.isSuccessful) {
            closeQuietly(response)
            throw IOException("HTTP " + code + " " + url)
        }
        return response
    }

    private fun cookieOf(url: String): String? = try {
        CookieManager.getInstance().getCookie(url)
    } catch (ignored: Throwable) {
        null
    }

    private fun closeQuietly(response: Response?) {
        if (response == null) return
        try {
            response.close()
        } catch (ignored: Throwable) {
        }
    }

    private fun messageOf(t: Throwable?): String {
        if (t == null) return "无异常信息"
        val message = t.message
        if (!message.isNullOrEmpty()) return message
        val name = t.javaClass.simpleName
        return if (name.isNullOrEmpty()) "未知错误" else name
    }

    private fun extensionOf(type: StreamType): String = when (type) {
        StreamType.HLS -> "ts"
        else -> "flv"
    }

    private fun maxBytesOf(config: RecordConfig): Long =
        if (config.segmentMaxMb > 0) config.segmentMaxMb.toLong() * 1024L * 1024L else 0L

    private fun backoffMillis(attempt: Int): Long {
        val shift = if (attempt <= 1) 0 else if (attempt > 5) 4 else attempt - 1
        val raw = 1000L shl shift
        return if (raw > MAX_BACKOFF_MS) MAX_BACKOFF_MS else raw
    }

    private companion object {
        const val MAX_RECONNECT = 10
        const val MAX_BACKOFF_MS = 15_000L
        const val BUFFER_SIZE = 64 * 1024
        const val RESOLVE_AFTER_FAILURES = 3
        const val MAX_PLAYLIST_DEPTH = 2
        const val FATAL_CREATE_MESSAGE = "无法创建录制文件（存储空间或权限问题）"
        const val PLAYLIST_EMPTY_MESSAGE = "播放列表未取到分片"
        const val REFERER = "https://live.douyin.com/"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
