package com.example.pulsar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 顶层导航契约（工单 04）：顺序、文案、非顶层页的哨兵值。纯函数，无 Compose 依赖。 */
class LibrarySectionTest {

    @Test
    fun `top level tabs are library browsing dimensions only`() {
        assertEquals(
            listOf(
                LibrarySection.Discover,
                LibrarySection.Tracks,
                LibrarySection.Albums,
                LibrarySection.Artists,
                LibrarySection.Genres,
                LibrarySection.Playlists
            ),
            TopLevelSections.map { it.first }
        )
        // QQ 下载退出顶层导航（ADR-0006）：它是「往曲库里加东西」的工具，不是曲库的一个维度。
        assertTrue(TopLevelSections.none { it.first == LibrarySection.DownloadService })
    }

    @Test
    fun `nav index follows tab order and is -1 for non top level pages`() {
        assertEquals(0, LibrarySection.Discover.navIndex())
        assertEquals(1, LibrarySection.Tracks.navIndex())
        assertEquals(3, LibrarySection.Artists.navIndex())
        assertEquals(-1, LibrarySection.DownloadService.navIndex())
        assertEquals(-1, LibrarySection.Search.navIndex())
        assertEquals(-1, LibrarySection.Settings.navIndex())
        assertEquals(-1, LibrarySection.CachedCopies.navIndex())
    }

    @Test
    fun `download service is a detail page reached from settings`() {
        assertTrue(LibrarySection.DownloadService.isDetail())
        assertNull(LibrarySection.DownloadService.navLabel())
    }

    @Test
    fun `group detail is a non top level detail page`() {
        val group = LibrarySection.GroupDetail(name = "Rock", isGenre = true)
        assertEquals(-1, group.navIndex())
        assertNull(group.navLabel())
        assertTrue(group.isDetail())
    }

    @Test
    fun `nav label matches the chip text and is null for non top level pages`() {
        assertEquals("发现", LibrarySection.Discover.navLabel())
        assertEquals("歌曲", LibrarySection.Tracks.navLabel())
        assertEquals("艺术家", LibrarySection.Artists.navLabel())
        assertEquals("流派", LibrarySection.Genres.navLabel())
        assertNull(LibrarySection.DownloadService.navLabel())
        assertNull(LibrarySection.Search.navLabel())
        assertNull(LibrarySection.Settings.navLabel())
    }
}
