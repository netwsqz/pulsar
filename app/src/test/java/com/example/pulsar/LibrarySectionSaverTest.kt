package com.example.pulsar

import androidx.compose.runtime.saveable.SaverScope
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 导航分区的保存器（`docs/library-state-refactor.md` Step 5 / #16）。
 *
 * 转屏 / Activity 重建后当前页签与详情页不丢：`remember` 只活一次组合，换成
 * `rememberSaveable` 后必须能把每个 [LibrarySection] 变体编码进 Bundle 再原样还原。
 * 详情页带的是整份 `Album` / `Playlist`（返回后要能继续拉曲目），所以断言用**结构相等**。
 */
class LibrarySectionSaverTest {

    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private fun roundTrip(section: LibrarySection): LibrarySection? {
        val saved = with(LibrarySectionSaver) { scope.save(section) }
        return LibrarySectionSaver.restore(saved!!)
    }

    @Test
    fun `顶层页签都能保存与恢复`() {
        TopLevelSections.forEach { (section, _) ->
            assertEquals(section, roundTrip(section))
        }
    }

    @Test
    fun `非顶层页都能保存与恢复`() {
        listOf(
            LibrarySection.Search,
            LibrarySection.Settings,
            LibrarySection.CachedCopies,
            LibrarySection.DownloadService,
        ).forEach { section ->
            assertEquals(section, roundTrip(section))
        }
    }

    @Test
    fun `专辑详情带整份专辑（含流派）保存与恢复`() {
        val album = Album(
            id = "album-1",
            name = "七里香",
            artist = "周杰伦",
            trackCount = 10,
            imageTag = "tag-a",
            genres = listOf("流行", "华语"),
        )

        assertEquals(LibrarySection.AlbumDetail(album), roundTrip(LibrarySection.AlbumDetail(album)))
    }

    @Test
    fun `专辑详情缺歌曲数与封面时也能原样恢复`() {
        val album = Album(id = "album-2", name = "无封面", artist = "歌手", trackCount = null, imageTag = null)

        assertEquals(LibrarySection.AlbumDetail(album), roundTrip(LibrarySection.AlbumDetail(album)))
    }

    @Test
    fun `播放列表详情保存与恢复`() {
        val playlist = Playlist(id = "p-1", name = "跑步", trackCount = null, imageTag = "tag-p")

        assertEquals(LibrarySection.PlaylistDetail(playlist), roundTrip(LibrarySection.PlaylistDetail(playlist)))
    }

    @Test
    fun `艺术家组详情保存与恢复`() {
        assertEquals(
            LibrarySection.GroupDetail("周杰伦"),
            roundTrip(LibrarySection.GroupDetail("周杰伦")),
        )
    }

    @Test
    fun `无法识别的存档返回 null 而不是崩溃`() {
        assertNull(LibrarySectionSaver.restore("not a saved section"))
    }
}
