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
    @SerialName("available_qualities") val availableQualities: List<String> = emptyList(),
    /** 服务端曲库索引判定：已在库。老服务端不回此字段，按「没有」处理。 */
    @SerialName("in_library") val inLibrary: Boolean = false,
    /** 任务队列里 queued/running。 */
    val downloading: Boolean = false
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

/** 专辑曲目单项（供 App 的选曲弹窗展示）。 */
@Serializable
data class QQAlbumSongDto(
    @SerialName("song_mid") val songMid: String = "",
    val name: String = "",
    /** 服务端曲库索引判定，同 QQSongDto。 */
    @SerialName("in_library") val inLibrary: Boolean = false,
    val downloading: Boolean = false
)

@Serializable
data class QQAlbumSongsResponse(
    @SerialName("song_mids") val songMids: List<String> = emptyList(),
    val songs: List<QQAlbumSongDto> = emptyList()
)

@Serializable
data class QQDownloadRequest(
    @SerialName("song_mid") val songMid: String,
    val quality: String,
    val title: String? = null,
    /** 判定「已在库」用；服务端必须同时有歌名和歌手才敢拦，缺了不传就等于不拦。 */
    val artist: String? = null,
    /** 已在库时仍要重下（原文件坏了 / 想换更高音质）。 */
    val force: Boolean = false
)

@Serializable
data class QQDownloadAlbumRequest(
    @SerialName("album_mid") val albumMid: String,
    val quality: String,
    @SerialName("album_name") val albumName: String? = null,
    /** 只下载选中的曲目；null = 整张（encodeDefaults 关着，null 不会进请求体）。 */
    @SerialName("song_mids") val songMids: List<String>? = null,
    /** 整张下载时强制全下；显式传了 song_mids 时无意义（勾选本身就是强制）。 */
    val force: Boolean = false
)

@Serializable
data class QQDownloadResponse(
    @SerialName("job_id") val jobId: String,
    /** true = 已在库被服务端跳过（jobId 为空串），不是错误。 */
    val skipped: Boolean = false
)

@Serializable
data class QQDownloadAlbumResponse(
    @SerialName("job_ids") val jobIds: List<String> = emptyList(),
    val count: Int = 0,
    /** 整张下载时被跳过的已在库曲目数；显式选曲时不跳过，恒为 0。 */
    @SerialName("skipped_count") val skippedCount: Int = 0
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

/** 服务端曲库索引状态（/api/health 的 library 块）。 */
@Serializable
data class QQLibraryHealth(
    val root: String = "",
    /** false = 服务刚启动还在扫第一遍，此时判定一律按「没有」。 */
    val ready: Boolean = true,
    val count: Int = 0
)

/** /api/health 响应。 */
@Serializable
data class QQHealthResponse(
    val ok: Boolean = false,
    @SerialName("ffmpeg_ready") val ffmpegReady: Boolean = false,
    @SerialName("ffmpeg_error") val ffmpegError: String? = null,
    @SerialName("credential_expired") val credentialExpired: Boolean = false,
    @SerialName("library_root") val libraryRoot: String = "",
    /** 老服务端不回此块：默认按「就绪」处理，界面不误报扫描中。 */
    val library: QQLibraryHealth = QQLibraryHealth()
)

@Serializable
data class QQCredentialRetryResponse(val ok: Boolean = false, val uin: String = "")
