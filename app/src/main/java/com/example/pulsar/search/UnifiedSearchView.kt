package com.example.pulsar.search

/**
 * 合并搜索的合并规则（ADR-0006，`docs/unified-search-refactor.md` Step 1）。
 *
 * 改前这套判断内联在 `SearchContent` 的 200 行 Composable 里、一条测试都没有；现在收成
 * 纯函数，先把现有行为钉死（`UnifiedSearchViewTest` 覆盖全部分支）。
 *
 * 边界（ADR-0006 + `docs/unified-search-refactor.md`）：
 * - **不新建聚合 ViewModel**：两个 ViewModel 各跑各的 300ms 防抖，本函数只读两边的结果；
 * - 纯函数：不碰 IO、不持有状态、不依赖 Android / Compose；
 * - 曲库错误 > QQ 状态 > 离线空态 > 普通空态；
 * - QQ 有结果时，曲库侧的错误 / 空态不能把它盖掉（那是正要下载的时候）。
 */
internal fun unifiedSearchViewState(
    input: SearchInput,
    qq: QqSearchInput
): SearchViewState {
    // 「搜过了确实没有」= 结果非 null 且三类全空。null 是「还没拉到 / 拉失败」，
    // 不能当成曲库没有——那会谎报「曲库中没找到」。
    val librarySearchedEmpty = input.results?.let { results ->
        results.tracks.isEmpty() && results.albums.isEmpty() && results.playlists.isEmpty()
    } ?: false
    val libraryEmpty = input.results == null || librarySearchedEmpty
    // 「全空」= 两边都没结果。曲库空但 QQ 有结果时不能报空。
    val bothEmpty = libraryEmpty && !qq.hasResults
    val libraryError = input.errorMessage
    val qqStatus = qq.searchStatus

    return when {
        input.query.isBlank() -> SearchViewState.Blank
        input.isSearching -> SearchViewState.Loading
        // 曲库空 + QQ 在搜：不能提前报「未找到」。
        libraryEmpty && qq.isSearching -> SearchViewState.Loading
        // 曲库侧的失败优先说：QQ 的错误不能让「曲库没搜成」看起来像「曲库没有」。
        bothEmpty && libraryError != null -> SearchViewState.LibraryError(libraryError)
        bothEmpty && qqStatus != null ->
            SearchViewState.LibraryEmptyQqError(input.query, librarySearchedEmpty, qqStatus)
        bothEmpty && input.searchNotice != null ->
            SearchViewState.OfflineEmpty(input.query, input.searchNotice)
        bothEmpty -> SearchViewState.BothEmpty(input.query)
        else -> SearchViewState.Results(
            library = input.results,
            librarySearchedEmpty = librarySearchedEmpty,
            qqSongs = qq.songs,
            qqAlbums = qq.albums,
            qqStatus = qqStatus,
            searchNotice = input.searchNotice
        )
    }
}
