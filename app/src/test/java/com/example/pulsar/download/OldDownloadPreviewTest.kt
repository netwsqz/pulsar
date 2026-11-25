package com.example.pulsar.download

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.MediaItem
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
import com.example.pulsar.data.companion.CompanionCopyCandidate
import com.example.pulsar.data.companion.CompanionDownloadEvidence
import com.example.pulsar.data.companion.CompanionDownloadEvidenceSource
import com.example.pulsar.data.jellyfin.JellyfinApiFactory
import com.example.pulsar.domain.model.Track
import com.example.pulsar.playback.FakePlaybackGate
import com.example.pulsar.playback.FakePlaybackRemote
import com.example.pulsar.playback.PlaybackIntent
import com.example.pulsar.playback.PlaybackSession
import com.example.pulsar.playback.RemoteSnapshot
import com.example.pulsar.playback.TrackMediaItemFactory
import com.example.pulsar.readCompleteCopyDigest
import com.example.pulsar.readCompleteCopyStamp
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

    @Test
    fun `单独确认前没有已有下载 重开后只列出原账号按专辑和播放列表下载的完整副本`() = runTest {
        val fixture = Fixture(this)
        fixture.saveAccount()
        val entries = listOf(
            entry("我的专辑", listOf("album-track", "partial")),
            entry("通勤列表", listOf("album-track", "playlist-track"), DownloadKind.PLAYLIST),
            entry("别的账号", listOf("other")).copy(userId = "其他账号"),
        )
        fixture.ledger.replaceAll(entries)
        val album = fixture.copyAt("$base/Videos/album-track/stream?static=true")
        val playlist = fixture.copyAt("$base/Videos/playlist-track/stream?static=true")
        fixture.copyAt("$base/Videos/partial/stream?static=true", cached = 2)
        fixture.copyAt("$base/Videos/other/stream?static=true")
        val report = fixture.previewer.preview() as OldDownloadPreview.Report
        assertEquals(ExistingDownloadView.NeedsConfirmation, fixture.existingDownloads().load())

        assertEquals(ExistingDownloadApply.Partial, fixture.existingDownloads().confirm(report))
        assertEquals(ExistingDownloadApply.Partial, fixture.existingDownloads().confirm(report))
        val reopened = fixture.existingDownloads().load() as ExistingDownloadView.Ready
        assertEquals(listOf("album-track", "playlist-track"), reopened.playable.map { it.item.trackId })
        assertEquals(listOf("partial"), reopened.unfinished.map { it.trackId })
        assertEquals(entries, fixture.ledger.load().entries)
        assertEquals(listOf<Byte>(1, 2, 3, 4), album.readBytes().toList())
        assertEquals(listOf<Byte>(1, 2, 3, 4), playlist.readBytes().toList())

        fixture.saveAccount("其他账号")
        assertEquals(ExistingDownloadView.NeedsConfirmation, fixture.existingDownloads().load())
    }

    @Test
    fun `飞行模式从已有下载点播只给播放器原副本的读取定位 丢失字节后原请求不能再播`() = runTest {
        val fixture = Fixture(this)
        fixture.saveAccount()
        fixture.ledger.replaceAll(listOf(entry("通勤列表", listOf("album 曲"), DownloadKind.PLAYLIST)))
        val originalKey = "$base/Videos/album%20%E6%9B%B2/stream?static=true"
        val copy = fixture.copyAt(originalKey)
        val existing = fixture.existingDownloads()
        assertEquals(ExistingDownloadApply.Applied, existing.confirm(fixture.previewer.preview() as OldDownloadPreview.Report))

        val reopened = fixture.existingDownloads()
        val track = (reopened.load() as ExistingDownloadView.Ready).playable.single()
        val remote = FakePlaybackRemote().apply { snapshot = RemoteSnapshot(isConnected = true) }
        val playback = PlaybackSession(remote, TrackMediaItemFactory {
            MediaItem.Builder().setMediaId(it.id).build()
        }, FakePlaybackGate(), backgroundScope)
        assertEquals(true, playback.dispatch(PlaybackIntent.Play(listOf(track.toTrack()), track.mediaId)))
        val queuedId = remote.setQueueCalls.single().items.single().mediaId
        assertEquals(originalKey, reopened.playbackKey(queuedId))
        playback.close()
        assertEquals(null, reopened.playbackKey("album 曲"))
        assertEquals(null, reopened.playbackKey("pc:新曲库:album 曲"))

        copy.writeBytes(byteArrayOf(1, 2))
        assertEquals(null, reopened.playbackKey(track.mediaId))
        val changed = reopened.load() as ExistingDownloadView.Ready
        assertEquals(emptyList<ExistingDownload>(), changed.playable)
        assertEquals(listOf("album 曲"), changed.unavailable.map { it.trackId })
    }

    @Test
    fun `写盘失败不覆盖上次可播结果 重试只补新确认的副本`() = runTest {
        val fixture = Fixture(this)
        fixture.saveAccount()
        val first = entry("第一张专辑", listOf("first"))
        fixture.ledger.replaceAll(listOf(first))
        fixture.copyAt("$base/Videos/first/stream?static=true")
        val store = DataStoreExistingDownloadStore(fixture.preferences)
        val existing = fixture.existingDownloads()
        assertEquals(ExistingDownloadApply.Applied, existing.confirm(fixture.previewer.preview() as OldDownloadPreview.Report))

        fixture.ledger.replaceAll(listOf(first, entry("第二张专辑", listOf("second"))))
        fixture.copyAt("$base/Videos/second/stream?static=true")
        val report = fixture.previewer.preview() as OldDownloadPreview.Report
        val failing = ExistingDownloads(fixture.previewer, object : ExistingDownloadStore by store {
            override suspend fun add(account: AccountScope, items: List<OldDownloadItem>) {
                throw java.io.IOException("空间不足")
            }
        })
        assertEquals(ExistingDownloadApply.Unavailable, failing.confirm(report))
        assertEquals(listOf("first"), (existing.load() as ExistingDownloadView.Ready).playable.map { it.item.trackId })
        assertEquals(ExistingDownloadApply.Applied, existing.confirm(report))
        assertEquals(listOf("first", "second"), (existing.load() as ExistingDownloadView.Ready).playable.map { it.item.trackId })
    }

    @Test
    fun `应用期间已有副本变为不可用时 只能报告部分可用且保留原登记`() = runTest {
        val fixture = Fixture(this)
        fixture.saveAccount()
        fixture.ledger.replaceAll(listOf(entry("我的专辑", listOf("one", "two"))))
        fixture.copyAt("$base/Videos/one/stream?static=true")
        val changed = fixture.copyAt("$base/Videos/two/stream?static=true")
        val store = DataStoreExistingDownloadStore(fixture.preferences)
        val existing = ExistingDownloads(fixture.previewer, object : ExistingDownloadStore by store {
            override suspend fun add(account: AccountScope, items: List<OldDownloadItem>) {
                changed.writeBytes(byteArrayOf(1, 2))
                store.add(account, items)
            }
        })

        val report = fixture.previewer.preview() as OldDownloadPreview.Report
        assertEquals(ExistingDownloadApply.Partial, existing.confirm(report))
        val reopened = fixture.existingDownloads().load() as ExistingDownloadView.Ready
        assertEquals(listOf("one"), reopened.playable.map { it.item.trackId })
        assertEquals(listOf("two"), reopened.unavailable.map { it.trackId })
    }

    @Test
    fun `旧账号或完整性在预览后改变时 必须重新核对再确认`() = runTest {
        val fixture = Fixture(this)
        fixture.saveAccount()
        fixture.ledger.replaceAll(listOf(entry("我的专辑", listOf("song"))))
        val file = fixture.copyAt("$base/Videos/song/stream?static=true")
        val report = fixture.previewer.preview() as OldDownloadPreview.Report
        val existing = fixture.existingDownloads()

        fixture.saveAccount("其他账号")
        assertEquals(ExistingDownloadApply.PreviewChanged, existing.confirm(report))
        fixture.saveAccount()
        file.writeBytes(byteArrayOf(1, 2))
        assertEquals(ExistingDownloadApply.PreviewChanged, existing.confirm(report))
        assertEquals(ExistingDownloadView.NeedsConfirmation, existing.load())
    }

    @Test
    fun `会话凭据失效仍可重开已有下载 主动退出后不对无归属的用户开放`() = runTest {
        val fixture = Fixture(this)
        fixture.saveAccount()
        fixture.ledger.replaceAll(listOf(entry("我的专辑", listOf("song"))))
        fixture.copyAt("$base/Videos/song/stream?static=true")
        val before = fixture.existingDownloads()
        assertEquals(ExistingDownloadApply.Applied, before.confirm(fixture.previewer.preview() as OldDownloadPreview.Report))
        val track = (before.load() as ExistingDownloadView.Ready).playable.single()

        fixture.expireAccount()
        assertEquals("$base/Videos/song/stream?static=true", fixture.existingDownloads().playbackKey(track.mediaId))
        fixture.clearAccount()
        assertEquals(ExistingDownloadView.NoSavedAccount, fixture.existingDownloads().load())
        assertEquals(null, fixture.existingDownloads().playbackKey(track.mediaId))
    }

    @Test
    fun `字节完整但播放器报错的歌曲单列保留 重新检查后可以再试`() = runTest {
        val fixture = Fixture(this)
        fixture.saveAccount()
        fixture.ledger.replaceAll(listOf(entry("我的专辑", listOf("song"))))
        val original = fixture.copyAt("$base/Videos/song/stream?static=true")
        val downloads = fixture.existingDownloads()
        val report = fixture.previewer.preview() as OldDownloadPreview.Report
        assertEquals(ExistingDownloadApply.Applied, downloads.confirm(report))
        val track = (downloads.load() as ExistingDownloadView.Ready).playable.single()

        downloads.recordPlaybackFailure(track.mediaId)
        val view = downloads.load() as ExistingDownloadView.Ready
        assertEquals(emptyList<ExistingDownload>(), view.playable)
        assertEquals(listOf("song"), view.unavailable.map { it.trackId })
        assertEquals(ExistingDownloadApply.Partial, downloads.confirm(report))
        assertEquals(listOf<Byte>(1, 2, 3, 4), original.readBytes().toList())
        downloads.resetPlaybackFailures()
        assertEquals(listOf(track), (downloads.load() as ExistingDownloadView.Ready).playable)
    }

    @Test
    fun `只确认字节完全相同的候选 断网后仍读原键 未匹配的原副本仍可播`() = runTest {
        val fixture = Fixture(this)
        fixture.saveAccount()
        val ids = listOf("same", "replaced", "moved")
        val entry = entry("我的专辑", ids)
        fixture.ledger.replaceAll(listOf(entry))
        fixture.snapshot.save(LibrarySnapshot(tracks = ids.map { id ->
            track(id, "同名歌").copy(filePath = "E:/Music/$id.flac")
        }))
        val original = fixture.copyAt("$base/Videos/same/stream?static=true")
        fixture.copyAt("$base/Videos/replaced/stream?static=true")
        fixture.copyAt("$base/Videos/moved/stream?static=true")
        val exact = track("pc:source:exact", "同名歌").copy(sourceRevision = "version-1")
        val different = exact.copy(id = "pc:source:different")
        var online = true
        val source = CompanionDownloadEvidenceSource { paths ->
            check(online) { "离线读取确认结果不能请求电脑" }
            assertEquals(ids.map { "E:/Music/$it.flac" }, paths)
            CompanionDownloadEvidence("paired-computer", listOf(
                CompanionCopyCandidate(paths[0], exact, 4, "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"),
                CompanionCopyCandidate(paths[1], different, 4, "0".repeat(64)),
            ))
        }
        val downloads = fixture.existingDownloads()
        downloads.confirm(fixture.previewer.preview() as OldDownloadPreview.Report)
        val links = fixture.links(source)
        val preview = checkNotNull(links.preview())
        assertEquals(listOf(exact), preview.matches.map { it.track })
        assertEquals(2, preview.unmatched)
        assertEquals(emptyList<LinkedDownload>(), links.load("paired-computer"))
        assertEquals(true, links.confirm(preview))

        online = false
        val reopened = fixture.links(source)
        val linked = reopened.load("paired-computer").single()
        assertEquals(exact, linked.track)
        assertEquals("$base/Videos/same/stream?static=true", reopened.playbackKey(linked.playbackUri))
        assertEquals(emptyList<LinkedDownload>(), reopened.load("另一台电脑"))
        fixture.companionScope = "另一台电脑"
        assertEquals(null, reopened.playbackKey(linked.playbackUri))
        fixture.companionScope = "paired-computer"
        assertEquals(ids, (downloads.load() as ExistingDownloadView.Ready).playable.map { it.item.trackId })
        assertEquals(listOf(entry), fixture.ledger.load().entries)

        reopened.checkCatalog("paired-computer", listOf(exact.copy(sourceRevision = "version-2")))
        assertEquals(emptyList<LinkedDownload>(), reopened.load("paired-computer"))
        assertEquals(null, reopened.playbackKey(linked.playbackUri))
        assertEquals("$base/Videos/same/stream?static=true", downloads.playbackKey(linked.originalId))
        online = true
        assertEquals(true, links.confirm(preview))
        online = false

        original.writeBytes(byteArrayOf(1, 2))
        assertEquals(null, reopened.playbackKey(linked.playbackUri))
        fixture.saveAccount("其他账号")
        assertEquals(emptyList<LinkedDownload>(), reopened.load("paired-computer"))
    }

    private inner class Fixture(scope: TestScope) {
        val prefsFile = File(temporary.root, "pulsar.preferences_pb")
        val preferences = PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { prefsFile }
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

        fun existingDownloads() = ExistingDownloads(previewer, DataStoreExistingDownloadStore(preferences))

        var companionScope = "paired-computer"

        fun links(source: CompanionDownloadEvidenceSource) = LinkedDownloads(
            previewer, existingDownloads(), snapshot, DataStoreLinkedDownloadStore(preferences), source,
            { companionScope }, { key -> cache().readCompleteCopyDigest(key) },
            { key -> cache().readCompleteCopyStamp(key) },
        )

        suspend fun saveAccount(userId: String = user) = store.save(Session(server, userId, "旧账号", "test-only-token"))
        suspend fun expireAccount() = store.clearExpired()
        suspend fun clearAccount() = sessionManager.signOut()

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
