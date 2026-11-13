package com.example.pulsar.state

import com.example.pulsar.CachedAlbum
import com.example.pulsar.JellyfinUiState
import com.example.pulsar.data.libraryfiles.LibraryRecycleFile
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * reset 语义钉子（`docs/library-state-refactor.md` Step 3 / #13）。
 *
 * 登出 / 会话过期 / 回在线三条路径的清理语义都收在 slice 自己身上：
 * - [SessionState.cleared] / [LibraryState.cleared] / [DownloadsState.cleared]，
 *   由 [JellyfinUiState.cleared] 组合（登出与过期共用）；
 * - [LibraryState.clearSnapshotBackedScreens]（回在线）。
 *
 * **约定：slice 字段的默认值就是「已清」的值。** 因此 `cleared()` 直接返回默认实例，
 * 给 slice 新增字段时自动跟上，不会漏清。谁想改回逐字段 copy，[夹具覆盖了每个字段] 会红。
 */
class StateClearedTest {

    private val track = Track(
        id = "t1",
        title = "歌名",
        artist = "歌手",
        album = "专辑",
        durationMs = 1_000L,
        imageTag = "tag-t",
    )
    private val album = Album(id = "a1", name = "专辑", artist = "歌手", trackCount = 2, imageTag = "tag-a")
    private val playlist = Playlist(id = "p1", name = "列表", trackCount = 3, imageTag = "tag-p")
    private val searchResults = SearchResults(tracks = listOf(track), albums = listOf(album))
    private val cachedAlbum = CachedAlbum(album = album, tracks = listOf(track), totalCount = 2)
    private val recycleFile = LibraryRecycleFile(relPath = "歌手/专辑/歌名.flac", size = 42L, deletedAt = 1.0)

    /** 每个字段都**不是**默认值的 state：只有它能证明清理真的发生了（默认值清默认值看不出问题）。 */
    private val populated = JellyfinUiState(
        session = SessionState(
            serverUrl = "https://jelly.example",
            serverUrlHistory = listOf("https://old.example"),
            username = "alice",
            password = "secret",
            isLoading = true,
            isRestoring = true,
            errorMessage = "登录失败",
        ),
        library = LibraryState(
            tracks = listOf(track),
            albums = listOf(album),
            discoverAlbums = listOf(album),
            albumTracks = listOf(track),
            playlists = listOf(playlist),
            playlistTracks = listOf(track),
            recentlyPlayed = listOf(track),
            mostPlayed = listOf(track),
            recentlyAdded = listOf(album),
            discoverFailedSections = setOf(LibrarySectionKey.MostPlayed),
            historyEpoch = 3,
            isDiscoverRefreshing = true,
            search = SearchState(
                query = "周杰伦",
                results = searchResults,
                isSearching = true,
                isOfflineSearch = true,
            ),
            multiSelectIds = setOf("t1"),
            fileOps = FileOpsState(
                isBusy = true,
                message = "删除失败",
                recycleFiles = listOf(recycleFile),
                isRecycleLoading = true,
                recycleMessage = "已清空",
            ),
            isOffline = true,
        ),
        downloads = DownloadsState(
            visibleTrackIds = setOf("t1"),
            cachedAlbums = listOf(cachedAlbum),
            isDeleting = true,
            deleteMessage = "已删除 1 首",
        ),
    )

    // ── 登出 / 过期：三个 slice 一起清 ─────────────────────────────

    @Test
    fun `登出清空三个 slice，只保留地址历史`() {
        val cleared = populated.cleared()

        assertEquals(LibraryState(), cleared.library)
        assertEquals(DownloadsState(), cleared.downloads)
        assertEquals(listOf("https://old.example"), cleared.session.serverUrlHistory)
        assertEquals(SessionState(serverUrlHistory = listOf("https://old.example")), cleared.session)
    }

