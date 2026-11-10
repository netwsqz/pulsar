package com.example.pulsar.offline

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Track

/**
 * 专辑身份：按**专辑名**认专辑（[Track] 只有专辑名，没有专辑 id）。
 *
 * 归一化规则与艺术家分组一致：**去首尾空白 + 大小写不敏感**，显示名取首次出现的写法。
 * 离线专辑墙（`offlineVisibleLibrary`）与已下载列表（`groupCachedByAlbum`）共用这一份实现；
 * 艺术家组详情只共用名字归一化（[normalizedNameKey]），分组维度仍是艺术家。
 *
 * 代价：同名不同歌手会并成一张专辑——这是现有规则（按专辑名配对），不是新引入的。
 * 空白专辑名不构成身份（[key] 为空），调用方按它跳过，别造一张没名字的专辑。
 */
internal data class AlbumIdentity(
    /** 归一化后的专辑名（大小写 / 空白）。 */
    val key: String,
    /** 首次出现的显示写法（已去首尾空白）。 */
    val displayName: String,
    val artist: String
)

internal fun albumIdentityOf(track: Track): AlbumIdentity =
    albumIdentityOf(name = track.album, artist = track.artist)

internal fun albumIdentityOf(album: Album): AlbumIdentity =
    albumIdentityOf(name = album.name, artist = album.artist)

internal fun albumIdentityOf(name: String, artist: String): AlbumIdentity {
    val displayName = name.trim()
    return AlbumIdentity(
        key = normalizedNameKey(name).orEmpty(),
        displayName = displayName,
        artist = artist
    )
}

/**
 * 名字归一化：**去首尾空白 + 小写**；空白返回 null（不建组）。
 *
 * 专辑身份与艺术家分组共用这一条规则，别再各写一套 `trim().lowercase()`。
 */
internal fun normalizedNameKey(raw: String): String? =
    raw.trim().takeIf { it.isNotEmpty() }?.lowercase()

/** 服务端专辑对象缺席时的反推卡片：id 用显示名（曲目和下载行都没有专辑 id 可用）。 */
internal fun AlbumIdentity.toAlbumCard(): Album =
    Album(id = displayName, name = displayName, artist = artist, trackCount = null, imageTag = null)

/**
 * 从曲目反推专辑卡：按专辑身份归堆，每堆取**首次出现**的曲目的歌手与写法。
 * 空白专辑名跳过；顺序按首次出现，稳定可测。
 */
internal fun albumsFromTracks(tracks: List<Track>): List<Album> =
    tracks
        .map { albumIdentityOf(it) }
        .filter { it.key.isNotEmpty() }
        .distinctBy { it.key }
        .map { it.toAlbumCard() }
