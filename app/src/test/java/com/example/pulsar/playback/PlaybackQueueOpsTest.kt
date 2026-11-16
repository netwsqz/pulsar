package com.example.pulsar.playback

import com.example.pulsar.QueueTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 队列的纯算术：插入位置、删除索引、重排映射、拖拽顺序合并。 */
class PlaybackQueueOpsTest {

    private fun queueTrack(id: String, current: Boolean = false) = QueueTrack(
        id = id,
        title = "歌曲-$id",
        artist = "歌手",
        album = "专辑",
        imageTag = null,
        isCurrent = current
    )

    @Test
    fun `insertAfterCurrentIndex 插在当前项之后，末项之后追加到队尾，无当前项时插到队首`() {
        // 当前在第 3 首（索引 2）的后面
        assertEquals(3, insertAfterCurrentIndex(currentIndex = 2, size = 5))
        // 当前就是最后一首：追加到队尾（索引 == size 表示插进末尾）
        assertEquals(5, insertAfterCurrentIndex(currentIndex = 4, size = 5))
        // 队列为空 / 没有当前项（currentMediaItemIndex == -1）：插到队首
        assertEquals(0, insertAfterCurrentIndex(currentIndex = -1, size = 0))
        assertEquals(0, insertAfterCurrentIndex(currentIndex = -1, size = 5))
    }

    @Test
    fun `removeIdsFromQueue 返回倒序索引 跳过正在播的那首 忽略不在队列里的 id`() {
        // 删 b 与 d，当前停在 c（索引 2）：倒序给索引，删掉 d 不会让 b 的索引前移
        assertEquals(
            listOf(3, 1),
            removeIdsFromQueue(listOf("a", "b", "c", "d"), setOf("b", "d"), currentIndex = 2)
        )
        // 要删的列表里含正在播的那首：跳过它，只删另一首（正在播的被摘掉会断掉播放）
        assertEquals(
            listOf(0),
            removeIdsFromQueue(listOf("a", "b", "c"), setOf("a", "b"), currentIndex = 1)
        )
        // 队列里没有的 id：不产生任何索引
        assertEquals(
            emptyList<Int>(),
            removeIdsFromQueue(listOf("a", "b"), setOf("c"), currentIndex = 0)
        )
        // 空队列
        assertEquals(
            emptyList<Int>(),
            removeIdsFromQueue(emptyList(), setOf("a"), currentIndex = -1)
        )
    }

    @Test
    fun `applyOrderToPositions 按目标顺序给出逐条 move 的起止索引`() {
        // 已经是目标顺序：不需要任何 move
        assertEquals(
            emptyList<Pair<Int, Int>>(),
            applyOrderToPositions(listOf("a", "b", "c"), listOf("a", "b", "c"))
        )
        // 完全倒序：每条 move 都作用在**前一条 move 之后的队列**上，所以是 2→0、2→1
        assertEquals(
            listOf(2 to 0, 2 to 1),
            applyOrderToPositions(listOf("a", "b", "c"), listOf("c", "b", "a"))
        )
        // 把队首那首挪到末尾（第一首的循环）
        assertEquals(
            listOf(1 to 0, 2 to 1),
            applyOrderToPositions(listOf("a", "b", "c"), listOf("b", "c", "a"))
        )
        // 目标里出现队列中没有的 id：跳过它，且后续插入位置夹在合法范围内
        // （moveMediaItem 对越界索引会抛异常，不能把越界值交出去）
        assertEquals(
            listOf(0 to 1),
            applyOrderToPositions(listOf("a", "b"), listOf("x", "b", "a"))
        )
    }

    @Test
    fun `applyQueueOrder 没有暂态时原样返回 有暂态时按 id 顺序重排`() {
        val queue = listOf(queueTrack("a"), queueTrack("b", current = true), queueTrack("c"))

        assertSame(queue, applyQueueOrder(queue, null))

        val ordered = applyQueueOrder(queue, listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), ordered.map { it.id })
        // 曲目对象取自会话快照：当前标记跟着曲目走，不会因为重排丢失。
        assertTrue(ordered.first { it.id == "b" }.isCurrent)
    }

    @Test
    fun `applyQueueOrder 跳过已删除的 id 并把新曲目按会话顺序补在末尾`() {
        val queue = listOf(queueTrack("a"), queueTrack("b"), queueTrack("c"))

        assertEquals(
            listOf("c", "a", "b"),
            applyQueueOrder(queue, listOf("c", "x")).map { it.id }
        )
        // 暂态里只有队首：其余按会话顺序补齐，绝不丢行。
        assertEquals(
            listOf("b", "a", "c"),
            applyQueueOrder(queue, listOf("b")).map { it.id }
        )
    }

    @Test
    fun `applyQueueOrder 顺序里的重复 id 只保留一次`() {
        val queue = listOf(queueTrack("a"), queueTrack("b"))

        assertEquals(
            listOf("b", "a"),
            applyQueueOrder(queue, listOf("b", "a", "b")).map { it.id }
        )
    }
}
