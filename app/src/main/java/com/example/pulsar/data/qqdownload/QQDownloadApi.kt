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
    /** 专辑曲目（mid + 歌名 + 已在库/下载中标记），供选曲弹窗展示。 */
    suspend fun albumSongs(albumMid: String): Result<List<QQAlbumSongDto>>

    /**
     * 入队单曲。[artist] 用于服务端「已在库」判定（必须同时有歌名和歌手它才拦）；
     * [force] = 已在库仍要重下。响应带 [QQDownloadResponse.skipped]：true 表示服务端
     * 判定已在库、未入队（不是错误）。
     */
    suspend fun enqueueDownload(
        songMid: String,
        quality: String,
        title: String? = null,
        artist: String? = null,
        force: Boolean = false
    ): Result<QQDownloadResponse>

    /**
     * 入队专辑；[songMids] 为 null 时整张下载（服务端自动跳过已在库的曲目），
     * 否则只入队其中选中的曲目（显式勾选不跳过——勾选本身就是强制）。
     * [force] 仅整张下载时有意义：跳过已在库的判定，全下。
     */
    suspend fun enqueueAlbum(
        albumMid: String,
        quality: String,
        songMids: List<String>? = null,
        force: Boolean = false
    ): Result<QQDownloadAlbumResponse>
    suspend fun jobs(): Result<List<QQJobDto>>
    suspend fun retryCredential(): Result<Unit>
}
