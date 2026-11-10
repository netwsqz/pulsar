package com.example.pulsar.offline

import com.example.pulsar.DiscoverEmpty
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线策略（ADR-0008）：喂进各屏的原始状态，吐出「能显示什么 / 是否在筛 / 提示什么 /
 * 发现页哪几节 / 空态类型 / 能不能播」。
 *
 * 纯函数、不碰 IO。旧有的 `OfflineVisibleLibraryTest` / `DiscoverSectionsTest` 仍然保留，
 * 钉住内部委托的实现细节；这里钉的是**整块策略**的组合矩阵——界面只读这里的输出。
 */
class OfflinePolicyTest {
    private val a1 = Track("t1", "A1", "Artist X", "Album A", 1000L, null)
    private val a2 = Track("t2", "A2", "Artist X", "Album A", 1000L, null)
    private val b1 = Track("t3", "B1", "Artist Y", "Album B", 1000L, null)
    private val albumA = Album("al-a", "Album A", "Artist X", trackCount = 2, imageTag = null)
    private val albumB = Album("al-b", "Album B", "Artist Y", trackCount = 1, imageTag = null)

    private val allTracks = listOf(a1, a2, b1)
    private val allAlbums = listOf(albumA, albumB)

    private fun input(
        isOffline: Boolean = false,
        downloadedTrackIds: Set<String>? = null,
        tracks: List<Track>? = allTracks,
        albums: List<Album>? = allAlbums,
        discoverAlbums: List<Album>? = albums,
        albumTracks: List<Track>? = null,
        playlistTracks: List<Track>? = null,
        searchResults: SearchResults? = null,
        isOfflineSearch: Boolean = false,
        recentlyPlayed: List<Track>? = null,
        mostPlayed: List<Track>? = null,
        recentlyAdded: List<Album>? = null,
        tracksLoaded: Boolean = tracks != null,
        isLoading: Boolean = false
    ) = OfflineInput(
        isOffline = isOffline,
        downloadedTrackIds = downloadedTrackIds,
        tracks = tracks,
        albums = albums,
        discoverAlbums = discoverAlbums,
        albumTracks = albumTracks,
        playlistTracks = playlistTracks,
        searchResults = searchResults,
        isOfflineSearch = isOfflineSearch,
        recentlyPlayed = recentlyPlayed,
        mostPlayed = mostPlayed,
        recentlyAdded = recentlyAdded,
        tracksLoaded = tracksLoaded,
        isLoading = isLoading
    )

    // ── 在线 ────────────────────────────────────────────────────────────

    @Test
    fun `在线时各屏全量显示、不筛、不提示`() {
        // 在线时就算手里有已下载集合也不筛：在线以服务器为准。
        val view = offlineView(input(isOffline = false, downloadedTrackIds = setOf("t1")))

        assertEquals(allTracks, view.visibleLibrary.tracks)
        assertEquals(allAlbums, view.visibleLibrary.albums)
        assertFalse(view.isFiltering)
        assertNull(view.notice)
        assertEquals(DiscoverEmpty.Library, view.emptyKind)
    }

    // ── 离线但已下载集合未知 ─────────────────────────────────────────────

    @Test
    fun `离线但已下载集合还没算出来时不筛，也不提示`() {
        // 把能放的歌藏掉比多显示几首更糟：未知 = 不筛（与改前一致）。
        val view = offlineView(input(isOffline = true, downloadedTrackIds = null))

        assertEquals(allTracks, view.visibleLibrary.tracks)
        assertEquals(allAlbums, view.visibleLibrary.albums)
        assertFalse(view.isFiltering)
        assertNull(view.notice)
    }

    // ── 离线 + 空集 ──────────────────────────────────────────────────────

