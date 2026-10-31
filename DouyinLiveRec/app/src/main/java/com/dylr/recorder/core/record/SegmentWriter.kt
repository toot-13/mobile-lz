package com.dylr.recorder.core.record

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单个切片文件的写入器。
 * 命名：<getExternalFilesDir("rec")>/<roomId>_<yyyyMMdd_HHmmss>_<index>.<ext>
 * write() 返回 false 表示已达 maxBytes 上限，调用方应切片。
 *
 * flv 切片会自动补齐 FLV 文件头：断流重连和大小/时长切片都是从流中间开始，
 * 缺了 13 字节头（F L V + version + flags + dataOffset + PreviousTagSize0）
 * MediaExtractor 会认不出整段数据。首次连接本来就是完整流（前 3 字节就是 FLV），
 * 此时原样写入，不会重复插头。ts 有自己的同步字节 0x47，不加任何头。
 */
class SegmentWriter(
    context: Context,
    val roomId: String = "unknown",
    val index: Int = 0,
    val extension: String = "flv",
    private val maxBytes: Long = 0L
) {

    private val lock = Any()
    private var closed = false
    private var written = 0L

    /** 只有 flv 需要补头。 */
    private val needsFlvHeader: Boolean = extension.equals("flv", ignoreCase = true)

    /** 头部判定只做一次。 */
    private var headerSettled: Boolean = !needsFlvHeader

    /** 窥视前 3 个字节的小缓冲：不足 3 字节时先存这里，绝不丢首块数据。 */
    private val peek = ByteArray(3)
    private var peeked = 0

    val dir: File = (context.getExternalFilesDir("rec") ?: File(context.filesDir, "rec")).also { target ->
        if (!target.exists()) target.mkdirs()
    }

    val file: File = File(dir, buildFileName())

    private val stream: FileOutputStream = FileOutputStream(file)

    val bytesWritten: Long
        get() = synchronized(lock) { written }

    val path: String
        get() = file.absolutePath

    /**
     * @return false 表示本片已达 maxBytes（或写失败），上层应关闭本片并开启下一片。
     */
    fun write(bytes: ByteArray, offset: Int, len: Int): Boolean {
        if (len <= 0) return true
        return synchronized(lock) {
            if (closed) {
                false
            } else {
                var keepWriting = true
                try {
                    var start = offset
                    var count = len
                    if (!headerSettled) {
                        while (peeked < PEEK_SIZE && count > 0) {
                            peek[peeked] = bytes[start]
                            peeked++
                            start++
                            count--
                        }
                        if (peeked < PEEK_SIZE) {
                            // 还不足以判定是不是 FLV，先缓存，等下一块再来判断
                            return@synchronized true
                        }
                        val looksLikeFlv = peek[0] == BYTE_F && peek[1] == BYTE_L && peek[2] == BYTE_V
                        val looksLikeTs = peek[0] == BYTE_TS_SYNC
                        if (!looksLikeFlv && !looksLikeTs) {
                            // 中途流：先补标准 13 字节头（flags=0x05 音视频都有）
                            stream.write(FLV_HEADER)
                            written += FLV_HEADER.size.toLong()
                        }
                        stream.write(peek, 0, PEEK_SIZE)
                        written += PEEK_SIZE.toLong()
                        peeked = 0
                        headerSettled = true
                    }
                    if (count > 0) {
                        stream.write(bytes, start, count)
                        written += count
                    }
                    keepWriting = maxBytes <= 0L || written < maxBytes
                } catch (ignored: IOException) {
                    closeLocked()
                    keepWriting = false
                }
                keepWriting
            }
        }
    }

    fun close() {
        synchronized(lock) { closeLocked() }
    }

    fun hasData(): Boolean = file.isFile && file.length() > 0L

    private fun closeLocked() {
        if (closed) return
        closed = true
        try {
            stream.flush()
        } catch (ignored: IOException) {
        }
        try {
            stream.close()
        } catch (ignored: IOException) {
        }
    }

    private fun buildFileName(): String = roomId + "_" + timeStamp() + "_" + index + "." + extension

    private fun timeStamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(System.currentTimeMillis()))

    private companion object {
        const val PEEK_SIZE = 3

        /** F L V | version 0x01 | flags 0x05（音+视频）| dataOffset 9 | PreviousTagSize0 = 0 */
        val FLV_HEADER: ByteArray = byteArrayOf(
            0x46.toByte(), 0x4C.toByte(), 0x56.toByte(), 0x01.toByte(), 0x05.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x09.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
        )
        val BYTE_F: Byte = 0x46.toByte()
        val BYTE_L: Byte = 0x4C.toByte()
        val BYTE_V: Byte = 0x56.toByte()
        val BYTE_TS_SYNC: Byte = 0x47.toByte()
    }
}
