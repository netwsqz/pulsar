package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Track
import com.example.pulsar.offline.AlbumIdentity
import com.example.pulsar.offline.albumIdentityOf
import com.example.pulsar.offline.toAlbumCard

/**
 * 已下载列表的一行：一张专辑 + 它**已下载的那些曲目**（展开逐首删要用）。
 * [tracks] 只含完整副本（字节真相，spec 铁律 3）；[totalCount] 是这张专辑在曲库里一共几首。
 */
data class CachedAlbum(val album: Album, val tracks: List<Track>, val totalCount: Int)

/**
 * 把「有完整副本的歌」按专辑归组。只有至少下了一首的专辑才出现；
 * 专辑对象优先从 [albums] 里按**专辑身份**配对（[albumIdentityOf]：去空白 + 大小写不敏感），
 * 配不上的从曲目反推一张卡片——专辑列表本来就可能缺席（快照里只有曲目表），
 * 此时显示专辑名 + 歌手就够用，这张行不跳详情页。同名专辑（不同歌手）并成一组，
 * 与离线专辑详情页同一代价。空白专辑名的曲目不建组。
 */
internal fun groupCachedByAlbum(
    tracks: List<Track>,
    albums: List<Album>,
    cachedTrackIds: Set<String>
): List<CachedAlbum> {
    if (cachedTrackIds.isEmpty()) return emptyList()
    // 键 = 归一化专辑名；值 = (首次出现的身份, 已下载曲目)
    val cachedByAlbum = linkedMapOf<String, Pair<AlbumIdentity, MutableList<Track>>>()
    tracks.filter { it.id in cachedTrackIds }.forEach { track ->
        val identity = albumIdentityOf(track)
        if (identity.key.isEmpty()) return@forEach
        cachedByAlbum.getOrPut(identity.key) { identity to mutableListOf() }.second += track
    }
    if (cachedByAlbum.isEmpty()) return emptyList()
    val albumByKey = albums
        .map { albumIdentityOf(it) to it }
        .filter { (identity, _) -> identity.key.isNotEmpty() }
        .associate { (identity, album) -> identity.key to album }
    val totalByKey = tracks
        .map { albumIdentityOf(it) }
        .filter { it.key.isNotEmpty() }
        .groupingBy { it.key }
        .eachCount()
    return cachedByAlbum.map { (key, entry) ->
        val (identity, cachedTracks) = entry
        CachedAlbum(
            album = albumByKey[key] ?: identity.toAlbumCard(),
            tracks = cachedTracks,
            totalCount = totalByKey[key] ?: cachedTracks.size
        )
    }.sortedWith(compareBy({ it.album.artist }, { it.album.name }))
}