    @Test
    fun `会话过期保留登录表单，只清密码与错误位`() {
        val cleared = populated.cleared(keepLoginForm = true, errorMessage = "登录已失效，请重新登录")

        assertEquals("https://jelly.example", cleared.session.serverUrl)
        assertEquals("alice", cleared.session.username)
        assertEquals("", cleared.session.password)
        assertEquals("登录已失效，请重新登录", cleared.session.errorMessage)
        assertEquals(listOf("https://old.example"), cleared.session.serverUrlHistory)
        assertFalse(cleared.session.isLoading)
        assertFalse(cleared.session.isRestoring)
        // 曲库与下载界面字段与登出同口径：一起清
        assertEquals(LibraryState(), cleared.library)
        assertEquals(DownloadsState(), cleared.downloads)
    }

    @Test
    fun `清理不碰下载的字节与名单`() {
        // ADR-0007：「下载不清」指的是字节与名单。它们不在 JellyfinUiState 里，
        // 这里用「清完只剩默认值」反向确认：清理方法没有地方能碰到它们。
        val cleared = populated.cleared()
        assertNull(cleared.downloads.visibleTrackIds)
        assertNull(cleared.downloads.cachedAlbums)
    }

    // ── 回在线：只清快照来源的屏 ──────────────────────────────────

    @Test
    fun `回在线清掉快照来源的屏与离线标记`() {
        val online = populated.library.clearSnapshotBackedScreens()

        assertNull(online.albums)
        assertNull(online.discoverAlbums)
        assertNull(online.recentlyPlayed)
        assertNull(online.mostPlayed)
        assertNull(online.recentlyAdded)
        assertEquals(emptySet<LibrarySectionKey>(), online.discoverFailedSections)
        assertNull(online.albumTracks)
        assertFalse(online.isOffline)
        assertFalse(online.search.isOfflineSearch)
    }

    @Test
    fun `回在线不动服务器拉的曲库、播放列表、浏览位置与刷新圈`() {
        val before = populated.library
        val online = before.clearSnapshotBackedScreens()

        // 曲库曲目由调用方用服务器返回值替换；播放列表本来就不在快照里
        assertEquals(before.tracks, online.tracks)
        assertEquals(before.playlists, online.playlists)
        assertEquals(before.playlistTracks, online.playlistTracks)
        assertEquals(before.multiSelectIds, online.multiSelectIds)
        assertEquals(before.historyEpoch, online.historyEpoch)
        assertEquals(before.fileOps, online.fileOps)
        assertEquals(before.search.query, online.search.query)
        assertEquals(before.search.results, online.search.results)
        // 刷新圈由 refreshDiscover 等四路落地后自己收（#12），回在线不清它
        assertTrue(online.isDiscoverRefreshing)
    }

    // ── 新增字段的保险丝 ─────────────────────────────────────────

    @Test
    fun `夹具覆盖了每个 slice 的每个字段`() {
        assertEveryFieldPopulated(SessionState(), populated.session, "session")
        assertEveryFieldPopulated(LibraryState(), populated.library, "library")
        assertEveryFieldPopulated(DownloadsState(), populated.downloads, "downloads")
    }

    /**
     * 逐字段确认 [populated] 与 [default] 不同（嵌套 slice 递归下去）。
     *
     * 给 slice 新增字段却没更新夹具时，这条会红——提示把新字段也纳入 reset 测试。
     * 不更新的话，「清成默认值」的断言对新字段永远成立，等于没测。
     */
    private fun assertEveryFieldPopulated(default: Any, populated: Any, path: String) {
        val fields = populated.javaClass.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
        assertTrue("$path 上没找到字段（反射失效？）", fields.isNotEmpty())
        fields.forEach { field ->
            field.isAccessible = true
            val name = "$path.${field.name}"
            val actual = field.get(populated)
            val fallback = field.get(default)
            if (isStateSlice(field.type)) {
                assertEveryFieldPopulated(fallback, actual, name)
            } else {
                assertNotEquals("新增字段 $name 没进 reset 夹具（默认值即「已清」）", fallback, actual)
            }
        }
    }

    /** `com.example.pulsar.state` 包里的 data class（嵌套 slice）；枚举不算。 */
    private fun isStateSlice(type: Class<*>): Boolean =
        type.name.startsWith("com.example.pulsar.state.") && !type.isEnum
}
