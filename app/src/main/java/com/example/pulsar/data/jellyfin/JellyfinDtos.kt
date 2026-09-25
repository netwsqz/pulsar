package com.example.pulsar.data.jellyfin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JellyfinLoginRequest(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String
)

@Serializable
data class JellyfinLoginResponse(
    @SerialName("User") val user: JellyfinUserDto? = null,
    @SerialName("AccessToken") val accessToken: String = ""
)

@Serializable
data class JellyfinUserDto(
    @SerialName("Id") val id: String = ""
)

@Serializable
data class JellyfinItemsResponse(
    @SerialName("Items") val items: List<JellyfinItemDto> = emptyList()
)

@Serializable
data class JellyfinItemDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("Artists") val artists: List<String> = emptyList(),
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("Type") val type: String? = null,
    @SerialName("IsFolder") val isFolder: Boolean? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    /**
     * 回放增益（dB）。Jellyfin 10.9+ 按 ReplayGain 2.0 的 -18 LUFS 基准现算：
     * 有 LUFS 数据就用 `-18 - LUFS`，否则回落到文件里的 REPLAYGAIN_TRACK_GAIN 标签。
     * 不受 Fields 参数限制，跟着歌曲列表一起返回；没有响度数据时为 null。
     */
    @SerialName("NormalizationGain") val normalizationGain: Float? = null,
    /**
     * 文件在电脑上的绝对路径。**仅管理员账号返回**（请求要带 Fields=Path），
     * 供 PC 伴生服务的删歌/改名接口定位文件；拿不到时为 null。
     */
    @SerialName("Path") val path: String? = null
)

fun JellyfinItemDto.toTrack(): com.example.pulsar.domain.model.Track {
    val artist = artists.firstOrNull { it.isNotBlank() }
        ?: albumArtist?.takeIf { it.isNotBlank() }
        ?: "Unknown artist"
    return com.example.pulsar.domain.model.Track(
        id = id,
        title = name,
        artist = artist,
        album = album.orEmpty(),
        durationMs = runTimeTicks?.div(10_000),
        imageTag = imageTags.values.firstOrNull(),
        normalizationGainDb = normalizationGain,
        filePath = path
    )
}

fun JellyfinItemDto.toAlbum(): com.example.pulsar.domain.model.Album {
    val artist = albumArtist?.takeIf { it.isNotBlank() }
        ?: artists.firstOrNull { it.isNotBlank() }
        ?: "Unknown artist"
    return com.example.pulsar.domain.model.Album(
        id = id,
        name = name,
        artist = artist,
        trackCount = childCount,
        imageTag = imageTags["Primary"]
    )
}

fun JellyfinItemDto.toPlaylist(): com.example.pulsar.domain.model.Playlist {
    return com.example.pulsar.domain.model.Playlist(
        id = id,
        name = name,
        trackCount = childCount,
        imageTag = imageTags["Primary"]
    )
}
