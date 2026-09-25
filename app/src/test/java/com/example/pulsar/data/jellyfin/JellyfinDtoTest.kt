package com.example.pulsar.data.jellyfin

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JellyfinDtoTest {
    @Test
    fun `maps ticks and artist fields to track`() {
        val track = JellyfinItemDto(
            id = "song-1",
            name = "Song",
            album = "Album",
            albumArtist = "Album Artist",
            artists = listOf("Artist"),
            runTimeTicks = 123_450_000,
            imageTags = mapOf("Primary" to "tag")
        ).toTrack()

        assertEquals(
            Track("song-1", "Song", "Artist", "Album", 12_345, "tag"),
            track
        )
    }

    @Test
    fun `uses album artist and nullable duration when item lacks artists`() {
        val track = JellyfinItemDto("song-2", "Song", albumArtist = "Album Artist").toTrack()

        assertEquals("Album Artist", track.artist)
        assertNull(track.durationMs)
    }

    @Test
    fun `uses stable fallback for missing artist`() {
        assertEquals("Unknown artist", JellyfinItemDto("song-3", "Song").toTrack().artist)
    }

    @Test
    fun `maps file path for library file operations`() {
        // Path 是 Jellyfin 返回的绝对路径（仅管理员可见），删歌/改名要拿它定位文件；
        // 没有这个字段时为 null，文件操作菜单对这首歌不可用。
        val withPath = JellyfinItemDto("song-4", "Song", path = "E:\\file\\Te_Music\\a.flac").toTrack()
        assertEquals("E:\\file\\Te_Music\\a.flac", withPath.filePath)

        assertNull(JellyfinItemDto("song-5", "Song").toTrack().filePath)
    }

    @Test
    fun `maps album fields preferring album artist`() {
        val album = JellyfinItemDto(
            id = "album-1",
            name = "Great Album",
            albumArtist = "Album Artist",
            artists = listOf("Performer"),
            childCount = 10,
            imageTags = mapOf("Primary" to "tag-1")
        ).toAlbum()

        assertEquals(Album("album-1", "Great Album", "Album Artist", 10, "tag-1"), album)
    }

    @Test
    fun `album falls back to first artist when album artist missing`() {
        val album = JellyfinItemDto("album-2", "Great Album", artists = listOf("Performer")).toAlbum()

        assertEquals("Performer", album.artist)
    }

    @Test
    fun `album uses stable fallback when no artist present`() {
        assertEquals("Unknown artist", JellyfinItemDto("album-3", "Great Album").toAlbum().artist)
    }

    @Test
    fun `album track count is nullable when child count missing`() {
        assertNull(JellyfinItemDto("album-4", "Great Album").toAlbum().trackCount)
    }

    @Test
    fun `album image tag prefers the Primary image`() {
        val album = JellyfinItemDto(
            id = "album-5",
            name = "Great Album",
            imageTags = mapOf("Backdrop" to "b", "Primary" to "p")
        ).toAlbum()

        assertEquals("p", album.imageTag)
    }

    @Test
    fun `album image tag is null when no Primary image exists`() {
        val album = JellyfinItemDto(
            id = "album-6",
            name = "Great Album",
            imageTags = mapOf("Backdrop" to "b")
        ).toAlbum()

        assertNull(album.imageTag)
    }

    @Test
    fun `maps playlist fields preferring Primary image`() {
        val playlist = JellyfinItemDto(
            id = "playlist-1",
            name = "Morning Run",
            childCount = 12,
            imageTags = mapOf("Primary" to "tag-1")
        ).toPlaylist()

        assertEquals(Playlist("playlist-1", "Morning Run", 12, "tag-1"), playlist)
    }

    @Test
    fun `playlist track count and image are nullable when missing`() {
        val playlist = JellyfinItemDto("playlist-2", "Empty").toPlaylist()

        assertNull(playlist.trackCount)
        assertNull(playlist.imageTag)
    }
}
