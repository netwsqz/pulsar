package com.example.pulsar

import androidx.compose.runtime.saveable.Saver
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
     * 只带组名，专辑从已加载的曲库（`JellyfinUiState.library.albums`）现算——
     * 不复制一份列表进导航状态，数据刷新后详情页自动跟着变。
     */
    data class GroupDetail(val name: String) : LibrarySection
}

/**
 * 导航分区的保存器（`docs/library-state-refactor.md` Step 5 / #16）。
 *
 * 当前页签与详情页存进 `rememberSaveable`：转屏 / Activity 重建后不丢（改前用 `remember`，
 * 一重建就落回发现页）。编码成 JSON 字符串是 Bundle 能存的最小形态；详情页连整份
 * [Album] / [Playlist] 一起存，还原后能直接继续拉曲目。
 *
 * 无法识别的存档（版本升级改了形状）返回 null，界面落回默认页签，不崩。
 */
internal val LibrarySectionSaver: Saver<LibrarySection, String> = Saver(
    save = { section -> Json.encodeToString(SavedSection.from(section)) },
    restore = { encoded ->
        runCatching { SavedSection.toSection(Json.decodeFromString<SavedSection>(encoded)) }.getOrNull()
    }
)

/** 存档形状：只保留还原一个分区需要的字段（不存集合与列表，导航状态只描述「在哪」）。 */
@Serializable
private data class SavedSection(
    val kind: String,
    val album: Album? = null,
    val playlist: Playlist? = null,
    val name: String? = null,
) {
    companion object {
        fun from(section: LibrarySection): SavedSection = when (section) {
            LibrarySection.Discover -> SavedSection("discover")
            LibrarySection.Tracks -> SavedSection("tracks")
            LibrarySection.Albums -> SavedSection("albums")
            LibrarySection.Artists -> SavedSection("artists")
            LibrarySection.Playlists -> SavedSection("playlists")
            LibrarySection.Search -> SavedSection("search")
            LibrarySection.Settings -> SavedSection("settings")
            LibrarySection.CachedCopies -> SavedSection("cachedCopies")
            LibrarySection.DownloadService -> SavedSection("downloadService")
            is LibrarySection.AlbumDetail -> SavedSection("albumDetail", album = section.album)
            is LibrarySection.PlaylistDetail -> SavedSection("playlistDetail", playlist = section.playlist)
            is LibrarySection.GroupDetail -> SavedSection("groupDetail", name = section.name)
        }

        fun toSection(saved: SavedSection): LibrarySection? = when (saved.kind) {
            "discover" -> LibrarySection.Discover
            "tracks" -> LibrarySection.Tracks
            "albums" -> LibrarySection.Albums
            "artists" -> LibrarySection.Artists
            "playlists" -> LibrarySection.Playlists
            "search" -> LibrarySection.Search
            "settings" -> LibrarySection.Settings
            "cachedCopies" -> LibrarySection.CachedCopies
            "downloadService" -> LibrarySection.DownloadService
            "albumDetail" -> saved.album?.let(LibrarySection::AlbumDetail)
            "playlistDetail" -> saved.playlist?.let(LibrarySection::PlaylistDetail)
            "groupDetail" -> saved.name?.let(LibrarySection::GroupDetail)
            else -> null
        }
    }
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
