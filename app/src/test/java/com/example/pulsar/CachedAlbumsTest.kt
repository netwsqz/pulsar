package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/** 已缓存列表的分组规则：把「有完整副本的歌」按专辑归组（CONTEXT.md「已缓存列表」）。 */
class CachedAlbumsTest {
    private val trackA1 = Track("t1", "Song A1", "Artist X", "Album A", 1000L, null)
    private val trackA2 = Track("t2", "Song A2", "Artist X", "Album A", 1000L, null)
    private val trackB1 = Track("t3", "Song B1", "Artist Y", "Album B", 1000L, null)
    private val albumA = Album("a1", "Album A", "Artist X", trackCount = null, imageTag = null)
    private val albumB = Album("a2", "Album B", "Artist Y", trackCount = null, imageTag = null)

    @Test
    fun `按专辑分组并统计缓存进度，按歌手加专辑名排序`() {
        val result = groupCachedByAlbum(
            tracks = listOf(trackA1, trackA2, trackB1),
            albums = listOf(albumB, albumA),
            cachedTrackIds = setOf("t1", "t3")
        )
        assertEquals(
            listOf(
                CachedAlbum(albumA, cachedCount = 1, totalCount = 2),
                CachedAlbum(albumB, cachedCount = 1, totalCount = 1)
            ),
            result
        )
    }

    @Test
    fun `一首副本都没有的专辑不出现`() {
        val result = groupCachedByAlbum(
            tracks = listOf(trackA1, trackB1),
            albums = listOf(albumA, albumB),
            cachedTrackIds = setOf("t3")
        )
        assertEquals(listOf(CachedAlbum(albumB, cachedCount = 1, totalCount = 1)), result)
    }

    @Test
    fun `曲库专辑列表里配不上的专辑名不显示（点不了详情页）`() {
        val result = groupCachedByAlbum(
            tracks = listOf(trackA1, trackB1),
            albums = listOf(albumB),
            cachedTrackIds = setOf("t1", "t3")
        )
        assertEquals(listOf(CachedAlbum(albumB, cachedCount = 1, totalCount = 1)), result)
    }

    @Test
    fun `没有完整副本时列表为空`() {
        val result = groupCachedByAlbum(
            tracks = listOf(trackA1),
            albums = listOf(albumA),
            cachedTrackIds = emptySet()
        )
        assertEquals(emptyList<CachedAlbum>(), result)
    }
}
