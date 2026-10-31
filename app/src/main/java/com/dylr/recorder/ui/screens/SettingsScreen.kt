package com.dylr.recorder.ui.screens

import android.content.Context
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * 极简「关于」页：说明原理、保存目录与版本号。
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val saveDir = saveDirectory(context)
    val version = appVersion(context)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("关于", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("工作原理", style = MaterialTheme.typography.titleMedium)
                Text(
                    "1. 应用内用 WebView 打开直播间页面，注入脚本监听页面里的播放请求，从 m3u8 / flv 请求中拦截到真实流地址。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "2. 拿到流地址后直接按原始字节写入本地文件，不做转码，属于无损重封装：画质与源流完全一致。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "3. 结束录制时补齐文件容器（FLV 原样保留，HLS 的分片按顺序落盘），再把文件登记进历史列表。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "本应用不破解、不下载私密内容，仅用于记录公开直播，请遵守平台规则与当地法律。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("保存目录", style = MaterialTheme.typography.titleMedium)
                Text("Movies/DouyinLiveRec", style = MaterialTheme.typography.bodyMedium)
                Text(saveDir, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Android 10 及以上使用分区存储，文件通过 MediaStore 写入相册目录，卸载应用不会删除已录制文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("版本", style = MaterialTheme.typography.titleMedium)
                Text("当前版本：$version", style = MaterialTheme.typography.bodyMedium)
                Text("包名：" + context.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun saveDirectory(context: Context): String {
    return try {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "DouyinLiveRec")
        dir.absolutePath
    } catch (t: Throwable) {
        "Movies/DouyinLiveRec"
    }
}

private fun appVersion(context: Context): String {
    return try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val name = info.versionName
        if (name.isNullOrBlank()) "未知" else name
    } catch (t: Throwable) {
        "未知"
    }
}
