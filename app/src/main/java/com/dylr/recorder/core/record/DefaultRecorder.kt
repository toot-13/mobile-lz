package com.dylr.recorder.core.record

import android.content.Context
import com.dylr.recorder.core.LiveSource
import com.dylr.recorder.core.Locator
import com.dylr.recorder.core.RecordConfig
import com.dylr.recorder.core.RecordItem
import com.dylr.recorder.core.RecordPhase
import com.dylr.recorder.core.RecordState
import com.dylr.recorder.core.Recorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 录制总控：状态机 + 开播监控 + 入库。
 * start() 只负责“解析地址并起任务”，真正的下载/收尾跑在本类自带的 scope 上，
 * 这样调用方（ViewModel/UI）被销毁也不影响后台录制。
 */
class DefaultRecorder(context: Context) : Recorder {

    private val appContext: Context = context.applicationContext
    private val store = HistoryStore(appContext)
    private val busy = AtomicBoolean(false)
    private val monitorJobs = ConcurrentHashMap<String, Job>()

    private val _state = MutableStateFlow(RecordState(roomId = "", phase = RecordPhase.IDLE))
    private val _monitors = MutableStateFlow<List<RecordState>>(emptyList())
    private val _history = MutableStateFlow<List<RecordItem>>(emptyList())

    override val state: StateFlow<RecordState> = _state
    override val monitors: StateFlow<List<RecordState>> = _monitors
    override val history: StateFlow<List<RecordItem>> = _history

