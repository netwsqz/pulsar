package com.example.pulsar.playback

import android.util.Log
import com.example.pulsar.SessionRepository
import com.example.pulsar.data.jellyfin.JellyfinApi
import com.example.pulsar.data.jellyfin.JellyfinApiFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 把播放行为上报给 Jellyfin，让服务端的 `UserData` 真正记录播过什么。
 * 发现页的「最近播放 / 最常播放」（工单 02）依赖这份数据。
 *
 * 三条铁律：
 * 1. **绝不拖慢播放**：上报在传入的协程作用域里异步发，调用方立即返回。
 * 2. **失败静默**：未登录、断网、会话过期都只记日志，不影响播放、不触发 goOffline。
 * 3. **不重复**：同一首歌只报一次开始；没报过开始的歌不报停止。
 *
 * 会话每次现读 [SessionRepository]（不缓存 token）：换账号/退出登录后自然失效。
 * 上报串行执行（[mutex]）：切歌时「停上一首」必须排在「开始下一首」之前。
 */
class PlaybackReporter(
    private val sessions: SessionRepository,
    private val apiFactory: JellyfinApiFactory,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    /** 正在上报/已上报开始的那首歌。null = 当前没有「已开始」的歌。 */
    private var reportedItemId: String? = null

    /** 媒体项开始播放。同一首歌重复调用只上报一次。 */
    fun onTrackStarted(itemId: String, positionMs: Long) {
        if (!isJellyfinItemId(itemId)) return
        scope.launch {
            mutex.withLock {
                if (itemId == reportedItemId) return@withLock
                val session = loadSession() ?: return@withLock
                // 只有确实拿得到会话才记为「已开始」：未登录期间播的歌不该在登录后补发停止。
                reportedItemId = itemId
                request("开始", itemId, positionMs, session) {
                    it.reportPlaybackStarted(session.accessToken, itemId, positionTicks(positionMs))
                }
            }
        }
    }

    /**
     * 当前曲目停止/切歌/服务销毁。没有「已开始」的歌时什么也不做（例如未登录期间
     * 播过的歌），避免给服务端发无主停止请求。
     */
    fun onPlaybackStopped(positionMs: Long) {
        scope.launch {
            mutex.withLock {
                val itemId = reportedItemId ?: return@withLock
                reportedItemId = null
                val session = loadSession() ?: return@withLock
                request("停止", itemId, positionMs, session) {
                    it.reportPlaybackStopped(session.accessToken, itemId, positionTicks(positionMs))
                }
            }
        }
    }

    private suspend fun loadSession(): com.example.pulsar.SavedSession? = try {
        sessions.load()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w(TAG, "读取会话失败，跳过上报（静默）", error)
        null
    }

    private suspend fun request(
        action: String,
        itemId: String,
        positionMs: Long,
        session: com.example.pulsar.SavedSession,
        call: suspend (JellyfinApi) -> Result<Unit>
    ) {
        try {
            val result = call(apiFactory.create(session.serverUrl))
            result.exceptionOrNull()?.let { error ->
                Log.w(TAG, "上报${action}失败（静默）: item=$itemId pos=${positionMs}ms", error)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 任何意外都不许漏到播放链路：这里只是「锦上添花」的数据。
            Log.w(TAG, "上报${action}异常（静默）: item=$itemId", error)
        }
    }

    /** 本地音频（`content://` / `file://`）没有 Jellyfin 曲目 id，不参与上报。 */
    private fun isJellyfinItemId(itemId: String): Boolean = itemId.isNotBlank() && ':' !in itemId

    companion object {
        private const val TAG = "PlaybackReporter"

        /** Jellyfin 时间单位：1 ms = 10,000 ticks。 */
        private const val TICKS_PER_MS = 10_000L

        /** 毫秒换算成 Jellyfin ticks；负位置（异常态）按 0 处理。 */
        fun positionTicks(positionMs: Long): Long = positionMs.coerceAtLeast(0) * TICKS_PER_MS
    }
}
