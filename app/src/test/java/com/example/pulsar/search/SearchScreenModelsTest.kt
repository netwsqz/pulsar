package com.example.pulsar.search

import com.example.pulsar.QQDownloadUiState
import com.example.pulsar.data.jellyfin.JellyfinRequest
import com.example.pulsar.data.qqdownload.QQSongDto
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import com.example.pulsar.offline.OfflineSearchNotice
import com.example.pulsar.searchStatus
import com.example.pulsar.state.SearchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索页渲染输入的组装（#18）。
 *
 * 合并规则本身由 [UnifiedSearchViewTest] 钉住；这里只管调用点与页面之间的旁路口径：
 * 输入框即时值、离线标识（Loading 时也要在）、QQ 下载状态与播放高亮是否原样带到。
 */
class SearchScreenModelsTest {
    private val track = Track("t1", "七里香", "周杰伦", "七里香", 1000L, null)
    private val noCover: (Track) -> JellyfinRequest? = { null }

    private fun screenState(
        search: SearchState = SearchState(query = "周杰伦"),
        results: SearchResults? = SearchResults(
            tracks = listOf(track),
            albums = emptyList(),
            playlists = emptyList()
        ),
        errorMessage: String? = null,
        searchNotice: OfflineSearchNotice? = null,
        qq: QQDownloadUiState = QQDownloadUiState()
    ) = searchScreenState(
        search = search,
        results = results,
        errorMessage = errorMessage,
        searchNotice = searchNotice,
        qq = qq,
        currentTrackId = track.id,
        isPlaying = true,
        trackCover = noCover,
        albumCover = { null },
        playlistCover = { null }
    )

    @Test
    fun `输入框即时值与播放高亮原样带到页面`() {
        val state = screenState()

        assertEquals("周杰伦", state.query)
        assertEquals(track.id, state.currentTrackId)
        assertTrue(state.isPlaying)
    }

    @Test
    fun `离线搜索在途时搜索框下方的离线标识仍然保留`() {
        // 曲库还在防抖 / 请求（Loading），但「离线搜索」那句话在改前就一直挂在搜索框下面。
        val state = screenState(
            search = SearchState(query = "周杰伦", isSearching = true, isOfflineSearch = true),
            searchNotice = OfflineSearchNotice.DownloadedOnly
        )

        assertEquals(SearchViewState.Loading, state.view)
        assertEquals(OfflineSearchNotice.DownloadedOnly, state.searchNotice)
    }

    @Test
    fun `QQ 下载状态原样带给分区与弹窗`() {
        val qq = QQDownloadUiState(
            searchQuery = "周杰伦",
            songs = listOf(QQSongDto(title = "QQ 歌", singer = "QQ 歌手", songMid = "qq-1")),
            searchError = "连不上 QQ 音乐服务"
        )

        val state = screenState(results = null, qq = qq)

        // 曲库还没拉到、QQ 有结果：走 Results（QQ 分区照常渲染），QQ 状态不被吞掉。
        assertSame(qq, state.qq)
        assertEquals("连不上 QQ 音乐服务", state.qq.searchStatus)
        val view = state.view as SearchViewState.Results
        assertEquals(listOf("qq-1"), view.qqSongs.map { it.songMid })
    }

    @Test
    fun `曲库为空且 QQ 报错时页面拿到的是 QQ 状态而不是空态`() {
        val state = screenState(
            results = SearchResults(),
            qq = QQDownloadUiState(searchError = "连不上 QQ 音乐服务")
        )

        val view = state.view as SearchViewState.LibraryEmptyQqError
        assertTrue(view.librarySearchedEmpty)
        assertEquals("连不上 QQ 音乐服务", view.qqStatus)
    }
}