    @Test
    fun `离线且一首都没下载时全部为空、标记在筛、给统一提示`() {
        val view = offlineView(
            input(
                isOffline = true,
                downloadedTrackIds = emptySet(),
                albumTracks = listOf(a1),
                playlistTracks = listOf(a1, b1),
                searchResults = SearchResults(tracks = listOf(a1), albums = listOf(albumA))
            )
        )

        assertTrue("曲目应为空", view.visibleLibrary.tracks.orEmpty().isEmpty())
        assertTrue("专辑应为空", view.visibleLibrary.albums.orEmpty().isEmpty())
        assertTrue("专辑详情应为空", view.visibleLibrary.albumTracks.orEmpty().isEmpty())
        assertTrue("播放列表详情应为空", view.visibleLibrary.playlistTracks.orEmpty().isEmpty())
        assertEquals(SearchResults(), view.visibleLibrary.searchResults)
        assertTrue(view.isFiltering)
        assertEquals(OfflineNotice.VisibleOnly, view.notice)
        assertEquals(DiscoverEmpty.Downloaded, view.emptyKind)
    }

    // ── 离线 + 部分命中 ─────────────────────────────────────────────────

    @Test
    fun `离线时只留已下载的曲目，有一首已下载的专辑就保留`() {
        val view = offlineView(input(isOffline = true, downloadedTrackIds = setOf("t2")))

        assertEquals(listOf(a2), view.visibleLibrary.tracks)
        // 专辑 A 只下了 1 首，卡片照样在（选曲下载不该让专辑消失）；专辑 B 一首没下，不显示。
        assertEquals(listOf(albumA), view.visibleLibrary.albums)
        assertTrue(view.isFiltering)
    }

    @Test
    fun `离线时专辑详情与播放列表详情只列已下载曲目`() {
        val view = offlineView(
            input(
                isOffline = true,
                downloadedTrackIds = setOf("t3"),
                albumTracks = listOf(a1, a2),
                playlistTracks = listOf(a1, a2, b1)
            )
        )

        assertTrue("整张专辑都没下载时详情为空", view.visibleLibrary.albumTracks.orEmpty().isEmpty())
        assertEquals(listOf(b1), view.visibleLibrary.playlistTracks)
    }

    @Test
    fun `离线时搜索结果只留已下载的曲目与专辑`() {
        val view = offlineView(
            input(
                isOffline = true,
                downloadedTrackIds = setOf("t1"),
                searchResults = SearchResults(tracks = listOf(a1, b1), albums = listOf(albumA, albumB))
            )
        )

        assertEquals(SearchResults(tracks = listOf(a1), albums = listOf(albumA)), view.visibleLibrary.searchResults)
    }

    // ── 离线搜索标识 ────────────────────────────────────────────────────

    @Test
    fun `离线搜索的提示类型由策略给出`() {
        // 在线搜索不打标。
        assertNull(offlineView(input(isOfflineSearch = false)).searchNotice)
        // 离线 + 已下载集合已知：列表筛过，范围 = 下载过的歌。
        assertEquals(
            OfflineSearchNotice.DownloadedOnly,
            offlineView(
                input(isOffline = true, downloadedTrackIds = emptySet(), isOfflineSearch = true)
            ).searchNotice
        )
        // 离线 + 集合未知：列表没筛，只能说「只搜快照」。
        assertEquals(
            OfflineSearchNotice.SnapshotOnly,
            offlineView(
                input(isOffline = true, downloadedTrackIds = null, isOfflineSearch = true)
            ).searchNotice
        )
    }

    // ── 离线 + 专辑列表缺席（反推）──────────────────────────────────────

    @Test
    fun `离线且专辑列表没拉到（null）时从已下载曲目反推专辑卡`() {
        // 登录只写曲目表、快照里没有专辑、用户也没逛过专辑页 → albums 为 null。
        // 不反推的话专辑页会显示「一首都没下载」，而歌曲页明明列着这些歌。
        val view = offlineView(
            input(isOffline = true, downloadedTrackIds = setOf("t1", "t3"), albums = null)
        )

        assertEquals(
            listOf(
                Album(id = "Album A", name = "Album A", artist = "Artist X", trackCount = null, imageTag = null),
                Album(id = "Album B", name = "Album B", artist = "Artist Y", trackCount = null, imageTag = null)
            ),
            view.visibleLibrary.albums
        )
    }