    /** 兜底：任何漏网的协程异常都收敛成 ERROR 状态，绝不让进程崩。 */
    private val crashGuard = CoroutineExceptionHandler { _, exception ->
        _state.update { current -> current.copy(phase = RecordPhase.ERROR, message = describe(exception)) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)

    @Volatile
    private var downloader: StreamDownloader? = null

    @Volatile
    private var stopRequested = false

    /** 前台服务起不来这类致命原因，收尾时不会被 COMPLETED 覆盖。 */
    @Volatile
    private var fatalMessage: String? = null

    /** 本次会话第一条错误信息，没录到任何数据时作为最终 message 的具体原因。 */
    @Volatile
    private var lastError: String? = null

    init {
        _history.value = store.load()
        INSTANCE = this
    }

    override suspend fun start(config: RecordConfig) {
        if (busy.get()) {
            // 先停旧的，等它收尾（有上限，不无限等）
            stop()
            val deadline = System.currentTimeMillis() + STOP_WAIT_MS
            while (busy.get() && System.currentTimeMillis() < deadline) {
                delay(100L)
            }
        }
        if (!busy.compareAndSet(false, true)) {
            _state.value = RecordState(
                roomId = config.roomId,
                phase = RecordPhase.ERROR,
                title = config.title ?: "",
                message = "上一个录制任务尚未结束"
            )
            return
        }
        stopRequested = false
        fatalMessage = null
        lastError = null
        var handedOff = false
        try {
            val roomId = config.roomId
            var title = config.title ?: ""
            update(RecordState(roomId, RecordPhase.RESOLVING, title = title))

            val source = withContext(Dispatchers.IO) { Locator.resolver.resolve(roomId) }
            val resolvedTitle = source.title
            if (!resolvedTitle.isNullOrEmpty()) title = resolvedTitle

            update(
                RecordState(
                    roomId,
                    RecordPhase.RECORDING,
                    title = title,
                    startedAt = System.currentTimeMillis()
                )
            )
            RecordService.start(appContext)

            val finalTitle = title
            scope.launch { runSession(config, source, roomId, finalTitle) }
            handedOff = true
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            update(RecordState(config.roomId, RecordPhase.ERROR, title = config.title ?: "", message = describe(t)))
            throw t
        } finally {
            if (!handedOff) busy.set(false)
        }
    }

    override fun stop() {
        stopRequested = true
        val active = downloader
        if (active != null) active.stop()
    }

    override fun startMonitor(config: RecordConfig) {
        val roomId = config.roomId
        if (monitorJobs.containsKey(roomId)) return
        setMonitor(RecordState(roomId = roomId, phase = RecordPhase.MONITORING, title = config.title ?: ""))
        val job = scope.launch {
            while (isActive) {
                try {
                    val status = Locator.resolver.probe(roomId)
                    setMonitor(
                        RecordState(
                            roomId = roomId,
                            phase = RecordPhase.MONITORING,
                            title = status.title ?: config.title ?: "",
                            message = if (status.isLive) "直播中" else "未开播"
                        )
                    )
                    if (status.isLive && !busy.get()) {
                        // 挂在总 scope 上：取消监控不会连带取消录制
                        scope.launch {
                            try {
                                start(config.copy(autoMonitor = true))
                            } catch (ignored: Throwable) {
                                // 失败已由 start() 写进 state
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    setMonitor(
                        RecordState(
                            roomId = roomId,
                            phase = RecordPhase.MONITORING,
                            title = config.title ?: "",
                            message = "检测失败：" + describe(t)
                        )
                    )
                }
                delay(MONITOR_INTERVAL_MS)
            }
        }
        monitorJobs[roomId] = job
        // 监控要能在后台/锁屏继续轮询，必须有前台服务兜着
        RecordService.start(appContext)
    }

    override fun stopMonitor(roomId: String) {
        val job = monitorJobs.remove(roomId)
        if (job != null) job.cancel()
        _monitors.update { list -> list.filterNot { it.roomId == roomId } }
        if (!hasWork()) RecordService.stop(appContext)
    }

    override fun deleteHistory(id: String) {
        // 非 suspend 接口：立刻返回，读写磁盘放到 IO scope
        scope.launch { deleteHistoryNow(id) }
    }

    override fun refreshHistory() {
        scope.launch { loadHistoryNow() }
    }

    /** 前台服务起不来时由 RecordService 回调：记为 ERROR 并终止本次录制。 */
    internal fun reportServiceFailure(message: String) {
        val text = "前台服务启动失败：" + message
        fatalMessage = text
        _state.update { current -> current.copy(phase = RecordPhase.ERROR, message = text) }
    }

    internal fun hasWork(): Boolean =
        busy.get() || _monitors.value.isNotEmpty() || monitorJobs.isNotEmpty()

    private fun deleteHistoryNow(id: String) {
        val target = _history.value.firstOrNull { it.id == id }
        if (target != null) {
            try {
                File(target.path).delete()
            } catch (ignored: Throwable) {
            }
        }
        store.delete(id)
        loadHistoryNow()
    }

    private fun loadHistoryNow() {
        _history.value = store.load()
    }

    /** 已解析到地址之后的完整录制流程：下载 -> 切片 -> 重封装 -> 入库。 */
    private suspend fun runSession(
        config: RecordConfig,
        source: LiveSource,
        roomId: String,
        title: String
    ) {
        try {
            val paths = ArrayList<String>()
            val active = StreamDownloader(appContext)
            downloader = active
            if (stopRequested) {
                // start() 交出任务后立刻被 stop()：别让下载再启动
                active.stop()
            }
            active.download(source, config) { event -> onDownloadEvent(event, roomId, title, paths) }

            update(_state.value.copy(phase = RecordPhase.FINALIZING))
            finish(roomId, title, config, paths)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            update(RecordState(roomId, RecordPhase.ERROR, title = title, message = describe(t)))
        } finally {
            downloader = null
            busy.set(false)
            // 还有监控项就留着前台服务，否则「停录制」会连带掐掉后台监控
            if (!hasWork()) RecordService.stop(appContext)
        }
    }

    private fun onDownloadEvent(
        event: DownloadEvent,
        roomId: String,
        title: String,
        paths: MutableList<String>
    ) {
        when (event) {
            is DownloadEvent.Bytes -> _state.update { current ->
                current.copy(roomId = roomId, title = title, bytesWritten = event.totalBytes)
            }
            is DownloadEvent.DataSegment -> {
                paths.add(event.path)
                _state.update { current ->
                    current.copy(
                        roomId = roomId,
                        title = title,
                        phase = RecordPhase.RECORDING,
                        segmentIndex = event.index,
                        lastFile = event.path
                    )
                }
            }
            is DownloadEvent.Error -> {
                if (lastError == null) {
                    lastError = event.message
                }
                if (event.fatal && fatalMessage == null) {
                    // 终止性错误（例如无法创建文件）要留到收尾时显示，别被最终状态覆盖
                    fatalMessage = event.message
                }
                _state.update { current ->
                    current.copy(roomId = roomId, title = title, message = event.message)
                }
            }
            is DownloadEvent.Stopped -> _state.update { current ->
                current.copy(
                    roomId = roomId,
                    title = title,
                    message = if (stopRequested) "已手动停止" else current.message
                )
            }
        }
    }

    private fun finish(roomId: String, title: String, config: RecordConfig, paths: List<String>) {
        val notes = linkedSetOf<String>()
        var last: RecordItem? = null
        var landed = 0
        for (path in paths) {
            val item = finalizeOne(roomId, title, config, path, notes)
            if (item != null) {
                last = item
                if (item.success) landed++
            }
        }
        loadHistoryNow()
        val saved = last
        val noteText = if (notes.isEmpty()) null else notes.joinToString("；")
        val fatal = fatalMessage
        when {
            fatal != null -> update(
                RecordState(
                    roomId,
                    RecordPhase.ERROR,
                    title = title,
                    message = if (noteText == null) fatal else fatal + "；" + noteText,
                    lastFile = if (saved == null) null else saved.path
                )
            )
            saved == null || landed == 0 -> update(
                RecordState(
                    roomId,
                    RecordPhase.ERROR,
                    title = title,
                    message = failureMessage(paths, noteText),
                    lastFile = if (saved == null) null else saved.path
                )
            )
            else -> update(
                RecordState(
                    roomId,
                    RecordPhase.COMPLETED,
                    title = title,
                    message = noteText,
                    lastFile = saved.path
                )
            )
        }
    }

    private fun failureMessage(paths: List<String>, notes: String?): String {
        val base = when {
            paths.isNotEmpty() -> "录制文件未能落盘"
            stopRequested -> "已停止，未产生文件"
            else -> "未录制到任何数据"
        }
        val builder = StringBuilder(base)
        val detail = lastError
        if (!detail.isNullOrEmpty()) builder.append("：").append(detail)
        if (notes != null) builder.append("；").append(notes)
        return builder.toString()
    }

    /**
     * 单个切片：无损重封装 MP4 -> 入相册 -> 写历史。
     * 只有转封装确实产出了带视频轨的 mp4 才替换并删除原始文件；
     * 否则保留原始 .flv/.ts（success 仍为 true，因为文件本身有效），并把原因写进 notes。
     */
    private fun finalizeOne(
        roomId: String,
        title: String,
        config: RecordConfig,
        rawPath: String,
        notes: MutableSet<String>
    ): RecordItem? {
        val raw = File(rawPath)
        if (!raw.isFile || raw.length() <= 0L) {
            if (raw.isFile) raw.delete()
            return null
        }
        val mp4 = File(raw.parentFile, raw.nameWithoutExtension + ".mp4")
        val remuxResult = Mp4Remuxer.remux(raw.absolutePath, mp4.absolutePath)
        val remuxed = remuxResult.ok
        if (remuxed) {
            val info = remuxResult.note
            if (!info.isNullOrEmpty()) notes.add(info)
        } else {
            notes.add(keepOriginalNote(raw, remuxResult.note))
        }
        val target = if (remuxed) mp4 else raw
        if (!target.isFile || target.length() <= 0L) {
            notes.add("录制文件写入失败")
            return null
        }
        if (config.saveToGallery) {
            val uri = MediaStoreSaver.saveToGallery(appContext, target, target.name)
            if (uri == null) notes.add("已保存到 App 目录，但未写入相册")
        }
        val item = RecordItem(
            id = UUID.randomUUID().toString(),
            roomId = roomId,
            title = if (title.isEmpty()) target.name else title,
            path = target.absolutePath,
            sizeBytes = target.length(),
            createdAt = System.currentTimeMillis(),
            success = target.isFile && target.length() > 0L
        )
        store.add(item)
        if (remuxed && raw.absolutePath != target.absolutePath && raw.exists()) {
            // 源片已无损转成 mp4，删掉临时切片省空间
            raw.delete()
        }
        return item
    }

    /** 转封装失败时给用户一条自解释的提示：文件在哪、为什么、怎么播。 */
    private fun keepOriginalNote(raw: File, reason: String?): String {
        val ext = raw.extension.ifEmpty { "flv" }
        val why = if (reason.isNullOrEmpty()) "系统无法转封装为 MP4" else reason
        return "已保留原始 ." + ext + "（" + why + "，可复制到电脑或用 VLC 等播放器播放）"
    }

    private fun update(state: RecordState) {
        _state.value = state
    }

    private fun setMonitor(state: RecordState) {
        _monitors.update { list ->
            val index = list.indexOfFirst { it.roomId == state.roomId }
            if (index < 0) {
                list + state
            } else {
                val copy = list.toMutableList()
                copy[index] = state
                copy
            }
        }
    }

    private fun describe(t: Throwable): String {
        val message = t.message
        if (!message.isNullOrEmpty()) return message
        val name = t.javaClass.simpleName
        return if (name.isNullOrEmpty()) "录制异常" else name
    }

    companion object {
        private const val MONITOR_INTERVAL_MS = 15_000L
        private const val STOP_WAIT_MS = 30_000L

        @Volatile
        private var INSTANCE: DefaultRecorder? = null

        /** Service / UI 取同一个实例；Locator 走的构造器也会把自身写进 INSTANCE。 */
        fun get(context: Context): DefaultRecorder {
            val existing = INSTANCE
            if (existing != null) return existing
            synchronized(DefaultRecorder::class.java) {
                val again = INSTANCE
                if (again != null) return again
                val created = DefaultRecorder(context.applicationContext)
                INSTANCE = created
                return created
            }
        }
    }
}
