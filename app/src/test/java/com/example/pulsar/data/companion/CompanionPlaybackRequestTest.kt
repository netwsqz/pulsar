package com.example.pulsar.data.companion

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.TimeUnit

class CompanionPlaybackRequestTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `播放地址不含访问密钥且跳播请求只鉴权到已配对电脑`() = runTest {
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                val path = "/api/library/tracks/$trackId/audio"
                val otherUrl = other.url(path)
                server.enqueue(MockResponse().setResponseCode(206).setBody("音频"))
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", otherUrl))
                other.enqueue(MockResponse().setResponseCode(204))
                val listClient = HttpClient(MockEngine {
                    respond("""{"tracks":[{"id":"$trackId","title":"浮夸","artist":"陈奕迅","album":"U 87"}]}""")
                })
                listClient.use {
                    val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
                        temp.root.resolve("pulsar.preferences_pb")
                    }
                    val connection = CompanionLibraryConnection(listClient, CompanionPairingStore(preferences))
                    val track = connection.pair(server.url("/").toString(), "test-pairing-key").getOrThrow().single()
                    val url = connection.streamUrl(track.id) ?: error("曲目没有播放地址")
                    assertEquals(server.url(path).toString(), url)
                    val playback = companionPlaybackHttpClient(connection)
                    val request = Request.Builder().url(url).header("Range", "bytes=0-5").build()
                    playback.newCall(request).execute().use { assertEquals(206, it.code) }
                    val seen = server.takeRequest(1, TimeUnit.SECONDS) ?: error("没有收到跳播请求")
                    assertEquals("Bearer test-pairing-key", seen.getHeader("Authorization"))
                    assertEquals("bytes=0-5", seen.getHeader("Range"))
                    // 同一首歌的地址不能借重定向或改端口把访问密钥发给其他服务。
                    playback.newCall(request).execute().use { assertEquals(302, it.code) }
                    assertThrows(IOException::class.java) {
                        playback.newCall(Request.Builder().url(otherUrl).build()).execute().close()
                    }
                    assertEquals(0, other.requestCount)
                }
            }
        }
    }

    @Test
    fun `封面密钥只发给当前配对的封面接口 不跟跳转或旧配对`() = runTest {
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                val path = "/api/library/tracks/$trackId/artwork"
                val otherUrl = other.url(path)
                server.enqueue(MockResponse().setBody("内嵌封面"))
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", otherUrl))
                other.enqueue(MockResponse().setResponseCode(200))
                HttpClient(MockEngine {
                    respond("""{"tracks":[{"id":"$trackId","title":"浮夸","artist":"陈奕迅","album":"U 87",
                        "has_artwork":true,"revision":"${"b".repeat(64)}"}]}""")
                }).use { client ->
                    val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
                        temp.root.resolve("pulsar.preferences_pb")
                    }
                    val connection = CompanionLibraryConnection(client, CompanionPairingStore(preferences))
                    val address = server.url("/").toString()
                    val track = connection.pair(address, "test-pairing-key").getOrThrow().single()
                    val artwork = checkNotNull(connection.artwork(track, checkNotNull(connection.savedScope())))
                    assertEquals(server.url(path).toString(), artwork.url)
                    val http = companionArtworkHttpClient(connection)
                    val request = Request.Builder().url(artwork.url).tag(CompanionArtworkRequest::class.java, artwork).build()
                    assertEquals(null, request.header("Authorization"))
                    http.newCall(request).execute().use { assertEquals("内嵌封面", it.body!!.string()) }
                    val seen = server.takeRequest(1, TimeUnit.SECONDS) ?: error("没有收到封面请求")
                    assertEquals(path, seen.path)
                    assertEquals("Bearer test-pairing-key", seen.getHeader("Authorization"))
                    assertEquals("\"${"b".repeat(64)}\"", seen.getHeader("If-Match"))
                    http.newCall(request).execute().use { assertEquals(302, it.code) }
                    for (url in listOf(otherUrl, server.url(path.replace("/artwork", "/audio")))) {
                        assertThrows(IOException::class.java) {
                            http.newCall(request.newBuilder().url(url).build()).execute().close()
                        }
                    }
                    connection.pair(address, "changed-key").getOrThrow()
                    assertThrows(IOException::class.java) { http.newCall(request).execute().close() }
                    assertEquals(2, server.requestCount)
                    assertEquals(0, other.requestCount)
                }
            }
        }
    }

    @Test
    fun `下载续传只接受当前配对和请求的文件版本`() = runTest {
        MockWebServer().use { server ->
            val revision = "b".repeat(64)
            val tag = "\"$revision\""
            server.enqueue(MockResponse().setResponseCode(206).setHeader("ETag", tag).setBody("original"))
            server.enqueue(MockResponse().setResponseCode(206).setBody("different"))
            HttpClient(MockEngine {
                respond("""{"tracks":[{"id":"$trackId","title":"浮夸","artist":"陈奕迅","album":"U 87","revision":"$revision"}]}""")
            }).use { client ->
                val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
                    temp.root.resolve("pulsar.preferences_pb")
                }
                val connection = CompanionLibraryConnection(client, CompanionPairingStore(preferences))
                val track = connection.pair(server.url("/").toString(), "test-pairing-key").getOrThrow().single()
                val pairing = checkNotNull(connection.savedScope())
                val request = Request.Builder().url(checkNotNull(connection.streamUrl(track.id)))
                    .header("Range", "bytes=8-").header("If-Match", tag)
                    .header("Authorization", checkNotNull(connection.downloadAuthorization(server.url("/api/library/tracks/$trackId/audio"), pairing)))
                    .build()
                val download = companionDownloadHttpClient()
                download.newCall(request).execute().use { assertEquals("original", it.body!!.string()) }
                val seen = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("Bearer test-pairing-key", seen.getHeader("Authorization"))
                assertEquals("bytes=8-", seen.getHeader("Range"))
                assertEquals(tag, seen.getHeader("If-Match"))
                assertThrows(IOException::class.java) { download.newCall(request).execute().close() }
                connection.pair(server.url("/").toString(), "changed-key").getOrThrow()
                assertEquals(null, connection.downloadAuthorization(request.url, pairing))
            }
        }
    }

    private companion object {
        val trackId = "a".repeat(64)
    }
}
