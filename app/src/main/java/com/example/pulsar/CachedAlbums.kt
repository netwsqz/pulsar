package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Track

/** 已缓存列表的一行：一张专辑 + 它的完整副本进度（CONTEXT.md「已缓存列表」）。 */
data class CachedAlbum(val album: Album, val cachedCount: Int, val totalCount: Int)

/**
 * 把「有完整副本的歌」按专辑归组。只有 cachedCount ≥ 1 的专辑才出现；
 * 专辑对象从 [albums] 里按专辑名配对（Track 只有专辑名没有专辑 ID），配不上的
 * 不显示——没有 Album 对象就开不了详情页。同名专辑（不同歌手）并成一组，
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
    return cachedByAlbumName.mapNotNull { (name, cachedTracks) ->
        val album = albumByName[name] ?: return@mapNotNull null
        CachedAlbum(album, cachedTracks.size, tracks.count { it.album == name })
    }.sortedWith(compareBy({ it.album.artist }, { it.album.name }))
}
