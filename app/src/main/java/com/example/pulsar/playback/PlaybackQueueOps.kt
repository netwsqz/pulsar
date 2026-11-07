package com.example.pulsar.playback

/**
 * 「添加到播放队列」的插入位置：当前曲目之后。
 *
 * `currentIndex == -1`（队列空 / 没有当前项）时插到队首；当前是最后一首时追加到队尾
 * （返回 `size`，即允许插进列表末尾）。结果夹在 `0..size`，调用方不必再兜底。
 */
internal fun insertAfterCurrentIndex(currentIndex: Int, size: Int): Int =
    (currentIndex + 1).coerceIn(0, size)

/**
 * 从队列里摘掉指定 id 的曲目：返回**倒序**的待删索引，并跳过正在播的那一首。
 *
 * 两个约束都来自 Media3 的语义：
 * - **倒序**：`removeMediaItem` 会让后面的索引前移，从后往前删才不会删错；
 * - **跳过当前项**：正在播的那首被摘掉会直接断掉播放（ViewModel 侧已挡单曲删除，这里兜底）。
 *
 * 不在队列里的 id 不会产生索引。
 */
internal fun removeIdsFromQueue(
    mediaIds: List<String>,
    ids: Set<String>,
    currentIndex: Int
): List<Int> = mediaIds.indices.reversed().filter { index ->
    index != currentIndex && mediaIds[index] in ids
}

/**
 * 拖拽重排后，把「目标顺序」翻译成逐条 move 的 `(from, to)` 序列。
 *
 * **必须逐条模拟**：Media3 的 `moveMediaItem` 会即时改变队列镜像，上一条 move 会让下一条的
 * 起止索引都变。一次性按初始索引算出来的序列会越挪越乱。这里在本地列表上重放同一套
 * 「摘出来再插进去」的动作，产出与真实播放器一致的调用序列。
 *
 * 已在目标位置的曲目不产生 move。目标顺序里出现队列中没有的 id 时跳过它；此时后面曲目的
 * 目标位置会比真实队列长，插入位置**夹到合法范围**——`moveMediaItem` 对越界索引是抛异常
 * 而不是自动夹紧，所以这里不能把越界值交出去（正常情况 orderedIds 是队列的一个排列，
 * 夹紧是空操作）。
 */
internal fun applyOrderToPositions(
    currentIds: List<String>,
    orderedIds: List<String>
): List<Pair<Int, Int>> {
    val working = currentIds.toMutableList()
    val moves = mutableListOf<Pair<Int, Int>>()
    for (targetIndex in orderedIds.indices) {
        val id = orderedIds[targetIndex]
        val from = working.indexOf(id)
        if (from < 0 || from == targetIndex) continue
        working.removeAt(from)
        val insertAt = targetIndex.coerceAtMost(working.size)
        working.add(insertAt, id)
        moves += from to insertAt
    }
    return moves
}
