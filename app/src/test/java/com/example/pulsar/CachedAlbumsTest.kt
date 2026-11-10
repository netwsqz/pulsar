package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/** 已下载列表的分组规则：把「有完整副本的歌」按专辑归组（CONTEXT.md「已下载列表」）。 */
class CachedAlbumsTest {
    private val trackA1 = Track("t1", "Song A1", "Artist X", "Album A", 1000L, null)
    private val trackA2 = Track("t2", "Song A2", "Artist X", "Album A", 1000L, null)
    private val trackB1 = Track("t3", "Song B1", "Artist Y", "Album B", 1000L, null)
    private val albumA = Album("a1", "Album A", "Artist X", trackCount = null, imageTag = null)
    private val albumB = Album("a2", "Album B", "Artist Y", trackCount = null, imageTag = null)

    @Test
    fun `按专辑分组并统计下载进度，按歌手加专辑名排序`() {
        val result = groupCachedByAlbum(
            tracks = listOf(trackA1, trackA2, trackB1),
            albums = listOf(albumB, albumA),
            cachedTrackIds = setOf("t1", "t3")
        )
        assertEquals(
            listOf(
                CachedAlbum(albumA, tracks = listOf(trackA1), totalCount = 2),
                CachedAlbum(albumB, tracks = listOf(trackB1), totalCount = 1)
            ),
            result
        )
    }

    @Test
    fun `展开用的曲目列表只含已下载的那些，没下载的不进来`() {
        val result = groupCachedByAlbum(
            tracks = listOf(trackA1, trackA2),
            albums = listOf(albumA),
            cachedTrackIds = setOf("t2")
        )
        // 展开后逐首删只能看见真的在盘上的那首；总数仍是 2（界面显示 1/2）
        assertEquals(listOf(CachedAlbum(albumA, tracks = listOf(trackA2), totalCount = 2)), result)
    }

    @Test
    fun `一首副本都没有的专辑不出现`() {
        val result = groupCachedByAlbum(
            tracks = listOf(trackA1, trackB1),
            albums = listOf(albumA, albumB),
            cachedTrackIds = setOf("t3")
        )
        assertEquals(listOf(CachedAlbum(albumB, tracks = listOf(trackB1), totalCount = 1)), result)
    }

    @Test
    fun `曲库专辑列表里配不上的专辑从曲目反推，不再整行丢掉`() {
        // 回归：快照里常常只有曲目表、没有专辑列表，配不上就丢会让「下载完的专辑」整页空白。
        val result = groupCachedByAlbum(
            tracks = listOf(trackA1, trackB1),
            albums = listOf(albumB),
            cachedTrackIds = setOf("t1", "t3")
        )
        assertEquals(listOf("Album A", "Album B"), result.map { it.album.name })
        // 配得上的仍用真对象（有 id、能显示封面），反推的 id 退回专辑名。
        assertEquals(listOf("Album A", albumB.id), result.map { it.album.id })
        assertEquals(listOf(1, 1), result.map { it.tracks.size })
    }

    @Test
    fun `专辑名大小写或空白不同也归到同一张卡`() {
        val trackACaseVariant = Track("t4", "Song A3", "Artist X", " album a ", 1000L, null)
        val result = groupCachedByAlbum(
            tracks = listOf(trackA1, trackACaseVariant, trackB1),
            albums = listOf(albumB, albumA),
            cachedTrackIds = setOf("t1", "t4")
        )
        // 归一化后是同一张专辑：真专辑对象仍优先，统计按归一化名算。
        assertEquals(
            listOf(CachedAlbum(albumA, tracks = listOf(trackA1, trackACaseVariant), totalCount = 2)),
            result
        )
    }

    @Test
    fun `空白专辑名的曲目不建组`() {
        val blankAlbum = Track("t9", "Song Z", "Artist X", "   ", 1000L, null)
        val result = groupCachedByAlbum(
            tracks = listOf(trackA1, blankAlbum),
            albums = listOf(albumA),
            cachedTrackIds = setOf("t1", "t9")
        )
        // 没有专辑名就不该造一行空名字的专辑。
        assertEquals(listOf(CachedAlbum(albumA, tracks = listOf(trackA1), totalCount = 1)), result)
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
