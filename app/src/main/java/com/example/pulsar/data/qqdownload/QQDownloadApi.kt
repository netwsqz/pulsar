package com.example.pulsar.data.qqdownload

/**
 * QQ 音乐下载服务（PC 侧 FastAPI）的客户端接口。
 *
 * 所有方法返回 Result，失败统一映射为 [QQDownloadError]；
 * 服务地址由调用方（QQDownloadViewModel）提供并持久化。
 */
interface QQDownloadApi {
    suspend fun health(): Result<QQHealthResponse>
    suspend fun search(keyword: String, limit: Int = 20): Result<List<QQSongDto>>
    suspend fun searchAlbums(keyword: String, limit: Int = 20): Result<List<QQAlbumDto>>
    suspend fun albumSongs(albumMid: String): Result<List<String>>
    suspend fun enqueueDownload(songMid: String, quality: String, title: String? = null): Result<String>
    suspend fun enqueueAlbum(albumMid: String, quality: String): Result<Int>
    suspend fun jobs(): Result<List<QQJobDto>>
    suspend fun retryCredential(): Result<Unit>
}
