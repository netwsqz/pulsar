package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Track

/**
 * 已下载列表的一行：一张专辑 + 它**已下载的那些曲目**（展开逐首删要用）。
 * [tracks] 只含完整副本（字节真相，spec 铁律 3）；[totalCount] 是这张专辑在曲库里一共几首。
 */
data class CachedAlbum(val album: Album, val tracks: List<Track>, val totalCount: Int)

/**
 * 把「有完整副本的歌」按专辑归组。只有至少下了一首的专辑才出现；
 * 专辑对象优先从 [albums] 里按专辑名配对（Track 只有专辑名没有专辑 ID），配不上的
 * 从曲目反推一张卡片（[toAlbumCard]）——专辑列表本来就可能缺席（快照里只有曲目表），
 * 此时显示专辑名 + 歌手就够用，这张行不跳详情页。同名专辑（不同歌手）并成一组，
 * 与离线专辑详情页同一代价。
 */
internal fun groupCachedByAlbum(
    tracks: List<Track>,
    albums: List<Album>,
    cachedTrackIds: Set<String>
): List<CachedAlbum> {
    if (cachedTrackIds.isEmpty()) return emptyList()
    val cachedByAlbumName = tracks.filter { it.id in cachedTrackIds }.groupBy { it.album }
    if (cachedByAlbumName.isEmpty()) return emptyList()
    val albumByName = albums.associateBy { it.name }
    return cachedByAlbumName.map { (name, cachedTracks) ->
        val album = albumByName[name] ?: cachedTracks.first().toAlbumCard()
        CachedAlbum(album, cachedTracks, tracks.count { it.album == name })
    }.sortedWith(compareBy({ it.album.artist }, { it.album.name }))
}
