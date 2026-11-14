package com.example.pulsar.search

import com.example.pulsar.data.qqdownload.QQAlbumDto
import com.example.pulsar.data.qqdownload.QQSongDto
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.offline.OfflineSearchNotice
import com.example.pulsar.offline.emptyMessage

/**
 * 合并搜索（ADR-0006）的窄输入：曲库那一半。
 *
 * 只挑合并规则真正要用的字段，不直接吃 `LibraryState` / `SessionState`——纯函数因此
 * 不依赖 Android / Compose，也不会长成第二个大 interface。
 */
internal data class SearchInput(
    val query: String,
    /** 曲库搜索结果。null = 还没拉到 / 拉失败；空 [SearchResults] = 搜过了确实没有。 */
    val results: SearchResults? = null,
    /** 曲库搜索在途（含 300ms 防抖窗口）。 */
    val isSearching: Boolean = false,
    /** 曲库侧的错误通道（`SessionState.errorMessage`，与登录共用）。 */
    val errorMessage: String? = null,
    /**
     * 离线搜索标识，来自 `OfflinePolicy` 的 `OfflineView.searchNotice`（#8）；
     * null = 在线搜索，不打标。命名沿用 candidate 2，不另造 `isOfflineFiltered`。
     */
    val searchNotice: OfflineSearchNotice? = null
)

/**
 * 合并搜索的窄输入：QQ 音乐那一半。
 *
 * 两个 ViewModel 各跑各的 300ms 防抖（ADR-0006），这里只是把两边结果摆在一起做判据；
 * 不新建聚合 ViewModel。
 */
internal data class QqSearchInput(
    val songs: List<QQSongDto> = emptyList(),
    val albums: List<QQAlbumDto> = emptyList(),
    val isSearching: Boolean = false,
    /** 搜索期失败的一行小字（连不上服务 / 凭证过期）；null = 没有失败。 */
    val searchStatus: String? = null
) {
    /** 两边任意一边有结果就算有。由列表派生，调用方不用再传一份容易对不上的布尔。 */
    val hasResults: Boolean get() = songs.isNotEmpty() || albums.isNotEmpty()
}

/**
 * 合并搜索的渲染视图（`docs/unified-search-refactor.md` Step 1）。
 *
 * 合并规矩只在 [unifiedSearchViewState] 一处决定，界面只按类型渲染：
 * 错误优先级 = 曲库错误 > QQ 状态 > 离线空态 > 普通空态；
 * **QQ 有结果时，曲库侧的问题不能把它盖掉**（ADR-0006）。
 */
internal sealed interface SearchViewState {
    /** 查询为空：还没开始搜（优先于任何在途状态）。 */
    data object Blank : SearchViewState

    /** 还在等防抖 / 请求：曲库在搜，或曲库空但 QQ 还在搜。 */
    data object Loading : SearchViewState

    /**
     * 至少一边有内容可显示。
     *
     * [library] 为 null 时表示曲库还没拉到 / 拉失败；[librarySearchedEmpty] 区分
     * 「搜过了确实没有」与「没搜成」——只有前者才会在 QQ 分区上方说「曲库中没找到」。
     */
    data class Results(
        val library: SearchResults?,
        val librarySearchedEmpty: Boolean,
        val qqSongs: List<QQSongDto>,
        val qqAlbums: List<QQAlbumDto>,
        /** QQ 分区里的常驻小字；null = 没失败。曲库有结果时它只降级、不变成整页错误。 */
        val qqStatus: String?,
        /** 离线搜索标识；null = 在线搜索。界面据此标明「离线搜索」并渲染对应文案。 */
        val searchNotice: OfflineSearchNotice?,
        /**
         * QQ 分区上方那句「曲库中没找到「X」」；null = 不显示。
         *
         * 只在曲库确实搜过且为空时给（此时 [Results] 能成立，说明有结果的只可能是 QQ）。
         * 由合并规则算好，分区不再自己判断「曲库空不空」——文案与判据都只有这里一个来源。
         */
        val libraryMessage: String? = null
    ) : SearchViewState

    /** 两边都空，且曲库侧有错误：整页错误 + 曲库重试。 */
    data class LibraryError(val message: String) : SearchViewState

    /**
     * 两边都空，曲库没有结果，QQ 侧报了错。
     *
     * [librarySearchedEmpty] 决定头部那句话：搜过了说「曲库中没找到」，没搜成说「曲库搜索未完成」。
     */
    data class LibraryEmptyQqError(
        val query: String,
        val librarySearchedEmpty: Boolean,
        val qqStatus: String
    ) : SearchViewState {
        /** 曲库那一句的唯一出处（与 QQ 分区的 [Results.libraryMessage] 共用同一句）。 */
        val libraryMessage: String get() = libraryEmptyMessage(query, librarySearchedEmpty)
    }

    /** 两边都空，没有错误、也不在离线搜索。 */
    data class BothEmpty(val query: String) : SearchViewState

    /**
     * 离线搜索且没结果：专用空态——范围只有已下载 / 快照，跟「曲库本来就没有」不是一回事。
     * [searchNotice] 决定说「已下载的歌曲里没找到」还是「快照里没找到」。
     */
    data class OfflineEmpty(
        val query: String,
        val searchNotice: OfflineSearchNotice
    ) : SearchViewState {
        /** 离线空态文案：字面量在 `offline/OfflineSearchNotice.emptyMessage`，这里只转交。 */
        val message: String get() = searchNotice.emptyMessage(query)
    }
}

/**
 * 「曲库没搜到」那一句的唯一出处：整页空态与 QQ 分区上方共用。
 *
 * 搜过了确实没有 → 「曲库中没找到」；没搜成（results 还是 null）→ 「曲库搜索未完成」。
 * 两种情况不能混：把没搜成说成没找到是在谎报。
 */
internal fun libraryEmptyMessage(query: String, searchedEmpty: Boolean): String =
    if (searchedEmpty) "曲库中没找到「$query」" else "曲库搜索未完成"
