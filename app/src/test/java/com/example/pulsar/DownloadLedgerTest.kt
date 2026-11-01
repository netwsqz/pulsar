package com.example.pulsar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadLedgerTest {

    private val albumEntry = DownloadEntry(
        serverUrl = "https://server.test",
        userId = "user-1",
        kind = DownloadKind.ALBUM,
        id = "album-1",
        name = "专辑一",
        trackIds = listOf("t1", "t2"),
        createdAt = 1_000L
    )

    // ── 纯函数：编解码 ────────────────────────────────────────────

    @Test
    fun `名单往返：encode 后 decode 回到同一份内容`() {
        val ledger = DownloadLedger(
            entries = listOf(
                albumEntry,
                DownloadEntry(
                    serverUrl = "https://server.test",
                    userId = "user-1",
                    kind = DownloadKind.PLAYLIST,
                    id = "playlist-1",
                    name = "通勤",
                    trackIds = listOf("t3"),
                    createdAt = 2_000L
                ),
            )
        )

        assertEquals(ledger, DownloadLedgerCodec.decode(DownloadLedgerCodec.encode(ledger)))
    }

    @Test
    fun `坏 JSON 返回 null 而不是抛异常`() {
        assertNull(DownloadLedgerCodec.decode("这不是 JSON"))
        assertNull(DownloadLedgerCodec.decode("{\"entries\": 123}"))
    }

    @Test
    fun `未知字段不挡解码（旧名单兼容新字段）`() {
        val encoded = DownloadLedgerCodec.encode(DownloadLedger())
        val withExtra = encoded.dropLast(1) + ",\"futureField\": 1}"
        assertEquals(DownloadLedger(), DownloadLedgerCodec.decode(withExtra))
    }

    @Test
    fun `同名不同 kind 的两条都能往返且互不覆盖`() {
        val album = albumEntry.copy(id = "same-id", name = "同名", kind = DownloadKind.ALBUM)
        val playlist = albumEntry.copy(id = "same-id", name = "同名", kind = DownloadKind.PLAYLIST)

        val decoded = DownloadLedgerCodec.decode(DownloadLedgerCodec.encode(DownloadLedger(listOf(album, playlist))))

        assertEquals(listOf(album, playlist), decoded?.entries)
    }

    @Test
    fun `落盘格式与规格一致：字段名照写，kind 用 album playlist`() {
        // 规格的 DownloadEntry 字段是这份名单的对外契约（迁移 / 其他工单都按它读），钉死别漂
        val encoded = DownloadLedgerCodec.encode(
            DownloadLedger(
                listOf(
                    albumEntry,
                    albumEntry.copy(kind = DownloadKind.PLAYLIST)
                )
            )
        )

        listOf("\"serverUrl\"", "\"userId\"", "\"kind\"", "\"id\"", "\"name\"", "\"trackIds\"", "\"createdAt\"")
            .forEach { assertTrue("缺少字段 $it：$encoded", encoded.contains(it)) }
        listOf("\"album\"", "\"playlist\"")
            .forEach { assertTrue("kind 取值不对：$encoded", encoded.contains(it)) }
    }

    // ── DataStore 实现 ───────────────────────────────────────────

    @Test
    fun `空 store 读出空名单`() = runTest {
        assertEquals(DownloadLedger(), DataStoreDownloadLedgerStore(tempDataStore()).load())
    }

    @Test
    fun `replaceAll 是整表覆写，第二次写入替换第一次`() = runTest {
        val store = DataStoreDownloadLedgerStore(tempDataStore())
        store.replaceAll(listOf(albumEntry))

        val replacement = albumEntry.copy(trackIds = listOf("t1"))
        store.replaceAll(listOf(replacement))

        assertEquals(listOf(replacement), store.load().entries)
    }

    @Test
    fun `按账号过滤：同服务器不同用户互相看不见`() = runTest {
        val store = DataStoreDownloadLedgerStore(tempDataStore())
        val other = albumEntry.copy(userId = "user-2", id = "album-2")
        store.replaceAll(listOf(albumEntry, other))

        assertEquals(listOf(albumEntry), store.entriesFor("https://server.test", "user-1"))
        assertEquals(listOf(other), store.entriesFor("https://server.test", "user-2"))
    }

    @Test
    fun `按账号过滤：同用户不同服务器互相看不见`() = runTest {
        val store = DataStoreDownloadLedgerStore(tempDataStore())
        val otherServer = albumEntry.copy(serverUrl = "https://other.test")
        store.replaceAll(listOf(albumEntry, otherServer))

        assertEquals(listOf(albumEntry), store.entriesFor("https://server.test", "user-1"))
    }

    @Test
    fun `按账号过滤保留同名不同 kind 的两条`() = runTest {
        val store = DataStoreDownloadLedgerStore(tempDataStore())
        val album = albumEntry.copy(id = "same-id", kind = DownloadKind.ALBUM)
        val playlist = albumEntry.copy(id = "same-id", kind = DownloadKind.PLAYLIST)
        store.replaceAll(listOf(album, playlist))

        assertEquals(
            listOf(album, playlist),
            store.entriesFor("https://server.test", "user-1")
        )
    }

    @Test
    fun `退出登录（清会话）不清理下载名单`() = runTest {
        // 用户故事 16：下载是用户主动存的，退出登录只清会话，不能顺手把名单一起清掉。
        val dataStore = tempDataStore()
        val sessions = SessionStore(dataStore)
        val ledger = DataStoreDownloadLedgerStore(dataStore)
        sessions.save(SavedSession("https://server.test", "user-1", "alice", "token-abc"))
        ledger.replaceAll(listOf(albumEntry))

        // 模拟 JellyfinViewModel.signOut() 里的会话清理
        sessions.clear()

        assertNull(sessions.load())
        assertEquals(listOf(albumEntry), ledger.entriesFor("https://server.test", "user-1"))
    }

    @Test
    fun `落盘的 JSON 坏了时读出空名单，不抛给界面`() = runTest {
        val dataStore = tempDataStore()
        // 键名与实现里的存储键一致：坏数据只能降级成「没有名单」，不能变成新的故障源
        dataStore.edit { it[stringPreferencesKey("download_ledger_json")] = "{半截" }

        assertEquals(DownloadLedger(), DataStoreDownloadLedgerStore(dataStore).load())
    }

    @Test
    fun `剔除无归属条目：serverUrl 为空的历史坏数据被清掉`() = runTest {
        val dataStore = tempDataStore()
        val store = DataStoreDownloadLedgerStore(dataStore)
        val bad = albumEntry.copy(serverUrl = "", id = "bad-album")
        store.replaceAll(listOf(albumEntry, bad))

        assertEquals(1, store.pruneUnattributed())

        assertEquals(listOf(albumEntry), store.load().entries)
    }

    @Test
    fun `剔除无归属条目：名单本来就干净时什么都不改`() = runTest {
        val dataStore = tempDataStore()
        val store = DataStoreDownloadLedgerStore(dataStore)
        store.replaceAll(listOf(albumEntry))

        assertEquals(0, store.pruneUnattributed())
        assertEquals(listOf(albumEntry), store.load().entries)
    }

    private fun tempDataStore(): DataStore<Preferences> {
        val file = File.createTempFile("pulsar-download-ledger", ".preferences_pb")
        file.delete()
        return PreferenceDataStoreFactory.create { file }
    }
}
