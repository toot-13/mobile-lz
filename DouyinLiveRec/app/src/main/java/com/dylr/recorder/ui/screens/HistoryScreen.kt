package com.dylr.recorder.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dylr.recorder.core.RecordItem
import com.dylr.recorder.ui.MainViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史列表：展示标题 / 房间号 / 时间 / 大小 / 成功失败标签，点击用 FileProvider 打开，
 * 右侧删除图标删除记录。
 */
@Composable
fun HistoryScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val history by vm.history.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        try {
            vm.refreshHistory()
        } catch (t: Throwable) {
            historyToast(context, "刷新失败：" + (t.message ?: "未知错误"))
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("录制历史", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { try { vm.refreshHistory() } catch (t: Throwable) { historyToast(context, "刷新失败：" + (t.message ?: "未知错误")) } }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }
        Text(
            "点击条目打开文件，点删除图标移除记录",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (history.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "还没有录制记录，回到「录制」页开始一个任务吧",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = history,
                    key = { item -> item.id },
                    itemContent = { item ->
                        HistoryRow(
                            item = item,
                            onOpen = { openRecordedFile(context, item.path) },
                            onDelete = { try { vm.delete(item.id) } catch (t: Throwable) { historyToast(context, "删除失败：" + (t.message ?: "未知错误")) } }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(item: RecordItem, onOpen: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (item.title.isNotBlank()) item.title else item.roomId,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    StatusTag(success = item.success)
                }
                Text("房间号：" + item.roomId, style = MaterialTheme.typography.bodySmall)
                Text(formatTime(item.createdAt) + " · " + formatSize(item.sizeBytes), style = MaterialTheme.typography.bodySmall)
                Text(item.path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onOpen) {
                Icon(Icons.Default.PlayArrow, contentDescription = "打开")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun StatusTag(success: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (success) colors.secondaryContainer else colors.errorContainer,
        contentColor = if (success) colors.onSecondaryContainer else colors.onErrorContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = if (success) "成功" else "失败",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * 用 FileProvider 生成 content:// 授权后交给系统播放器。
 * 清单里必须声明 authority = "<applicationId>.fileprovider" 的 provider 与对应 file_paths。
 */
private fun openRecordedFile(context: Context, path: String) {
    try {
        if (path.isBlank()) {
            historyToast(context, "文件路径为空，无法打开")
            return
        }
        val file = File(path)
        if (!file.exists()) {
            historyToast(context, "文件不存在：" + path)
            return
        }
        val authority = context.packageName + ".fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        openWithUri(context, uri, path)
    } catch (t: Throwable) {
        // FileProvider 未配置时退回 file:// 尝试一次，仍失败就提示
        try {
            val fallback = Uri.fromFile(File(path))
            openWithUri(context, fallback, path)
        } catch (inner: Throwable) {
            historyToast(context, "无法打开该文件：" + (t.message ?: "未知错误"))
        }
    }
}

private fun openWithUri(context: Context, uri: Uri, path: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, guessMimeType(path))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun guessMimeType(path: String): String {
    val lower = path.lowercase(Locale.US)
    return when {
        lower.endsWith(".flv") -> "video/x-flv"
        lower.endsWith(".ts") -> "video/mp2t"
        lower.endsWith(".mp4") -> "video/mp4"
        lower.endsWith(".m4a") -> "audio/mp4"
        lower.endsWith(".aac") -> "audio/aac"
        else -> "video/*"
    }
}

private fun formatTime(timestamp: Long): String {
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    } catch (t: Throwable) {
        timestamp.toString()
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> bytes.toString() + " B"
        bytes < 1048576L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1073741824L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    }
}

private fun historyToast(context: Context, text: String) {
    runCatching { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
}
