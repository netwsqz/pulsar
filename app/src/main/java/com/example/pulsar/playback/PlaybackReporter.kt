package com.example.pulsar.playback

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/** 按发出事件的媒体项选通道，而不是按当前浏览的曲库选。 */
internal fun interface PlaybackReportSource {
    fun channelFor(identity: PlaybackReportIdentity): PlaybackReportChannel?
}

/** 通道绑定开播时的来源；归属更换后不再发送排队中的旧事件。 */
interface PlaybackReportChannel {
    fun isCurrent(): Boolean
    suspend fun reportPlaybackStarted(itemId: String, positionTicks: Long): Result<Unit>
    suspend fun reportPlaybackStopped(itemId: String, positionTicks: Long): Result<Unit>
}

/** 发现页依赖这份数据。上报串行、异步、失败静默，不触碰曲库连通性或播放器。 */
internal class PlaybackReporter(
    private val reports: PlaybackReportSource,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var reported: ReportedPlayback? = null

    fun onTrackStarted(itemId: String, positionMs: Long, identity: PlaybackReportIdentity, queueEntry: String? = null) {
        // 收到播放器事件时立即捕获原通道；后台排队期间换来源，也不重新绑定凭据。
        val channel = try {
            reports.channelFor(identity)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Log.w(TAG, "读取播放上报通道失败（静默）")
            null
        } ?: return
        enqueue {
            if (reported?.let { it.itemId == itemId && it.queueEntry == queueEntry && it.channel.isCurrent() } == true ||
                !channel.isCurrent()
            ) return@enqueue Result.success(Unit)
            // 「已开始」表示已发起上报：响应丢失时服务端可能已经收到，仍为同一次播放收尾。
            reported = ReportedPlayback(itemId, queueEntry, identity.trackId, channel)
            channel.reportPlaybackStarted(identity.trackId, positionTicks(positionMs))
        }
    }

    fun onPlaybackStopped(positionMs: Long, itemId: String? = null, queueEntry: String? = null) {
        enqueue {
            val previous = reported ?: return@enqueue Result.success(Unit)
            if (itemId != null && (previous.itemId != itemId || previous.queueEntry != queueEntry)) {
                return@enqueue Result.success(Unit)
            }
            reported = null
            if (previous.channel.isCurrent()) {
                previous.channel.reportPlaybackStopped(previous.sourceItemId, positionTicks(positionMs))
            } else Result.success(Unit)
        }
    }

    private fun enqueue(report: suspend () -> Result<Unit>) {
        // 先按播放器回调顺序排进锁，再让出线程；请求在上报作用域执行，不占播放线程。
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                yield()
                try {
                    report().getOrThrow()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    Log.w(TAG, "播放上报失败（静默）")
                }
            }
        }
    }

    private data class ReportedPlayback(
        val itemId: String, val queueEntry: String?, val sourceItemId: String, val channel: PlaybackReportChannel,
    )

    companion object {
        private const val TAG = "PlaybackReporter"

        /** Jellyfin 时间单位：1 ms = 10,000 ticks。 */
        private const val TICKS_PER_MS = 10_000L

        /** 毫秒换算成 Jellyfin ticks；负位置（异常态）按 0 处理。 */
        fun positionTicks(positionMs: Long): Long = positionMs.coerceAtLeast(0) * TICKS_PER_MS
    }
}
