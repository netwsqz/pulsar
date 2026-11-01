package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist

internal sealed interface LibrarySection {
    data object Discover : LibrarySection
    data object Tracks : LibrarySection
    data object Albums : LibrarySection
    data object Artists : LibrarySection
    data object Playlists : LibrarySection
    data object Search : LibrarySection
    /** QQ 音乐导入页（ADR-0006 / ADR-0007）：从「设置 → QQ 音乐导入」进入，不再占顶层 chip。 */
    data object DownloadService : LibrarySection
    data object Settings : LibrarySection
    data object CachedCopies : LibrarySection
    data class AlbumDetail(val album: Album) : LibrarySection
    data class PlaylistDetail(val playlist: Playlist) : LibrarySection

    /**
     * 艺术家分组详情（工单 06）：点艺术家进入，列出它下面的专辑。
     * 只带组名，专辑从已加载的 [com.example.pulsar.JellyfinUiState.albums] 现算——
     * 不复制一份列表进导航状态，数据刷新后详情页自动跟着变。
     */
    data class GroupDetail(val name: String) : LibrarySection
}

/**
 * 顶层导航顺序与文案（工单 04）：对齐 Auxio 的 tab 语义——发现 + Songs / Albums / Artists /
 * Playlists。流派页已删除（ADR-0009），顶层只留这五个曲库浏览维度。
 *
 * 只放「曲库的浏览维度」。QQ 音乐导入是「往曲库里加东西」的工具，已降级为设置里的二级页
 * （ADR-0006），不在此列。详情页 / 搜索 / 设置 / 已下载同样从别处进入，不是顶层页签。
 * 文案跟着 [LibrarySection] 放这里，避免 chip 和占位页各写一份标题。
 */
internal val TopLevelSections: List<Pair<LibrarySection, String>> = listOf(
    LibrarySection.Discover to "发现",
    LibrarySection.Tracks to "歌曲",
    LibrarySection.Albums to "专辑",
    LibrarySection.Artists to "艺术家",
    LibrarySection.Playlists to "播放列表"
)

/** 顶层页签序号（决定左右切换方向）；非顶层页返回 -1。 */
internal fun LibrarySection.navIndex(): Int =
    TopLevelSections.indexOfFirst { it.first == this }

/** 顶层页签文案；非顶层页返回 null。 */
internal fun LibrarySection.navLabel(): String? =
    TopLevelSections.firstOrNull { it.first == this }?.second

internal fun LibrarySection.isDetail(): Boolean =
    this is LibrarySection.AlbumDetail ||
        this is LibrarySection.PlaylistDetail ||
        this is LibrarySection.GroupDetail ||
        this is LibrarySection.Settings ||
        this is LibrarySection.CachedCopies ||
        this is LibrarySection.DownloadService
