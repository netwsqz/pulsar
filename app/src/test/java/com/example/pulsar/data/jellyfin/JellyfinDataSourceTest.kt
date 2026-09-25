package com.example.pulsar.data.jellyfin

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.Track
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinDataSourceTest {
    @Test
    fun `login sends MediaBrowser authorization and username password JSON`() = runTest {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respond(
                content = "{\"User\":{\"Id\":\"user-1\"},\"AccessToken\":\"token-1\"}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        })

        val result = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        ).login("alice", "s3cret")

        assertTrue(result.isSuccess)
        val request = requireNotNull(capturedRequest)
        assertEquals("/Users/AuthenticateByName", request.url.encodedPath)
        assertEquals("POST", request.method.value)
        val authorization = request.headers[HttpHeaders.Authorization]
        assertNotNull(authorization)
        assertEquals(
            "MediaBrowser Client=\"Pulsar\", Device=\"Android\", " +
                "DeviceId=\"41e55f58-619c-45d1-9bc7-6e59b70437fd\", Version=\"0.1.0\"",
            authorization
        )
        val payload = Json.parseToJsonElement(
            (request.body as TextContent).text
        ).jsonObject
        assertEquals("alice", payload.getValue("Username").jsonPrimitive.content)
        assertEquals("s3cret", payload.getValue("Pw").jsonPrimitive.content)
        client.close()
    }

    @Test
    fun `music list sends MediaBrowser authorization with token`() = runTest {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respond(
                content = "{\"Items\":[{\"Id\":\"track-1\",\"Name\":\"Track One\",\"Type\":\"Audio\"}]}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        })
        val deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        val result = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = deviceId
        ).getMusicItems("user-1", "token-1")

        assertTrue(result.isSuccess)
        val request = requireNotNull(capturedRequest)
        assertEquals("/Users/user-1/Items", request.url.encodedPath)
        assertEquals("Audio", request.url.parameters["IncludeItemTypes"])
        assertEquals("true", request.url.parameters["Recursive"])
        assertEquals(
            "MediaBrowser Client=\"Pulsar\", Device=\"Android\", " +
                "DeviceId=\"$deviceId\", Version=\"0.1.0\", Token=\"token-1\"",
            request.headers[HttpHeaders.Authorization]
        )
        client.close()
    }

    @Test
    fun `music list maps replay gain and leaves tracks without loudness data untouched`() = runTest {
        // NormalizationGain 是 Jellyfin 10.9+ 按 -18 LUFS 基准现算的回放增益（dB）。
        // JSON 里字段名写错的话会被 ignoreUnknownKeys 静默吞掉——正是这条用例要挡的。
        val client = HttpClient(MockEngine {
            respond(
                content = "{\"Items\":[" +
                    "{\"Id\":\"track-1\",\"Name\":\"Loud\",\"Type\":\"Audio\",\"NormalizationGain\":-7.14}," +
                    "{\"Id\":\"track-2\",\"Name\":\"NoLoudnessData\",\"Type\":\"Audio\"}]}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        })
        val result = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        ).getMusicItems("user-1", "token-1")

        assertTrue(result.isSuccess)
        val tracks = result.getOrThrow()
        assertEquals(-7.14f, tracks[0].normalizationGainDb!!, 0.001f)
        assertNull("没有响度数据的曲目必须保持 null，播放时按原样走", tracks[1].normalizationGainDb)
        client.close()
    }

    @Test
    fun `streamUrl builds playable URL without exposing token`() {
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })
        val deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        val dataSource = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = deviceId
        )

        val url = dataSource.streamUrl("track-1", "secret-token")

        assertEquals("https://media.example.com/Videos/track-1/stream?static=true", url)
        assertFalse(url.contains("secret-token"))
        client.close()
    }

    @Test
    fun `authorizationHeader uses MediaBrowser format with token`() {
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })
        val deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        val dataSource = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = deviceId
        )

        val header = dataSource.authorizationHeader("token-1")

        assertEquals(
            "MediaBrowser Client=\"Pulsar\", Device=\"Android\", " +
                "DeviceId=\"$deviceId\", Version=\"0.1.0\", Token=\"token-1\"",
            header
        )
        client.close()
    }

    @Test
    fun `albums list sends MusicAlbum query with auth and parses albums`() = runTest {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respond(
                content = "{\"Items\":[{\"Id\":\"album-1\",\"Name\":\"Great Album\"," +
                    "\"AlbumArtist\":\"Album Artist\",\"ChildCount\":10,\"Type\":\"MusicAlbum\"}]}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        })
        val deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        val result = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = deviceId
        ).getAlbums("user-1", "token-1")

        assertTrue(result.isSuccess)
        val request = requireNotNull(capturedRequest)
        assertEquals("/Users/user-1/Items", request.url.encodedPath)
        assertEquals("MusicAlbum", request.url.parameters["IncludeItemTypes"])
        assertEquals("true", request.url.parameters["Recursive"])
        assertEquals("ChildCount", request.url.parameters["Fields"])
        assertEquals("SortName", request.url.parameters["SortBy"])
        assertEquals("Ascending", request.url.parameters["SortOrder"])
        assertEquals(
            "MediaBrowser Client=\"Pulsar\", Device=\"Android\", " +
                "DeviceId=\"$deviceId\", Version=\"0.1.0\", Token=\"token-1\"",
            request.headers[HttpHeaders.Authorization]
        )
        assertEquals(
            listOf(Album("album-1", "Great Album", "Album Artist", 10, null)),
            result.getOrThrow()
        )
        client.close()
    }

    @Test
    fun `random albums sends Random sort with limit and parses albums`() = runTest {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respond(
                content = "{\"Items\":[{\"Id\":\"album-1\",\"Name\":\"Great Album\"," +
                    "\"AlbumArtist\":\"Album Artist\",\"ChildCount\":10,\"Type\":\"MusicAlbum\"}]}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        })
        val deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        val result = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = deviceId
        ).getRandomAlbums("user-1", "token-1", limit = 8)

        assertTrue(result.isSuccess)
        val request = requireNotNull(capturedRequest)
        assertEquals("/Users/user-1/Items", request.url.encodedPath)
        assertEquals("MusicAlbum", request.url.parameters["IncludeItemTypes"])
        assertEquals("true", request.url.parameters["Recursive"])
        assertEquals("ChildCount", request.url.parameters["Fields"])
        assertEquals("Random", request.url.parameters["SortBy"])
        assertEquals("8", request.url.parameters["Limit"])
        assertEquals(
            "MediaBrowser Client=\"Pulsar\", Device=\"Android\", " +
                "DeviceId=\"$deviceId\", Version=\"0.1.0\", Token=\"token-1\"",
            request.headers[HttpHeaders.Authorization]
        )
        assertEquals(
            listOf(Album("album-1", "Great Album", "Album Artist", 10, null)),
            result.getOrThrow()
        )
        client.close()
    }

    @Test
    fun `albums list keeps MusicAlbum items that are marked as folders`() = runTest {
        val client = HttpClient(MockEngine { request ->
            respond(
                content = "{\"Items\":[{\"Id\":\"album-1\",\"Name\":\"Great Album\"," +
                    "\"AlbumArtist\":\"Album Artist\",\"ChildCount\":10,\"Type\":\"MusicAlbum\",\"IsFolder\":true}]}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        })
        val deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        val result = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = deviceId
        ).getAlbums("user-1", "token-1")

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(Album("album-1", "Great Album", "Album Artist", 10, null)),
            result.getOrThrow()
        )
        client.close()
    }

    @Test
    fun `album tracks sends ParentId query with auth and parses tracks`() = runTest {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respond(
                content = "{\"Items\":[{\"Id\":\"track-1\",\"Name\":\"Track One\",\"Type\":\"Audio\"}]}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        })
        val deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        val result = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = deviceId
        ).getAlbumTracks("user-1", "token-1", "album-1")

        assertTrue(result.isSuccess)
        val request = requireNotNull(capturedRequest)
        assertEquals("/Users/user-1/Items", request.url.encodedPath)
        assertEquals("album-1", request.url.parameters["ParentId"])
        assertEquals("Audio", request.url.parameters["IncludeItemTypes"])
        assertEquals("true", request.url.parameters["Recursive"])
        assertEquals(
            "MediaBrowser Client=\"Pulsar\", Device=\"Android\", " +
                "DeviceId=\"$deviceId\", Version=\"0.1.0\", Token=\"token-1\"",
            request.headers[HttpHeaders.Authorization]
        )
        assertEquals(
            listOf(Track("track-1", "Track One", "Unknown artist", "", null, null)),
            result.getOrThrow()
        )
        client.close()
    }

    @Test
    fun `playlists list sends Playlist query with auth and parses playlists`() = runTest {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respond(
                content = "{\"Items\":[{\"Id\":\"playlist-1\",\"Name\":\"Morning Run\"," +
                    "\"ChildCount\":12,\"Type\":\"Playlist\"}]}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        })
        val deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        val result = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = deviceId
        ).getPlaylists("user-1", "token-1")

        assertTrue(result.isSuccess)
        val request = requireNotNull(capturedRequest)
        assertEquals("/Users/user-1/Items", request.url.encodedPath)
        assertEquals("Playlist", request.url.parameters["IncludeItemTypes"])
        assertEquals("true", request.url.parameters["Recursive"])
        assertEquals("ChildCount", request.url.parameters["Fields"])
        assertEquals(
            "MediaBrowser Client=\"Pulsar\", Device=\"Android\", " +
                "DeviceId=\"$deviceId\", Version=\"0.1.0\", Token=\"token-1\"",
            request.headers[HttpHeaders.Authorization]
        )
        assertEquals(
            listOf(Playlist("playlist-1", "Morning Run", 12, null)),
            result.getOrThrow()
        )
        client.close()
    }

    @Test
    fun `playlist tracks sends ParentId query with auth and parses tracks`() = runTest {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respond(
                content = "{\"Items\":[{\"Id\":\"track-1\",\"Name\":\"Track One\",\"Type\":\"Audio\"}]}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        })
        val deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        val result = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = deviceId
        ).getPlaylistTracks("user-1", "token-1", "playlist-1")

        assertTrue(result.isSuccess)
        val request = requireNotNull(capturedRequest)
        assertEquals("/Users/user-1/Items", request.url.encodedPath)
        assertEquals("playlist-1", request.url.parameters["ParentId"])
        assertEquals("Audio", request.url.parameters["IncludeItemTypes"])
        assertEquals(
            "MediaBrowser Client=\"Pulsar\", Device=\"Android\", " +
                "DeviceId=\"$deviceId\", Version=\"0.1.0\", Token=\"token-1\"",
            request.headers[HttpHeaders.Authorization]
        )
        assertEquals(
            listOf(Track("track-1", "Track One", "Unknown artist", "", null, null)),
            result.getOrThrow()
        )
        client.close()
    }

    @Test
    fun `imageRequest builds image URL with token in header only`() {
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })
        val deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        val dataSource = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = deviceId
        )

        val request = dataSource.imageRequest("album-1", "tag-1", "secret-token")

        assertEquals(
            "https://media.example.com/Items/album-1/Images/Primary?tag=tag-1",
            request.url
        )
        assertEquals(
            "MediaBrowser Client=\"Pulsar\", Device=\"Android\", " +
                "DeviceId=\"$deviceId\", Version=\"0.1.0\", Token=\"secret-token\"",
            request.headers["Authorization"]
        )
        assertFalse(request.url.contains("secret-token"))
        client.close()
    }

    @Test
    fun `imageRequest omits tag query when image tag is null`() {
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })
        val deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        val dataSource = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = deviceId
        )

        val request = dataSource.imageRequest("album-1", null, "secret-token")

        assertEquals(
            "https://media.example.com/Items/album-1/Images/Primary",
            request.url
        )
        client.close()
    }

    @Test
    fun `track list requests ask for the file path`() = runTest {
        // 删歌/改名要用每首歌的 Path 字段（ADR-0001：文件操作走伴生服务，App 负责传路径）。
        // 歌曲列表、专辑/播放列表子曲目、搜索三类都得带上；专辑/播放列表本身不用。
        val fields = mutableListOf<String?>()
        val client = HttpClient(MockEngine { request ->
            fields += request.url.parameters["Fields"]
            respond(
                content = "{\"Items\":[{\"Id\":\"track-1\",\"Name\":\"Track One\",\"Type\":\"Audio\"}]}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        })
        val dataSource = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        )

        dataSource.getMusicItems("user-1", "token-1")
        dataSource.getAlbumTracks("user-1", "token-1", "album-1")
        dataSource.search("user-1", "token-1", "Beatles")

        assertEquals("PrimaryImageAspectRatio,Genres,Path", fields[0])
        assertEquals("PrimaryImageAspectRatio,Genres,Path", fields[1])
        assertEquals("PrimaryImageAspectRatio,Genres,ChildCount,Path", fields[2])
        client.close()
    }

    @Test
    fun `search sends SearchTerm query with combined item types and auth`() = runTest {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respond(
                content = "{\"Items\":[]}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        })
        val deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        val result = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = deviceId
        ).search("user-1", "token-1", "Beatles")

        assertTrue(result.isSuccess)
        val request = requireNotNull(capturedRequest)
        assertEquals("/Users/user-1/Items", request.url.encodedPath)
        assertEquals("Beatles", request.url.parameters["SearchTerm"])
        assertEquals("Audio,MusicAlbum,Playlist", request.url.parameters["IncludeItemTypes"])
        assertEquals("true", request.url.parameters["Recursive"])
        assertEquals("50", request.url.parameters["Limit"])
        assertEquals(
            "MediaBrowser Client=\"Pulsar\", Device=\"Android\", " +
                "DeviceId=\"$deviceId\", Version=\"0.1.0\", Token=\"token-1\"",
            request.headers[HttpHeaders.Authorization]
        )
        assertFalse(request.url.toString().contains("token-1"))
        client.close()
    }

    @Test
    fun `search parses mixed audio album and playlist results`() = runTest {
        val client = HttpClient(MockEngine { request ->
            respond(
                content = "{\"Items\":[" +
                    "{\"Id\":\"track-1\",\"Name\":\"Song One\",\"Type\":\"Audio\"}," +
                    "{\"Id\":\"album-1\",\"Name\":\"Great Album\",\"AlbumArtist\":\"Album Artist\",\"ChildCount\":10,\"Type\":\"MusicAlbum\"}," +
                    "{\"Id\":\"playlist-1\",\"Name\":\"Morning Run\",\"ChildCount\":12,\"Type\":\"Playlist\"}" +
                    "]}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        })
        val deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        val result = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = deviceId
        ).search("user-1", "token-1", "One")

        assertTrue(result.isSuccess)
        val results = result.getOrThrow()
        assertEquals(listOf(Track("track-1", "Song One", "Unknown artist", "", null, null)), results.tracks)
        assertEquals(listOf(Album("album-1", "Great Album", "Album Artist", 10, null)), results.albums)
        assertEquals(listOf(Playlist("playlist-1", "Morning Run", 12, null)), results.playlists)
        client.close()
    }

    @Test
    fun `search with blank query fails`() = runTest {
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })
        val deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        val result = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = deviceId
        ).search("user-1", "token-1", "   ")

        assertTrue(result.isFailure)
        client.close()
    }

    // ===== 歌词接口（GET /Audio/{itemId}/Lyrics，Jellyfin 10.9+） =====

    @Test
    fun `lyrics request sends MediaBrowser authorization with token`() = runTest {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respond(
                content = "{\"Lyrics\":[{\"Text\":\"第一行\",\"Start\":0},{\"Text\":\"第二行\",\"Start\":43500000}]}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        })
        val deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        val result = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = deviceId
        ).getLyrics("token-1", "track-9")

        assertTrue(result.isSuccess)
        val request = requireNotNull(capturedRequest)
        assertEquals("/Audio/track-9/Lyrics", request.url.encodedPath)
        assertEquals(
            "MediaBrowser Client=\"Pulsar\", Device=\"Android\", DeviceId=\"$deviceId\", " +
                "Version=\"0.1.0\", Token=\"token-1\"",
            request.headers[HttpHeaders.Authorization]
        )
        // token 绝不进 URL（本项目红线）
        assertFalse(request.url.toString().contains("token-1"))
        val lyrics = result.getOrThrow()
        assertTrue(lyrics.isSynced)
        assertEquals(
            listOf(LyricLine(0L, "第一行"), LyricLine(4_350_000L, "第二行")),
            lyrics.lines
        )
        client.close()
    }

    @Test
    fun `lyrics 404 maps to not found sentinel`() = runTest {
        val client = HttpClient(MockEngine {
            respond(content = "", status = HttpStatusCode.NotFound)
        })
        val deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        val result = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = deviceId
        ).getLyrics("token-1", "track-missing")

        // 没歌词是常态不是错误：404 归一化为成功 + Lyrics.NONE，UI 显示空态而不弹错误。
        assertTrue(result.isSuccess)
        assertEquals(Lyrics.NONE, result.getOrThrow())
        client.close()
    }

    @Test
    fun `lyrics 401 maps to Unauthorized`() = runTest {
        val client = HttpClient(MockEngine {
            respond(content = "", status = HttpStatusCode.Unauthorized)
        })
        val deviceId = "41e55f58-619c-45d1-9bc7-6e59b70437fd"
        val result = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = deviceId
        ).getLyrics("expired-token", "track-9")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is JellyfinError.Unauthorized)
        client.close()
    }

    @Test
    fun `lyrics with blank track id fails`() = runTest {
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })
        val result = JellyfinDataSource(
            client = client,
            urlBuilder = JellyfinUrlBuilder("https://media.example.com"),
            deviceId = "device-1"
        ).getLyrics("token-1", "  ")

        assertTrue(result.isFailure)
        client.close()
    }
}