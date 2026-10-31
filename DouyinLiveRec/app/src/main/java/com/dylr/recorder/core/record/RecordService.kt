package com.dylr.recorder.core.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.dylr.recorder.core.Locator
import com.dylr.recorder.core.RecordPhase
import com.dylr.recorder.core.RecordState
import com.dylr.recorder.core.Recorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 前台服务：保证后台/锁屏时录制或开播监控不被冻结。
 * 通知常驻显示当前房间与已录字节，带“停止”按钮。
 */
class RecordService : Service() {

    /** 通知文案的数据快照：录制状态 + 监控列表。 */
    private class Snapshot(val state: RecordState, val monitors: List<RecordState>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stateJob: Job? = null
    private var foreground = false

    /** 正在收尾：一旦置位就不再发通知，避免留下滑不掉的僵尸常驻通知。 */
    @Volatile
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            val recorder = currentRecorder()
            if (recorder != null) {
                try {
                    recorder.stop()
                } catch (ignored: Throwable) {
                }
            }
            val concrete = recorder as? DefaultRecorder
            if (concrete != null && concrete.hasWork()) {
                // 还有监控（或录制正在收尾）：保持前台，绝不把用户的停止意图反转成“继续录”
                updateNotification(snapshot())
                return START_STICKY
            }
            stopSelfSafely()
            return START_NOT_STICKY
        }
        if (intent == null && !hasActiveWork()) {
            // 被系统重启但没有录制/监控任务，别留一条假通知
            stopSelfSafely()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (!foreground) {
            // 失败路径已 stopSelf，这里必须返回，不能再去注册观察者（否则会补发一条僵尸通知）
            return START_NOT_STICKY
        }
        observeState()
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        stateJob?.cancel()
        stateJob = null
        if (foreground) {
            try {
                stopForeground(true)
            } catch (ignored: Throwable) {
            }
            foreground = false
        }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 进前台失败必须走收尾：否则系统会在约 5s 后以
     * "startForegroundService() did not then call startForeground()" 杀进程。
     */
    private fun startForegroundCompat() {
        val notification = buildNotification(snapshot())
        var ok = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            ok = true
        } catch (ignored: Throwable) {
            ok = false
        }
        foreground = ok
        if (!ok) handleForegroundFailure()
    }

    private fun handleForegroundFailure() {
        val reason = "无法进入前台（可能缺少通知权限或被系统限制）"
        val recorder = currentRecorder()
        val concrete = recorder as? DefaultRecorder
        if (concrete != null) {
            try {
                concrete.reportServiceFailure(reason)
            } catch (ignored: Throwable) {
            }
        }
        if (recorder != null) {
            try {
                // 让下载立刻收尾，别留下一个没人管的连接
                recorder.stop()
            } catch (ignored: Throwable) {
            }
        }
        stopSelfSafely()
    }

    private fun observeState() {
        if (stopping || stateJob != null) return
        val recorder = currentRecorder() ?: return
        stateJob = scope.launch {
            combine(recorder.state, recorder.monitors) { state, monitors ->
                Snapshot(state, monitors)
            }.collect { snap ->
                updateNotification(snap)
            }
        }
    }

