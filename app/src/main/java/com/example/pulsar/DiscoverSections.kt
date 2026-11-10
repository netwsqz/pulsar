package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Track

/**
 * 发现页「这一页有哪些节」的组合规则（工单 12）。
 *
 * 纯函数：喂进各节的**已经过滤好**的列表，拿一份可以直接驱动组合的结构。
 *
 * 核心是 spec §7 的那条离线规则——三节服务器历史（最近播放 / 最常播放 / 最近添加）在离线时
 * **整个不显示**：不组合、不给骨架、不给空态。数据仍留在 `JellyfinUiState` 里，回在线自动恢复。
 * 这是 UI 组合条件，不是 `VisibleLibrary` 的过滤项（离线时不是「过滤后展示」，是「不展示」），
 * 所以不往派生数据类加字段。
 */
internal data class DiscoverSections(
    /** 三节服务器历史：离线一律 false（数据在 state 里也不画）。 */
    val showRecentlyPlayed: Boolean,
    val showMostPlayed: Boolean,
    val showRecentlyAdded: Boolean,
    /** 每日推荐（本地计算）：有曲目才显示。 */
    val showDailyPicks: Boolean,
    /** 随机专辑墙：有专辑才显示。 */
    val showAlbums: Boolean,
    /** 整页空态：state 里确实没内容可渲染了（不是"还在加载"）。 */
    val showPageEmpty: Boolean
) {
    /**
     * **只统计实际会渲染的节**。离线时三节历史不算数——否则它们残留的缓存会把整页空态压掉，
     * 屏幕上就只剩几节点不动的内容（报告 #22）。
     */
    val hasAnySection: Boolean
        get() = showDailyPicks || showAlbums ||
            showRecentlyPlayed || showMostPlayed || showRecentlyAdded
}

/** 发现页没有可看内容时的提示类型。 */
internal enum class DiscoverEmpty {
    /**
     * 曲库确实是空的（在线、登录着、服务端一首歌都没有）。
     * 文案里指的入口必须**真实存在**（报告 #26）：搜索页是真的，「设置里刷新曲库」压根没有。
     */
    Library,

    /** 离线且手机里没有下载的歌曲。 */
    Downloaded
}

/**
 * 没有内容时该说哪句话（纯函数，两处空态共用一份判定）。
 *
 * [isOffline] 优先：离线时「曲库为空」这个说法没意义——用户的动作该是去下载，不是去搜索。
 */
internal fun discoverEmptyNotice(isOffline: Boolean): DiscoverEmpty =
    if (isOffline) DiscoverEmpty.Downloaded else DiscoverEmpty.Library

/**
 * 空态文案。放在这里而不是散在 Composable 里，是为了让「提示的入口真实存在」（报告 #26）
 * 这条约束可测、且两处空态不会各写一份走样。
 */
internal fun DiscoverEmpty.message(): String = when (this) {
    DiscoverEmpty.Library -> "曲库为空 —— 去搜索页搜歌并下载"
    DiscoverEmpty.Downloaded -> "离线 · 手机里还没有下载的歌曲"
}

/**
 * 各节的列表都用**过滤后**（离线时即 [VisibleLibrary] 派生出来的）的那一份：
 * 三节历史传原始 state 字段即可，反正离线时整节不画。
 *
 * [tracksLoaded] / [isLoading] 只为整页空态服务：还在加载或会话没恢复完（`state.tracks == null`）
 * 时**不给**整页空态——那是"还在连"，不是"确实没有"。
 */
internal fun discoverSections(
    dailyPicks: List<Track>,
    recentlyPlayed: List<Track>?,
    mostPlayed: List<Track>?,
    recentlyAdded: List<Album>?,
    discoverAlbums: List<Album>?,
    isOffline: Boolean,
    tracksLoaded: Boolean,
    isLoading: Boolean
): DiscoverSections = discoverSections(
    // 这一页只关心「每日推荐有没有曲目」；文案与选曲在上层。
    dailyPicksAvailable = dailyPicks.isNotEmpty(),
    recentlyPlayed = recentlyPlayed,
    mostPlayed = mostPlayed,
    recentlyAdded = recentlyAdded,
    discoverAlbums = discoverAlbums,
    isOffline = isOffline,
    tracksLoaded = tracksLoaded,
    isLoading = isLoading
)

/** [discoverSections] 的布尔入口：离线策略（OfflinePolicy）只拿得到「推荐有没有曲目」。 */
internal fun discoverSections(
    dailyPicksAvailable: Boolean,
    recentlyPlayed: List<Track>?,
    mostPlayed: List<Track>?,
    recentlyAdded: List<Album>?,
    discoverAlbums: List<Album>?,
    isOffline: Boolean,
    tracksLoaded: Boolean,
    isLoading: Boolean
): DiscoverSections {
    // 离线：三节服务器历史不是「过滤后展示」，是「不展示」（spec §7）。数据留在 state 里。
    val history = !isOffline
    val sections = DiscoverSections(
        showRecentlyPlayed = history && recentlyPlayed.orEmpty().isNotEmpty(),
        showMostPlayed = history && mostPlayed.orEmpty().isNotEmpty(),
        showRecentlyAdded = history && recentlyAdded.orEmpty().isNotEmpty(),
        showDailyPicks = dailyPicksAvailable,
        showAlbums = discoverAlbums.orEmpty().isNotEmpty(),
        showPageEmpty = false
    )
    return sections.copy(
        showPageEmpty = tracksLoaded && !isLoading && !sections.hasAnySection
    )
}
