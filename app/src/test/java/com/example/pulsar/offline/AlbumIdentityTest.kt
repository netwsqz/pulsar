package com.example.pulsar.offline

import com.example.pulsar.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 专辑身份（#9）：按**专辑名**认专辑，归一化规则为「去首尾空白 + 大小写不敏感」。
 *
 * 三处分组（离线专辑墙 / 已下载列表 / 艺术家组详情）共用这一份实现，不再各写一套。
 */
class AlbumIdentityTest {

    private fun track(id: String, artist: String, album: String) =
        Track(id = id, title = "Song $id", artist = artist, album = album, durationMs = 1000L, imageTag = null)

    @Test
    fun `专辑名去首尾空白并大小写不敏感`() {
        val identity = albumIdentityOf(track("t1", "Artist X", "  Album A  "))

        assertEquals("album a", identity.key)
        assertEquals("Album A", identity.displayName)
        assertEquals("Artist X", identity.artist)
    }

    @Test
    fun `空白专辑名不构成专辑身份`() {
        assertTrue(albumIdentityOf(track("t1", "Artist X", "   ")).key.isEmpty())
    }

    @Test
    fun `大小写和空白不同的同名曲目反推成同一张专辑卡`() {
        val albums = albumsFromTracks(
            listOf(
                track("t1", "Artist X", "Album A"),
                track("t2", "Artist X", " album a "),
                track("t3", "Artist Y", "Album B")
            )
        )

        // 顺序按首次出现；显示名与 id 取首次出现的写法。
        assertEquals(listOf("Album A", "Album B"), albums.map { it.name })
        assertEquals(listOf("Album A", "Album B"), albums.map { it.id })
        assertEquals(listOf("Artist X", "Artist Y"), albums.map { it.artist })
    }

    @Test
    fun `空白专辑名不产生专辑卡`() {
        val albums = albumsFromTracks(
            listOf(
                track("t1", "Artist X", "   "),
                track("t2", "Artist Y", "Album B")
            )
        )

        assertEquals(listOf("Album B"), albums.map { it.name })
    }

    @Test
    fun `同名不同歌手并成一张，歌手取首次出现的`() {
        val albums = albumsFromTracks(
            listOf(
                track("t1", "Artist X", "Same Album"),
                track("t2", "Artist Y", "same album")
            )
        )

        assertEquals(1, albums.size)
        assertEquals("Artist X", albums.single().artist)
    }
}
