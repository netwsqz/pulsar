package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import com.example.pulsar.offline.albumIdentityOf
import com.example.pulsar.offline.albumsFromTracks

/**
 * 离线时界面上**实际能显示**的那一份曲库（ADR-0008）。
 *
 * 离线只显示已下载的东西——不再「全量 + 标灰」。原始状态（[JellyfinUiState]）一律不动，
 * 这里派生出渲染用的列表：回在线时自动恢复全量，不需要重拉。
 *
 * **null 就是 null**：数据还没拉到（骨架屏）的屏保持 null，别变成「空列表」把骨架换成空态。
 */
internal data class VisibleLibrary(
    val tracks: List<Track>?,
    val albums: List<Album>?,
    val discoverAlbums: List<Album>?,
    val albumTracks: List<Track>?,
    val playlistTracks: List<Track>?,
    val searchResults: SearchResults?
)

/**
 * 按「已下载的曲目 id」把曲库筛一遍。
 *
 * - 曲目：只留 [visibleTrackIds] 里的。
 * - 专辑：**有一首已下载曲目就保留**（选曲下载的专辑不该整张消失）。按**专辑身份**
 *   （`offline.AlbumIdentity`：去空白 + 大小写不敏感）配对——[Track] 只有专辑名没有专辑 id，
 *   同名不同歌手的会并在一起，与 [groupCachedByAlbum] 同一代价。
 * - 搜索：曲目/专辑同一口径，播放列表原样（离线搜索本来就搜不到列表）。
 *
 * [discoverAlbums] 默认跟随 [albums]：发现页的随机专辑墙和专辑页同一套「有没有下载」判断。
 *
 * **专辑列表可以缺席**：`albums` 只由「逛过专辑页」或「快照里恰好有」才填得上，断网那一刻
 * 经常是 null（登录时 `replaceSnapshot` 只写曲目表）。这时从已下载的曲目**反推**专辑卡片，
 * 否则专辑页会显示「一首都没下载」，而歌曲页明明列着这些歌。
 * 反推出来的专辑 id 用专辑名（离线不存在服务端 id 的用途：详情页走 `offlineAlbumTracks`
 * 按名匹配），歌手同样从曲目里取。
 */
internal fun offlineVisibleLibrary(
    tracks: List<Track>?,
    albums: List<Album>?,
    discoverAlbums: List<Album>? = albums,
    albumTracks: List<Track>? = null,
    playlistTracks: List<Track>? = null,
    searchResults: SearchResults? = null,
    visibleTrackIds: Set<String>
): VisibleLibrary {
    val downloaded = tracks.orEmpty().filter { it.id in visibleTrackIds }
    val downloadedAlbumKeys = downloaded
        .map { albumIdentityOf(it) }
        .filter { it.key.isNotEmpty() }
        .mapTo(mutableSetOf()) { it.key }

    /** 从已下载曲目反推的专辑卡（空白专辑名不建组）。 */
    fun derivedAlbums(): List<Album> = albumsFromTracks(downloaded)

    /**
     * 筛一张专辑列表，并用反推结果补上列表里缺席的那些（快照过旧 / 压根没拉到）。
     * `null` 传进来表示「没拉到」——能反推就反推，反推不出来（一首都没下载）才保持 null，
     * 别把骨架屏换成空态。
     */
    fun albumsWithDownloads(list: List<Album>?): List<Album>? {
        val matched = list.orEmpty()
            .map { it to albumIdentityOf(it).key }
            .filter { (_, key) -> key in downloadedAlbumKeys }
        val matchedKeys = matched.mapTo(mutableSetOf()) { (_, key) -> key }
        val merged = matched.map { (album, _) -> album } +
            derivedAlbums().filter { albumIdentityOf(it).key !in matchedKeys }
        return merged.ifEmpty { if (list == null) null else emptyList() }
    }

    return VisibleLibrary(
        tracks = tracks?.filter { it.id in visibleTrackIds },
        albums = albumsWithDownloads(albums),
        discoverAlbums = albumsWithDownloads(discoverAlbums),
        albumTracks = albumTracks?.filter { it.id in visibleTrackIds },
        playlistTracks = playlistTracks?.filter { it.id in visibleTrackIds },
        searchResults = searchResults?.let {
            SearchResults(
                tracks = it.tracks.filter { track -> track.id in visibleTrackIds },
                albums = albumsWithDownloads(it.albums).orEmpty(),
                playlists = it.playlists
            )
        }
    )
}
