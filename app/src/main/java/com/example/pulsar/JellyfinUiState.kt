package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track

data class JellyfinUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val tracks: List<Track>? = null,
    val discoverAlbums: List<Album>? = null,
    val albums: List<Album>? = null,
    val albumTracks: List<Track>? = null,
    val selectedAlbum: Album? = null,
    val playlists: List<Playlist>? = null,
    val playlistTracks: List<Track>? = null,
    val selectedPlaylist: Playlist? = null,
    val searchQuery: String = "",
    val searchResults: SearchResults? = null,
    val isSearching: Boolean = false
)
