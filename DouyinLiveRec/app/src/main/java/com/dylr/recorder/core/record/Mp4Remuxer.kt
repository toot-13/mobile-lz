package com.dylr.recorder.core.record

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * 转封装结果。ok=false 时调用方必须保留原始文件，并把 note 透给用户。
 * 只在 Mp4Remuxer / DefaultRecorder 之间使用，不进入冻结契约。
 */
internal data class RemuxResult(
    val ok: Boolean,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val note: String?
)

/**
 * 无损重封装：只搬 sample，不转码。
 * 硬规则：输出里没有视频轨一律判失败（宁可留原始 .flv，也不给用户黑屏 mp4）。
 * 阻塞函数，调用方必须放在 IO 线程。
 */
object Mp4Remuxer {

    private const val MIN_BUFFER_SIZE = 1 shl 20
    private const val FRAME_GAP_US = 33_000L
    private const val KEY_CSD_0 = "csd-0"
    private const val MIME_AVC = "video/avc"
    private const val MIME_HEVC = "video/hevc"
    private const val NOTE_NO_VIDEO = "系统解析器未解析出视频轨，常见于 H.265/HEVC 直播流"
    private const val NOTE_NO_CSD = "视频轨缺少关键帧参数集 csd-0，写进 MP4 会黑屏"

    internal fun remux(srcPath: String, dstPath: String): RemuxResult {
        val src = File(srcPath)
        if (!src.isFile || src.length() <= 0L) return fail("源文件不存在或为空")
        val dst = File(dstPath)
        if (dst.absolutePath == src.absolutePath) return fail("源文件与目标文件相同")
        dst.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) return fail("无法创建输出目录")
        }
        if (dst.exists() && !dst.delete()) return fail("无法覆盖已存在的输出文件")

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        var success = false
        var hasVideo = false
        var hasAudio = false
        var note: String? = null
        try {
            extractor.setDataSource(srcPath)
            val trackCount = extractor.trackCount
            if (trackCount <= 0) return fail("未解析到任何音视频轨道")

            // 第一步：体检。视频轨缺失/缺参数集时整体失败，绝不产出只有音轨的 mp4。
            var videoCount = 0
            var audioCount = 0
            var videoMime: String? = null
            var missingCsd = false
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = mimeOf(format) ?: continue
                if (mime.startsWith("video/")) {
                    videoCount++
                    if (videoMime == null) videoMime = mime
                    if ((mime == MIME_AVC || mime == MIME_HEVC) && !format.containsKey(KEY_CSD_0)) {
                        missingCsd = true
                    }
                } else if (mime.startsWith("audio/")) {
                    audioCount++
                }
            }
            if (videoCount == 0) return RemuxResult(false, false, audioCount > 0, NOTE_NO_VIDEO)
            if (missingCsd) return RemuxResult(false, false, audioCount > 0, NOTE_NO_CSD)

            // 第二步：搬 sample。
            val trackMap = IntArray(trackCount) { -1 }
            val lastPts = LongArray(trackCount) { -1L }
            var outTracks = 0
            muxer = MediaMuxer(dstPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = mimeOf(format) ?: continue
                val isVideo = mime.startsWith("video/")
                if (!isVideo && !mime.startsWith("audio/")) continue
                extractor.selectTrack(i)
                trackMap[i] = muxer.addTrack(format)
                if (isVideo) hasVideo = true else hasAudio = true
                outTracks++
            }
            // 双保险：连一条视频轨都没加进去，就没有可用输出
            if (outTracks == 0 || !hasVideo) return RemuxResult(false, false, hasAudio, NOTE_NO_VIDEO)

            muxer.start()
            started = true

            val buffer = ByteBuffer.allocateDirect(bufferSize(extractor))
            val info = MediaCodec.BufferInfo()
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                if (track >= trackCount) {
                    extractor.advance()
                    continue
                }
                val dstTrack = trackMap[track]
                if (dstTrack < 0) {
                    extractor.advance()
                    continue
                }
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break

                var pts = extractor.sampleTime
                if (pts < 0L) pts = 0L
                val previous = lastPts[track]
                // 多段拼接后时间戳可能回退，强制单调递增，避免 muxer 丢帧
                if (previous >= 0L && pts <= previous) pts = previous + FRAME_GAP_US
                lastPts[track] = pts

                val flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                info.set(0, size, pts, flags)
                muxer.writeSampleData(dstTrack, buffer, info)
                extractor.advance()
            }

            muxer.stop()
            started = false
            success = dst.isFile && dst.length() > 0L
            if (!success) {
                note = "转封装输出为空"
            } else if (videoMime == MIME_HEVC) {
                note = "视频轨为 H.265/HEVC 编码，部分播放器需要硬件解码支持"
            }
        } catch (failure: Throwable) {
            success = false
            note = "转封装异常：" + (failure.message ?: failure.javaClass.simpleName)
        } finally {
            if (started) {
                try {
                    muxer?.stop()
                } catch (ignored: Throwable) {
                }
            }
            try {
                muxer?.release()
            } catch (ignored: Throwable) {
            }
            try {
                extractor.release()
            } catch (ignored: Throwable) {
            }
            if (!success) {
                // 失败只清理半成品，源文件由调用方保留
                dst.delete()
            }
        }
        return RemuxResult(success, hasVideo, hasAudio, note)
    }

    private fun fail(reason: String): RemuxResult = RemuxResult(false, false, false, reason)

    private fun mimeOf(format: MediaFormat): String? =
        if (format.containsKey(MediaFormat.KEY_MIME)) format.getString(MediaFormat.KEY_MIME) else null

    private fun bufferSize(extractor: MediaExtractor): Int {
        var size = MIN_BUFFER_SIZE
        for (i in 0 until extractor.trackCount) {
            try {
                val format = extractor.getTrackFormat(i)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    val value = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    if (value > size) size = value
                }
            } catch (ignored: Throwable) {
            }
        }
        return size
    }
}
