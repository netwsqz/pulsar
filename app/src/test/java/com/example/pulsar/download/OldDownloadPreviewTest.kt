package com.example.pulsar.download

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import com.example.pulsar.DataStoreDownloadLedgerStore
import com.example.pulsar.DownloadEntry
import com.example.pulsar.DownloadKind
import com.example.pulsar.DownloadedPlaylist
import com.example.pulsar.FileLibrarySnapshotRepository
import com.example.pulsar.LibrarySnapshot
import com.example.pulsar.SessionStore
import com.example.pulsar.data.jellyfin.JellyfinApiFactory
import com.example.pulsar.domain.model.Track
import com.example.pulsar.readDownloadCopies
import com.example.pulsar.session.Session
import com.example.pulsar.session.SessionManager
import java.io.File
import java.lang.reflect.Proxy
import java.util.TreeSet
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OldDownloadPreviewTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `不连旧服务也能核对专辑和播放列表的完整副本 同首只计一次且不写盘`() = runTest {
        val fixture = Fixture(this)
        fixture.saveAccount()
        fixture.ledger.replaceAll(listOf(
            entry("专辑原声", listOf("album 曲")),
            entry("通勤列表", listOf("album 曲", "list-track"), kind = DownloadKind.PLAYLIST),
        ))
        val originalSnapshot = LibrarySnapshot(downloadedPlaylists = listOf(
            DownloadedPlaylist("通勤列表", "通勤列表", server, user, tracks = listOf(
                track("list-track", "列表里的一首歌"),
            )),
        ))
        fixture.snapshot.save(originalSnapshot)
        val audio = fixture.copyAt("$base/Videos/album%20%E6%9B%B2/stream?static=true")
        fixture.copyAt("$base/Videos/list-track/stream?static=true")
        val prefsBefore = fixture.prefsFile.readBytes().toList()
        val snapshotBefore = fixture.snapshotFile.readBytes().toList()
        val audioBefore = audio.readBytes().toList()

        val result = fixture.previewer.preview() as OldDownloadPreview.Report

        assertEquals(listOf("album 曲", "list-track"), result.complete.map { it.trackId })
        assertEquals(listOf("专辑原声", "通勤列表"), result.complete.first().downloadNames)
        assertEquals("列表里的一首歌", result.complete.last().title)
        assertEquals(emptyList<OldDownloadItem>(), result.unfinished)
        assertEquals(emptyList<OldDownloadItem>(), result.pending)
        assertEquals(result, fixture.previewer.preview())
        assertEquals(prefsBefore, fixture.prefsFile.readBytes().toList())
        assertEquals(snapshotBefore, fixture.snapshotFile.readBytes().toList())
        assertEquals(audioBefore, audio.readBytes().toList())
    }

    @Test
    fun `同服务器另一账号不计入 只把有部分字节的当前账号曲目算作未下完`() = runTest {
        val fixture = Fixture(this)
        fixture.saveAccount()
        fixture.ledger.replaceAll(listOf(
            entry("我的专辑", listOf("partial", "missing", "unknown-size", "zero")),
            entry("对方的专辑", listOf("other")).copy(userId = "其他账号"),
            entry("没有归属", listOf("unowned")).copy(serverUrl = "", userId = ""),
            entry("地址写法不同", listOf("same-stream-key")).copy(serverUrl = base),
        ))
        fixture.snapshot.save(LibrarySnapshot(tracks = listOf(track("missing", "快照里的歌"))))
        fixture.copyAt("$base/Videos/partial/stream?static=true", cached = 2)
        fixture.copyAt("$base/Videos/unknown-size/stream?static=true", total = -1)
        fixture.copyAt("$base/Videos/zero/stream?static=true", cached = 0)
        fixture.copyAt("$base/Videos/other/stream?static=true")
        fixture.copyAt("$base/Videos/no-ledger/stream?static=true")
        fixture.copyAt("$base/Videos/same-stream-key/stream?static=true")
        fixture.copyAt("https://other.test/Videos/elsewhere/stream?static=true")

        val result = fixture.previewer.preview() as OldDownloadPreview.Report

        assertEquals(emptyList<OldDownloadItem>(), result.complete)
        assertEquals(listOf("partial"), result.unfinished.map { it.trackId })
        assertEquals(
            listOf("missing", "unknown-size", "zero", "unowned", "same-stream-key", "no-ledger"),
            result.pending.map { it.trackId },
        )
    }

    @Test
    fun `没有当前账号归属的快照不为旧下载提供曲名`() = runTest {
        val fixture = Fixture(this)
        fixture.saveAccount()
        fixture.ledger.replaceAll(listOf(entry("我的专辑", listOf("same-id"))))
        fixture.snapshot.save(LibrarySnapshot(
            tracks = listOf(track("same-id", "不知归属的旧快照曲名")),
            downloadedPlaylists = listOf(
                DownloadedPlaylist("other", "别人的列表", server, "其他账号", tracks = listOf(
                    track("same-id", "别人的列表曲名"),
                )),
            ),
        ))
        fixture.copyAt("$base/Videos/same-id/stream?static=true")

        val result = fixture.previewer.preview() as OldDownloadPreview.Report

        assertEquals(listOf(OldDownloadItem("same-id", null, listOf("我的专辑"))), result.complete)
    }

    @Test
    fun `索引仍标着完整但文件长度已变 留在待核对`() = runTest {
        val fixture = Fixture(this)
        fixture.saveAccount()
        fixture.ledger.replaceAll(listOf(entry("我的专辑", listOf("truncated", "extended"))))
        fixture.copyAt("$base/Videos/truncated/stream?static=true").writeBytes(byteArrayOf(1, 2))
        fixture.copyAt("$base/Videos/extended/stream?static=true").writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        val result = fixture.previewer.preview() as OldDownloadPreview.Report

        assertEquals(emptyList<OldDownloadItem>(), result.complete)
        assertEquals(emptyList<OldDownloadItem>(), result.unfinished)
        assertEquals(listOf("truncated", "extended"), result.pending.map { it.trackId })
    }

    @Test
    fun `没有保存旧账号时明确说无法核对 而不是报告零首下载`() = runTest {
        val fixture = Fixture(this)
        fixture.ledger.replaceAll(listOf(entry("还留着的下载", listOf("a"))))
        fixture.copyAt("$base/Videos/a/stream?static=true")

        assertEquals(OldDownloadPreview.NoSavedAccount, fixture.previewer.preview())
    }

    private inner class Fixture(scope: TestScope) {
        val prefsFile = File(temporary.root, "pulsar.preferences_pb")
        private val preferences = PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { prefsFile }
        val ledger = DataStoreDownloadLedgerStore(preferences)
        val snapshotFile = File(temporary.root, "library-snapshot.json")
        val snapshot = FileLibrarySnapshotRepository(snapshotFile)
        private val copies = mutableMapOf<String, Triple<Long, Long, File>>()
        private val store = SessionStore(preferences)
        private val sessionManager = SessionManager(store, object : JellyfinApiFactory {
            override suspend fun createLoginApi(serverUrl: String) = error("预览不能登录 Jellyfin")
            override suspend fun create(session: Session) = error("预览不能创建 Jellyfin 数据接口")
        }, scope.backgroundScope)
        val previewer = OldDownloadPreviewer(
            savedAccount = {
                sessionManager.savedDownloadAccount()
            },
            readLedger = ledger::readForPreview,
            snapshot = snapshot,
            readCopies = { acceptsKey ->
                cache().readDownloadCopies(acceptsKey)
            },
        )

        suspend fun saveAccount() = store.save(Session(server, user, "旧账号", "test-only-token"))

        fun copyAt(key: String, total: Long = 4L, cached: Long = 4L): File {
            val file = temporary.newFile().apply { writeBytes(ByteArray(cached.toInt()) { (it + 1).toByte() }) }
            copies[key] = Triple(total, cached, file)
            return file
        }

        private fun cache(): Cache = Proxy.newProxyInstance(
            Cache::class.java.classLoader,
            arrayOf(Cache::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getKeys" -> copies.keys.toSet()
                "getCachedSpans" -> TreeSet<CacheSpan>().apply {
                    val key = args[0] as String
                    val (_, cached, file) = copies.getValue(key)
                    if (cached > 0L) add(TestSpan(key, cached, file))
                }
                "getContentMetadata" -> metadata(copies.getValue(args[0] as String).first)
                "isCached" -> copies.getValue(args[0] as String).second >= args[2] as Long
                else -> error("预览不支持此缓存操作：${method.name}")
            }
        } as Cache
    }

    private class TestSpan(key: String, length: Long, file: File) : CacheSpan(key, 0, length, 0, file)

    private fun metadata(size: Long): ContentMetadata = Proxy.newProxyInstance(
        ContentMetadata::class.java.classLoader,
        arrayOf(ContentMetadata::class.java),
    ) { _, method, _ ->
        check(method.name == "get")
        size
    } as ContentMetadata

    private fun entry(name: String, ids: List<String>, kind: DownloadKind = DownloadKind.ALBUM) =
        DownloadEntry(server, user, kind, name, name, ids, 1L)

    private fun track(id: String, title: String) = Track(id, title, "歌手", "专辑", null, null)

    private companion object {
        const val base = "https://jellyfin.test/music"
        const val server = "$base/"
        const val user = "旧账号ID"
    }
}
