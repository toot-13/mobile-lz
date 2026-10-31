package com.dylr.recorder.core.record

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/**
 * 把录制好的文件放进系统相册（Movies/DouyinLiveRec）。
 * API >= 29 走 MediaStore + IS_PENDING；API < 29 直接写公共目录 + MediaScanner 扫描。
 */
object MediaStoreSaver {

    private const val ALBUM = "DouyinLiveRec"
    private const val RELATIVE_PATH = "Movies/DouyinLiveRec"

    fun saveToGallery(context: Context, file: File, displayName: String): Uri? {
        if (!file.isFile || file.length() <= 0L) return null
        val name = if (displayName.contains('.')) displayName else displayName + "." + defaultExtension()
        val mime = mimeOf(name)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveModern(context, file, name, mime)
        } else {
            saveLegacy(context, file, name, mime)
        }
    }

    /** 重封装失败时 target 可能是 .flv/.ts，MIME 必须跟着扩展名走，否则相册里打不开。 */
    private fun mimeOf(name: String): String {
        val lower = name.lowercase(Locale.US)
        return when {
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".ts") -> "video/mp2t"
            lower.endsWith(".flv") -> "video/x-flv"
            lower.endsWith(".m4v") -> "video/x-m4v"
            else -> "video/*"
        }
    }

    private fun defaultExtension(): String = "mp4"

    private fun saveModern(context: Context, file: File, name: String, mime: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues()
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name)
        values.put(MediaStore.Video.Media.MIME_TYPE, mime)
        values.put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
        values.put(MediaStore.Video.Media.IS_PENDING, 1)

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = try {
            resolver.insert(collection, values)
        } catch (ignored: Throwable) {
            null
        } ?: return null

        var done = false
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("openOutputStream 返回 null")
            output.use { target ->
                file.inputStream().use { source ->
                    source.copyTo(target)
                }
            }
            val pending = ContentValues()
            pending.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, pending, null, null)
            done = true
        } catch (ignored: Throwable) {
            done = false
        } finally {
            if (!done) {
                try {
                    resolver.delete(uri, null, null)
                } catch (ignored2: Throwable) {
                }
            }
        }
        return if (done) uri else null
    }

    private fun saveLegacy(context: Context, file: File, name: String, mime: String): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            // 没有存储权限就静默跳过，不打断录制流程
            return null
        }
        return try {
            val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val dir = File(movies, ALBUM)
            if (!dir.exists() && !dir.mkdirs()) return null
            val dst = File(dir, name)
            if (dst.exists()) dst.delete()
            FileOutputStream(dst).use { target ->
                file.inputStream().use { source ->
                    source.copyTo(target)
                }
            }
            MediaScannerConnection.scanFile(context, arrayOf(dst.absolutePath), arrayOf(mime), null)
            Uri.fromFile(dst)
        } catch (ignored: Throwable) {
            null
        }
    }
}
