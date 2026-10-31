package com.dylr.recorder.core

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

data class LiveSource(
    val roomId: String,
    val url: String,
    val type: StreamType,
    val title: String? = null,
    val anchor: String? = null
)

enum class StreamType { FLV, HLS, UNKNOWN }

data class RoomStatus(
    val roomId: String,
    val isLive: Boolean,
    val title: String? = null,
    val anchor: String? = null,
    val cover: String? = null
)

data class RecordConfig(
    val roomId: String,
    val title: String? = null,
    val autoMonitor: Boolean = false,
    val segmentMinutes: Int = 0,
    val segmentMaxMb: Int = 0,
    val saveToGallery: Boolean = true
)

enum class RecordPhase { IDLE, MONITORING, RESOLVING, RECORDING, FINALIZING, COMPLETED, ERROR }

data class RecordState(
    val roomId: String,
    val phase: RecordPhase,
    val title: String = "",
    val segmentIndex: Int = 0,
    val bytesWritten: Long = 0L,
    val startedAt: Long = 0L,
    val lastFile: String? = null,
    val message: String? = null
)

data class RecordItem(
    val id: String,
    val roomId: String,
    val title: String,
    val path: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val success: Boolean
)

interface LiveResolver {
    suspend fun resolve(input: String): LiveSource
    suspend fun probe(roomId: String): RoomStatus
}

interface Recorder {
    val state: StateFlow<RecordState>
    val monitors: StateFlow<List<RecordState>>
    val history: StateFlow<List<RecordItem>>
    suspend fun start(config: RecordConfig)
    fun stop()
    fun startMonitor(config: RecordConfig)
    fun stopMonitor(roomId: String)
    fun deleteHistory(id: String)
    fun refreshHistory()
}

object Locator {
    lateinit var resolver: LiveResolver
    lateinit var recorder: Recorder
    fun init(context: Context) {
        resolver = com.dylr.recorder.core.resolve.DefaultLiveResolver(context.applicationContext)
        recorder = com.dylr.recorder.core.record.DefaultRecorder(context.applicationContext)
    }
}
