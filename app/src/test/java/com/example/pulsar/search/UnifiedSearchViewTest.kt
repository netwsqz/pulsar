package com.example.pulsar.search

import com.example.pulsar.data.qqdownload.QQAlbumDto
import com.example.pulsar.data.qqdownload.QQSongDto
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import com.example.pulsar.offline.OfflineSearchNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 合并搜索视图（ADR-0006，`docs/unified-search-refactor.md` Step 0–1）。
 *
 * 这里把原先只活在 `SearchContent` 里的合并规则钉死：曲库与 QQ 谁盖谁、错误优先级、
 * 空态、离线标记。纯函数、不碰 Android / Compose，界面在 Step 2 只渲染它的输出。
 *
 * 分支表（条件 → 视图）：
 * | 条件 | 输出 |
 * |---|---|
 * | 查询为空 | [SearchViewState.Blank] |
 * | 曲库正在搜 | [SearchViewState.Loading] |
 * | 曲库空（或没拉到）且 QQ 正在搜 | [SearchViewState.Loading] |
 * | 曲库有错误且两边空 | [SearchViewState.LibraryError] |
 * | 两边空、QQ 有状态 | [SearchViewState.LibraryEmptyQqError] |
 * | 两边空、离线搜索 | [SearchViewState.OfflineEmpty] |
 * | 两边空 | [SearchViewState.BothEmpty] |
 * | 其余（至少一边有内容） | [SearchViewState.Results] |
 */
class UnifiedSearchViewTest {
    private val track = Track("t1", "七里香", "周杰伦", "七里香", 1000L, null)
    private val searchedResults = SearchResults(
        tracks = listOf(track),
        albums = emptyList(),
        playlists = emptyList()
    )
    private val searchSong = QQSongDto(
        title = "QQ 歌",
        singer = "QQ 歌手",
        songMid = "qq-song-1"
    )
    private val searchAlbum = QQAlbumDto(
        albumMid = "qq-album-1",
        albumName = "QQ 专辑",
        singer = "QQ 歌手"
    )

    private fun input(
        query: String = "周杰伦",
        results: SearchResults? = null,
        isSearching: Boolean = false,
        errorMessage: String? = null,
        searchNotice: OfflineSearchNotice? = null
    ) = SearchInput(
        query = query,
        results = results,
        isSearching = isSearching,
        errorMessage = errorMessage,
        searchNotice = searchNotice
    )

    private fun qq(
        songs: List<QQSongDto> = emptyList(),
        albums: List<QQAlbumDto> = emptyList(),
        isSearching: Boolean = false,
        searchStatus: String? = null
    ) = QqSearchInput(
        songs = songs,
        albums = albums,
        isSearching = isSearching,
        searchStatus = searchStatus
    )

    private fun view(libraryInput: SearchInput, qqInput: QqSearchInput = qq()) =
        unifiedSearchViewState(libraryInput, qqInput)

    // ── 空输入 ──────────────────────────────────────────────────────────

    @Test
    fun `查询为空时显示空白态`() {
        // 空输入优先于一切：清空搜索框后不该留着转圈，也不该露出上一轮的 QQ 结果。
        val state = view(
            input(query = "", isSearching = true),
            qq(songs = listOf(searchSong), isSearching = true)
        )

        assertEquals(SearchViewState.Blank, state)
    }

    // ── 加载中 ──────────────────────────────────────────────────────────

    @Test
    fun `曲库搜索中时显示加载中`() {
        val state = view(input(results = searchedResults, isSearching = true))

        assertEquals(SearchViewState.Loading, state)
    }

    @Test
    fun `曲库空且 QQ 搜索中时显示加载中`() {
        // 曲库还没拉到（results == null）+ QQ 在搜：不能提前报「未找到」。
        val state = view(input(results = null), qq(isSearching = true))

        assertEquals(SearchViewState.Loading, state)
    }

    @Test
    fun `曲库空且 QQ 搜索中时即使 QQ 留着上次结果也显示加载中`() {
        // 钉住改前语义：曲库空 + QQ 在搜一律转圈，不做「旧 QQ 结果先顶上」。
        val state = view(input(results = SearchResults()), qq(songs = listOf(searchSong), isSearching = true))

        assertEquals(SearchViewState.Loading, state)
    }

    // ── 结果 ────────────────────────────────────────────────────────────

    @Test
    fun `曲库有结果时显示结果并保留 QQ 结果`() {
        val state = view(
            input(results = searchedResults),
            qq(songs = listOf(searchSong), albums = listOf(searchAlbum))
        )

        assertEquals(
            SearchViewState.Results(
                library = searchedResults,
                librarySearchedEmpty = false,
                qqSongs = listOf(searchSong),
                qqAlbums = listOf(searchAlbum),
                qqStatus = null,
                searchNotice = null
            ),
            state
        )
    }

    @Test
    fun `曲库空但 QQ 有结果时显示结果`() {
        // 曲库确实搜过且三类全空：QQ 结果顶上，同时记下「曲库搜过了确实没有」。
        val state = view(
            input(results = SearchResults()),
            qq(songs = listOf(searchSong), albums = listOf(searchAlbum))
        )

        assertEquals(
            SearchViewState.Results(
                library = SearchResults(),
                librarySearchedEmpty = true,
                qqSongs = listOf(searchSong),
                qqAlbums = listOf(searchAlbum),
                qqStatus = null,
                searchNotice = null
            ),
            state
        )
    }

