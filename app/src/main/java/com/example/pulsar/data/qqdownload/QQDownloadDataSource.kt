package com.example.pulsar.data.qqdownload

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * QQ 下载服务的 Ktor 实现。
 *
 * 服务是局域网内自建 HTTP（无鉴权，见 server/README.md），baseUrl 形如
 * `http://192.168.1.10:8900`。错误映射：连接失败 → ServiceUnreachable，
 * 503 → CredentialExpired，其余非 2xx → Http。
 */
class QQDownloadDataSource(
    private val client: HttpClient,
    private val baseUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : QQDownloadApi {

    override suspend fun health(): Result<QQHealthResponse> = execute {
        val response = client.get("$baseUrl/api/health") { accept(ContentType.Application.Json) }
        ensureSuccess(response.status, response.body())
        json.decodeFromString(response.body())
    }

    override suspend fun search(keyword: String, limit: Int): Result<List<QQSongDto>> = execute {
        val response = client.get("$baseUrl/api/search") {
            accept(ContentType.Application.Json)
            parameter("q", keyword)
            parameter("limit", limit)
        }
        ensureSuccess(response.status, response.body())
        json.decodeFromString<QQSearchResponse>(response.body()).songs
    }

    override suspend fun searchAlbums(keyword: String, limit: Int): Result<List<QQAlbumDto>> = execute {
        val response = client.get("$baseUrl/api/album/search") {
            accept(ContentType.Application.Json)
            parameter("q", keyword)
            parameter("limit", limit)
        }
        ensureSuccess(response.status, response.body())
        json.decodeFromString<QQAlbumSearchResponse>(response.body()).albums
    }

    override suspend fun albumSongs(albumMid: String): Result<List<String>> = execute {
        val response = client.get("$baseUrl/api/album/$albumMid/songs") {
            accept(ContentType.Application.Json)
        }
        ensureSuccess(response.status, response.body())
        json.decodeFromString<QQAlbumSongsResponse>(response.body()).songMids
    }

    override suspend fun enqueueDownload(songMid: String, quality: String, title: String?): Result<String> = execute {
        val response = client.post("$baseUrl/api/download") {
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(QQDownloadRequest(songMid = songMid, quality = quality, title = title)))
        }
        ensureSuccess(response.status, response.body())
        json.decodeFromString<QQDownloadResponse>(response.body()).jobId
    }

    override suspend fun enqueueAlbum(albumMid: String, quality: String): Result<Int> = execute {
        val response = client.post("$baseUrl/api/download/album") {
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(QQDownloadAlbumRequest(albumMid = albumMid, quality = quality)))
        }
        ensureSuccess(response.status, response.body())
        json.decodeFromString<QQDownloadAlbumResponse>(response.body()).count
    }

    override suspend fun jobs(): Result<List<QQJobDto>> = execute {
        val response = client.get("$baseUrl/api/jobs") { accept(ContentType.Application.Json) }
        ensureSuccess(response.status, response.body())
        json.decodeFromString<QQJobsResponse>(response.body()).jobs
    }

    override suspend fun retryCredential(): Result<Unit> = execute {
        val response = client.post("$baseUrl/api/credential/retry") {
            accept(ContentType.Application.Json)
        }
        ensureSuccess(response.status, response.body())
    }

    private fun ensureSuccess(status: HttpStatusCode, bodyText: String) {
        if (status.value in 200..299) return
        val detail = bodyText.take(200)
        when (status.value) {
            503 -> throw QQDownloadError.CredentialExpired(detail)
            else -> throw QQDownloadError.Http(status.value, detail)
        }
    }

    private suspend fun <T> execute(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: QQDownloadError) {
        Result.failure(error)
    } catch (error: IOException) {
        Result.failure(QQDownloadError.ServiceUnreachable(error))
    } catch (error: Exception) {
        Result.failure(QQDownloadError.InvalidResponse(error))
    }
}
