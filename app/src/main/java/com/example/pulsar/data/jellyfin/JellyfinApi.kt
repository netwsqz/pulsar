package com.example.pulsar.data.jellyfin

import com.example.pulsar.domain.model.*

interface JellyfinApi {
    suspend fun login(username: String, password: String): Result<JellyfinSession>
    suspend fun getMusicItems(userId: String, accessToken: String): Result<List<Track>>
    suspend fun getAlbums(userId: String, accessToken: String): Result<List<Album>>
    suspend fun getRandomAlbums(userId: String, accessToken: String, limit: Int = 12): Result<List<Album>>
    /** 最近播放（服务端播放历史，按 DatePlayed 倒序）。 */
    suspend fun getRecentlyPlayed(userId: String, accessToken: String, limit: Int = 20): Result<List<Track>>
    /** 最常播放（服务端播放次数，按 PlayCount 倒序）。 */
    suspend fun getMostPlayed(userId: String, accessToken: String, limit: Int = 20): Result<List<Track>>
    /** 最近添加的专辑（按 DateCreated 倒序）。 */
    suspend fun getRecentlyAdded(userId: String, accessToken: String, limit: Int = 12): Result<List<Album>>
    suspend fun getAlbumTracks(userId: String, accessToken: String, albumId: String): Result<List<Track>>
    suspend fun getPlaylists(userId: String, accessToken: String): Result<List<Playlist>>
    suspend fun getPlaylistTracks(userId: String, accessToken: String, playlistId: String): Result<List<Track>>
    suspend fun search(userId: String, accessToken: String, query: String): Result<SearchResults>
    /**
     * 拉取歌词（Jellyfin 10.9+ /Audio/{itemId}/Lyrics）。曲库没有歌词是常态不是错误：
     * 服务器 404 归一化为成功 + [Lyrics.NONE]，UI 显示空态。
     */
    suspend fun getLyrics(accessToken: String, trackId: String): Result<Lyrics>
    fun streamUrl(mediaId: String, accessToken: String): String
    fun imageRequest(itemId: String, imageTag: String?, accessToken: String): JellyfinRequest
    fun authorizationHeader(accessToken: String): String
}

interface JellyfinApiFactory {
    suspend fun create(serverUrl: String): JellyfinApi
}
