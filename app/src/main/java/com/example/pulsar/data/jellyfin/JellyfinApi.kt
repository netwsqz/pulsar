package com.example.pulsar.data.jellyfin

import com.example.pulsar.domain.model.*
import com.example.pulsar.session.Session

/**
 * 会话作用域数据接口（#23）：`serverUrl / userId / accessToken` 在创建时绑定，
 * 方法只收领域参数。所有请求的鉴权头由实现从绑定的会话现取，token 不进 URL。
 *
 * 调用方拿不到「传错令牌」的机会——换账号就换一个 API 实例（`SessionManager` 负责）。
 */
interface SessionScopedJellyfinApi {
    suspend fun getMusicItems(): Result<List<Track>>
    suspend fun getAlbums(): Result<List<Album>>
    suspend fun getRandomAlbums(limit: Int = 12): Result<List<Album>>
    /** 最近播放（服务端播放历史，按 DatePlayed 倒序）。 */
    suspend fun getRecentlyPlayed(limit: Int = 20): Result<List<Track>>
    /** 最常播放（服务端播放次数，按 PlayCount 倒序）。 */
    suspend fun getMostPlayed(limit: Int = 20): Result<List<Track>>
    /** 最近添加的专辑（按 DateCreated 倒序）。 */
    suspend fun getRecentlyAdded(limit: Int = 12): Result<List<Album>>
    suspend fun getAlbumTracks(albumId: String): Result<List<Track>>
    suspend fun getPlaylists(): Result<List<Playlist>>
    suspend fun getPlaylistTracks(playlistId: String): Result<List<Track>>
    suspend fun search(query: String): Result<SearchResults>
    /**
     * 拉取歌词（Jellyfin 10.9+ /Audio/{itemId}/Lyrics）。曲库没有歌词是常态不是错误：
     * 服务器 404 归一化为成功 + [Lyrics.NONE]，UI 显示空态。
     */
    suspend fun getLyrics(trackId: String): Result<Lyrics>
    fun streamUrl(mediaId: String): String
    /** 开始播放上报（POST /Sessions/Playing）。失败只返回 Result，不抛异常。 */
    suspend fun reportPlaybackStarted(itemId: String, positionTicks: Long): Result<Unit>
    /** 停止播放上报（POST /Sessions/Playing/Stopped），带实际播放位置。 */
    suspend fun reportPlaybackStopped(itemId: String, positionTicks: Long): Result<Unit>
    fun imageRequest(itemId: String, imageTag: String?): JellyfinRequest
    /** 当前会话的 MediaBrowser 鉴权头；供 `SessionManager` 生成会话鉴权头用，不要进 UI / 日志。 */
    fun authorizationHeader(): String
}

/**
 * 登录入口：还没有会话时唯一可用的 Jellyfin 接口。
 * 成功返回完整 [Session]（serverUrl / username 由实现补齐），失败映射成 [JellyfinError]。
 */
interface JellyfinLoginApi {
    suspend fun login(username: String, password: String): Result<Session>
}

/**
 * API 工厂：登录前用 [createLoginApi]，拿到会话后用 [create] 建会话作用域接口。
 *
 * 之所以分两步：登录请求本身只需要服务器地址；数据方法必须绑定 userId / token，
 * 让工厂在创建时一次性绑定，调用点就不会再搬凭据（#23）。
 */
interface JellyfinApiFactory {
    suspend fun createLoginApi(serverUrl: String): JellyfinLoginApi
    suspend fun create(session: Session): SessionScopedJellyfinApi
}
