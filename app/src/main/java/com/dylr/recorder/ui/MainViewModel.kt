package com.dylr.recorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dylr.recorder.core.LiveSource
import com.dylr.recorder.core.Locator
import com.dylr.recorder.core.RecordConfig
import com.dylr.recorder.core.RecordItem
import com.dylr.recorder.core.RecordState
import com.dylr.recorder.core.Recorder
import com.dylr.recorder.core.resolve.RoomIdParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 只做四件事：转发 Locator 里的 Recorder 调用、把「用户粘贴的任意文本」解析成数字房间号、
 * 把异常转成中文提示、暴露 UI 需要的 StateFlow。
 * 不直接 new DefaultRecorder / DefaultLiveResolver。
 *
 * 注意：RoomIdParser.parse 只认本地能确定的房间号（含短链时直接返回 null），
 * 所以短链和整段分享文案必须交给 Locator.resolver.resolve() 走网络解析。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val recorder: Recorder = runCatching { Locator.recorder }.getOrElse {
        Locator.init(getApplication<Application>())
        Locator.recorder
    }

    val state: StateFlow<RecordState> = recorder.state
    val monitors: StateFlow<List<RecordState>> = recorder.monitors
    val history: StateFlow<List<RecordItem>> = recorder.history

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** 正在解析输入（可能走网络追短链），期间按钮置 loading 且禁用，防重复点击。 */
    private val _preparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = _preparing

    fun consumeMessage() {
        _message.value = null
    }

    /** 一键开工：解析输入 → 构造 RecordConfig → 立即录制或开始监控。 */
    fun submit(
        raw: String,
        autoMonitor: Boolean,
        segmentMinutes: Int,
        segmentMaxMb: Int,
        saveToGallery: Boolean
    ) {
        if (_preparing.value) return
        viewModelScope.launch {
            _preparing.value = true
            try {
                val roomId = prepareRoomId(raw) ?: return@launch
                val config = RecordConfig(
                    roomId = roomId,
                    title = null,
                    autoMonitor = autoMonitor,
                    segmentMinutes = segmentMinutes,
                    segmentMaxMb = segmentMaxMb,
                    saveToGallery = saveToGallery
                )
                runCatching {
                    if (autoMonitor) recorder.startMonitor(config) else recorder.start(config)
                }.onFailure { report(if (autoMonitor) "开始监控失败" else "启动录制失败", it) }
            } finally {
                _preparing.value = false
            }
        }
    }

    /**
     * 把用户输入（房间号 / 直播间链接 / v.douyin.com 短链 / 整段分享文案）解析成数字房间号。
     * 本地能解析就直接用；含抖音域名或是短链就交给 resolver.resolve()；其余中文提示并返回 null。
     */
    suspend fun prepareRoomId(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) {
            _message.value = "请先粘贴直播间链接或输入房间号"
            return null
        }

        // 1) 本地可确定的房间号（纯数字 / 完整链接），不联网
        val local: String? = runCatching { RoomIdParser.parse(text) }.getOrNull()
        if (!local.isNullOrBlank()) return local

        // 2) 短链或整段分享文案：交给 resolver（它内部自带 IO 调度，这里保持在主线程调用）
        val looksLikeDouyin = text.contains("douyin.com", ignoreCase = true) ||
            runCatching { RoomIdParser.isShortLink(text) }.getOrDefault(false)
        if (!looksLikeDouyin) {
            _message.value = "无法识别该链接或房间号，请检查后重试"
            return null
        }

        return try {
            val source: LiveSource = Locator.resolver.resolve(text)
            val roomId = source.roomId
            if (roomId.isBlank()) {
                _message.value = "解析完成但没拿到房间号，请稍后重试"
                null
            } else {
                roomId
            }
        } catch (t: Throwable) {
            _message.value = "解析直播地址失败：" + (t.message ?: t.javaClass.simpleName)
            null
        }
    }

    fun startRecord(config: RecordConfig) {
        viewModelScope.launch {
            runCatching { recorder.start(config) }.onFailure { report("启动录制失败", it) }
        }
    }

    fun startMonitor(config: RecordConfig) {
        viewModelScope.launch {
            runCatching { recorder.startMonitor(config) }.onFailure { report("开始监控失败", it) }
        }
    }

    fun stop() {
        viewModelScope.launch {
            runCatching { recorder.stop() }.onFailure { report("停止录制失败", it) }
        }
    }

    fun stopMonitor(roomId: String) {
        viewModelScope.launch {
            runCatching { recorder.stopMonitor(roomId) }.onFailure { report("取消监控失败", it) }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { recorder.deleteHistory(id) }.onFailure { report("删除历史失败", it) }
        }
    }

    fun refreshHistory() {
        viewModelScope.launch {
            runCatching { recorder.refreshHistory() }.onFailure { report("刷新历史失败", it) }
        }
    }

    /**
     * 纯本地校验（不联网），保留给需要即时反馈的调用方。
     * 注意：短链与整段分享文案在本地必然为 null，正式开工请走 [submit] / [prepareRoomId]。
     */
    fun parseInput(text: String): String? {
        val raw = text.trim()
        if (raw.isEmpty()) {
            _message.value = "请先粘贴直播间链接或输入房间号"
            return null
        }
        val parsed: String? = runCatching { RoomIdParser.parse(raw) }.getOrNull()
        if (parsed.isNullOrBlank()) {
            _message.value = "本地无法识别，开工时会自动联网解析；如仍失败请检查链接"
            return null
        }
        return parsed
    }

    private fun report(prefix: String, error: Throwable) {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        _message.value = prefix + "：" + detail
    }
}
