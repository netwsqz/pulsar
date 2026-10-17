package com.example.pulsar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 顶层导航契约（工单 04）：顺序、文案、非顶层页的哨兵值。纯函数，无 Compose 依赖。 */
class LibrarySectionTest {

    @Test
    fun `top level tabs follow the Auxio order with QQ download last`() {
        assertEquals(
            listOf(
                LibrarySection.Discover,
                LibrarySection.Tracks,
                LibrarySection.Albums,
                LibrarySection.Artists,
                LibrarySection.Genres,
                LibrarySection.Playlists,
                LibrarySection.QQDownload
            ),
            TopLevelSections.map { it.first }
        )
    }

    @Test
    fun `nav index follows tab order and is -1 for non top level pages`() {
        assertEquals(0, LibrarySection.Discover.navIndex())
        assertEquals(1, LibrarySection.Tracks.navIndex())
        assertEquals(3, LibrarySection.Artists.navIndex())
        assertEquals(6, LibrarySection.QQDownload.navIndex())
        assertEquals(-1, LibrarySection.Search.navIndex())
        assertEquals(-1, LibrarySection.Settings.navIndex())
        assertEquals(-1, LibrarySection.CachedCopies.navIndex())
    }

    @Test
    fun `nav label matches the chip text and is null for non top level pages`() {
        assertEquals("发现", LibrarySection.Discover.navLabel())
        assertEquals("歌曲", LibrarySection.Tracks.navLabel())
        assertEquals("艺术家", LibrarySection.Artists.navLabel())
        assertEquals("流派", LibrarySection.Genres.navLabel())
        assertEquals("QQ 下载", LibrarySection.QQDownload.navLabel())
        assertNull(LibrarySection.Search.navLabel())
        assertNull(LibrarySection.Settings.navLabel())
    }
}
