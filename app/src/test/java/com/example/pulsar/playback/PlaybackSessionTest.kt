package com.example.pulsar.playback

import androidx.media3.common.Player
import androidx.media3.session.SessionResult
import com.example.pulsar.QueueTrack
import com.example.pulsar.domain.model.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackSession] 的行为规格：连接、监听、500ms 轮询、自定义命令。
 *
 * 这一层只和 [PlaybackRemote] 打交道，所以全部用 [FakePlaybackRemote] 驱动；真实的
 * `MediaController` 适配器不在单测覆盖范围内（见 D4：不写 MediaController 集成测试）。
 *
 * 轮询是周期循环，**不能 `advanceUntilIdle()`**——虚拟时间会一直推进，测试进程会挂死。
 * 所有轮询断言都用 [pollTick] 的有界推进。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionTest {

    private val remote = FakePlaybackRemote()
    private val factory = FakeTrackMediaItemFactory()
    private val gate = FakePlaybackGate()

    private fun TestScope.newSession(): PlaybackSession =
        PlaybackSession(remote, factory, gate, backgroundScope)

    private fun TestScope.pollTick() {
        advanceTimeBy(PLAYBACK_POLL_INTERVAL_MS)
        runCurrent()
    }

    /** 模拟「已连上播放服务」的快照；连接事件本身由 [FakePlaybackRemote.connect] 触发。 */
    private fun connected(
        title: String? = "晴天",
        playbackState: Int = Player.STATE_READY,
        isPlaying: Boolean = false
    ) = RemoteSnapshot(
        isConnected = true,
        currentTitle = title,
        playbackState = playbackState,
        isPlaying = isPlaying
    )

    private fun queueTrack(id: String, current: Boolean = false) = QueueTrack(
        id = id,
        title = "歌曲-$id",
        artist = "歌手",
        album = "专辑",
        imageTag = null,
        isCurrent = current
    )

    private fun track(id: String) = Track(
        id = id,
        title = "歌曲-$id",
        artist = "歌手",
        album = "专辑",
        durationMs = 0L,
        imageTag = null
    )

    private fun TestScope.newSession(gate: PlaybackGate) =
        PlaybackSession(remote, factory, gate, backgroundScope)

    private fun TestScope.collectEvents(session: PlaybackSession): MutableList<PlaybackEvent> {
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch { session.events.collect { events += it } }
        runCurrent()
        return events
    }

    // ── 连接 ────────────────────────────────────────────────────────────────

    @Test
    fun `连接前状态是正在连接播放服务`() = runTest {
        val session = newSession()

        assertFalse(session.state.value.isConnected)
        assertEquals("正在连接播放服务…", session.state.value.status)
    }

    @Test
    fun `连接成功后标记已连接并显示当前曲目与暂停状态`() = runTest {
        remote.snapshot = connected()
        val session = newSession()

        remote.connect()

        assertTrue(session.state.value.isConnected)
        assertEquals("晴天 · 已暂停", session.state.value.status)
    }

    @Test
    fun `连接成功后立即同步播放中状态`() = runTest {
        remote.snapshot = connected(isPlaying = true)
        val session = newSession()

        remote.connect()

        assertTrue(session.state.value.isPlaying)
        assertEquals("晴天 · 播放中", session.state.value.status)
    }

    @Test
    fun `连接失败与服务不可用是两条不同文案`() = runTest {
        val failed = newSession()
        remote.connectionFailed()
        assertEquals("播放服务连接失败", failed.state.value.status)
        assertFalse(failed.state.value.isConnected)

        val other = FakePlaybackRemote()
        val unavailableSession = PlaybackSession(other, factory, gate, backgroundScope)
        other.unavailable()
        assertEquals("播放服务不可用", unavailableSession.state.value.status)
        assertFalse(unavailableSession.state.value.isConnected)
    }

    // ── 轮询 ────────────────────────────────────────────────────────────────

    @Test
    fun `轮询更新进度 时长 播放中状态与队列`() = runTest {
        remote.snapshot = connected()
        val session = newSession()
        remote.connect()
        runCurrent()

        remote.snapshot = connected(isPlaying = true).copy(
            positionMs = 42_000L,
            durationMs = 269_000L,
            queue = listOf(queueTrack("a"), queueTrack("b", current = true))
        )
        pollTick()

        val state = session.state.value
        assertEquals(42_000L, state.positionMs)
        assertEquals(269_000L, state.durationMs)
        assertTrue(state.isPlaying)
        assertEquals(listOf("a", "b"), state.queue.map { it.id })
        assertTrue(state.queue.last().isCurrent)
    }

    @Test
    fun `播放错误写进状态 重新开始播放后清掉`() = runTest {
        remote.snapshot = connected()
        val session = newSession()
        remote.connect()

        remote.emitError("播放失败：网络连接中断")
        assertEquals("播放失败：网络连接中断", session.state.value.playbackError)
        assertEquals("播放失败：网络连接中断", session.state.value.status)

        remote.snapshot = connected(isPlaying = true)
        remote.emitIsPlaying(true)
        assertNull(session.state.value.playbackError)
        assertEquals("晴天 · 播放中", session.state.value.status)
    }

    @Test
    fun `曲目切换后立刻刷新队列快照且清掉错误`() = runTest {
        remote.snapshot = connected()
        val session = newSession()
        remote.connect()
        remote.emitError("播放失败：请检查服务器或网络")

        remote.snapshot = connected(title = "七里香").copy(queue = listOf(queueTrack("x", current = true)))
        remote.emitTransition()

        assertNull(session.state.value.playbackError)
        assertEquals("七里香 · 已暂停", session.state.value.status)
        assertEquals(listOf("x"), session.state.value.queue.map { it.id })
    }

    // ── 播放与队列意图 ──────────────────────────────────────────────────────

    @Test
    fun `点歌播放过滤不可播曲目并从点的那首开始`() = runTest {
        remote.snapshot = connected()
        val blockedGate = FakePlaybackGate(mapOf("b" to "这首歌没有下载，联网后才能播放"))
        val session = newSession(blockedGate)
        remote.connect()

        val accepted = session.dispatch(
            PlaybackIntent.Play(listOf(track("a"), track("b"), track("c")), "c")
        )

        assertTrue(accepted)
        val call = remote.setQueueCalls.single()
        // buildPlaybackOrder 把点的那首旋到队首：c, a（b 被 gate 筛掉）。
        assertEquals(listOf("c", "a"), call.items.map { it.mediaId })
        assertEquals(0, call.startIndex)
        assertEquals(1, remote.prepareCalls)
        assertEquals(1, remote.playCalls)
        // 起播前把当前模式应用到播放器（与旧 playTracks 一致）。
        assertEquals(listOf(PlaybackMode.SEQUENCE), remote.appliedModes)
    }

    @Test
    fun `点的那首不可播时只发事件不写队列`() = runTest {
        remote.snapshot = connected()
        val blockedGate = FakePlaybackGate(mapOf("b" to "这首歌没有下载，联网后才能播放"))
        val session = newSession(blockedGate)
        remote.connect()
        val events = collectEvents(session)

        val accepted = session.dispatch(
            PlaybackIntent.Play(listOf(track("a"), track("b"), track("c")), "b")
        )
        runCurrent()

        assertTrue(accepted)
        assertEquals(0, remote.setQueueCalls.size)
        assertEquals(0, remote.prepareCalls)
        assertEquals(0, remote.playCalls)
        assertFalse(events.any { it is PlaybackEvent.PlaybackQueuePrepared })
        assertEquals(
            listOf<PlaybackEvent>(PlaybackEvent.Unplayable("这首歌没有下载，联网后才能播放")),
            events
        )
    }

    @Test
    fun `队列为空时添加到队列直接开始播放`() = runTest {
        remote.snapshot = connected()
        val session = newSession(FakePlaybackGate())
        remote.connect()
        val events = collectEvents(session)

        val accepted = session.dispatch(PlaybackIntent.AddToQueue(track("d")))
        runCurrent()

        assertTrue(accepted)
        assertEquals(listOf("d"), remote.singleItems.map { it.mediaId })
        assertEquals(1, remote.prepareCalls)
        assertEquals(1, remote.playCalls)
        assertEquals(0, remote.insertedItems.size)
        assertEquals(
            listOf<PlaybackEvent>(PlaybackEvent.Message("已添加到播放队列")),
            events
        )
    }

    @Test
    fun `非空队列时添加到当前曲目之后`() = runTest {
        remote.snapshot = connected().copy(
            queue = listOf(queueTrack("a", current = true), queueTrack("b"))
        )
        val session = newSession(FakePlaybackGate())
        remote.connect()
        val events = collectEvents(session)

        val accepted = session.dispatch(PlaybackIntent.AddToQueue(track("c")))
        runCurrent()

        assertTrue(accepted)
        assertEquals(listOf("c"), remote.insertedItems.map { it.mediaId })
        assertEquals(0, remote.singleItems.size)
        assertEquals(0, remote.prepareCalls)
        assertEquals(0, remote.playCalls)
        assertEquals(
            listOf<PlaybackEvent>(PlaybackEvent.Message("已添加到播放队列")),
            events
        )
    }

    @Test
    fun `删除队列项倒序摘并跳过当前项`() = runTest {
        remote.snapshot = connected().copy(
            queue = listOf(
                queueTrack("a"),
                queueTrack("b", current = true),
                queueTrack("c"),
                queueTrack("d")
            )
        )
        val session = newSession(FakePlaybackGate())
        remote.connect()

        val accepted = session.dispatch(
            PlaybackIntent.RemoveFromQueue(setOf("a", "b", "c", "d"))
        )

        assertTrue(accepted)
        // b 是当前项（索引 1），跳过；其余从后往前：d(3) → c(2) → a(0)。
        assertEquals(listOf(3, 2, 0), remote.removedIndices)
    }

    @Test
    fun `重排产生与旧实现一致的逐条 move 序列`() = runTest {
        remote.snapshot = connected().copy(
            queue = listOf(
                queueTrack("a"),
                queueTrack("b"),
                queueTrack("c"),
                queueTrack("d")
            )
        )
        val session = newSession(FakePlaybackGate())
        remote.connect()

        val accepted = session.dispatch(
            PlaybackIntent.Reorder(
                listOf(queueTrack("b"), queueTrack("a"), queueTrack("d"), queueTrack("c"))
            )
        )

        assertTrue(accepted)
        // 逐条模拟：b(1→0)，然后是 d(3→2)。
        assertEquals(listOf(1 to 0, 3 to 2), remote.moves)
    }

    @Test
    fun `SetMode 更新会话状态并应用到播放器`() = runTest {
        remote.snapshot = connected()
        val session = newSession(FakePlaybackGate())
        remote.connect()

        session.dispatch(PlaybackIntent.SetMode(PlaybackMode.REPEAT_ONE))

        assertEquals(PlaybackMode.REPEAT_ONE, session.state.value.mode)
        assertEquals(listOf(PlaybackMode.REPEAT_ONE), remote.appliedModes)
    }

    @Test
    fun `CycleMode 在三种模式之间循环`() = runTest {
        remote.snapshot = connected()
        val session = newSession(FakePlaybackGate())
        remote.connect()
        assertEquals(PlaybackMode.SEQUENCE, session.state.value.mode)

        session.dispatch(PlaybackIntent.CycleMode)
        assertEquals(PlaybackMode.REPEAT_ONE, session.state.value.mode)

        session.dispatch(PlaybackIntent.CycleMode)
        assertEquals(PlaybackMode.SHUFFLE, session.state.value.mode)

        session.dispatch(PlaybackIntent.CycleMode)
        assertEquals(PlaybackMode.SEQUENCE, session.state.value.mode)

        assertEquals(
            listOf(PlaybackMode.REPEAT_ONE, PlaybackMode.SHUFFLE, PlaybackMode.SEQUENCE),
            remote.appliedModes
        )
    }

    // ── 播放模式持久化（跨转屏 / 杀进程） ───────────────────────────────────

    @Test
    fun `播放模式从存储加载进会话状态`() = runTest {
        val store = FakePlaybackModeStore(PlaybackMode.REPEAT_ONE)
        val session = PlaybackSession(remote, factory, gate, backgroundScope, store)

        runCurrent()

        assertEquals(PlaybackMode.REPEAT_ONE, session.state.value.mode)
    }

    @Test
    fun `连接后把存储里的播放模式应用到播放器`() = runTest {
        val store = FakePlaybackModeStore(PlaybackMode.SHUFFLE)
        val session = PlaybackSession(remote, factory, gate, backgroundScope, store)
        // 先让加载完成，再连接：模式必须在连接时才下发给播放器。
        runCurrent()

        remote.snapshot = connected()
        remote.connect()

        assertEquals(PlaybackMode.SHUFFLE, session.state.value.mode)
        assertEquals(PlaybackMode.SHUFFLE, remote.appliedModes.last())
    }

    @Test
    fun `SetMode 与 CycleMode 会把模式写进存储`() = runTest {
        val store = FakePlaybackModeStore()
        val session = PlaybackSession(remote, factory, gate, backgroundScope, store)

        session.dispatch(PlaybackIntent.SetMode(PlaybackMode.REPEAT_ONE))
        runCurrent()
        assertEquals(listOf(PlaybackMode.REPEAT_ONE), store.savedModes)

        session.dispatch(PlaybackIntent.CycleMode)
        runCurrent()
        assertEquals(listOf(PlaybackMode.REPEAT_ONE, PlaybackMode.SHUFFLE), store.savedModes)
    }

    @Test
    fun `关闭后不再写存储`() = runTest {
        val store = FakePlaybackModeStore()
        val session = PlaybackSession(remote, factory, gate, backgroundScope, store)

        // 保存被排进协程队列后立刻关闭：取消必须发生在写盘之前。
        session.dispatch(PlaybackIntent.SetMode(PlaybackMode.SHUFFLE))
        session.close()
        runCurrent()

        assertTrue(store.savedModes.isEmpty())
        // 关闭后意图一律拒绝，更不能产生新的写盘。
        assertFalse(session.dispatch(PlaybackIntent.SetMode(PlaybackMode.REPEAT_ONE)))
        runCurrent()
        assertTrue(store.savedModes.isEmpty())
    }

    @Test
    fun `播放控制意图全部转发给播放器`() = runTest {
        remote.snapshot = connected(isPlaying = true)
        val session = newSession(FakePlaybackGate())
        remote.connect()

        session.dispatch(PlaybackIntent.TogglePlayPause)
        session.dispatch(PlaybackIntent.SeekTo(12_000L))
        session.dispatch(PlaybackIntent.Next)
        session.dispatch(PlaybackIntent.Previous)
        session.dispatch(PlaybackIntent.PlayQueueItem(2))

        assertEquals(1, remote.pauseCalls)
        assertEquals(listOf(12_000L), remote.seekToCalls)
        assertEquals(1, remote.nextCalls)
        assertEquals(1, remote.previousCalls)
        assertEquals(listOf(2), remote.seekToDefaultPositionCalls)
        assertEquals(1, remote.playCalls)
    }

    // ── USB 命令 ────────────────────────────────────────────────────────────

    @Test
    fun `轮询读到 USB 状态与音量`() = runTest {
        remote.snapshot = connected()
        remote.commandResults[UsbExclusiveMediaCommands.getCommand().customAction] = RemoteCommandResult(
            SessionResult.RESULT_SUCCESS,
            mapOf(
                UsbExclusiveMediaCommands.KEY_ENABLED to true,
                UsbExclusiveMediaCommands.KEY_STATE to "streaming",
                UsbExclusiveMediaCommands.KEY_VOLUME_PERCENT to 80
            )
        )
        val session = newSession()
        remote.connect()
        runCurrent()

        assertEquals(true, session.state.value.usb.enabled)
        assertEquals("streaming", session.state.value.usb.state)
        assertEquals(80, session.state.value.usb.volumePercent)
    }

    @Test
    fun `打开 USB 独占成功后回读状态并结束 pending`() = runTest {
        remote.snapshot = connected()
        val session = newSession()
        remote.connect()
        // 故意不推进轮询：isolate「设置命令 → 回读」这条链路，命令计数才干净。
        remote.commandResults[UsbExclusiveMediaCommands.getCommand().customAction] = RemoteCommandResult(
            SessionResult.RESULT_SUCCESS,
            mapOf(
                UsbExclusiveMediaCommands.KEY_ENABLED to true,
                UsbExclusiveMediaCommands.KEY_STATE to "awaiting_permission"
            )
        )

        val accepted = session.dispatch(PlaybackIntent.SetUsbExclusive(true))

        assertTrue(accepted)
        assertEquals(1, remote.sentCount(UsbExclusiveMediaCommands.SET_ENABLED))
        assertEquals(1, remote.sentCount(UsbExclusiveMediaCommands.GET_STATE))
        val usb = session.state.value.usb
        assertTrue(usb.enabled)
        assertEquals("awaiting_permission", usb.state)
        assertFalse(usb.pending)
        assertNull(usb.error)
    }

    @Test
    fun `回读 USB 状态失败时给出错误文案并结束 pending`() = runTest {
        remote.snapshot = connected()
        val session = newSession()
        remote.connect()
        remote.commandResults[UsbExclusiveMediaCommands.getCommand().customAction] =
            RemoteCommandResult(SessionResult.RESULT_ERROR_BAD_VALUE)

        session.dispatch(PlaybackIntent.SetUsbExclusive(true))

        assertEquals("USB 独占设置失败", session.state.value.usb.error)
        assertFalse(session.state.value.usb.pending)
    }

    @Test
    fun `USB 状态查询在途时不并发发第二条`() = runTest {
        remote.snapshot = connected()
        remote.holdCommands = true
        val session = newSession()
        remote.connect()
        runCurrent()

        assertEquals(1, remote.sentCount(UsbExclusiveMediaCommands.GET_STATE))
        pollTick()
        assertEquals(1, remote.sentCount(UsbExclusiveMediaCommands.GET_STATE))
        pollTick()
        assertEquals(1, remote.sentCount(UsbExclusiveMediaCommands.GET_STATE))

        // 完成在途查询后，下一拍才允许再发。
        remote.complete(0)
        pollTick()
        assertEquals(2, remote.sentCount(UsbExclusiveMediaCommands.GET_STATE))
    }

    @Test
    fun `USB 音量设置先本地生效再下发绝对值`() = runTest {
        remote.snapshot = connected()
        val session = newSession()
        remote.connect()
        runCurrent()

        session.dispatch(PlaybackIntent.SetUsbVolume(55))

        assertEquals(55, session.state.value.usb.volumePercent)
        assertEquals(1, remote.sentCount(UsbExclusiveMediaCommands.SET_VOLUME))
    }

    // ── 静音跳过 ────────────────────────────────────────────────────────────

    @Test
    fun `轮询读到静音跳过开关`() = runTest {
        remote.snapshot = connected()
        remote.commandResults[SilenceSkipMediaCommands.getCommand().customAction] = RemoteCommandResult(
            SessionResult.RESULT_SUCCESS,
            mapOf(SilenceSkipMediaCommands.KEY_ENABLED to false)
        )
        val session = newSession()
        remote.connect()
        runCurrent()

        assertFalse(session.state.value.silenceSkipEnabled)
    }

    @Test
    fun `切换静音跳过先本地生效再下发命令`() = runTest {
        remote.snapshot = connected()
        val session = newSession()
        remote.connect()
        runCurrent()

        session.dispatch(PlaybackIntent.SetSilenceSkip(false))

        assertFalse(session.state.value.silenceSkipEnabled)
        assertEquals(1, remote.sentCount(SilenceSkipMediaCommands.SET_ENABLED))
    }

    // ── 音量键（相对调整） ──────────────────────────────────────────────────

    @Test
    fun `调整音量成功时把服务算出的新音量发给界面`() = runTest {
        remote.snapshot = connected()
        remote.commandResults[UsbExclusiveMediaCommands.adjustVolumeCommand().customAction] = RemoteCommandResult(
            SessionResult.RESULT_SUCCESS,
            mapOf(UsbExclusiveMediaCommands.KEY_VOLUME_PERCENT to 73)
        )
        val session = newSession()
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch { session.events.collect { events += it } }
        runCurrent()

        val consumed = session.dispatch(PlaybackIntent.AdjustVolume(1))
        runCurrent()

        assertTrue(consumed)
        assertEquals(1, remote.sentCount(UsbExclusiveMediaCommands.ADJUST_VOLUME))
        assertEquals(listOf<PlaybackEvent>(PlaybackEvent.VolumeApplied(73)), events)
    }

    @Test
    fun `会话不提供调整音量命令时把按键交还系统`() = runTest {
        remote.commandsAvailable = false
        val session = newSession()

        val consumed = session.dispatch(PlaybackIntent.AdjustVolume(1))

        assertFalse(consumed)
        assertEquals(0, remote.sentCount(UsbExclusiveMediaCommands.ADJUST_VOLUME))
    }

    // ── 关闭 ────────────────────────────────────────────────────────────────

    @Test
    fun `关闭后不再轮询也不再发命令`() = runTest {
        remote.snapshot = connected()
        val session = newSession()
        remote.connect()
        runCurrent()
        val sentBeforeClose = remote.sentCommands.size

        session.close()
        advanceTimeBy(PLAYBACK_POLL_INTERVAL_MS * 4)
        runCurrent()

        assertEquals(sentBeforeClose, remote.sentCommands.size)
        assertTrue(remote.listeners.isEmpty())
        assertTrue(remote.closed)
        assertFalse(session.dispatch(PlaybackIntent.SetUsbExclusive(true)))
        assertFalse(session.dispatch(PlaybackIntent.SetSilenceSkip(false)))
        assertFalse(session.dispatch(PlaybackIntent.SetUsbVolume(30)))
        assertFalse(session.dispatch(PlaybackIntent.AdjustVolume(-1)))
        assertEquals(sentBeforeClose, remote.sentCommands.size)
    }
}
