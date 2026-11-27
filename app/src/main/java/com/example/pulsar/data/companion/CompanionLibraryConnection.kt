package com.example.pulsar.data.companion

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.pulsar.data.CoverRequest
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.Track
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.net.URI

/** 只管理电脑曲库的配对；不读写 Jellyfin 会话或下载数据。 */
internal class CompanionLibraryConnection(
    private val client: HttpClient,
    private val store: CompanionPairingStore,
) : CompanionDownloadEvidenceSource {
    private val mutex = Mutex()

    // 播放数据源在后台线程打开；地址和密钥作为一个快照读取，换配对不会串用。
    @Volatile
    private var current: CompanionPairing? = null

    /** 保存过的非敏感服务地址。暂时连不上也能独立发起 QQ 搜索，不用等重新配对。 */
    var serviceUrl: String? = null
        private set

    fun streamUrl(mediaId: String): String? = current?.streamUrl(mediaId)

    fun artwork(track: Track, scope: String): CompanionArtworkRequest? {
        val pairing = current?.takeIf { it.scope == scope } ?: return null
        val revision = track.imageTag?.takeIf(trackIdPattern::matches) ?: return null
        val url = pairing.artworkUrl(track.id) ?: return null
        return CompanionArtworkRequest(url, revision, scope)
    }

    fun artworkAuthorization(url: HttpUrl, scope: String): String? = current?.let { pairing ->
        pairing.authorization.takeIf { pairing.scope == scope && pairing.ownsArtwork(url) }
    }

    val savedScopeChanges = store.pairings.map { it?.scope }.distinctUntilChanged()

    /** 冷启动读取副本归属不发网络请求；服务地址相同但配对密钥变了也不是同一份确认。 */
    suspend fun savedScope(): String? = store.load()?.scope

    suspend fun savedScopeOrNull(): String? = try {
        savedScope()
    } catch (_: IOException) {
        null
    }

    override suspend fun evidence(paths: List<String>): CompanionDownloadEvidence? {
        return try {
            val pairing = store.load() ?: return null
            val candidates = paths.distinct().chunked(16).flatMap { batch ->
                val response = client.post(pairing.evidenceUrl()) {
                    header(HttpHeaders.Authorization, pairing.authorization)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(EvidenceRequest.serializer(), EvidenceRequest(batch)))
                }
                if (response.status != HttpStatusCode.OK) return null
                val rows = json.decodeFromString<EvidenceResponse>(response.bodyAsText()).tracks
                require(rows.map { it.path }.distinct().size == rows.size && rows.all {
                    it.path in batch && it.size > 0 && trackIdPattern.matches(it.sha256) &&
                        trackIdPattern.matches(it.id) && trackIdPattern.matches(it.revision)
                })
                rows.map { CompanionCopyCandidate(it.path, it.toTrack(pairing), it.size, it.sha256) }
            }
            CompanionDownloadEvidence(pairing.scope, candidates).takeIf { savedScope() == pairing.scope }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    fun playbackAuthorization(url: HttpUrl): String? = current?.let { pairing ->
        if (pairing.ownsStream(url)) pairing.authorization else null
    }

    fun downloadAuthorization(url: HttpUrl, scope: String): String? = current?.let { pairing ->
        pairing.authorization.takeIf { pairing.scope == scope && pairing.ownsStream(url) }
    }

    suspend fun pair(address: String, key: String): Result<List<Track>> = mutex.withLock {
        val pairing = CompanionPairing.parse(address, key)
        if (pairing == null) return@withLock Result.failure(CompanionLibraryError.InvalidPairing)
        val tracks = fetchTracks(pairing)
        if (tracks.isSuccess) {
            try {
                store.save(pairing)
                current = pairing
                serviceUrl = pairing.serviceUrl
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                return@withLock Result.failure(CompanionLibraryError.Storage)
            }
        }
        tracks
    }

    /** null = 尚未配对；空列表 = 已配对，曲库确实没有歌。 */
    suspend fun refresh(): Result<List<Track>?> = mutex.withLock {
        val pairing = try {
            store.load()
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            return@withLock Result.failure(CompanionLibraryError.Storage)
        }
        serviceUrl = pairing?.serviceUrl
        if (pairing == null) return@withLock Result.success(null)
        fetchTracks(pairing).also { if (it.isSuccess) current = pairing }
    }

    suspend fun playlists(scope: String): Result<List<CompanionPlaylist>> =
        playlistRequest(scope, HttpMethod.Get) { pairing, body ->
            json.decodeFromString<CompanionPlaylistsResponse>(body).playlists.map { it.toPlaylist(pairing) }
                .also { rows -> require(rows.distinctBy { it.playlist.id }.size == rows.size) }
        }

    suspend fun createPlaylist(scope: String, name: String): Result<CompanionPlaylist> =
        playlistRequest(scope, HttpMethod.Post, body = json.encodeToString(NewPlaylist.serializer(), NewPlaylist(name))) { pairing, body ->
            json.decodeFromString<CompanionPlaylistRow>(body).toPlaylist(pairing)
        }

    suspend fun savePlaylistOrder(
        scope: String, playlist: Playlist, previous: List<String>, order: List<String>,
    ): Result<CompanionPlaylist> {
        val pairing = current?.takeIf { it.scope == scope }
            ?: return Result.failure(CompanionLibraryError.InvalidPairing)
        val serverId = pairing.playlistId(playlist.id) ?: return Result.failure(CompanionLibraryError.PlaylistMissing)
        val previousIds = previous.map { pairing.trackId(it) ?: return Result.failure(CompanionLibraryError.PlaylistTracks) }
        val trackIds = order.map { pairing.trackId(it) ?: return Result.failure(CompanionLibraryError.PlaylistTracks) }
        val payload = json.encodeToString(PlaylistEdit.serializer(), PlaylistEdit(previousIds, trackIds))
        return playlistRequest(scope, HttpMethod.Put, serverId, payload) { samePairing, body ->
            json.decodeFromString<CompanionPlaylistRow>(body).toPlaylist(samePairing).also {
                require(it.playlist.id == playlist.id && it.trackIds == order)
            }
        }
    }

    private suspend fun <T> playlistRequest(
        scope: String,
        method: HttpMethod,
        id: String? = null,
        body: String? = null,
        decode: (CompanionPairing, String) -> T,
    ): Result<T> = execute {
        val pairing = current?.takeIf { it.scope == scope } ?: throw CompanionLibraryError.InvalidPairing
        val response = client.request(pairing.playlistsUrl(id)) {
            this.method = method
            header(HttpHeaders.Authorization, pairing.authorization)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        when (response.status) {
            HttpStatusCode.OK, HttpStatusCode.Created -> decode(pairing, response.bodyAsText())
            HttpStatusCode.NotFound -> throw if (id == null) CompanionLibraryError.Unavailable else CompanionLibraryError.PlaylistMissing
            HttpStatusCode.Conflict -> throw CompanionLibraryError.PlaylistConflict
            HttpStatusCode.UnprocessableEntity -> throw CompanionLibraryError.PlaylistTracks
            HttpStatusCode.InternalServerError -> throw CompanionLibraryError.PlaylistStorage
            else -> throw errorFor(response.status)
        }
    }

    private suspend fun fetchTracks(pairing: CompanionPairing): Result<List<Track>> = execute {
        val response = client.get(pairing.tracksUrl()) {
            header(HttpHeaders.Authorization, pairing.authorization)
        }
        if (response.status != HttpStatusCode.OK) throw errorFor(response.status)
        val tracks = json.decodeFromString<CompanionTracksResponse>(response.bodyAsText()).tracks
        require(tracks.all { trackIdPattern.matches(it.id) } && tracks.distinctBy { it.id }.size == tracks.size)
        tracks.map { it.toTrack(pairing) }
    }

    private fun errorFor(status: HttpStatusCode): CompanionLibraryError = when (status) {
        HttpStatusCode.Unauthorized -> CompanionLibraryError.Unauthorized
        HttpStatusCode.ServiceUnavailable -> CompanionLibraryError.LibraryNotReady
        HttpStatusCode.InternalServerError -> CompanionLibraryError.KeyUnavailable
        else -> CompanionLibraryError.Unavailable
    }

    private inline fun <T> execute(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: CompanionLibraryError) {
        Result.failure(error)
    } catch (_: IOException) {
        Result.failure(CompanionLibraryError.Network)
    } catch (_: Exception) {
        Result.failure(CompanionLibraryError.Unavailable)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

internal data class CompanionArtworkRequest(
    override val url: String,
    val revision: String,
    val scope: String,
) : CoverRequest

internal sealed class CompanionLibraryError(message: String) : Exception(message) {
    data object InvalidPairing : CompanionLibraryError("请填写局域网内的电脑 IP 和访问密钥")
    data object Unauthorized : CompanionLibraryError("访问密钥不正确，请重新配对")
    data object LibraryNotReady : CompanionLibraryError(
        "电脑曲库还没准备好，请稍后重试；一直不行请检查电脑上的音乐目录"
    )
    data object KeyUnavailable : CompanionLibraryError("电脑服务无法读取访问密钥，请检查电脑端服务提示")
    data object Network : CompanionLibraryError("连不上电脑，请确认两台设备在同一网络、PC 服务已启动")
    data object Storage : CompanionLibraryError("无法读取或保存配对，请检查手机存储空间后重试")
    data object Unavailable : CompanionLibraryError("暂时无法读取电脑曲库，请确认已更新 PC 服务")
    data object PlaylistName : CompanionLibraryError("请输入 1 到 120 个字符的播放列表名称")
    data object PlaylistMissing : CompanionLibraryError("播放列表不存在或不属于当前配对")
    data object PlaylistConflict : CompanionLibraryError("播放列表已在其他设备修改，请重新打开编辑")
    data object PlaylistTracks : CompanionLibraryError("不能加入重复、不在曲库或不属于当前配对的歌曲")
    data object PlaylistStorage : CompanionLibraryError("无法读写电脑上的播放列表，请检查 PC 服务目录")
}

internal data class CompanionPlaylist(val playlist: Playlist, val trackIds: List<String>)

@Serializable
private data class CompanionPlaylistsResponse(val playlists: List<CompanionPlaylistRow>)

@Serializable
private data class CompanionPlaylistRow(
    val id: String,
    val name: String,
    @SerialName("track_ids") val trackIds: List<String>,
) {
    fun toPlaylist(pairing: CompanionPairing): CompanionPlaylist {
        require(playlistIdPattern.matches(id) && name.isNotBlank() &&
            trackIds.all(trackIdPattern::matches) && trackIds.distinct().size == trackIds.size)
        return CompanionPlaylist(Playlist(pairing.playlistMediaId(id), name, trackIds.size, null), trackIds.map(pairing::mediaId))
    }
}

@Serializable
private data class NewPlaylist(val name: String)

@Serializable
private data class PlaylistEdit(
    @SerialName("previous_track_ids") val previous: List<String>,
    @SerialName("track_ids") val order: List<String>,
)

@Serializable
private data class CompanionTracksResponse(val tracks: List<CompanionTrack>)

@Serializable
private data class CompanionTrack(
    val id: String, val title: String, val artist: String, val album: String, val revision: String? = null,
    @SerialName("has_artwork") val hasArtwork: Boolean = false,
) {
    fun toTrack(pairing: CompanionPairing): Track {
        val validRevision = revision?.takeIf(trackIdPattern::matches)
        return Track(
            pairing.mediaId(id), title, artist, album, null, validRevision.takeIf { hasArtwork },
            sourceRevision = validRevision,
        )
    }
}

@Serializable
private data class EvidenceRequest(val paths: List<String>)

@Serializable
private data class EvidenceResponse(val tracks: List<CompanionEvidenceRow>)

@Serializable
private data class CompanionEvidenceRow(
    val path: String, val id: String, val title: String, val artist: String, val album: String,
    val revision: String, val size: Long, val sha256: String,
    @SerialName("has_artwork") val hasArtwork: Boolean = false,
) {
    fun toTrack(pairing: CompanionPairing) = CompanionTrack(id, title, artist, album, revision, hasArtwork).toTrack(pairing)
}

internal fun interface CompanionDownloadEvidenceSource {
    suspend fun evidence(paths: List<String>): CompanionDownloadEvidence?
}

internal data class CompanionDownloadEvidence(val scope: String, val candidates: List<CompanionCopyCandidate>)
internal data class CompanionCopyCandidate(val path: String, val track: Track, val size: Long, val sha256: String)

private const val TRACKS_PATH = "/api/library/tracks"
private const val PLAYLISTS_PATH = "/api/library/playlists"
private const val MEDIA_ID_PREFIX = "pc:"
private const val DEFAULT_COMPANION_PORT = 8900
private val trackIdPattern = Regex("[0-9a-f]{64}")
private val playlistIdPattern = Regex("[0-9a-f]{32}")
private val audioPathPattern = Regex("$TRACKS_PATH/[0-9a-f]{64}/audio")
private val artworkPathPattern = Regex("$TRACKS_PATH/[0-9a-f]{64}/artwork")
private val accessKeyPattern = Regex("[A-Za-z0-9_-]+")

internal fun isCompanionMediaId(id: String): Boolean = id.startsWith(MEDIA_ID_PREFIX)

internal fun isCompanionStreamUrl(url: String): Boolean =
    url.toHttpUrlOrNull()?.let { audioPathPattern.matches(it.encodedPath) } == true

internal class CompanionPairing private constructor(
    private val origin: HttpUrl,
    private val address: String,
    private val accessKey: String,
) {
    val authorization get() = "Bearer $accessKey"
    val serviceUrl get() = origin.toString().removeSuffix("/")
    // 服务端 ID 来自相对路径，两台电脑可以相同；本机播放身份必须包含服务归属。
    private val mediaIdPrefix = "$MEDIA_ID_PREFIX${serviceUrl.encodeUtf8().sha256().hex()}:"
    val scope: String = serviceUrl.encodeUtf8().hmacSha256(accessKey.encodeUtf8()).hex()

    fun mediaId(trackId: String): String = "$mediaIdPrefix$trackId"
    fun trackId(mediaId: String): String? = mediaId.removePrefix(mediaIdPrefix)
        .takeIf { mediaId.startsWith(mediaIdPrefix) && trackIdPattern.matches(it) }

    fun playlistMediaId(id: String): String = "pc-playlist:$scope:$id"
    fun playlistId(mediaId: String): String? = mediaId.removePrefix("pc-playlist:$scope:")
        .takeIf { it != mediaId && playlistIdPattern.matches(it) }

    fun playlistsUrl(id: String?): String = origin.newBuilder()
        .encodedPath(if (id == null) PLAYLISTS_PATH else "$PLAYLISTS_PATH/$id").build().toString()

    fun saveInto(edit: MutablePreferences) {
        edit[companionAddressKey] = address
        edit[companionAccessKey] = accessKey
    }

    fun tracksUrl(): String = origin.newBuilder().encodedPath(TRACKS_PATH).build().toString()
    fun evidenceUrl(): String = origin.newBuilder().encodedPath("/api/library/evidence").build().toString()

    fun streamUrl(mediaId: String): String? = trackUrl(mediaId, "audio")
    fun artworkUrl(mediaId: String): String? = trackUrl(mediaId, "artwork")

    private fun trackUrl(mediaId: String, resource: String): String? {
        if (!mediaId.startsWith(mediaIdPrefix)) return null
        val id = mediaId.removePrefix(mediaIdPrefix)
        return if (trackIdPattern.matches(id)) {
            origin.newBuilder().encodedPath("$TRACKS_PATH/$id/$resource").build().toString()
        } else null
    }

    fun ownsStream(url: HttpUrl): Boolean = ownsResource(url, audioPathPattern)
    fun ownsArtwork(url: HttpUrl): Boolean = ownsResource(url, artworkPathPattern)

    private fun ownsResource(url: HttpUrl, path: Regex): Boolean =
        origin.scheme == url.scheme && origin.host == url.host && origin.port == url.port &&
            url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty() &&
            url.encodedQuery == null && url.fragment == null && path.matches(url.encodedPath)

    // 普通类不输出字段，防止因日志或断言意外把访问密钥写出去。
    companion object {
        fun parse(address: String, key: String): CompanionPairing? {
            val trimmed = address.trim()
            val accessKey = key.trim()
            if (trimmed.isEmpty() || !accessKeyPattern.matches(accessKey)) return null
            val value = if ("://" in trimmed) trimmed else "http://$trimmed"
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            val parsed = value.toHttpUrlOrNull() ?: return null
            if (uri.host == null || uri.rawUserInfo != null || !isLanHost(parsed.host) ||
                (uri.rawPath != "" && uri.rawPath != "/") || uri.rawQuery != null || uri.rawFragment != null ||
                (uri.port != -1 && uri.port !in 1..65535)
            ) return null
            val port = if (uri.port == -1) DEFAULT_COMPANION_PORT else uri.port
            // URL 格式化会省掉 80/443；原输入落盘，恢复时才能区分「省略」与「显式填写」。
            return CompanionPairing(parsed.newBuilder().port(port).build(), trimmed, accessKey)
        }

        private fun isLanHost(host: String): Boolean {
            if (host == "localhost" || host == "::1") return true
            val firstWord = host.substringBefore(':').toIntOrNull(16)
            if (':' in host && firstWord != null && (firstWord in 0xfc00..0xfdff || firstWord in 0xfe80..0xfebf)) {
                return true
            }
            val octets = host.split('.').map { it.toIntOrNull() ?: return false }
            return octets.size == 4 && octets.all { it in 0..255 } && when (octets[0]) {
                10, 127 -> true
                172 -> octets[1] in 16..31
                192 -> octets[1] == 168
                else -> false
            }
        }
    }
}

internal class CompanionPairingStore(private val dataStore: DataStore<Preferences>) {
    val pairings = dataStore.data.map { preferences ->
        preferences[companionAddressKey] to preferences[companionAccessKey]
    }.distinctUntilChanged().map { (address, key) ->
        if (address == null || key == null) null else CompanionPairing.parse(address, key)
    }

    suspend fun load(): CompanionPairing? = pairings.first()

    suspend fun save(pairing: CompanionPairing) {
        dataStore.edit(pairing::saveInto)
    }
}

private val companionAddressKey = stringPreferencesKey("companion_library_address")
private val companionAccessKey = stringPreferencesKey("companion_library_access_key")