    private fun updateNotification(snapshot: Snapshot) {
        if (stopping) return
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(snapshot))
        } catch (ignored: Throwable) {
        }
    }

    private fun buildNotification(snapshot: Snapshot): Notification {
        ensureChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        builder.setContentTitle(titleOf(snapshot))
            .setContentText(textOf(snapshot))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        val content = contentIntent()
        if (content != null) builder.setContentIntent(content)
        builder.addAction(0, "停止", stopIntent())
        return builder.build()
    }

    private fun contentIntent(): PendingIntent? {
        val launch = try {
            packageManager.getLaunchIntentForPackage(packageName)
        } catch (ignored: Throwable) {
            null
        } ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            PendingIntent.getActivity(this, REQUEST_CONTENT, launch, pendingFlags())
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun stopIntent(): PendingIntent {
        val intent = Intent(this, RecordService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(this, REQUEST_STOP, intent, pendingFlags())
    }

    private fun pendingFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(CHANNEL_ID, "录制", NotificationManager.IMPORTANCE_LOW)
            channel.description = "抖音直播后台录制与开播监控"
            channel.setShowBadge(false)
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            manager.createNotificationChannel(channel)
        } catch (ignored: Throwable) {
        }
    }

    private fun snapshot(): Snapshot {
        val recorder = currentRecorder() ?: return Snapshot(idleState(), emptyList())
        return try {
            Snapshot(recorder.state.value, recorder.monitors.value)
        } catch (ignored: Throwable) {
            Snapshot(idleState(), emptyList())
        }
    }

    private fun idleState(): RecordState = RecordState(roomId = "", phase = RecordPhase.IDLE)

    private fun currentRecorder(): Recorder? = try {
        Locator.recorder
    } catch (ignored: Throwable) {
        null
    }

    private fun hasActiveWork(): Boolean {
        val phase = readPhase()
        if (phase == RecordPhase.RECORDING ||
            phase == RecordPhase.RESOLVING ||
            phase == RecordPhase.FINALIZING ||
            phase == RecordPhase.MONITORING
        ) {
            return true
        }
        return snapshot().monitors.isNotEmpty()
    }

    private fun readPhase(): RecordPhase = try {
        snapshot().state.phase
    } catch (ignored: Throwable) {
        RecordPhase.IDLE
    }

    private fun stopSelfSafely() {
        stopping = true
        stateJob?.cancel()
        stateJob = null
        if (foreground) {
            try {
                stopForeground(true)
            } catch (ignored: Throwable) {
            }
            foreground = false
        }
        stopSelf()
    }

    private fun titleOf(snapshot: Snapshot): String {
        val state = snapshot.state
        return when (state.phase) {
            RecordPhase.RECORDING -> "正在录制"
            RecordPhase.RESOLVING -> "正在解析直播地址"
            RecordPhase.FINALIZING -> "正在保存录制文件"
            RecordPhase.MONITORING -> "开播监控中"
            RecordPhase.COMPLETED -> "录制完成"
            RecordPhase.ERROR -> "录制异常"
            RecordPhase.IDLE -> if (snapshot.monitors.isEmpty()) "空闲" else "开播监控中"
        }
    }

    private fun textOf(snapshot: Snapshot): String {
        val state = snapshot.state
        if (state.phase == RecordPhase.RECORDING || state.phase == RecordPhase.FINALIZING) {
            val room = if (state.roomId.isEmpty()) "未绑定房间" else "房间 " + state.roomId
            val title = if (state.title.isEmpty()) "" else " · " + state.title
            return room + title + " · 已录 " + humanSize(state.bytesWritten)
        }
        if (state.phase == RecordPhase.RESOLVING) {
            return "房间 " + state.roomId + " · 正在解析地址"
        }
        if (state.phase == RecordPhase.ERROR) {
            val detail = state.message
            val base = if (detail.isNullOrEmpty()) "录制异常" else detail
            val watching = snapshot.monitors.firstOrNull()
            return if (watching == null) base else base + "（仍在监控 " + watching.roomId + "）"
        }
        val first = snapshot.monitors.firstOrNull()
        if (first != null) {
            val live = if (first.message.isNullOrEmpty()) "等待开播" else first.message
            val extra = if (snapshot.monitors.size > 1) " 等 " + snapshot.monitors.size + " 个房间" else ""
            return "监控中：" + first.roomId + "（" + live + "）" + extra
        }
        return if (state.message.isNullOrEmpty()) "等待开始" else state.message
    }

    private fun humanSize(bytes: Long): String =
        String.format(Locale.US, "%.1f MB", bytes.toDouble() / 1048576.0)

    companion object {
        const val ACTION_START = "com.dylr.recorder.action.RECORD_START"
        const val ACTION_STOP = "com.dylr.recorder.action.RECORD_STOP"

        private const val CHANNEL_ID = "rec"
        private const val NOTIFICATION_ID = 20100
        private const val REQUEST_CONTENT = 1001
        private const val REQUEST_STOP = 1002

        fun start(context: Context) {
            val intent = Intent(context, RecordService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (error: Throwable) {
                reportStartBlocked(error)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, RecordService::class.java))
            } catch (ignored: Throwable) {
            }
        }

        /**
         * 后台启动前台服务被系统拒绝（API 31+ ForegroundServiceStartNotAllowedException 等）
         * 时不能静默吞掉：把原因写进录制状态，让界面能提示用户。
         */
        private fun reportStartBlocked(error: Throwable) {
            val name = error.javaClass.simpleName
            val detail = error.message
            val reason = when {
                name != null && name.contains("ForegroundServiceStartNotAllowed") ->
                    "后台无法启动前台服务，请保持界面在前台"
                !detail.isNullOrEmpty() -> detail
                else -> "前台服务启动失败"
            }
            val recorder = (try {
                Locator.recorder
            } catch (ignored: Throwable) {
                null
            }) as? DefaultRecorder
            if (recorder != null) {
                try {
                    recorder.reportServiceFailure(reason)
                } catch (ignored: Throwable) {
                }
            }
        }
    }
}
