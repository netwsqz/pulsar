package com.example.pulsar.data.libraryfiles

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.get
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
 * PC 伴生服务文件操作的 Ktor 实现。
 *
 * 密钥只通过 `X-Pulsar-Key` 请求头发送，绝不拼进 URL 或请求体。
 */
class LibraryFilesDataSource(
    private val client: HttpClient,
    private val baseUrl: String,
    private val fileOpsKey: String,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
) : LibraryFilesApi {

    override suspend fun delete(paths: List<String>): Result<LibraryDeleteResponse> = execute {
        val response = client.post(url("/api/library/delete")) {
            jsonRequest()
            setBody(json.encodeToString(LibraryPathsRequest(paths)))
        }
        ensureSuccess(response.status, response.body())
        json.decodeFromString(response.body())
    }

    override suspend fun rename(
        path: String,
        title: String?,
        artist: String?,
        album: String?
    ): Result<LibraryRenameResponse> = execute {
        val response = client.post(url("/api/library/rename")) {
            jsonRequest()
            setBody(json.encodeToString(LibraryRenameRequest(path, title, artist, album)))
        }
        ensureSuccess(response.status, response.body())
        json.decodeFromString(response.body())
    }

    override suspend fun recycle(): Result<LibraryRecycleResponse> = execute {
        val response = client.get(url("/api/library/recycle")) { commonRequest() }
        ensureSuccess(response.status, response.body())
        json.decodeFromString(response.body())
    }

    override suspend fun restore(paths: List<String>): Result<LibraryRestoreResponse> = execute {
        val response = client.post(url("/api/library/recycle/restore")) {
            jsonRequest()
            setBody(json.encodeToString(LibraryPathsRequest(paths)))
        }
        ensureSuccess(response.status, response.body())
        json.decodeFromString(response.body())
    }

    override suspend fun purge(): Result<LibraryPurgeResponse> = execute {
        val response = client.post(url("/api/library/recycle/purge")) { commonRequest() }
        ensureSuccess(response.status, response.body())
        json.decodeFromString(response.body())
    }

    private fun url(path: String): String = "$baseUrl$path"

    private fun io.ktor.client.request.HttpRequestBuilder.commonRequest() {
        accept(ContentType.Application.Json)
        header("X-Pulsar-Key", fileOpsKey)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.jsonRequest() {
        commonRequest()
        contentType(ContentType.Application.Json)
    }

    private fun ensureSuccess(status: HttpStatusCode, bodyText: String) {
        if (status.value in 200..299) return
        val detail = bodyText.take(200)
        if (status == HttpStatusCode.Unauthorized) {
            throw LibraryFilesError.Unauthorized(detail)
        }
        throw LibraryFilesError.Http(status.value, detail)
    }

    private suspend fun <T> execute(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: LibraryFilesError) {
        Result.failure(error)
    } catch (error: IOException) {
        Result.failure(LibraryFilesError.ServiceUnreachable(error))
    } catch (error: Exception) {
        Result.failure(LibraryFilesError.InvalidResponse(error))
    }
}
