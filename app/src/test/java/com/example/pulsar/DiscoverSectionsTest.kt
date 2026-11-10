package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Track
import com.example.pulsar.offline.OfflineEmpty
import com.example.pulsar.offline.message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发现页的「这一页到底有哪些节」——纯函数，不碰 IO（工单 12）。
 *
 * 分工（spec §7）：三节服务器历史（最近播放 / 最常播放 / 最近添加）在**离线时整个不显示**，
 * 用 `isOffline` 做组合条件；每日推荐与随机专辑墙仍按已下载内容显示。
 * `hasAnySection` 只统计**实际会渲染**的节，否则三节历史的残留缓存会压制整页空态。
 */
class DiscoverSectionsTest {

    private fun track(id: String) = Track(
        id = id,
        title = "歌$id",
        artist = "歌手$id",
        album = "专辑$id",
        durationMs = 100_000L,
        imageTag = null
    )

    private fun album(id: String) = Album(
        id = id,
        name = "专辑$id",
        artist = "歌手$id",
        trackCount = null,
        imageTag = null
    )

    private fun sections(
        dailyPicks: List<Track> = emptyList(),
        recentlyPlayed: List<Track>? = null,
        mostPlayed: List<Track>? = null,
        recentlyAdded: List<Album>? = null,
        discoverAlbums: List<Album>? = null,
        isOffline: Boolean = false,
        tracksLoaded: Boolean = true,
        isLoading: Boolean = false
    ) = discoverSections(
        dailyPicksAvailable = dailyPicks.isNotEmpty(),
        recentlyPlayed = recentlyPlayed,
        mostPlayed = mostPlayed,
        recentlyAdded = recentlyAdded,
        discoverAlbums = discoverAlbums,
        isOffline = isOffline,
        tracksLoaded = tracksLoaded,
        isLoading = isLoading
    )

    @Test
    fun `在线时三节服务器历史都显示`() {
        val sut = sections(
            recentlyPlayed = listOf(track("t1")),
            mostPlayed = listOf(track("t2")),
            recentlyAdded = listOf(album("a1"))
        )
        assertTrue(sut.showRecentlyPlayed)
        assertTrue(sut.showMostPlayed)
        assertTrue(sut.showRecentlyAdded)
    }

    @Test
    fun `离线时三节服务器历史整个不显示，数据留在 state 里也不画`() {
        // goOffline() 不清这三个字段，断网后旧数据原样躺在 state 里——
        // 离线时它们必须整节缺席（连骨架和空态都不给，spec §7）。
        val sut = sections(
            recentlyPlayed = listOf(track("t1")),
            mostPlayed = listOf(track("t2")),
            recentlyAdded = listOf(album("a1")),
            isOffline = true
        )
        assertFalse(sut.showRecentlyPlayed)
        assertFalse(sut.showMostPlayed)
        assertFalse(sut.showRecentlyAdded)
    }

    @Test
    fun `离线时每日推荐与专辑墙照常显示`() {
        val sut = sections(
            dailyPicks = listOf(track("t1")),
            discoverAlbums = listOf(album("a1")),
            isOffline = true
        )
        assertTrue(sut.showDailyPicks)
        assertTrue(sut.showAlbums)
    }

    @Test
    fun `离线时三节历史有缓存不会压制整页空态`() {
        // 回归 #22：以前 hasAnySection 直接读原始 state 字段，离线时三节旧数据还在，
        // 整页空态永远不出现——屏幕上只有三节点不动的内容。现在它们不算「有内容」。
        val sut = sections(
            recentlyPlayed = listOf(track("t1")),
            mostPlayed = listOf(track("t2")),
            recentlyAdded = listOf(album("a1")),
            isOffline = true
        )
        assertFalse("离线时三节历史不算内容", sut.hasAnySection)
    }

    @Test
    fun `在线时三节历史有内容就算有内容`() {
        val sut = sections(recentlyPlayed = listOf(track("t1")))
        assertTrue(sut.hasAnySection)
    }

    @Test
    fun `每日推荐为空时不计入 hasAnySection`() {
        val sut = sections(dailyPicks = emptyList(), discoverAlbums = emptyList())
        assertFalse(sut.hasAnySection)
    }

