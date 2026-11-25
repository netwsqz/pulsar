package com.example.pulsar.data.companion

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.pulsar.DataStoreDownloadLedgerStore
import com.example.pulsar.DownloadEntry
import com.example.pulsar.DownloadKind
import com.example.pulsar.SessionStore
import com.example.pulsar.session.Session
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompanionLibraryConnectionTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `配对成功后重开仍能列歌且不改旧会话和下载名单`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temp.root.resolve("pulsar.preferences_pb")
        }
        val oldSession = Session("http://192.168.1.3:8096", "user-1", "user", "old-token")
        val oldDownloads = listOf(
            DownloadEntry(oldSession.serverUrl, oldSession.userId, DownloadKind.ALBUM,
                "album-1", "旧专辑", listOf("old-track"), 1L)
        )
        SessionStore(preferences).save(oldSession)
        DataStoreDownloadLedgerStore(preferences).replaceAll(oldDownloads)
        val client = HttpClient(MockEngine { request ->
            assertEquals("http://192.168.1.8:8900/api/library/tracks", request.url.toString())
            assertEquals("Bearer $key", request.headers[HttpHeaders.Authorization])
            respond(
                """{"tracks":[{"id":"$trackId","title":"浮夸","artist":"陈奕迅","album":"U 87"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        })
        client.use {
            val first = CompanionLibraryConnection(client, CompanionPairingStore(preferences))
            assertEquals("浮夸", first.pair("192.168.1.8", key).getOrThrow().single().title)
            val reopened = CompanionLibraryConnection(client, CompanionPairingStore(preferences))
            assertEquals("陈奕迅", reopened.refresh().getOrThrow()!!.single().artist)
            assertEquals(oldSession, SessionStore(preferences).load())
            assertEquals(oldDownloads, DataStoreDownloadLedgerStore(preferences).load().entries)
        }
    }

    @Test
    fun `错密钥不覆盖成功配对且不回显服务端错误正文`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temp.root.resolve("pulsar.preferences_pb")
        }
        val client = HttpClient(MockEngine { request ->
            if (request.headers[HttpHeaders.Authorization] == "Bearer $key") {
                assertEquals("192.168.1.8", request.url.host)
                respond("""{"tracks":[]}""")
            } else {
                respond("wrong-secret", HttpStatusCode.Unauthorized)
            }
        })
        client.use {
            val connection = CompanionLibraryConnection(client, CompanionPairingStore(preferences))
            assertEquals(emptyList<Any>(), connection.pair("192.168.1.8", key).getOrThrow())
            val failure = connection.pair("192.168.1.9", "wrong-secret").exceptionOrNull()
            assertEquals("访问密钥不正确，请重新配对", failure?.message)
            assertEquals(emptyList<Any>(), connection.refresh().getOrThrow())
        }
    }

    @Test
    fun `连不上电脑时返回可重试错误且未配对`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temp.root.resolve("pulsar.preferences_pb")
        }
        val client = HttpClient(MockEngine { throw java.io.IOException("故障细节") })
        client.use {
            val connection = CompanionLibraryConnection(client, CompanionPairingStore(preferences))
            assertEquals("连不上电脑，请确认两台设备在同一网络、PC 服务已启动",
                connection.pair("192.168.1.8", key).exceptionOrNull()?.message)
            assertEquals(null, connection.refresh().getOrThrow())
        }
    }

    @Test
    fun `电脑曲库初次扫描未完成时不保存半截配对`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temp.root.resolve("pulsar.preferences_pb")
        }
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.ServiceUnavailable) })
        client.use {
            val connection = CompanionLibraryConnection(client, CompanionPairingStore(preferences))
            assertEquals("电脑曲库还没准备好，请稍后重试；一直不行请检查电脑上的音乐目录",
                connection.pair("192.168.1.8", key).exceptionOrNull()?.message)
            assertEquals(null, connection.refresh().getOrThrow())
        }
    }

    @Test
    fun `显式指定标准端口的配对在重开后仍用同一端口`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temp.root.resolve("pulsar.preferences_pb")
        }
        val client = HttpClient(MockEngine { request ->
            assertEquals(80, request.url.port)
            respond("""{"tracks":[]}""")
        })
        client.use {
            val first = CompanionLibraryConnection(client, CompanionPairingStore(preferences))
            assertEquals(emptyList<Any>(), first.pair("http://192.168.1.8:80", key).getOrThrow())
            val reopened = CompanionLibraryConnection(client, CompanionPairingStore(preferences))
            assertEquals(emptyList<Any>(), reopened.refresh().getOrThrow())
        }
    }

    @Test
    fun `不是家庭网络的地址或附带路径的地址不能收到访问密钥`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temp.root.resolve("pulsar.preferences_pb")
        }
        val client = HttpClient(MockEngine { error("无效地址不应发起请求") })
        client.use {
            val connection = CompanionLibraryConnection(client, CompanionPairingStore(preferences))
            for (address in listOf("https://example.org", "http://192.168.1.8/api", "http://192.168.1.8?x=1")) {
                assertEquals(CompanionLibraryError.InvalidPairing, connection.pair(address, key).exceptionOrNull())
            }
            assertEquals(null, connection.refresh().getOrThrow())
        }
    }

    @Test
    fun `只把请求的路径发给已配对电脑 并拒绝与清单无关的证据`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temp.root.resolve("pulsar.preferences_pb")
        }
        val path = "E:/Music/中文.flac"
        val revision = "c".repeat(64)
        var returnedPath = path
        HttpClient(MockEngine { request ->
            assertEquals("Bearer $key", request.headers[HttpHeaders.Authorization])
            when (request.method) {
                HttpMethod.Get -> respond("""{"tracks":[]}""")
                HttpMethod.Post -> {
                    assertEquals("http://192.168.1.8:8900/api/library/evidence", request.url.toString())
                    assertEquals("""{"paths":["$path"]}""", (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                    respond("""{"tracks":[{"path":"$returnedPath","id":"$trackId","title":"新曲目","artist":"歌手",
                        "album":"专辑","revision":"$revision","size":4,"sha256":"${"d".repeat(64)}"}]}""")
                }
                else -> error("请求方法不正确")
            }
        }).use { client ->
            val connection = CompanionLibraryConnection(client, CompanionPairingStore(preferences))
            assertEquals(null, connection.evidence(listOf(path)))
            connection.pair("192.168.1.8", key).getOrThrow()
            val checked = checkNotNull(connection.evidence(listOf(path)))
            assertEquals(connection.savedScope(), checked.scope)
            assertEquals("新曲目", checked.candidates.single().track.title)
            assertEquals(revision, checked.candidates.single().track.sourceRevision)
            returnedPath = "E:/Music/另一首.flac"
            assertEquals(null, connection.evidence(listOf(path)))
        }
    }

    private companion object {
        const val key = "library-test-key"
        val trackId = "a".repeat(64)
    }
}
