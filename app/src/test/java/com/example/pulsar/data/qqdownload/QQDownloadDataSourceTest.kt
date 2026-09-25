package com.example.pulsar.data.qqdownload

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QQDownloadDataSourceTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun clientWith(
        status: HttpStatusCode = HttpStatusCode.OK,
        content: String = "{}",
        capture: (io.ktor.client.request.HttpRequestData) -> Unit = {}
    ): HttpClient = HttpClient(MockEngine { request ->
        capture(request)
        respond(content = content, status = status, headers = jsonHeaders)
    })

    @Test
    fun `search requests songs with query parameter and maps fields`() = runTest {
        var path: String? = null
        var query: String? = null
        val client = clientWith(
            content = """{"songs":[{"title":"晴天","singer":"周杰伦","album":"叶惠美","song_mid":"0039MnYb0qxYhV",
                "size_flac":40000000,"available_qualities":["flac","320"]}]}"""
        ) { request ->
            path = request.url.encodedPath
            query = request.url.parameters["q"]
        }

        val result = QQDownloadDataSource(client, "http://192.168.1.10:8900").search("晴天")

        assertTrue(result.isSuccess)
        assertEquals("/api/search", path)
        assertEquals("晴天", query)
        val song = result.getOrThrow().single()
        assertEquals("0039MnYb0qxYhV", song.songMid)
        assertEquals("周杰伦", song.singer)
        assertEquals(listOf("flac", "320"), song.availableQualities)
        client.close()
    }

    @Test
    fun `enqueue download posts song mid and quality as JSON`() = runTest {
        var method: String? = null
        var body: String? = null
        var contentTypeHeader: String? = null
        val client = clientWith(content = """{"job_id":"abc123","skipped":false}""") { request ->
            method = request.method.value
            body = (request.body as TextContent).text
            contentTypeHeader = request.body.contentType.toString()
        }

        val result = QQDownloadDataSource(client, "http://192.168.1.10:8900")
            .enqueueDownload("0039MnYb0qxYhV", "flac", title = "晴天")

        assertEquals("abc123", result.getOrThrow().jobId)
        assertFalse(result.getOrThrow().skipped)
        assertEquals("POST", method)
        assertTrue(body!!.contains("\"song_mid\":\"0039MnYb0qxYhV\""))
        assertTrue(body!!.contains("\"quality\":\"flac\""))
        assertTrue(contentTypeHeader!!.startsWith("application/json"))
        client.close()
    }

    @Test
    fun `enqueue download posts artist and force when provided`() = runTest {
        // 服务端判定「已在库」必须同时有歌名和歌手：缺 artist 时它一律不拦。
        var body: String? = null
        val client = clientWith(content = """{"job_id":"abc123","skipped":false}""") { request ->
            body = (request.body as TextContent).text
        }

        QQDownloadDataSource(client, "http://192.168.1.10:8900")
            .enqueueDownload("0039MnYb0qxYhV", "flac", title = "晴天", artist = "周杰伦", force = true)

        assertTrue(body!!.contains("\"artist\":\"周杰伦\""))
        assertTrue(body!!.contains("\"force\":true"))
        client.close()
    }

    @Test
    fun `enqueue download without artist and force omits them from body`() = runTest {
        // encodeDefaults 关着：默认值不进请求体，请求形状与老客户端完全一致。
        var body: String? = null
        val client = clientWith(content = """{"job_id":"abc123","skipped":false}""") { request ->
            body = (request.body as TextContent).text
        }

        QQDownloadDataSource(client, "http://192.168.1.10:8900").enqueueDownload("0039MnYb0qxYhV", "flac")

        assertFalse(body!!.contains("artist"))
        assertFalse(body!!.contains("force"))
        client.close()
    }

    @Test
    fun `enqueue download parses skipped response`() = runTest {
        // 服务端跳过已入库曲目时 job_id 为空串 + skipped=true，不是错误。
        val client = clientWith(content = """{"job_id":"","skipped":true}""")

        val response = QQDownloadDataSource(client, "http://192.168.1.10:8900")
            .enqueueDownload("0039MnYb0qxYhV", "flac").getOrThrow()

        assertTrue(response.skipped)
        assertEquals("", response.jobId)
        client.close()
    }

    @Test
    fun `search response parses in-library and downloading marks`() = runTest {
        val client = clientWith(
            content = """{"songs":[
                {"title":"晴天","song_mid":"0039MnYb0qxYhV","in_library":true,"downloading":false},
                {"title":"七里香","song_mid":"000X","in_library":false,"downloading":true}]}"""
        )

        val songs = QQDownloadDataSource(client, "http://192.168.1.10:8900").search("晴").getOrThrow()

        assertTrue(songs[0].inLibrary)
        assertFalse(songs[0].downloading)
        assertFalse(songs[1].inLibrary)
        assertTrue(songs[1].downloading)
        client.close()
    }

    @Test
    fun `search response without marks still parses for old servers`() = runTest {
        // 老服务端不回这两个字段：按「没有」处理（缺省 false），向后兼容。
        val client = clientWith(content = """{"songs":[{"title":"晴天","song_mid":"0039MnYb0qxYhV"}]}""")

        val song = QQDownloadDataSource(client, "http://192.168.1.10:8900").search("晴").getOrThrow().single()

        assertFalse(song.inLibrary)
        assertFalse(song.downloading)
        client.close()
    }

    @Test
    fun `album songs parses in-library and downloading marks`() = runTest {
        val client = clientWith(
            content = """{"song_mids":["000a","000b"],
                "songs":[{"song_mid":"000a","name":"夜曲","in_library":true},
                    {"song_mid":"000b","name":"发如雪","downloading":true}]}"""
        )

        val tracks = QQDownloadDataSource(client, "http://192.168.1.10:8900").albumSongs("000MkMni19ClKG").getOrThrow()

        assertTrue(tracks[0].inLibrary)
        assertFalse(tracks[0].downloading)
        assertFalse(tracks[1].inLibrary)
        assertTrue(tracks[1].downloading)
        client.close()
    }

    @Test
    fun `album songs maps track names for the picker`() = runTest {
        var path: String? = null
        val client = clientWith(
            content = """{"song_mids":["000a","000b"],
                "songs":[{"song_mid":"000a","name":"夜曲"},{"song_mid":"000b","name":"发如雪"}]}"""
        ) { request -> path = request.url.encodedPath }

        val result = QQDownloadDataSource(client, "http://192.168.1.10:8900").albumSongs("000MkMni19ClKG")

        assertEquals("/api/album/000MkMni19ClKG/songs", path)
        assertEquals(
            listOf(QQAlbumSongDto("000a", "夜曲"), QQAlbumSongDto("000b", "发如雪")),
            result.getOrThrow()
        )
        client.close()
    }

    @Test
    fun `enqueue album with selection posts song mids`() = runTest {
        var body: String? = null
        val client = clientWith(content = """{"job_ids":["j1"],"count":1,"skipped_count":0}""") { request ->
            body = (request.body as TextContent).text
        }

        val result = QQDownloadDataSource(client, "http://192.168.1.10:8900")
            .enqueueAlbum("000MkMni19ClKG", "flac", songMids = listOf("000a", "000b"))

        assertEquals(1, result.getOrThrow().count)
        assertTrue(body!!.contains("\"album_mid\":\"000MkMni19ClKG\""))
        assertTrue(body!!.contains("\"song_mids\":[\"000a\",\"000b\"]"))
        client.close()
    }

    @Test
    fun `enqueue whole album parses skipped count and posts force`() = runTest {
        // 整张下载：服务端跳过已在库的曲目，回传入队数 + 跳过数；force 用于强制全下。
        var body: String? = null
        val client = clientWith(
            content = """{"job_ids":["j1","j2"],"count":2,"skipped_count":9,"album_mid":"000MkMni19ClKG"}"""
        ) { request ->
            body = (request.body as TextContent).text
        }

        val response = QQDownloadDataSource(client, "http://192.168.1.10:8900")
            .enqueueAlbum("000MkMni19ClKG", "flac", force = true).getOrThrow()

        assertEquals(2, response.count)
        assertEquals(9, response.skippedCount)
        assertTrue(body!!.contains("\"force\":true"))
        client.close()
    }

    @Test
    fun `album enqueue response without skipped count still parses`() = runTest {
        // 老服务端不回 skipped_count：缺字段按 0 处理。
        val client = clientWith(content = """{"job_ids":["j1"],"count":11}""")

        val response = QQDownloadDataSource(client, "http://192.168.1.10:8900")
            .enqueueAlbum("000MkMni19ClKG", "flac").getOrThrow()

        assertEquals(11, response.count)
        assertEquals(0, response.skippedCount)
        client.close()
    }

    @Test
    fun `enqueue whole album omits song mids field`() = runTest {
        // 不选曲（整张下载）时请求体里不能出现 song_mids，服务端才按「整张」处理。
        var body: String? = null
        val client = clientWith(content = """{"job_ids":["j1"],"count":11}""") { request ->
            body = (request.body as TextContent).text
        }

        QQDownloadDataSource(client, "http://192.168.1.10:8900").enqueueAlbum("000MkMni19ClKG", "flac")

        assertTrue(!body!!.contains("song_mids"))
        client.close()
    }

    @Test
    fun `credential expired maps http 503 to CredentialExpired`() = runTest {
        val client = clientWith(
            status = HttpStatusCode.ServiceUnavailable,
            content = """{"detail":"凭证已过期，请重提"}"""
        )

        val result = QQDownloadDataSource(client, "http://192.168.1.10:8900").search("任意")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrThrow() is QQDownloadError.CredentialExpired)
        client.close()
    }

    @Test
    fun `http 404 maps to Http error with detail`() = runTest {
        val client = clientWith(status = HttpStatusCode.NotFound, content = """{"detail":"任务不存在"}""")

        val result = QQDownloadDataSource(client, "http://192.168.1.10:8900").jobs()

        assertTrue(result.isFailure)
        val error = result.exceptionOrThrow() as QQDownloadError.Http
        assertEquals(404, error.statusCode)
        client.close()
    }

    @Test
    fun `connection failure maps to ServiceUnreachable`() = runTest {
        val client = HttpClient(MockEngine { throw java.io.IOException("connection refused") })

        val result = QQDownloadDataSource(client, "http://192.168.1.10:8900").health()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrThrow() is QQDownloadError.ServiceUnreachable)
        client.close()
    }

    @Test
    fun `health maps credential and ffmpeg state`() = runTest {
        val client = clientWith(
            content = """{"ok":true,"ffmpeg_ready":true,"credential_expired":true,
                "library_root":"E:\\file\\Te_Music"}"""
        )

        val health = QQDownloadDataSource(client, "http://192.168.1.10:8900").health().getOrThrow()

        assertTrue(health.ok)
        assertTrue(health.credentialExpired)
        assertEquals("E:\\file\\Te_Music", health.libraryRoot)
        // 老服务端没有 library 块：按「就绪」处理，别让界面误报扫描中。
        assertTrue(health.library.ready)
        assertEquals(0, health.library.count)
        client.close()
    }

    @Test
    fun `health parses library index state`() = runTest {
        val client = clientWith(
            content = """{"ok":true,"ffmpeg_ready":true,"credential_expired":false,
                "library_root":"E:\\file\\Te_Music",
                "library":{"root":"E:\\file\\Te_Music","ready":false,"count":0}}"""
        )

        val health = QQDownloadDataSource(client, "http://192.168.1.10:8900").health().getOrThrow()

        assertFalse(health.library.ready)
        assertEquals(0, health.library.count)
        assertEquals("E:\\file\\Te_Music", health.library.root)
        client.close()
    }

    @Test
    fun `jobs lists status error and final path`() = runTest {
        val client = clientWith(
            content = """{"jobs":[{"job_id":"j1","song_mid":"0039MnYb0qxYhV","quality":"flac",
                "status":"done","final_path":"E:\\a\\b.flac"},
                {"job_id":"j2","song_mid":"00X","quality":"128","status":"failed","error":"需要VIP"}]}"""
        )

        val jobs = QQDownloadDataSource(client, "http://192.168.1.10:8900").jobs().getOrThrow()

        assertEquals(2, jobs.size)
        assertEquals("done", jobs[0].status)
        assertEquals("E:\\a\\b.flac", jobs[0].finalPath)
        assertEquals("failed", jobs[1].status)
        assertEquals("需要VIP", jobs[1].error)
        client.close()
    }

    private fun <T> Result<T>.exceptionOrThrow(): Throwable =
        fold({ throw IllegalStateException("expected failure but succeeded") }) { it }
}
