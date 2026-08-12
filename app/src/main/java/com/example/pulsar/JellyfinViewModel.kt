package com.example.pulsar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import com.example.pulsar.data.jellyfin.JellyfinDataSource
import com.example.pulsar.data.jellyfin.JellyfinError
import com.example.pulsar.data.jellyfin.JellyfinRequest
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val SEARCH_DEBOUNCE_MS = 300L

class JellyfinViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(JellyfinUiState())
    val state: StateFlow<JellyfinUiState> = _state.asStateFlow()
    private val sessionStore = SessionStore(getApplication<PulsarApplication>().pulsarPreferencesDataStore)
    private var session: com.example.pulsar.data.jellyfin.JellyfinSession? = null
    private var dataSource: JellyfinDataSource? = null
    private var loginJob: Job? = null
    private var loginGeneration = 0
    private var albumsJob: Job? = null
    private var albumsGeneration = 0
    private var searchJob: Job? = null
    private var searchGeneration = 0

    private val _playbackTracks = MutableStateFlow<Map<String, Track>>(emptyMap())
    val playbackTracks: StateFlow<Map<String, Track>> = _playbackTracks.asStateFlow()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    init { restoreSavedLogin() }

    fun updateServerUrl(value: String) { _state.value = _state.value.copy(serverUrl = value, errorMessage = null) }
    fun updateUsername(value: String) { _state.value = _state.value.copy(username = value, errorMessage = null) }
    fun updatePassword(value: String) { _state.value = _state.value.copy(password = value, errorMessage = null) }

    fun login() {
        loginJob?.cancel()
        val current = _state.value
        if (current.serverUrl.isBlank() || current.username.isBlank() || current.password.isBlank()) {
            _state.value = current.copy(errorMessage = "请填写服务器地址、用户名和密码", password = "")
            return
        }
        val username = current.username
        val password = current.password
        val generation = ++loginGeneration
        _state.value = current.copy(isLoading = true, errorMessage = null, password = "")
        loginJob = viewModelScope.launch {
            try {
                val source = getApplication<PulsarApplication>().jellyfinDataSource(current.serverUrl)
                val signedIn = source.login(username, password).getOrThrow()
                val tracks = source.getMusicItems(signedIn.userId, signedIn.accessToken).getOrThrow()
                if (coroutineContext.isActive && generation == loginGeneration) {
                    session = signedIn
                    dataSource = source
                    getApplication<PulsarApplication>().activeAuthorization =
                        source.authorizationHeader(signedIn.accessToken)
                    _state.value = _state.value.copy(isLoading = false, tracks = tracks)
                    sessionStore.save(SavedSession(current.serverUrl, signedIn.userId, username, signedIn.accessToken))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == loginGeneration) {
                    _state.value = _state.value.copy(isLoading = false, errorMessage = friendlyError(error, isLogin = true))
                }
            }
        }
    }

    private fun restoreSavedLogin() {
        viewModelScope.launch {
            val saved = sessionStore.load() ?: return@launch
            val generation = ++loginGeneration
            _state.value = _state.value.copy(isLoading = true)
            try {
                val source = getApplication<PulsarApplication>().jellyfinDataSource(saved.serverUrl)
                val tracks = source.getMusicItems(saved.userId, saved.accessToken).getOrThrow()
                if (coroutineContext.isActive && generation == loginGeneration) {
                    session = com.example.pulsar.data.jellyfin.JellyfinSession(saved.userId, saved.accessToken)
                    dataSource = source
                    getApplication<PulsarApplication>().activeAuthorization =
                        source.authorizationHeader(saved.accessToken)
                    _state.value = _state.value.copy(isLoading = false, tracks = tracks)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == loginGeneration) {
                    if (error is JellyfinError.Unauthorized) sessionStore.clear()
                    _state.value = _state.value.copy(isLoading = false, errorMessage = friendlyError(error))
                }
            }
        }
    }

    fun signOut() {
        loginGeneration++
        loginJob?.cancel()
        loginJob = null
        albumsJob?.cancel()
        albumsJob = null
        albumsGeneration++
        searchJob?.cancel()
        searchJob = null
        searchGeneration++
        session = null
        dataSource = null
        getApplication<PulsarApplication>().activeAuthorization = null
        _playbackTracks.value = emptyMap()
        _currentTrack.value = null
        _state.value = JellyfinUiState()
        viewModelScope.launch { sessionStore.clear() }
    }

    fun streamUrl(track: Track): String? {
        val src = dataSource ?: return null
        val currentSession = session ?: return null
        return src.streamUrl(track.id, currentSession.accessToken)
    }

    fun albumCover(album: Album): JellyfinRequest? = coverRequest(album.id, album.imageTag)
    fun playlistCover(playlist: Playlist): JellyfinRequest? = coverRequest(playlist.id, playlist.imageTag)

    fun trackCover(track: Track): JellyfinRequest? {
        if (track.id.isBlank()) return null
        val src = dataSource ?: return null
        val currentSession = session ?: return null
        return src.imageRequest(track.id, track.imageTag, currentSession.accessToken)
    }

    private fun coverRequest(itemId: String, imageTag: String?): JellyfinRequest? {
        val tag = imageTag ?: return null
        val src = dataSource ?: return null
        val currentSession = session ?: return null
        return src.imageRequest(itemId, tag, currentSession.accessToken)
    }

    fun setPlaybackQueue(tracks: List<Track>, startTrackId: String) {
        val byId = tracks.associateBy { it.id }
        _playbackTracks.value = byId
        _currentTrack.value = byId[startTrackId]
    }

    fun onMediaItemTransition(mediaItem: MediaItem?) {
        _currentTrack.value = mediaItem?.let { item ->
            item.mediaId?.let { _playbackTracks.value[it] } ?: item.toRecoveredTrack()
        }
    }

    private fun MediaItem.toRecoveredTrack(): Track = Track(
        id = mediaId,
        title = mediaMetadata.title?.toString() ?: "未知歌曲",
        artist = mediaMetadata.artist?.toString() ?: "未知歌手",
        album = mediaMetadata.albumTitle?.toString().orEmpty(),
        durationMs = mediaMetadata.durationMs,
        imageTag = localConfiguration?.tag as? String
    )

    fun loadAlbums() {
        if (_state.value.albums != null) return
        loadLibrary(
            request = { src, currentSession -> src.getAlbums(currentSession.userId, currentSession.accessToken) },
            success = { albums -> _state.value.copy(isLoading = false, albums = albums) }
        )
    }

    fun loadDiscoverAlbums() {
        if (_state.value.discoverAlbums != null) return
        loadLibrary(
            request = { src, currentSession -> src.getRandomAlbums(currentSession.userId, currentSession.accessToken) },
            success = { albums -> _state.value.copy(isLoading = false, discoverAlbums = albums) }
        )
    }

    fun refreshDiscoverAlbums() {
        loadLibrary(
            request = { src, currentSession -> src.getRandomAlbums(currentSession.userId, currentSession.accessToken) },
            success = { albums -> _state.value.copy(isLoading = false, discoverAlbums = albums) }
        )
    }

    fun loadAlbumTracks(album: Album) {
        if (_state.value.selectedAlbum?.id == album.id && _state.value.albumTracks != null) return
        loadLibrary(
            before = { _state.value.copy(isLoading = true, errorMessage = null, selectedAlbum = album, albumTracks = null) },
            request = { src, currentSession -> src.getAlbumTracks(currentSession.userId, currentSession.accessToken, album.id) },
            success = { tracks -> _state.value.copy(isLoading = false, albumTracks = tracks) }
        )
    }

    fun loadPlaylists() {
        if (_state.value.playlists != null) return
        loadLibrary(
            request = { src, currentSession -> src.getPlaylists(currentSession.userId, currentSession.accessToken) },
            success = { playlists -> _state.value.copy(isLoading = false, playlists = playlists) }
        )
    }

    fun loadPlaylistTracks(playlist: Playlist) {
        if (_state.value.selectedPlaylist?.id == playlist.id && _state.value.playlistTracks != null) return
        loadLibrary(
            before = { _state.value.copy(isLoading = true, errorMessage = null, selectedPlaylist = playlist, playlistTracks = null) },
            request = { src, currentSession -> src.getPlaylistTracks(currentSession.userId, currentSession.accessToken, playlist.id) },
            success = { tracks -> _state.value.copy(isLoading = false, playlistTracks = tracks) }
        )
    }

    private fun <T> loadLibrary(
        before: () -> JellyfinUiState = { _state.value.copy(isLoading = true, errorMessage = null) },
        request: suspend (JellyfinDataSource, com.example.pulsar.data.jellyfin.JellyfinSession) -> Result<T>,
        success: (T) -> JellyfinUiState
    ) {
        val src = dataSource ?: return
        val currentSession = session ?: return
        albumsJob?.cancel()
        val generation = ++albumsGeneration
        _state.value = before()
        albumsJob = viewModelScope.launch {
            try {
                val result = request(src, currentSession).getOrThrow()
                if (coroutineContext.isActive && generation == albumsGeneration) _state.value = success(result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == albumsGeneration) {
                    _state.value = _state.value.copy(isLoading = false, errorMessage = friendlyError(error))
                }
            }
        }
    }

    fun updateSearchQuery(value: String) {
        _state.value = _state.value.copy(searchQuery = value, errorMessage = null)
        searchJob?.cancel()
        val query = value.trim()
        val src = dataSource ?: return
        val currentSession = session ?: return
        if (query.isEmpty()) {
            searchGeneration++
            _state.value = _state.value.copy(searchResults = null, isSearching = false)
            return
        }
        val generation = ++searchGeneration
        _state.value = _state.value.copy(isSearching = true)
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            if (coroutineContext.isActive && generation == searchGeneration) {
                try {
                    val results = src.search(currentSession.userId, currentSession.accessToken, query).getOrThrow()
                    if (coroutineContext.isActive && generation == searchGeneration) {
                        _state.value = _state.value.copy(isSearching = false, searchResults = results)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (generation == searchGeneration) {
                        _state.value = _state.value.copy(isSearching = false, errorMessage = friendlyError(error))
                    }
                }
            }
        }
    }

    fun clearAlbumDetail() {
        albumsJob?.cancel()
        albumsGeneration++
        _state.value = _state.value.copy(selectedAlbum = null, errorMessage = null)
    }

    fun clearPlaylistDetail() {
        albumsJob?.cancel()
        albumsGeneration++
        _state.value = _state.value.copy(selectedPlaylist = null, errorMessage = null)
    }

    override fun onCleared() {
        loginJob?.cancel()
        albumsJob?.cancel()
        searchJob?.cancel()
        super.onCleared()
    }

    private fun friendlyError(error: Throwable, isLogin: Boolean = false): String = when (error) {
        is JellyfinError.InvalidCredentials -> "用户名或密码不正确"
        is JellyfinError.Unauthorized -> "登录已失效，请重新登录"
        is JellyfinError.Network -> "无法连接服务器，请检查地址和网络"
        is JellyfinError.Http -> "服务器请求失败（${error.statusCode}）"
        is JellyfinError.InvalidResponse -> "服务器返回了无法识别的数据"
        is IllegalArgumentException -> error.message ?: if (isLogin) "服务器地址无效" else "请求参数无效"
        else -> if (isLogin) "登录失败，请检查服务器地址和网络" else "加载失败，请稍后重试"
    }
}
