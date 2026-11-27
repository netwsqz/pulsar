package com.example.pulsar.playback

import com.example.pulsar.session.SessionManager

/**
 * 生产实现：从 [SessionManager] 取**当前会话与其 API 的原子快照**（候选 6 / 工单 #22）。
 *
 * 这里只读一次快照，不再每次上报都 `apiFactory.create()`：数据源在登录 / 恢复时
 * 建好一次，安装 ID 也只读一次。未登录（或快照未就绪）时返回 null，
 * [PlaybackReporter] 会静默跳过。
 */
internal class SessionPlaybackReportSource(
    private val sessionManager: SessionManager,
) : PlaybackReportSource {

    override fun channelFor(identity: PlaybackReportIdentity): PlaybackReportChannel? {
        if (identity.trackId.isBlank() || ':' in identity.trackId) return null
        val connection = sessionManager.currentConnection()?.takeIf { it.playbackReportOwner == identity.owner } ?: return null
        return object : PlaybackReportChannel {
            override fun isCurrent(): Boolean = sessionManager.currentConnection() === connection

            override suspend fun reportPlaybackStarted(itemId: String, positionTicks: Long): Result<Unit> =
                connection.api.reportPlaybackStarted(itemId, positionTicks)

            override suspend fun reportPlaybackStopped(itemId: String, positionTicks: Long): Result<Unit> =
                connection.api.reportPlaybackStopped(itemId, positionTicks)
        }
    }
}
