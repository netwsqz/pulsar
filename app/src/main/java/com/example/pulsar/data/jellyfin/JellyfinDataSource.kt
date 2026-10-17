package com.example.pulsar.data.jellyfin

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CancellationException
import io.ktor.client.request.setBody
import java.io.IOException

class JellyfinDataSource(
    private val client: HttpClient,
    private val urlBuilder: JellyfinUrlBuilder,
    val deviceId: String,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : JellyfinApi {
    override suspend fun login(username: String, password: String): Result<JellyfinSession> =
        execute {
            require(username.isNotBlank()) { "Username must not be blank" }
            require(password.isNotBlank()) { "Password must not be blank" }
            val response = client.post(urlBuilder.loginUrl()) {
                header(HttpHeaders.Authorization, mediaBrowserAuthorization(deviceId))
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                setBody(json.encodeToString(JellyfinLoginRequest(username, password)))
            }
            if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
                throw JellyfinError.InvalidCredentials()
            }
            ensureSuccess(response.status)
            val body = json.decodeFromString<JellyfinLoginResponse>(response.body())
            if (body.accessToken.isBlank() || body.user?.id.isNullOrBlank()) {
                throw JellyfinError.InvalidResponse(IllegalStateException("Missing session fields"))
            }
            JellyfinSession(body.user.id, body.accessToken)
        }

    override suspend fun getMusicItems(userId: String, accessToken: String): Result<List<Track>> =
        execute {
            require(userId.isNotBlank()) { "User ID must not be blank" }
            require(accessToken.isNotBlank()) { "Access token must not be blank" }
            fetchItems(
                userId,
                accessToken,
                mapOf(
                    "IncludeItemTypes" to "Audio",
                    "Recursive" to "true",
                    // Path 是删歌/改名的定位依据（ADR-0001），歌曲列表必带
                    "Fields" to "PrimaryImageAspectRatio,Genres,Path"
                )
            ).map(JellyfinItemDto::toTrack)
        }

    override suspend fun getAlbums(userId: String, accessToken: String): Result<List<Album>> =
        execute {
            require(userId.isNotBlank()) { "User ID must not be blank" }
            require(accessToken.isNotBlank()) { "Access token must not be blank" }
            fetchItems(
                userId,
                accessToken,
                mapOf(
                    "IncludeItemTypes" to "MusicAlbum",
                    "Recursive" to "true",
                    // Genres 供艺术家/流派页做客户端分组（工单 06），不需要额外请求
                    "Fields" to "ChildCount,Genres",
                    "SortBy" to "SortName",
                    "SortOrder" to "Ascending"
                )
            ).map(JellyfinItemDto::toAlbum)
        }

    /** 拉取随机专辑（发现页用）：与 [getAlbums] 复用同一接口，仅排序方式换成 Random 并限制返回数量。 */
    override suspend fun getRandomAlbums(userId: String, accessToken: String, limit: Int): Result<List<Album>> =
        execute {
            require(userId.isNotBlank()) { "User ID must not be blank" }
            require(accessToken.isNotBlank()) { "Access token must not be blank" }
            fetchItems(
                userId,
                accessToken,
                mapOf(
                    "IncludeItemTypes" to "MusicAlbum",
                    "Recursive" to "true",
                    "Fields" to "ChildCount",
                    "SortBy" to "Random",
                    "Limit" to limit.toString()
                )
            ).map(JellyfinItemDto::toAlbum)
        }

    /**
     * 最近播放：服务端播放历史，按最后播放时间倒序。
     * `Filters=IsPlayed` 是必需的——没播过的歌 DatePlayed 为空，不加过滤会混进来。
     */
    override suspend fun getRecentlyPlayed(userId: String, accessToken: String, limit: Int): Result<List<Track>> =
        playedTracks(userId, accessToken, "DatePlayed", limit)

    /** 最常播放：服务端播放次数倒序（同样只看播过的）。 */
    override suspend fun getMostPlayed(userId: String, accessToken: String, limit: Int): Result<List<Track>> =
        playedTracks(userId, accessToken, "PlayCount", limit)

    /** 播过的曲目按 [sortBy] 倒序取前 [limit] 首。 */
    private suspend fun playedTracks(
        userId: String,
        accessToken: String,
        sortBy: String,
        limit: Int
    ): Result<List<Track>> = execute {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        require(accessToken.isNotBlank()) { "Access token must not be blank" }
        fetchItems(
            userId,
            accessToken,
            mapOf(
                "IncludeItemTypes" to "Audio",
                "Recursive" to "true",
                "Filters" to "IsPlayed",
                "SortBy" to sortBy,
                "SortOrder" to "Descending",
                "Limit" to limit.toString(),
                "Fields" to "PrimaryImageAspectRatio,Genres,Path"
            )
        ).map(JellyfinItemDto::toTrack)
    }

    /** 最近添加：按入库时间倒序的专辑。 */
    override suspend fun getRecentlyAdded(userId: String, accessToken: String, limit: Int): Result<List<Album>> =
        execute {
            require(userId.isNotBlank()) { "User ID must not be blank" }
            require(accessToken.isNotBlank()) { "Access token must not be blank" }
            fetchItems(
                userId,
                accessToken,
                mapOf(
                    "IncludeItemTypes" to "MusicAlbum",
                    "Recursive" to "true",
                    "Fields" to "ChildCount",
                    "SortBy" to "DateCreated",
                    "SortOrder" to "Descending",
                    "Limit" to limit.toString()
                )
            ).map(JellyfinItemDto::toAlbum)
        }

    override suspend fun getAlbumTracks(userId: String, accessToken: String, albumId: String): Result<List<Track>> =
        execute {
            require(userId.isNotBlank()) { "User ID must not be blank" }
            require(accessToken.isNotBlank()) { "Access token must not be blank" }
            require(albumId.isNotBlank()) { "Album ID must not be blank" }
            fetchChildTracks(userId, accessToken, albumId)
        }

    override suspend fun getPlaylists(userId: String, accessToken: String): Result<List<Playlist>> =
        execute {
            require(userId.isNotBlank()) { "User ID must not be blank" }
            require(accessToken.isNotBlank()) { "Access token must not be blank" }
            fetchItems(
                userId,
                accessToken,
                mapOf(
                    "IncludeItemTypes" to "Playlist",
                    "Recursive" to "true",
                    "Fields" to "ChildCount",
                    "SortBy" to "SortName",
                    "SortOrder" to "Ascending"
                )
            ).map(JellyfinItemDto::toPlaylist)
        }

    override suspend fun getPlaylistTracks(userId: String, accessToken: String, playlistId: String): Result<List<Track>> =
        execute {
            require(userId.isNotBlank()) { "User ID must not be blank" }
            require(accessToken.isNotBlank()) { "Access token must not be blank" }
            require(playlistId.isNotBlank()) { "Playlist ID must not be blank" }
            fetchChildTracks(userId, accessToken, playlistId)
        }

    /**
     * 搜索音乐库：一次请求同时查歌曲 / 专辑 / 播放列表三类，按响应 Type 分组。
     * 走标准 /Users/{userId}/Items 接口 + SearchTerm 参数，token 只进 header 不进 URL。
     * Limit=50 限制返回量，避免结果过多拖慢响应。
     */
    override suspend fun search(userId: String, accessToken: String, query: String): Result<SearchResults> =
        execute {
            require(userId.isNotBlank()) { "User ID must not be blank" }
            require(accessToken.isNotBlank()) { "Access token must not be blank" }
            require(query.isNotBlank()) { "Search query must not be blank" }
            val items = fetchItems(
                userId,
                accessToken,
                mapOf(
                    "SearchTerm" to query,
                    "IncludeItemTypes" to "Audio,MusicAlbum,Playlist",
                    "Recursive" to "true",
                    "Limit" to "50",
                    "Fields" to "PrimaryImageAspectRatio,Genres,ChildCount,Path"
                )
            )
            SearchResults(
                tracks = items.filter { it.type?.equals("Audio", ignoreCase = true) == true }
                    .map(JellyfinItemDto::toTrack),
                albums = items.filter { it.type?.equals("MusicAlbum", ignoreCase = true) == true }
                    .map(JellyfinItemDto::toAlbum),
                playlists = items.filter { it.type?.equals("Playlist", ignoreCase = true) == true }
                    .map(JellyfinItemDto::toPlaylist)
            )
        }

    /**
     * 拉取歌词（GET /Audio/{itemId}/Lyrics，Jellyfin 10.9+）。曲库没有歌词是常态：
     * 404 归一化为成功 + [Lyrics.NONE]（UI 显示空态，不当错误弹窗）；
     * 401/403 照常映射 Unauthorized 走会话过期流程。
     */
    override suspend fun getLyrics(accessToken: String, trackId: String): Result<Lyrics> =
        execute {
            require(accessToken.isNotBlank()) { "Access token must not be blank" }
            require(trackId.isNotBlank()) { "Track ID must not be blank" }
            val request = urlBuilder.lyricsRequest(trackId, accessToken, deviceId)
            val response = client.get(request.url) {
                header(HttpHeaders.Authorization, request.headers["Authorization"].orEmpty())
                accept(ContentType.Application.Json)
            }
            if (response.status == HttpStatusCode.NotFound) {
                return@execute Lyrics.NONE
            }
            if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
                throw JellyfinError.Unauthorized()
            }
            ensureSuccess(response.status)
            json.decodeFromString<LyricResponseDto>(response.body()).toLyrics()
        }

    override fun streamUrl(mediaId: String, accessToken: String): String {
        require(accessToken.isNotBlank()) { "Access token must not be blank" }
        return urlBuilder.mediaStreamRequest(mediaId, accessToken, deviceId).url
    }

    /** 构建专辑封面请求：token 只进 header，不进 URL。imageTag 为空时省略 ?tag 参数。 */
    override fun imageRequest(itemId: String, imageTag: String?, accessToken: String): JellyfinRequest {
        require(itemId.isNotBlank()) { "Item ID must not be blank" }
        require(accessToken.isNotBlank()) { "Access token must not be blank" }
        return urlBuilder.imageRequest(itemId, imageTag, accessToken, deviceId)
    }

    override fun authorizationHeader(accessToken: String): String {
        require(accessToken.isNotBlank()) { "Access token must not be blank" }
        return mediaBrowserAuthorization(deviceId, accessToken)
    }

    /** 拉取某容器（专辑 / 播放列表）下的歌曲：同一接口，用 ParentId 定位容器。 */
    private suspend fun fetchChildTracks(
        userId: String,
        accessToken: String,
        parentId: String
    ): List<Track> = fetchItems(
        userId,
        accessToken,
        mapOf(
            "ParentId" to parentId,
            "IncludeItemTypes" to "Audio",
            "Recursive" to "true",
            // 与歌曲列表同理：专辑/播放列表里的歌也要能删能改名
            "Fields" to "PrimaryImageAspectRatio,Genres,Path"
        )
    ).map(JellyfinItemDto::toTrack)

    /** 拉取 Items 列表：带鉴权头，按 [params] 传查询参数。仅防御性过滤纯文件夹类型（Folder），专辑等容器项不受影响。 */
    private suspend fun fetchItems(
        userId: String,
        accessToken: String,
        params: Map<String, String>
    ): List<JellyfinItemDto> {
        val response = client.get(urlBuilder.itemsUrl(userId)) {
            header(HttpHeaders.Authorization, mediaBrowserAuthorization(deviceId, accessToken))
            accept(ContentType.Application.Json)
            params.forEach { (key, value) -> parameter(key, value) }
        }
        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
            throw JellyfinError.Unauthorized()
        }
        ensureSuccess(response.status)
        return json.decodeFromString<JellyfinItemsResponse>(response.body())
            .items
            .filterNot { it.type?.equals("Folder", ignoreCase = true) == true }
    }

    private suspend fun <T> execute(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: JellyfinError) {
        Result.failure(error)
    } catch (error: IllegalArgumentException) {
        Result.failure(error)
    } catch (error: IOException) {
        Result.failure(JellyfinError.Network(error))
    } catch (error: Exception) {
        Result.failure(JellyfinError.InvalidResponse(error))
    }

    private fun ensureSuccess(status: HttpStatusCode) {
        if (status.value !in 200..299) throw JellyfinError.Http(status.value)
    }

    private fun mediaBrowserAuthorization(deviceId: String, accessToken: String? = null): String =
        "MediaBrowser Client=\"Pulsar\", Device=\"Android\", DeviceId=\"$deviceId\", Version=\"0.1.0\"" +
            accessToken?.let { ", Token=\"$it\"" }.orEmpty()
}

data class JellyfinSession(val userId: String, val accessToken: String)
