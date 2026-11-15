package com.example.pulsar.playback

import com.example.pulsar.session.SessionManager

/**
 * 生产实现：从 [SessionManager] 取**当前会话与其 API 的原子快照**（候选 6 / 工单 #22）。
 *
 * 这里只读一次快照，不再每次上报都 `apiFactory.create()`：数据源在登录 / 恢复时
 * 建好一次，安装 ID 也只读一次。未登录（或快照未就绪）时返回 null，
 * [PlaybackReporter] 会静默跳过。
 */
class SessionPlaybackReportSource(
    private val sessionManager: SessionManager,
) : PlaybackReportSource {

    override fun currentChannel(): PlaybackReportChannel? {
        // 快照一次取出，不会拼出「旧令牌 + 新 API」；会话作用域 API 自带凭据。
        val api = sessionManager.currentConnection()?.api ?: return null
        return object : PlaybackReportChannel {
            override suspend fun reportPlaybackStarted(itemId: String, positionTicks: Long): Result<Unit> =
                api.reportPlaybackStarted(itemId, positionTicks)

            override suspend fun reportPlaybackStopped(itemId: String, positionTicks: Long): Result<Unit> =
                api.reportPlaybackStopped(itemId, positionTicks)
        }
    }
}
