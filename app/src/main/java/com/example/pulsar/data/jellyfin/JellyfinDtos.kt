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
    @SerialName("ChildCount") val childCount: Int? = null
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
        imageTag = imageTags.values.firstOrNull()
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