    @Test
    fun `曲库还没拉到但 QQ 有结果时显示结果并标明曲库没搜成`() {
        // results == null 不能当成「曲库没有」——QQ 分区据此说「曲库搜索未完成」而不是「没找到」。
        val state = view(
            input(results = null),
            qq(songs = listOf(searchSong))
        ) as SearchViewState.Results

        assertFalse(state.librarySearchedEmpty)
        assertNull(state.library)
        assertEquals(listOf(searchSong), state.qqSongs)
    }

    @Test
    fun `曲库报错但 QQ 有结果时 QQ 结果不被盖掉`() {
        val state = view(
            input(results = null, errorMessage = "连不上服务器"),
            qq(songs = listOf(searchSong))
        ) as SearchViewState.Results

        assertEquals(listOf(searchSong), state.qqSongs)
    }

    @Test
    fun `曲库有结果时曲库错误不让结果消失`() {
        // 错误通道是共用的：上一轮残留的 errorMessage 不该把这一轮结果换成错误页。
        val state = view(
            input(results = searchedResults, errorMessage = "连不上服务器"),
            qq()
        ) as SearchViewState.Results

        assertEquals(searchedResults, state.library)
    }

    // ── 空态与错误优先级 ────────────────────────────────────────────────

    @Test
    fun `曲库空且 QQ 报错时显示曲库没找到与 QQ 状态`() {
        val state = view(
            input(results = SearchResults()),
            qq(searchStatus = "QQ 音乐服务连不上")
        )

        assertEquals(
            SearchViewState.LibraryEmptyQqError(
                query = "周杰伦",
                librarySearchedEmpty = true,
                qqStatus = "QQ 音乐服务连不上"
            ),
            state
        )
    }

    @Test
    fun `曲库没搜成且 QQ 报错时标明曲库没搜成`() {
        val state = view(
            input(results = null),
            qq(searchStatus = "QQ 音乐服务连不上")
        ) as SearchViewState.LibraryEmptyQqError

        assertFalse(state.librarySearchedEmpty)
    }

    @Test
    fun `两边全空时显示未找到`() {
        val state = view(input(results = SearchResults()), qq())

        assertEquals(SearchViewState.BothEmpty("周杰伦"), state)
    }

    @Test
    fun `曲库错误优先于 QQ 状态`() {
        // 曲库没搜成时不能只甩 QQ 的错误，那会把「曲库失败」伪装成「曲库没有」。
        val state = view(
            input(results = null, errorMessage = "连不上服务器"),
            qq(searchStatus = "QQ 音乐服务连不上")
        )

        assertEquals(SearchViewState.LibraryError("连不上服务器"), state)
    }

    @Test
    fun `QQ 状态优先于离线空态`() {
        val state = view(
            input(results = SearchResults(), searchNotice = OfflineSearchNotice.DownloadedOnly),
            qq(searchStatus = "QQ 音乐服务连不上")
        )

        assertEquals(
            SearchViewState.LibraryEmptyQqError(
                query = "周杰伦",
                librarySearchedEmpty = true,
                qqStatus = "QQ 音乐服务连不上"
            ),
            state
        )
    }

    // ── 离线搜索 ────────────────────────────────────────────────────────

    @Test
    fun `离线搜索有结果时标明离线搜索`() {
        val state = view(
            input(results = searchedResults, searchNotice = OfflineSearchNotice.DownloadedOnly)
        )

        assertEquals(
            SearchViewState.Results(
                library = searchedResults,
                librarySearchedEmpty = false,
                qqSongs = emptyList(),
                qqAlbums = emptyList(),
                qqStatus = null,
                searchNotice = OfflineSearchNotice.DownloadedOnly
            ),
            state
        )
    }

    @Test
    fun `离线搜索无结果时给专用空态`() {
        val state = view(
            input(results = SearchResults(), searchNotice = OfflineSearchNotice.DownloadedOnly)
        )

        assertEquals(
            SearchViewState.OfflineEmpty("周杰伦", OfflineSearchNotice.DownloadedOnly),
            state
        )
    }

    @Test
    fun `离线快照搜索无结果时保留快照口径`() {
        // 已下载集合还没算出来时范围是整份快照，文案与 DownloadedOnly 不同（#8）。
        val state = view(
            input(results = SearchResults(), searchNotice = OfflineSearchNotice.SnapshotOnly)
        )

        assertEquals(
            SearchViewState.OfflineEmpty("周杰伦", OfflineSearchNotice.SnapshotOnly),
            state
        )
    }

    @Test
    fun `离线搜索但 QQ 有结果时仍然显示结果`() {
        // 曲库空、QQ 有结果：离线标记只是曲库那一半的说明，不能把 QQ 结果藏掉。
        val state = view(
            input(results = SearchResults(), searchNotice = OfflineSearchNotice.DownloadedOnly),
            qq(songs = listOf(searchSong))
        )

        assertEquals(
            SearchViewState.Results(
                library = SearchResults(),
                librarySearchedEmpty = true,
                qqSongs = listOf(searchSong),
                qqAlbums = emptyList(),
                qqStatus = null,
                searchNotice = OfflineSearchNotice.DownloadedOnly
            ),
            state
        )
    }
}