    // ── 发现页三节 gating ───────────────────────────────────────────────

    @Test
    fun `离线时三节服务器历史整节不显示，数据留在输入里也不画`() {
        val view = offlineView(
            input(
                isOffline = true,
                downloadedTrackIds = setOf("t1"),
                recentlyPlayed = listOf(a1),
                mostPlayed = listOf(a2),
                recentlyAdded = listOf(albumA)
            )
        )

        assertFalse(view.discover.showRecentlyPlayed)
        assertFalse(view.discover.showMostPlayed)
        assertFalse(view.discover.showRecentlyAdded)
    }

    @Test
    fun `在线时三节服务器历史有内容就显示`() {
        val view = offlineView(
            input(
                recentlyPlayed = listOf(a1),
                mostPlayed = listOf(a2),
                recentlyAdded = listOf(albumA)
            )
        )

        assertTrue(view.discover.showRecentlyPlayed)
        assertTrue(view.discover.showMostPlayed)
        assertTrue(view.discover.showRecentlyAdded)
    }

    @Test
    fun `离线时每日推荐与随机专辑墙仍按已下载内容显示`() {
        val view = offlineView(
            input(isOffline = true, downloadedTrackIds = setOf("t1", "t3"))
        )

        assertTrue(view.discover.showDailyPicks)
        assertTrue(view.discover.showAlbums)
    }

    @Test
    fun `离线且一首都没下载时发现页给专用空态，而不是骨架`() {
        val view = offlineView(input(isOffline = true, downloadedTrackIds = emptySet()))

        assertFalse(view.discover.showDailyPicks)
        assertFalse(view.discover.showAlbums)
        assertTrue(view.discover.showPageEmpty)
        assertEquals(DiscoverEmpty.Downloaded, view.emptyKind)
    }

    @Test
    fun `曲库还没拉到或正在加载时不给发现页空态`() {
        val notLoaded = offlineView(input(tracks = null, albums = null, tracksLoaded = false))
        assertFalse(notLoaded.discover.showPageEmpty)

        val loading = offlineView(input(isLoading = true))
        assertFalse(loading.discover.showPageEmpty)
    }

    // ── 可播判定 ────────────────────────────────────────────────────────

    @Test
    fun `在线时一律可播`() {
        assertTrue(offlineIsPlayable(b1, downloadedTrackIds = emptySet(), isOffline = false))
        assertNull(offlineUnplayableReason(b1, downloadedTrackIds = emptySet(), isOffline = false))
    }

    @Test
    fun `离线时只有已下载的歌可播`() {
        assertTrue(offlineIsPlayable(a1, downloadedTrackIds = setOf("t1"), isOffline = true))
        assertNull(offlineUnplayableReason(a1, downloadedTrackIds = setOf("t1"), isOffline = true))
        assertFalse(offlineIsPlayable(b1, downloadedTrackIds = setOf("t1"), isOffline = true))
    }

    @Test
    fun `离线点未下载的歌：提示文案与改前一字不差`() {
        assertEquals(
            "这首歌没有下载，联网后才能播放",
            offlineUnplayableReason(b1, downloadedTrackIds = setOf("t1"), isOffline = true)
        )
    }

    @Test
    fun `离线且已下载集合未知时按不可播处理（不能把能放的歌放出去）`() {
        // 与改前一致：离线 + 未知集合 = 不能证明有副本，宁可不放（会话层已有兜底提示）。
        assertFalse(offlineIsPlayable(a1, downloadedTrackIds = null, isOffline = true))
        assertEquals(
            "这首歌没有下载，联网后才能播放",
            offlineUnplayableReason(a1, downloadedTrackIds = null, isOffline = true)
        )
    }
}
