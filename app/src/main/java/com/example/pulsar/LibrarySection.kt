package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist

internal sealed interface LibrarySection {
    data object Discover : LibrarySection
    data object Tracks : LibrarySection
    data object Albums : LibrarySection
    data object Playlists : LibrarySection
    data object Search : LibrarySection
    data object Settings : LibrarySection
    data class AlbumDetail(val album: Album) : LibrarySection
    data class PlaylistDetail(val playlist: Playlist) : LibrarySection
}

internal fun LibrarySection.isDetail(): Boolean =
    this is LibrarySection.AlbumDetail ||
        this is LibrarySection.PlaylistDetail ||
        this is LibrarySection.Settings
