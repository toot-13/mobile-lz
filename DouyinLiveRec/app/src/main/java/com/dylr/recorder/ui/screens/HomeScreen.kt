package com.dylr.recorder.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dylr.recorder.core.RecordPhase
import com.dylr.recorder.core.RecordState
import com.dylr.recorder.ui.MainViewModel
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun HomeScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val monitors by vm.monitors.collectAsStateWithLifecycle()
    val preparing by vm.preparing.collectAsStateWithLifecycle()

    var input by rememberSaveable { mutableStateOf(readIncomingText(context)) }
    var autoMonitor by rememberSaveable { mutableStateOf(false) }
    var saveToGallery by rememberSaveable { mutableStateOf(true) }
    var segmentMinutes by rememberSaveable { mutableStateOf("") }
    var segmentMaxMb by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("抖音直播录制", style = MaterialTheme.typography.headlineSmall)
        Text(
            "粘贴直播间链接或房间号，选择立即录制或开播自动监控",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("直播间链接 / 房间号") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        SwitchRow(title = "开播自动监控", checked = autoMonitor, onCheckedChange = { autoMonitor = it })
        SwitchRow(title = "自动存相册", checked = saveToGallery, onCheckedChange = { saveToGallery = it })

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = segmentMinutes,
                onValueChange = { segmentMinutes = digitsOnly(it) },
                label = { Text("分片时长(分钟)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedTextField(
                value = segmentMaxMb,
                onValueChange = { segmentMaxMb = digitsOnly(it) },
                label = { Text("分片大小(MB)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            "两项分片条件留空或填 0 表示不自动切片",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = {
                try {
                    vm.submit(
                        raw = input,
                        autoMonitor = autoMonitor,
                        segmentMinutes = segmentMinutes.toIntOrNull() ?: 0,
                        segmentMaxMb = segmentMaxMb.toIntOrNull() ?: 0,
                        saveToGallery = saveToGallery
                    )
                } catch (t: Throwable) {
                    homeToast(context, "操作失败：" + (t.message ?: "未知错误"))
                }
            },
            enabled = !preparing,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (preparing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("正在解析…")
            } else {
                Text(if (autoMonitor) "开始监控" else "立即录制")
            }
        }

        StatusCard(state = state, onStop = { try { vm.stop() } catch (t: Throwable) { homeToast(context, "停止失败：" + (t.message ?: "未知错误")) } })

        MonitorsSection(monitors = monitors, onCancel = { roomId -> try { vm.stopMonitor(roomId) } catch (t: Throwable) { homeToast(context, "取消失败：" + (t.message ?: "未知错误")) } })
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StatusCard(state: RecordState, onStop: () -> Unit) {
    val accent = phaseColors(state.phase)
    val ticking = state.phase == RecordPhase.RECORDING || state.phase == RecordPhase.RESOLVING || state.phase == RecordPhase.FINALIZING
    val now = rememberNow(ticking)
    val canStop = state.phase == RecordPhase.RECORDING || state.phase == RecordPhase.RESOLVING ||
        state.phase == RecordPhase.MONITORING || state.phase == RecordPhase.FINALIZING

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = accent.first, contentColor = accent.second)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(phaseLabel(state.phase), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state.segmentIndex > 0) {
                    Text("第 " + (state.segmentIndex + 1) + " 片", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.roomId.isNotBlank()) {
                Text("房间号：" + state.roomId, style = MaterialTheme.typography.bodySmall)
            }
            if (state.title.isNotBlank()) {
                Text("标题：" + state.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (state.phase == RecordPhase.RECORDING || state.phase == RecordPhase.FINALIZING) {
                Text("已写入：" + String.format(Locale.US, "%.2f", state.bytesWritten / 1048576.0) + " MB", style = MaterialTheme.typography.bodyMedium)
                Text("已录时长：" + formatDuration(now - state.startedAt), style = MaterialTheme.typography.bodyMedium)
                Text("当前分片序号：" + (state.segmentIndex + 1), style = MaterialTheme.typography.bodySmall)
            }
            val file = state.lastFile
            if (file != null && file.isNotBlank()) {
                Text("当前文件：" + file, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val hint = state.message
            if (hint != null && hint.isNotBlank()) {
                Text("提示：" + hint, style = MaterialTheme.typography.bodySmall)
            }
            if (canStop) {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(onClick = onStop) {
                    Icon(Icons.Default.Close, contentDescription = "停止")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("停止")
                }
            }
        }
    }
}

@Composable
private fun MonitorsSection(monitors: List<RecordState>, onCancel: (String) -> Unit) {
    if (monitors.isEmpty()) {
        return
    }
    Text("监控中的房间", style = MaterialTheme.typography.titleMedium)
    monitors.forEach { item ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (item.title.isNotBlank()) item.title else item.roomId,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "房间号：" + item.roomId + " · " + phaseLabel(item.phase),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { onCancel(item.roomId) }) {
                    Text("取消")
                }
            }
        }
    }
}

@Composable
private fun rememberNow(active: Boolean): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) {
            now = System.currentTimeMillis()
            delay(1000L)
        }
    }
    return now
}

private fun phaseLabel(phase: RecordPhase): String = when (phase) {
    RecordPhase.IDLE -> "空闲"
    RecordPhase.MONITORING -> "监控中"
    RecordPhase.RESOLVING -> "正在解析播放地址"
    RecordPhase.RECORDING -> "录制中"
    RecordPhase.FINALIZING -> "正在收尾封装"
    RecordPhase.COMPLETED -> "已完成"
    RecordPhase.ERROR -> "出错"
}

@Composable
private fun phaseColors(phase: RecordPhase): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (phase) {
        RecordPhase.IDLE -> c.surfaceVariant to c.onSurfaceVariant
        RecordPhase.MONITORING -> c.tertiaryContainer to c.onTertiaryContainer
        RecordPhase.RESOLVING -> c.secondaryContainer to c.onSecondaryContainer
        RecordPhase.RECORDING -> c.primaryContainer to c.onPrimaryContainer
        RecordPhase.FINALIZING -> c.secondaryContainer to c.onSecondaryContainer
        RecordPhase.COMPLETED -> c.surfaceVariant to c.onSurfaceVariant
        RecordPhase.ERROR -> c.errorContainer to c.onErrorContainer
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun digitsOnly(text: String): String = text.filter { ch -> ch.isDigit() }

private fun homeToast(context: Context, text: String) {
    runCatching { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
}

/** 读取分享/打开方式传进来的文本，作为输入框预填内容。 */
private fun readIncomingText(context: Context): String {
    return try {
        val activity = context as? Activity ?: return ""
        val intent = activity.intent ?: return ""
        when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            Intent.ACTION_VIEW -> intent.dataString ?: ""
            else -> intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        }
    } catch (t: Throwable) {
        ""
    }
}
