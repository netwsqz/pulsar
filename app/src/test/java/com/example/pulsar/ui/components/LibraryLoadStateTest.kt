package com.example.pulsar.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 列表页三态的映射规则（#24 Step 1）：把「数据 + 加载中 + 错误 + 空列表」收成一个
 * [LoadState]，专辑页与播放列表页共用同一套判断。
 *
 * 这些用例钉死的是**改前两页 `when` 的分支顺序**——迁移只允许换写法，不允许换行为：
 * 加载中优先于错误、离线说明型空态优先于错误、已有数据时刷新中仍显示内容。
 */
class LibraryLoadStateTest {

    @Test
    fun `没拉到数据且正在加载时给骨架`() {
        assertEquals(
            LoadState.Loading,
            libraryLoadState<String>(
                items = null,
                isLoading = true,
                errorMessage = null,
                emptyMessage = "暂无专辑"
            )
        )
    }

    @Test
    fun `已有数据时刷新中仍显示内容`() {
        // 改前两页只在 `list == null` 时看 isLoading；后台刷新不能把列表换成骨架。
        assertEquals(
            LoadState.Content,
            libraryLoadState(
                items = listOf("a"),
                isLoading = true,
                errorMessage = null,
                emptyMessage = "暂无专辑"
            )
        )
    }

    @Test
    fun `没拉到数据且报错时给错误与重试`() {
        val retry: () -> Unit = {}
        assertEquals(
            LoadState.Error("网络错误", retry),
            libraryLoadState<String>(
                items = null,
                isLoading = false,
                errorMessage = "网络错误",
                emptyMessage = "暂无专辑",
                onRetry = retry
            )
        )
    }

    @Test
    fun `加载中与错误同时成立时骨架优先`() {
        // 改前两页的 when 都是先判 isLoading、再判 errorMessage。
        assertEquals(
            LoadState.Loading,
            libraryLoadState<String>(
                items = null,
                isLoading = true,
                errorMessage = "网络错误",
                emptyMessage = "暂无专辑"
            )
        )
    }

    @Test
    fun `加载完成但列表为空时给在线空态`() {
        assertEquals(
            LoadState.Empty("暂无专辑"),
            libraryLoadState(
                items = emptyList<String>(),
                isLoading = false,
                errorMessage = null,
                emptyMessage = "暂无专辑"
            )
        )
    }

    @Test
    fun `离线且没拉到数据时给说明型空态，并优先于错误`() {
        // 播放列表页的第三条分支：断网且没拉到列表 → 「需要联网」，不给必然失败的重试。
        assertEquals(
            LoadState.Empty("播放列表需要联网才能查看"),
            libraryLoadState<String>(
                items = null,
                isLoading = false,
                errorMessage = "网络错误",
                emptyMessage = "暂无播放列表",
                offlineUnavailableMessage = "播放列表需要联网才能查看"
            )
        )
    }

    @Test
    fun `没拉到数据也没在加载没报错时落在线空态`() {
        // 改前两页最后都落到 `list.orEmpty().isEmpty()`；没有加载/错误时显示空态而不是空白内容。
        assertEquals(
            LoadState.Empty("暂无播放列表"),
            libraryLoadState<String>(
                items = null,
                isLoading = false,
                errorMessage = null,
                emptyMessage = "暂无播放列表"
            )
        )
    }

    @Test
    fun `离线但已有数据时仍显示内容`() {
        // 播放列表页改前只对 `playlists == null && isOffline` 给「需要联网」；
        // 断网不该把已经拉到的列表藏掉，离线说明只在没数据时生效。
        assertEquals(
            LoadState.Content,
            libraryLoadState(
                items = listOf("p"),
                isLoading = false,
                errorMessage = null,
                emptyMessage = "暂无播放列表",
                offlineUnavailableMessage = "播放列表需要联网才能查看"
            )
        )
    }

    @Test
    fun `有数据时给内容`() {
        assertEquals(
            LoadState.Content,
            libraryLoadState(
                items = listOf("a", "b"),
                isLoading = false,
                errorMessage = null,
                emptyMessage = "暂无专辑"
            )
        )
    }
}