    @Test
    fun `专辑墙有内容就算有内容`() {
        assertTrue(sections(discoverAlbums = listOf(album("a1"))).hasAnySection)
    }

    @Test
    fun `每日推荐空态与整页空态只出现一处：整页有内容时每日推荐才有自己的小字`() {
        // 整页空 → 由页面底部那一个空态独占；每日推荐不再重复一段同样的提示（报告 #25）。
        val pageEmpty = sections()
        assertTrue(pageEmpty.showPageEmpty)

        // 整页还有别的内容（专辑墙）→ 每日推荐就地为自己的空写一行小字。
        val pageHasContent = sections(discoverAlbums = listOf(album("a1")))
        assertFalse(pageHasContent.showPageEmpty)
        assertTrue(pageHasContent.hasAnySection)
    }

    @Test
    fun `没有任何节可渲染且已加载完成时给整页空态`() {
        val sut = sections()
        assertFalse(sut.hasAnySection)
        assertTrue(sut.showPageEmpty)
    }

    @Test
    fun `曲库还没拉到（tracks 为 null）时不给整页空态`() {
        // state.tracks == null = 会话还没恢复完，界面正在骨架屏；这不是「确实没有内容」。
        val sut = discoverSections(
            dailyPicksAvailable = false,
            recentlyPlayed = null,
            mostPlayed = null,
            recentlyAdded = null,
            discoverAlbums = null,
            isOffline = false,
            tracksLoaded = false,
            isLoading = false
        )
        assertFalse(sut.showPageEmpty)
    }

    @Test
    fun `加载中的骨架期间不给整页空态`() {
        val sut = discoverSections(
            dailyPicksAvailable = false,
            recentlyPlayed = null,
            mostPlayed = null,
            recentlyAdded = null,
            discoverAlbums = null,
            isOffline = false,
            tracksLoaded = true,
            isLoading = true
        )
        assertFalse(sut.showPageEmpty)
    }

    @Test
    fun `离线且本地什么都没有时同时满足专用空态的条件`() {
        // 每日推荐空 + 专辑墙空 + 离线 → 专用空态「离线 · 手机里还没有下载的歌曲」。
        val sut = sections(isOffline = true)
        assertFalse(sut.showDailyPicks)
        assertFalse(sut.showAlbums)
        assertTrue(sut.showPageEmpty)
    }

    @Test
    fun `有每日推荐曲目时不给整页空态`() {
        assertFalse(sections(dailyPicks = listOf(track("t1"))).showPageEmpty)
    }

    @Test
    fun `离线且一首都没下载时整页给一个空态，每日推荐那个小字不再出`() {
        // 这是界面上的实际分支：`dailyEmpty` 只在 `!showDailyPicks && hasAnySection` 时画，
        // 整页空态只在 showPageEmpty 时画——两者互斥，所以同一屏只会有一处提示（报告 #25）。
        val sut = sections(isOffline = true)
        assertFalse(sut.showDailyPicks)
        assertFalse(sut.hasAnySection)
        assertTrue(sut.showPageEmpty)
    }

    @Test
    fun `空态文案离线说去下载，在线说去搜索`() {
        // 报告 #26：文案里的入口必须真实存在。在线指搜索页（真有），离线指下载——
        // 离线时「曲库为空」这个说法本身就没意义。
        assertEquals(DiscoverEmpty.Downloaded, discoverEmptyNotice(isOffline = true))
        assertEquals(DiscoverEmpty.Library, discoverEmptyNotice(isOffline = false))
        assertEquals("离线 · 手机里还没有下载的歌曲", discoverEmptyNotice(isOffline = true).message())
        // 离线空态文案的唯一出处是 offline/OfflineEmpty（#10）：发现页这里只委托，别各写一份。
        assertEquals(OfflineEmpty.Tracks.message(), discoverEmptyNotice(isOffline = true).message())
        assertEquals("曲库为空 —— 去搜索页搜歌并下载", discoverEmptyNotice(isOffline = false).message())
    }

    @Test
    fun `文案里不会再提到并不存在的设置页刷新曲库入口`() {
        DiscoverEmpty.entries.forEach { kind ->
            assertFalse(
                "「${kind.message()}」指向的设置页刷新曲库入口不存在（报告 #26）",
                kind.message().contains("刷新曲库")
            )
        }
    }
}
