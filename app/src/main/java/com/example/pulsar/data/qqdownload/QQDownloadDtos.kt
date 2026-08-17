package com.example.pulsar.data.qqdownload

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 搜索结果单曲（对齐 PC 服务 /api/search 的每项字段）。 */
@Serializable
data class QQSongDto(
    val title: String = "",
    val singer: String = "",
    val album: String = "",
    @SerialName("song_mid") val songMid: String = "",
    @SerialName("media_mid") val mediaMid: String = "",
    @SerialName("size_hires") val sizeHires: Long = 0,
    @SerialName("size_flac") val sizeFlac: Long = 0,
    @SerialName("size_320") val size320: Long = 0,
    @SerialName("size_128") val size128: Long = 0,
    @SerialName("available_qualities") val availableQualities: List<String> = emptyList()
)

@Serializable
data class QQSearchResponse(val songs: List<QQSongDto> = emptyList())

/** 专辑搜索结果单项。 */
@Serializable
data class QQAlbumDto(
    @SerialName("album_mid") val albumMid: String = "",
    @SerialName("album_id") val albumId: Long = 0,
    @SerialName("album_name") val albumName: String = "",
    val singer: String = "",
    @SerialName("public_time") val publicTime: String = "",
    @SerialName("song_count") val songCount: Int = 0
)

@Serializable
data class QQAlbumSearchResponse(val albums: List<QQAlbumDto> = emptyList())

@Serializable
data class QQAlbumSongsResponse(@SerialName("song_mids") val songMids: List<String> = emptyList())

@Serializable
data class QQDownloadRequest(
    @SerialName("song_mid") val songMid: String,
    val quality: String,
    val title: String? = null
)

@Serializable
data class QQDownloadAlbumRequest(
    @SerialName("album_mid") val albumMid: String,
    val quality: String,
    @SerialName("album_name") val albumName: String? = null
)

@Serializable
data class QQDownloadResponse(@SerialName("job_id") val jobId: String)

@Serializable
data class QQDownloadAlbumResponse(
    @SerialName("job_ids") val jobIds: List<String> = emptyList(),
    val count: Int = 0
)

/** 下载任务状态（对齐 PC 服务 job dict 字段）。 */
@Serializable
data class QQJobDto(
    @SerialName("job_id") val jobId: String,
    @SerialName("song_mid") val songMid: String,
    val quality: String,
    val title: String? = null,
    val status: String,
    @SerialName("created_at") val createdAt: Double = 0.0,
    val error: String? = null,
    @SerialName("final_path") val finalPath: String? = null
)

@Serializable
data class QQJobsResponse(val jobs: List<QQJobDto> = emptyList())

/** /api/health 响应。 */
@Serializable
data class QQHealthResponse(
    val ok: Boolean = false,
    @SerialName("ffmpeg_ready") val ffmpegReady: Boolean = false,
    @SerialName("ffmpeg_error") val ffmpegError: String? = null,
    @SerialName("credential_expired") val credentialExpired: Boolean = false,
    @SerialName("library_root") val libraryRoot: String = ""
)

@Serializable
data class QQCredentialRetryResponse(val ok: Boolean = false, val uin: String = "")
