package com.example.pulsar.ui.components

import com.example.pulsar.QueueTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 队列拖拽暂态（#28）：队列的唯一真相是会话队列，暂态只在拖拽期间及松手后等会话追上时
 * 保存一份 id 顺序；曲目数据（含「正在播」标记）永远从会话快照现取。
 */
class QueueDragStateTest {

    private fun track(id: String, current: Boolean = false) = QueueTrack(
        id = id,
        title = "歌曲-$id",
        artist = "歌手",
        album = "专辑",
        imageTag = null,
        isCurrent = current
    )

    private val queue = listOf(track("a"), track("b", current = true), track("c"))

    @Test
    fun `没有拖拽时显示的队列就是会话队列`() {
        val state = QueueDragState()

        assertSame(queue, state.displayed(queue))
        assertFalse(state.isDragging)
    }

    @Test
    fun `拖拽取消后迟到的跨位不再移动队列`() {
        val state = QueueDragState()
        state.start(queue)
        state.cancel()

        state.move(from = 0, to = 2, sessionQueue = queue)
        assertSame(queue, state.displayed(queue))
        assertNull(state.finish(queue))
    }

    @Test
    fun `拖拽中按暂态顺序显示 曲目数据始终取会话快照`() {
        val state = QueueDragState()
        state.start(queue)

        state.move(from = 2, to = 0, sessionQueue = queue)

        assertEquals(listOf("c", "a", "b"), state.displayed(queue).map { it.id })
        // 拖拽期间切歌：会话快照把「正在播」挪到 c，暂态不能把旧标记带过来。
        val switched = listOf(track("a"), track("b"), track("c", current = true))
        val shown = state.displayed(switched)
        assertEquals(listOf("c", "a", "b"), shown.map { it.id })
        assertTrue(shown.first().isCurrent)
        assertFalse(shown.first { it.id == "b" }.isCurrent)
    }

    @Test
    fun `拖拽期间会话增删曲目时显示列表跟着会话走`() {
        val state = QueueDragState()
        state.start(queue)
        // 拖拽中队列末尾新增一首：暂态顺序里没有它，按会话顺序补在末尾。
        val added = queue + track("d")
        assertEquals(listOf("a", "b", "c", "d"), state.displayed(added).map { it.id })
        // 拖拽中删掉一首：暂态里不留已删的 id。
        val removed = listOf(track("a"), track("c"))
        assertEquals(listOf("a", "c"), state.displayed(removed).map { it.id })
    }

    @Test
    fun `拖拽中会话队列变化不释放暂态`() {
        val state = QueueDragState()
        state.start(queue)
        state.move(from = 2, to = 0, sessionQueue = queue)

        // 拖拽期间轮询 / 切歌让会话队列变了，暂态顺序不能被清掉。
        state.onSessionQueueChanged(queue)

        assertEquals(listOf("c", "a", "b"), state.displayed(queue).map { it.id })
    }

    @Test
    fun `松手返回目标顺序 并保留暂态到会话队列追上`() {
        val state = QueueDragState()
        state.start(queue)
        state.move(from = 0, to = 2, sessionQueue = queue)

        val ordered = state.finish(queue)

        assertFalse(state.isDragging)
        assertTrue(state.isPending)
        assertEquals(listOf("b", "c", "a"), ordered?.map { it.id })
        // 播放器快照还没追上：界面继续显示拖完的顺序，不能闪回旧顺序。
        assertEquals(listOf("b", "c", "a"), state.displayed(queue).map { it.id })
    }

    @Test
    fun `松手后无关的会话变化不释放暂态 只有顺序追上才释放`() {
        val state = QueueDragState()
        state.start(queue)
        state.move(from = 0, to = 2, sessionQueue = queue)
        state.finish(queue)

        // 播放器还在走 move：这期间切歌（isCurrent 移动）会触发一次会话变化，
        // 但顺序还是旧的 —— 暂态必须继续压着，否则界面会先闪回旧顺序。
        val switched = listOf(track("a"), track("b", current = true), track("c"))
        state.onSessionQueueChanged(switched)
        assertTrue(state.isPending)
        assertEquals(listOf("b", "c", "a"), state.displayed(switched).map { it.id })

        // 播放器把顺序走完了：会话顺序 == 暂态顺序，释放，回到唯一真相。
        val caughtUp = listOf(track("b"), track("c"), track("a", current = true))
        state.onSessionQueueChanged(caughtUp)
        assertFalse(state.isPending)
        assertSame(caughtUp, state.displayed(caughtUp))
    }

    @Test
    fun `会话队列被整条换掉时释放暂态`() {
        val state = QueueDragState()
        state.start(queue)
        state.move(from = 0, to = 2, sessionQueue = queue)
        state.finish(queue)

        val replaced = listOf(track("x", current = true), track("y"))
        state.onSessionQueueChanged(replaced)

        assertSame(replaced, state.displayed(replaced))
    }

    @Test
    fun `松手把新顺序交给回写 写回成功保留暂态`() {
        val state = QueueDragState()
        state.start(queue)
        state.move(from = 0, to = 2, sessionQueue = queue)

        var written: List<String>? = null
        state.drop(queue) { ordered ->
            written = ordered.map { it.id }
            true
        }

        assertEquals(listOf("b", "c", "a"), written)
        assertEquals(listOf("b", "c", "a"), state.displayed(queue).map { it.id })
    }

    @Test
    fun `松手写回失败时立刻回到会话顺序`() {
        val state = QueueDragState()
        state.start(queue)
        state.move(from = 0, to = 2, sessionQueue = queue)

        state.drop(queue) { false }

        assertSame(queue, state.displayed(queue))
    }

    @Test
    fun `松手时顺序没变则不回写也不留暂态`() {
        val state = QueueDragState()
        state.start(queue)

        assertNull(state.finish(queue))

        val changed = listOf(track("c"), track("b"), track("a"))
        assertEquals(listOf("c", "b", "a"), state.displayed(changed).map { it.id })
    }

    @Test
    fun `拖拽被中断时直接回到会话顺序`() {
        val state = QueueDragState()
        state.start(queue)
        state.move(from = 0, to = 2, sessionQueue = queue)

        state.cancel()

        assertFalse(state.isDragging)
        assertSame(queue, state.displayed(queue))
    }
}
