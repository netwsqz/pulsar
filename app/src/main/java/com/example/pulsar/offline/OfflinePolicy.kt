package com.example.pulsar.offline

import com.example.pulsar.VisibleLibrary
import com.example.pulsar.discoverEmptyNotice
import com.example.pulsar.discoverSections
import com.example.pulsar.domain.model.Track
import com.example.pulsar.offlineVisibleLibrary

/**
 * 把原始状态收成渲染用的视图。
 *
 * 离线策略（ADR-0008）把「能看见什么 / 能不能播 / 哪一节不显示 / 提示什么」收成一份纯函数判据：
 * 界面喂进 [OfflineInput]、只渲染 [OfflineView]，不再自己组合 `isOffline + cachedTrackIds`。
 * 过滤仍委托 `offlineVisibleLibrary`、节组合仍委托 `discoverSections`——本模块负责把几份判据
 * 收口到一处。
 *
 * 关键规则：
 * - `downloadedTrackIds == null`（还没算出来）时**不筛**——把能播的歌藏掉比多显示几首更糟；
 * - `isFiltering = isOffline && downloadedTrackIds != null`，只在这里算一次；
 * - 在线一律全量、不提示（ADR-0008：过滤不是「在线也过滤」）；
 * - 发现页三节服务器历史离线时整节不显示，每日推荐/专辑墙仍按已下载内容显示；
 * - 发现页空态类型：离线说「还没下载」，在线说「曲库为空」；
 * - 搜索页的离线标识也在这里给类型（[OfflineSearchNotice]），界面只渲染文案。
 */
internal fun offlineView(input: OfflineInput): OfflineView {
    val filtering = input.isOffline && input.downloadedTrackIds != null
    val visible = if (filtering) {
        offlineVisibleLibrary(
            tracks = input.tracks,
            albums = input.albums,
            discoverAlbums = input.discoverAlbums,
            albumTracks = input.albumTracks,
            playlistTracks = input.playlistTracks,
            searchResults = input.searchResults,
            visibleTrackIds = input.downloadedTrackIds.orEmpty()
        )
    } else {
        VisibleLibrary(
            tracks = input.tracks,
            albums = input.albums,
            discoverAlbums = input.discoverAlbums,
            albumTracks = input.albumTracks,
            playlistTracks = input.playlistTracks,
            searchResults = input.searchResults
        )
    }
    val discover = discoverSections(
        // 每日推荐（4 首）只从过滤后的曲目里挑，所以「曲目表非空」⇔「这一节有内容」。
        dailyPicksAvailable = visible.tracks.orEmpty().isNotEmpty(),
        recentlyPlayed = input.recentlyPlayed,
        mostPlayed = input.mostPlayed,
        recentlyAdded = input.recentlyAdded,
        discoverAlbums = visible.discoverAlbums,
        isOffline = input.isOffline,
        tracksLoaded = input.tracksLoaded,
        isLoading = input.isLoading
    )
    return OfflineView(
        visibleLibrary = visible,
        isFiltering = filtering,
        notice = if (filtering) OfflineNotice.VisibleOnly else null,
        discover = discover,
        emptyKind = discoverEmptyNotice(input.isOffline),
        searchNotice = offlineSearchNotice(input.isOfflineSearch, filtering)
    )
}

/**
 * 这首歌**现在**能不能放：在线一律能放；离线只有已下载的能放（半截的不算）。
 *
 * `downloadedTrackIds == null`（离线且集合没算出来）按**不能放**处理——证明不了有完整副本，
 * 宁可不放。注意它与 [offlineView] 的「未知 = 不筛」不同：藏列表只是少看几首，放歌会直接失败。
 */
internal fun offlineIsPlayable(
    track: Track,
    downloadedTrackIds: Set<String>?,
    isOffline: Boolean
): Boolean = !isOffline || downloadedTrackIds?.contains(track.id) == true

/**
 * 离线时点没下载的歌：返回不能放的原因（给界面弹提示用），可放返回 null。
 * 文案与改前一字不差（#10 才统一收口）。
 */
internal fun offlineUnplayableReason(
    track: Track,
    downloadedTrackIds: Set<String>?,
    isOffline: Boolean
): String? =
    if (offlineIsPlayable(track, downloadedTrackIds, isOffline)) null else "这首歌没有下载，联网后才能播放"
