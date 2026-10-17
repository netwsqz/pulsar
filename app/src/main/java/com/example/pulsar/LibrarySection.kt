package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist

internal sealed interface LibrarySection {
    data object Discover : LibrarySection
    data object Tracks : LibrarySection
    data object Albums : LibrarySection
    data object Artists : LibrarySection
    data object Genres : LibrarySection
    data object Playlists : LibrarySection
    data object Search : LibrarySection
    data object QQDownload : LibrarySection
    data object Settings : LibrarySection
    data object CachedCopies : LibrarySection
    data class AlbumDetail(val album: Album) : LibrarySection
    data class PlaylistDetail(val playlist: Playlist) : LibrarySection
}

/**
 * 顶层导航顺序与文案（工单 04）：对齐 Auxio 的 tab 语义——发现 + Songs / Albums / Artists /
 * Genres / Playlists；QQ 下载是 Pulsar 自有入口，排在最后。
 *
 * 详情页 / 搜索 / 设置 / 已缓存不在此列：它们从别处进入，不是顶层页签。
 */
internal val TopLevelSections: List<Pair<LibrarySection, String>> = listOf(
    LibrarySection.Discover to "发现",
    LibrarySection.Tracks to "歌曲",
    LibrarySection.Albums to "专辑",
    LibrarySection.Artists to "艺术家",
    LibrarySection.Genres to "流派",
    LibrarySection.Playlists to "播放列表",
    LibrarySection.QQDownload to "QQ 下载"
)

/** 顶层页签序号（决定左右切换方向）；非顶层页返回 -1。 */
internal fun LibrarySection.navIndex(): Int =
    TopLevelSections.indexOfFirst { it.first == this }

internal fun LibrarySection.isDetail(): Boolean =
    this is LibrarySection.AlbumDetail ||
        this is LibrarySection.PlaylistDetail ||
        this is LibrarySection.Settings ||
        this is LibrarySection.CachedCopies
