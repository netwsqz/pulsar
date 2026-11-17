package com.example.pulsar

import com.example.pulsar.domain.model.Track
import com.example.pulsar.state.LibraryState

/**
 * 多选「全选」的目标列表：**按当前页面取可见列表**（#30）。
 *
 * 入参是 [VisibleLibrary]（已过离线策略，ADR-0008）而不是原始 state——离线时界面只显示
 * 已下载的歌，全选若取全量就会把看不见的歌勾进删除集合。
 *
 * [dailyPicks] 是发现页顶部「每日推荐」那几首：发现页只有这几行是可勾选的曲目行
 * （其余是专辑卡 / 横滑卡），全选也只能勾这些。挑选规则在 `discoverDailyPicks`，
 * 由调用方算好传进来，保证全选和页面渲染用同一份。
 * 设置 / 下载等没有曲目行的页面返回空列表。
 */
internal fun currentListTracks(
    section: LibrarySection,
    visible: VisibleLibrary,
    dailyPicks: List<Track>
): List<Track> =
    when (section) {
        LibrarySection.Tracks -> visible.tracks.orEmpty()
        LibrarySection.Discover -> dailyPicks
        is LibrarySection.AlbumDetail -> visible.albumTracks.orEmpty()
        is LibrarySection.PlaylistDetail -> visible.playlistTracks.orEmpty()
        LibrarySection.Search -> visible.searchResults?.tracks.orEmpty()
        else -> emptyList()
    }

/**
 * 多选「删除」的候选池：按当前页面取**原始（未过离线过滤）**列表。
 *
 * 和「全选」口径不同，别合并：全选只能勾看得见的（[currentListTracks]），
 * 但勾上之后离线翻转 / 下载名单变化会让可见列表收缩。删除若还从可见列表取，
 * 已勾的歌会被悄悄丢掉——多选条写着「已选 12 首」，确认框却只删 5 首；
 * 一枚都不剩时点了「删除」更是毫无反应（空目标不弹框）。
 * 用户既然勾了，就按勾选删；这里回到原始 state 反查。
 */
internal fun deleteCandidateTracks(section: LibrarySection, library: LibraryState): List<Track> =
    when (section) {
        LibrarySection.Tracks, LibrarySection.Discover -> library.tracks.orEmpty()
        is LibrarySection.AlbumDetail -> library.albumTracks.orEmpty()
        is LibrarySection.PlaylistDetail -> library.playlistTracks.orEmpty()
        LibrarySection.Search -> library.search.results?.tracks.orEmpty()
        else -> emptyList()
    }
