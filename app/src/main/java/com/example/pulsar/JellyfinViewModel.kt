package com.example.pulsar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import com.example.pulsar.data.jellyfin.JellyfinApi
import com.example.pulsar.data.jellyfin.JellyfinApiFactory
import com.example.pulsar.data.jellyfin.JellyfinError
import com.example.pulsar.data.jellyfin.JellyfinRequest
import com.example.pulsar.data.jellyfin.Lyrics
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

private const val SEARCH_DEBOUNCE_MS = 300L

class JellyfinViewModel(
    private val sessionStore: SessionRepository,
    private val apiFactory: JellyfinApiFactory,
    private val authorizationSink: AuthorizationSink,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {
    private val _state = MutableStateFlow(JellyfinUiState())
    val state: StateFlow<JellyfinUiState> = _state.asStateFlow()
    private var session: com.example.pulsar.data.jellyfin.JellyfinSession? = null
    private var dataSource: JellyfinApi? = null
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

    // 歌词：随 currentTrack 变化自动拉取（会话就绪才有值），带代次防护防过期协程写入。
    // 独立 StateFlow 而非塞进 JellyfinUiState：歌词高频换曲刷新，不牵动整个库 UI 状态。
    private val _lyrics = MutableStateFlow(Lyrics.NONE)
    val lyrics: StateFlow<Lyrics> = _lyrics.asStateFlow()
    private var lyricsJob: Job? = null
    private var lyricsGeneration = 0

    init {
        // 启动即声明"正在恢复会话"：在读到本地会话、拉到曲库之前 UI 直接进主页面、
        // 用灰色波浪骨架占位；不这样做的话这段等待期会落到 tracks == null 分支，
        // 闪一屏空白登录表单。
        _state.value = _state.value.copy(isRestoring = true)
        restoreSavedLogin()
    }

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
        invalidateSessionRequests()
        session = null
        dataSource = null
        authorizationSink.activeAuthorization = null
        _playbackTracks.value = emptyMap()
        _currentTrack.value = null
        clearLyrics()
        val generation = ++loginGeneration
        _state.value = current.copy(
            isLoading = true,
            errorMessage = null,
            password = "",
            tracks = null,
            discoverAlbums = null,
            albums = null,
            albumTracks = null,
            selectedAlbum = null,
            playlists = null,
            playlistTracks = null,
            selectedPlaylist = null,
            searchQuery = "",
            searchResults = null,
            isSearching = false
        )
        loginJob = viewModelScope.launch(dispatcher) {
            try {
                val source = apiFactory.create(current.serverUrl)
                val signedIn = source.login(username, password).getOrThrow()
                val tracks = source.getMusicItems(signedIn.userId, signedIn.accessToken).getOrThrow()
                if (coroutineContext.isActive && generation == loginGeneration) {
                    session = signedIn
                    dataSource = source
                    authorizationSink.activeAuthorization =
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
        loginJob = viewModelScope.launch(dispatcher) {
            try {
                val saved = sessionStore.load()
                if (saved == null) {
                    // 本地没有可恢复的会话：结束恢复态，UI 落到登录表单。
                    _state.value = _state.value.copy(isRestoring = false)
                    return@launch
                }
                val generation = ++loginGeneration
                // 顺手预填地址/用户名：万一后面网络失败回退到登录表单，用户面对的
                // 不是一张空白表单，只需补密码即可重试。
                _state.value = _state.value.copy(
                    isRestoring = true,
                    isLoading = true,
                    serverUrl = saved.serverUrl,
                    username = saved.username
                )
                val source = apiFactory.create(saved.serverUrl)
                val tracks = source.getMusicItems(saved.userId, saved.accessToken).getOrThrow()
                if (coroutineContext.isActive && generation == loginGeneration) {
                    session = com.example.pulsar.data.jellyfin.JellyfinSession(saved.userId, saved.accessToken)
                    dataSource = source
                    authorizationSink.activeAuthorization =
                        source.authorizationHeader(saved.accessToken)
                    _state.value = _state.value.copy(isLoading = false, isRestoring = false, tracks = tracks)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // 只在代次仍匹配时才修改状态与会话，防止已取消的恢复任务误清新登录的 token。
                val currentGeneration = loginGeneration
                if (error is JellyfinError.Unauthorized && currentGeneration == loginGeneration) {
                    sessionStore.clear()
                    authorizationSink.activeAuthorization = null
                }
                if (currentGeneration == loginGeneration) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isRestoring = false,
                        errorMessage = friendlyError(error)
                    )
                }
            }
        }
    }

    private fun invalidateSessionRequests() {
        albumsJob?.cancel()
        albumsJob = null
        albumsGeneration++
        searchJob?.cancel()
        searchJob = null
        searchGeneration++
    }

    fun signOut() {
        loginGeneration++
        loginJob?.cancel()
        loginJob = null
        invalidateSessionRequests()
        session = null
        dataSource = null
        authorizationSink.activeAuthorization = null
        _playbackTracks.value = emptyMap()
        _currentTrack.value = null
        clearLyrics()
        _state.value = JellyfinUiState()
        viewModelScope.launch(dispatcher) { sessionStore.clear() }
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

    /**
     * 拉取当前曲目的歌词。曲库没歌词是常态：404 已在数据层归一化为 Lyrics.NONE，
     * 其余失败静默回 NONE（歌词页显示空态，不为辅助功能弹错误打断播放体验）。
     * 代次防护与 albumsJob 同模式：切歌/登出/会话过期后旧协程的响应作废。
     */
    fun loadLyricsForCurrentTrack() {
        val track = _currentTrack.value ?: return
        val src = dataSource ?: return
        val currentSession = session ?: return
        val generation = ++lyricsGeneration
        _lyrics.value = Lyrics.NONE
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch(dispatcher) {
            val result = src.getLyrics(currentSession.accessToken, track.id)
            if (generation == lyricsGeneration) {
                _lyrics.value = result.getOrElse { Lyrics.NONE }
            }
        }
    }

    fun clearLyrics() {
        lyricsJob?.cancel()
        lyricsJob = null
        lyricsGeneration++
        _lyrics.value = Lyrics.NONE
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
        request: suspend (JellyfinApi, com.example.pulsar.data.jellyfin.JellyfinSession) -> Result<T>,
        success: (T) -> JellyfinUiState
    ) {
        val src = dataSource ?: return
        val currentSession = session ?: return
        albumsJob?.cancel()
        val generation = ++albumsGeneration
        _state.value = before()
        albumsJob = viewModelScope.launch(dispatcher) {
            try {
                val result = request(src, currentSession).getOrThrow()
                if (coroutineContext.isActive && generation == albumsGeneration) _state.value = success(result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == albumsGeneration) {
                    if (error is JellyfinError.Unauthorized) {
                        expireSession()
                    } else {
                        _state.value = _state.value.copy(isLoading = false, errorMessage = friendlyError(error))
                    }
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
        searchJob = viewModelScope.launch(dispatcher) {
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
                        if (error is JellyfinError.Unauthorized) {
                            expireSession()
                        } else {
                            _state.value = _state.value.copy(isSearching = false, errorMessage = friendlyError(error))
                        }
                    }
                }
            }
        }
    }

    private fun expireSession() {
        loginGeneration++
        loginJob?.cancel()
        loginJob = null
        invalidateSessionRequests()
        session = null
        dataSource = null
        authorizationSink.activeAuthorization = null
        _playbackTracks.value = emptyMap()
        _currentTrack.value = null
        clearLyrics()
        _state.value = _state.value.copy(
            serverUrl = _state.value.serverUrl,
            username = _state.value.username,
            password = "",
            isLoading = false,
            errorMessage = "登录已失效，请重新登录",
            tracks = null,
            discoverAlbums = null,
            albums = null,
            albumTracks = null,
            selectedAlbum = null,
            playlists = null,
            playlistTracks = null,
            selectedPlaylist = null,
            searchQuery = "",
            searchResults = null,
            isSearching = false
        )
        viewModelScope.launch(dispatcher) { sessionStore.clear() }
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
        lyricsJob?.cancel()
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
