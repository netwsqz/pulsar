package com.example.pulsar.playback

import com.example.pulsar.data.jellyfin.JellyfinError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReporterTest {
    @Test
    fun `start reports item id and position ticks`() = runTest {
        val (reporter, channel) = createReporter()

        reporter.onTrackStarted("track-1", positionMs = 0)
        advanceUntilIdle()

        assertEquals(listOf(Report("/Sessions/Playing", "track-1", 0L)), channel.reports)
    }

    @Test
    fun `same track does not report start twice`() = runTest {
        val (reporter, channel) = createReporter()

        reporter.onTrackStarted("track-1", positionMs = 0)
        reporter.onTrackStarted("track-1", positionMs = 0)
        advanceUntilIdle()

        assertEquals(1, channel.reports.size)
    }

    @Test
    fun `重建队列再次播放同一首时 上次播放先结束再记一次开始`() = runTest {
        val (reporter, channel) = createReporter()
        val identity = PlaybackReportIdentity("测试会话", "track-1")
        reporter.onTrackStarted("track-1", 0, identity, queueEntry = "第一次点播")
        reporter.onTrackStarted("track-1", 400, identity, queueEntry = "第一次点播")
        reporter.onPlaybackStopped(1_000, "track-1", queueEntry = "第一次点播")
        reporter.onTrackStarted("track-1", 0, identity, queueEntry = "再次点播")
        reporter.onPlaybackStopped(2_000, "track-1", queueEntry = "第一次点播")
        reporter.onPlaybackStopped(3_000, "track-1", queueEntry = "再次点播")
        advanceUntilIdle()
        assertEquals(listOf(
            Report("/Sessions/Playing", "track-1", 0L),
            Report("/Sessions/Playing/Stopped", "track-1", 10_000_000L),
            Report("/Sessions/Playing", "track-1", 0L),
            Report("/Sessions/Playing/Stopped", "track-1", 30_000_000L),
        ), channel.reports)
    }

    @Test
    fun `track change reports stop for previous track then start for next`() = runTest {
        val (reporter, channel) = createReporter()

        reporter.onTrackStarted("track-1", positionMs = 0)
        reporter.onPlaybackStopped(positionMs = 90_000)
        reporter.onTrackStarted("track-2", positionMs = 0)
        advanceUntilIdle()

        assertEquals(
            listOf(
                Report("/Sessions/Playing", "track-1", 0L),
                // 停止上报带实际播放位置：90 s = 900,000,000 ticks
                Report("/Sessions/Playing/Stopped", "track-1", 900_000_000L),
                // 切歌后新的开始上报位置归零（旧曲的位置不能带过去）
                Report("/Sessions/Playing", "track-2", 0L),
            ),
            channel.reports
        )
    }

    @Test
    fun `切换会话后 旧曲目的停止不会写进新会话`() = runTest {
        val original = FakeChannel()
        val replacement = FakeChannel()
        val reports = FakeReports(original)
        val reporter = PlaybackReporter(reports, this)
        reporter.onTrackStarted("track-1", positionMs = 0)
        advanceUntilIdle()

        reports.channel = replacement
        reporter.onPlaybackStopped(positionMs = 1_000)
        reporter.onTrackStarted("track-2", positionMs = 0)
        advanceUntilIdle()

        assertEquals(listOf(Report("/Sessions/Playing", "track-2", 0L)), replacement.reports)
    }

    @Test
    fun `stop without a reported start sends nothing`() = runTest {
        val (reporter, channel) = createReporter()

        reporter.onPlaybackStopped(positionMs = 5_000)
        advanceUntilIdle()

        assertTrue(channel.reports.isEmpty())
    }

    @Test
    fun `local audio items are never reported`() = runTest {
        val (reporter, channel) = createReporter()

        reporter.onTrackStarted("content://com.android.providers.media.documents/document/42", positionMs = 0)
        reporter.onTrackStarted("file:///storage/emulated/0/a.flac", positionMs = 0)
        advanceUntilIdle()

        assertTrue(channel.reports.isEmpty())
    }

    @Test
    fun `电脑曲库的歌不向 Jellyfin 上报`() = runTest {
        val (reporter, channel) = createReporter()

        reporter.onTrackStarted("pc:${"a".repeat(64)}", positionMs = 0)
        reporter.onPlaybackStopped(positionMs = 5_000)
        advanceUntilIdle()

        assertTrue(channel.reports.isEmpty())
    }

    @Test
    fun `stop is sent before the next start`() = runTest {
        val (reporter, channel) = createReporter()
        channel.gate = CompletableDeferred()

        reporter.onTrackStarted("track-1", positionMs = 0)
        reporter.onPlaybackStopped(positionMs = 1_000)
        reporter.onTrackStarted("track-2", positionMs = 0)
        channel.gate?.complete(Unit)
        advanceUntilIdle()

        // 串行执行：切歌时「停上一首」一定排在「开始下一首」之前
        assertEquals(
            listOf(
                Report("/Sessions/Playing", "track-1", 0L),
                Report("/Sessions/Playing/Stopped", "track-1", 10_000_000L),
                Report("/Sessions/Playing", "track-2", 0L),
            ),
            channel.reports
        )
    }

    @Test
    fun `track played while logged out is not stopped after login`() = runTest {
        val reports = FakeReports(channel = null)
        val reporter = PlaybackReporter(reports, this)

        reporter.onTrackStarted("track-1", positionMs = 0)
        advanceUntilIdle()
        // 登录后再切歌：不能补发一个没有对应开始的停止请求
        val channel = FakeChannel()
        reports.channel = channel
        reporter.onPlaybackStopped(positionMs = 1_000)
        advanceUntilIdle()

        assertTrue(channel.reports.isEmpty())
    }

    @Test
    fun `missing session skips reporting silently`() = runTest {
        val reports = FakeReports(channel = null)
        val reporter = PlaybackReporter(reports, this)

        reporter.onTrackStarted("track-1", positionMs = 0)
        reporter.onPlaybackStopped(positionMs = 1_000)
        advanceUntilIdle()

        // 未登录时既不上报，也不留下「已开始」的假象（登录后不会补发停止）。
        reports.channel = FakeChannel()
        reporter.onPlaybackStopped(positionMs = 2_000)
        advanceUntilIdle()
        assertTrue(reports.channel!!.reports.isEmpty())
    }

    @Test
    fun `network failure is swallowed and does not break later reports`() = runTest {
        val (reporter, channel) = createReporter()
        channel.failing = true

        reporter.onTrackStarted("track-1", positionMs = 0)
        advanceUntilIdle()

        channel.failing = false
        reporter.onPlaybackStopped(positionMs = 1_000)
        advanceUntilIdle()

        // 开始请求失败了，但停止请求照常发出（调用方一次异常都没收到）
        assertEquals(
            listOf(Report("/Sessions/Playing/Stopped", "track-1", 10_000_000L)),
            channel.reports
        )
    }

    @Test
    fun `position ticks use ten thousand ticks per millisecond`() {
        assertEquals(0L, PlaybackReporter.positionTicks(0))
        assertEquals(10_000L, PlaybackReporter.positionTicks(1))
        assertEquals(2_145_000_000L, PlaybackReporter.positionTicks(214_500))
        // 负位置（播放器异常态）按 0 处理，别给服务端发负 ticks
        assertEquals(0L, PlaybackReporter.positionTicks(-5))
    }

    private fun PlaybackReporter.onTrackStarted(itemId: String, positionMs: Long) =
        onTrackStarted(itemId, positionMs, PlaybackReportIdentity("测试会话", itemId))

    private data class Report(val path: String, val itemId: String, val positionTicks: Long)

    /**
     * 上报 Fake（#22）：只有 reporter 真正用的 3 个方法——一个「当前通道」入口 +
     * 两条上报。`channel = null` 等价于未登录；测试中途换掉它即可模拟登录 / 退出。
     */
    private class FakeReports(
        var channel: FakeChannel? = FakeChannel(),
    ) : PlaybackReportSource {
        override fun channelFor(identity: PlaybackReportIdentity): PlaybackReportChannel? {
            if (identity.trackId.isBlank() || ':' in identity.trackId) return null
            val captured = channel ?: return null
            return object : PlaybackReportChannel by captured {
                override fun isCurrent() = channel === captured
            }
        }
    }

    private class FakeChannel : PlaybackReportChannel {
        override fun isCurrent() = true

        val reports = mutableListOf<Report>()
        var failing = false

        /** 挂住第一次上报，用来验证上报是串行排队的。 */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun reportPlaybackStarted(itemId: String, positionTicks: Long) =
            record("/Sessions/Playing", itemId, positionTicks)

        override suspend fun reportPlaybackStopped(itemId: String, positionTicks: Long) =
            record("/Sessions/Playing/Stopped", itemId, positionTicks)

        private suspend fun record(path: String, itemId: String, positionTicks: Long): Result<Unit> {
            gate?.await()
            gate = null
            if (failing) return Result.failure(JellyfinError.Network(IOException("offline")))
            reports += Report(path, itemId, positionTicks)
            return Result.success(Unit)
        }
    }

    private fun TestScope.createReporter(): Pair<PlaybackReporter, FakeChannel> {
        val reports = FakeReports()
        return PlaybackReporter(reports, this) to checkNotNull(reports.channel)
    }
}
