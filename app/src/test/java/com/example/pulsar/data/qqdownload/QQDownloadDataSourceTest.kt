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
        val client = clientWith(content = """{"job_id":"abc123"}""") { request ->
            method = request.method.value
            body = (request.body as TextContent).text
            contentTypeHeader = request.body.contentType.toString()
        }

        val result = QQDownloadDataSource(client, "http://192.168.1.10:8900")
            .enqueueDownload("0039MnYb0qxYhV", "flac", title = "晴天")

        assertEquals("abc123", result.getOrThrow())
        assertEquals("POST", method)
        assertTrue(body!!.contains("\"song_mid\":\"0039MnYb0qxYhV\""))
        assertTrue(body!!.contains("\"quality\":\"flac\""))
        assertTrue(contentTypeHeader!!.startsWith("application/json"))
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
