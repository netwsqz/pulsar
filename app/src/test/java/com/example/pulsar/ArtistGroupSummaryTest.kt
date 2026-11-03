package com.example.pulsar

import com.example.pulsar.domain.model.Album
import org.junit.Assert.assertEquals
import org.junit.Test

/** 艺术家组详情摘要（工单 11）：与专辑/播放列表同一套头部语言，计数跟网格实际卡片数同源。 */
class ArtistGroupSummaryTest {

    private fun album(name: String) =
        Album(id = name, name = name, artist = "歌手", trackCount = null, imageTag = null)

    @Test
    fun `在线时用网格实际卡片数`() {
        assertEquals(
            "3 张专辑",
            artistGroupSummary(listOf(album("a"), album("b"), album("c")), isOfflineView = false)
        )
    }

    @Test
    fun `在线时没有专辑显示 0 张专辑`() {
        assertEquals("0 张专辑", artistGroupSummary(emptyList(), isOfflineView = false))
    }

    @Test
    fun `离线时按已下载张数并给出服务端总数`() {
        assertEquals(
            "已下载 2 张 · 共 5 张",
            artistGroupSummary(listOf(album("a"), album("b")), isOfflineView = true, serverTotal = 5)
        )
    }

    @Test
    fun `离线服务端总数滞后时不小于可见张数`() {
        assertEquals(
            "已下载 3 张 · 共 3 张",
            artistGroupSummary(listOf(album("a"), album("b"), album("c")), isOfflineView = true, serverTotal = 1)
        )
    }

    @Test
    fun `离线不知道服务端总数时总数取可见张数`() {
        assertEquals(
            "已下载 2 张 · 共 2 张",
            artistGroupSummary(listOf(album("a"), album("b")), isOfflineView = true, serverTotal = null)
        )
    }
}
