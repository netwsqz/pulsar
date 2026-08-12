package com.example.pulsar

import android.app.Application
import com.example.pulsar.ui.theme.PulsarTheme
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.pulsar.data.jellyfin.JellyfinDataSource
import com.example.pulsar.data.jellyfin.JellyfinError
import com.example.pulsar.data.jellyfin.JellyfinRequest
import com.example.pulsar.data.jellyfin.JellyfinUrlBuilder
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import com.example.pulsar.playback.PlaybackDiagnostics
import com.example.pulsar.playback.PlaybackMode
import com.example.pulsar.playback.PlaybackOutputInfo
import com.example.pulsar.playback.SilenceSkipMediaCommands
import com.example.pulsar.playback.SleepTimer
import com.example.pulsar.playback.UsbExclusiveMediaCommands
import com.example.pulsar.playback.buildPlaybackOrder
import com.example.pulsar.playback.formatPosition
import com.example.pulsar.playback.playbackModeConfig
import com.google.common.util.concurrent.ListenableFuture
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        setContent { PulsarApp(controllerFuture = controllerFuture) }
    }

    override fun onDestroy() {
        controllerFuture?.let { future ->
            if (future.isDone) runCatching { future.get().release() } else future.cancel(false)
        }
        controllerFuture = null
        super.onDestroy()
    }
}

data class JellyfinUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val tracks: List<Track>? = null,
    // 发现页：随机专辑，null 表示尚未加载。
    val discoverAlbums: List<Album>? = null,
    // 专辑浏览：albums 为 null 表示专辑列表尚未加载；albumTracks/selectedAlbum 仅在专辑详情页使用。
    val albums: List<Album>? = null,
    val albumTracks: List<Track>? = null,
    val selectedAlbum: Album? = null,
    // 播放列表浏览：playlists 为 null 表示列表尚未加载；playlistTracks/selectedPlaylist 仅在播放列表详情页使用。
    val playlists: List<Playlist>? = null,
    val playlistTracks: List<Track>? = null,
    val selectedPlaylist: Playlist? = null,
    // 搜索：searchResults 为 null 表示尚未完成一次搜索；isSearching 表示请求进行中。
    val searchQuery: String = "",
    val searchResults: SearchResults? = null,
    val isSearching: Boolean = false
)

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

    // 播放会话 UI 状态：当前队列索引 + 当前曲目。放 ViewModel 而非 Composable，
    // 这样从后台回来 Activity 被系统重建时信息仍在，底部浮窗不丢歌名/封面。
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
                    sessionStore.save(
                        SavedSession(
                            serverUrl = current.serverUrl,
                            userId = signedIn.userId,
                            username = username,
                            accessToken = signedIn.accessToken
                        )
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == loginGeneration) {
                    _state.value = _state.value.copy(isLoading = false, errorMessage = friendlyError(error))
                }
            }
        }
    }

    /**
     * 启动时如果有已保存的登录会话，自动恢复登录（直接用 token 拉取音乐列表）。
     * token 失效时清除会话回到登录页；网络失败保留会话，下次启动再试。
     */
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

    /** 根据当前登录会话构造歌曲流地址；未登录时返回 null。 */
    fun streamUrl(track: Track): String? {
        val src = dataSource ?: return null
        val currentSession = session ?: return null
        return src.streamUrl(track.id, currentSession.accessToken)
    }

    /** 构造专辑封面请求；未登录或专辑无封面时返回 null（UI 显示占位色块）。 */
    fun albumCover(album: Album): JellyfinRequest? = coverRequest(album.id, album.imageTag)

    /** 构造播放列表封面请求；未登录或无封面时返回 null（UI 显示占位色块）。 */
    fun playlistCover(playlist: Playlist): JellyfinRequest? = coverRequest(playlist.id, playlist.imageTag)

    /**
     * 构造歌曲封面请求（用于底部播放条）；未登录时返回 null。
     * 封面 tag 缺失（歌曲本身无图，或后台播放回来信息丢失）时仍按歌曲 id 请求不带 tag 的图，
     * 服务器无图会返回 404，UI 显示占位块，不影响播放。
     */
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

    /** 记录播放队列与起始曲目，供底部浮窗显示当前歌曲。 */
    fun setPlaybackQueue(tracks: List<Track>, startTrackId: String) {
        val byId = tracks.associateBy { it.id }
        _playbackTracks.value = byId
        _currentTrack.value = byId[startTrackId]
    }

    /** 播放器切歌时按 mediaId 更新当前曲目；优先查队列索引，查不到（重建后队列未恢复）时从播放器条目兜底恢复。 */
    fun onMediaItemTransition(mediaItem: MediaItem?) {
        _currentTrack.value = mediaItem?.let { mi ->
            mi.mediaId?.let { _playbackTracks.value[it] } ?: mi.toRecoveredTrack()
        }
    }

    /** 从播放器当前条目恢复曲目信息：ViewModel 重建后队列索引丢失时，用条目里存的歌名/歌手/封面信息兜底。 */
    private fun MediaItem.toRecoveredTrack(): Track = Track(
        id = mediaId,
        title = mediaMetadata.title?.toString() ?: "未知歌曲",
        artist = mediaMetadata.artist?.toString() ?: "未知歌手",
        album = mediaMetadata.albumTitle?.toString().orEmpty(),
        durationMs = mediaMetadata.durationMs,
        imageTag = localConfiguration?.tag as? String
    )

    /**
     * 加载专辑列表；仅当已登录且尚未加载时执行，避免重复请求。
     * 失败时复用 [friendlyError] 设置错误信息，切回专辑页可重试。
     */
    fun loadAlbums() {
        if (_state.value.albums != null) return
        val src = dataSource ?: return
        val currentSession = session ?: return
        albumsJob?.cancel()
        val generation = ++albumsGeneration
        _state.value = _state.value.copy(isLoading = true, errorMessage = null)
        albumsJob = viewModelScope.launch {
            try {
                val albums = src.getAlbums(currentSession.userId, currentSession.accessToken).getOrThrow()
                if (coroutineContext.isActive && generation == albumsGeneration) {
                    _state.value = _state.value.copy(isLoading = false, albums = albums)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == albumsGeneration) {
                    _state.value = _state.value.copy(isLoading = false, errorMessage = friendlyError(error))
                }
            }
        }
    }

    /**
     * 加载发现页随机专辑；仅当已登录且尚未加载时执行，避免重复请求。
     * 失败时复用 [friendlyError] 设置错误信息，切回发现页可重试。
     */
    fun loadDiscoverAlbums() {
        if (_state.value.discoverAlbums != null) return
        val src = dataSource ?: return
        val currentSession = session ?: return
        albumsJob?.cancel()
        val generation = ++albumsGeneration
        _state.value = _state.value.copy(isLoading = true, errorMessage = null)
        albumsJob = viewModelScope.launch {
            try {
                val albums = src.getRandomAlbums(currentSession.userId, currentSession.accessToken).getOrThrow()
                if (coroutineContext.isActive && generation == albumsGeneration) {
                    _state.value = _state.value.copy(isLoading = false, discoverAlbums = albums)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == albumsGeneration) {
                    _state.value = _state.value.copy(isLoading = false, errorMessage = friendlyError(error))
                }
            }
        }
    }

    /**
     * 强制刷新发现页随机专辑（"换一批"）；忽略当前是否已加载，直接重新请求。
     */
    fun refreshDiscoverAlbums() {
        val src = dataSource ?: return
        val currentSession = session ?: return
        albumsJob?.cancel()
        val generation = ++albumsGeneration
        _state.value = _state.value.copy(isLoading = true, errorMessage = null)
        albumsJob = viewModelScope.launch {
            try {
                val albums = src.getRandomAlbums(currentSession.userId, currentSession.accessToken).getOrThrow()
                if (coroutineContext.isActive && generation == albumsGeneration) {
                    _state.value = _state.value.copy(isLoading = false, discoverAlbums = albums)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == albumsGeneration) {
                    _state.value = _state.value.copy(isLoading = false, errorMessage = friendlyError(error))
                }
            }
        }
    }

    /**
     * 加载某个专辑的歌曲列表并记录当前选中专辑；重复进入同一专辑不重复请求。
     */
    fun loadAlbumTracks(album: Album) {
        if (_state.value.selectedAlbum?.id == album.id && _state.value.albumTracks != null) return
        val src = dataSource ?: return
        val currentSession = session ?: return
        albumsJob?.cancel()
        val generation = ++albumsGeneration
        _state.value = _state.value.copy(
            isLoading = true,
            errorMessage = null,
            selectedAlbum = album,
            albumTracks = null
        )
        albumsJob = viewModelScope.launch {
            try {
                val tracks = src.getAlbumTracks(currentSession.userId, currentSession.accessToken, album.id).getOrThrow()
                if (coroutineContext.isActive && generation == albumsGeneration) {
                    _state.value = _state.value.copy(isLoading = false, albumTracks = tracks)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == albumsGeneration) {
                    _state.value = _state.value.copy(isLoading = false, errorMessage = friendlyError(error))
                }
            }
        }
    }

    /**
     * 加载播放列表列表；仅当已登录且尚未加载时执行，避免重复请求。
     * 失败时复用 [friendlyError] 设置错误信息，切回播放列表页可重试。
     */
    fun loadPlaylists() {
        if (_state.value.playlists != null) return
        val src = dataSource ?: return
        val currentSession = session ?: return
        albumsJob?.cancel()
        val generation = ++albumsGeneration
        _state.value = _state.value.copy(isLoading = true, errorMessage = null)
        albumsJob = viewModelScope.launch {
            try {
                val playlists = src.getPlaylists(currentSession.userId, currentSession.accessToken).getOrThrow()
                if (coroutineContext.isActive && generation == albumsGeneration) {
                    _state.value = _state.value.copy(isLoading = false, playlists = playlists)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == albumsGeneration) {
                    _state.value = _state.value.copy(isLoading = false, errorMessage = friendlyError(error))
                }
            }
        }
    }

    /** 加载某个播放列表的歌曲列表并记录当前选中播放列表；重复进入同一播放列表不重复请求。 */
    fun loadPlaylistTracks(playlist: Playlist) {
        if (_state.value.selectedPlaylist?.id == playlist.id && _state.value.playlistTracks != null) return
        val src = dataSource ?: return
        val currentSession = session ?: return
        albumsJob?.cancel()
        val generation = ++albumsGeneration
        _state.value = _state.value.copy(
            isLoading = true,
            errorMessage = null,
            selectedPlaylist = playlist,
            playlistTracks = null
        )
        albumsJob = viewModelScope.launch {
            try {
                val tracks = src.getPlaylistTracks(currentSession.userId, currentSession.accessToken, playlist.id).getOrThrow()
                if (coroutineContext.isActive && generation == albumsGeneration) {
                    _state.value = _state.value.copy(isLoading = false, playlistTracks = tracks)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == albumsGeneration) {
                    _state.value = _state.value.copy(isLoading = false, errorMessage = friendlyError(error))
                }
            }
        }
    }

    /**
     * 搜索输入：每次输入先取消上一个搜索任务，停顿 [SEARCH_DEBOUNCE_MS] 后才真正请求（防抖）。
     * 关键词清空时清掉结果；结果用 generation 校验，过期响应不覆盖新结果。
     */
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

    /**
     * 清空专辑详情，回到专辑列表。
     * albumTracks 刻意保留：详情页退场滑动动画期间仍在渲染，数据被清会让退场页闪「该专辑暂无歌曲」。
     * 重进专辑时 loadAlbumTracks 会重新加载，不会读到残留。
     */
    fun clearAlbumDetail() {
        albumsJob?.cancel()
        albumsGeneration++
        _state.value = _state.value.copy(selectedAlbum = null, errorMessage = null)
    }

    /**
     * 清空播放列表详情，回到播放列表页。
     * playlistTracks 刻意保留：详情页退场滑动动画期间仍在渲染，数据被清会让退场页闪「该播放列表暂无歌曲」。
     * 重进播放列表时 loadPlaylistTracks 会重新加载，不会读到残留。
     */
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

    private fun friendlyError(error: Throwable): String = when (error) {
        is JellyfinError.InvalidCredentials -> "用户名或密码不正确"
        is JellyfinError.Unauthorized -> "登录已失效，请重新登录"
        is JellyfinError.Network -> "无法连接服务器，请检查地址和网络"
        is JellyfinError.Http -> "服务器请求失败（${error.statusCode}）"
        is JellyfinError.InvalidResponse -> "服务器返回了无法识别的数据"
        is IllegalArgumentException -> error.message ?: "服务器地址无效"
        else -> "登录失败，请检查服务器地址和网络"
    }
}

@Composable
private fun PulsarApp(controllerFuture: ListenableFuture<MediaController>?) {
    val viewModel: JellyfinViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 使用 Pulsar 液态玻璃主题（默认浅色模式）
    PulsarTheme(darkTheme = false) {
        if (state.tracks == null) {
            LoginScreen(state, viewModel)
        } else {
            MusicLibraryScreen(
                state = state,
                viewModel = viewModel,
                controllerFuture = controllerFuture
            )
        }
    }
}

@Composable
private fun LoginScreen(state: JellyfinUiState, viewModel: JellyfinViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
            Text("连接 Jellyfin", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(state.serverUrl, viewModel::updateServerUrl, Modifier.fillMaxWidth(), label = { Text("服务器地址") }, placeholder = { Text("https://example.com 或 http://192.168.1.2:8096") }, singleLine = true)
            OutlinedTextField(state.username, viewModel::updateUsername, Modifier.fillMaxWidth(), label = { Text("用户名") }, singleLine = true)
            OutlinedTextField(state.password, viewModel::updatePassword, Modifier.fillMaxWidth(), label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = viewModel::login,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading
            ) {
                if (state.isLoading) CircularProgressIndicator(strokeWidth = 2.dp) else Text("登录")
        }
    }
}

/** 音乐库页面的本地导航状态：全部歌曲 / 专辑列表 / 专辑详情 / 播放列表 / 播放列表详情。 */
private sealed interface LibrarySection {
    data object Discover : LibrarySection
    data object Tracks : LibrarySection
    data object Albums : LibrarySection
    data object Playlists : LibrarySection
    data object Search : LibrarySection
    data object Settings : LibrarySection
    data class AlbumDetail(val album: Album) : LibrarySection
    data class PlaylistDetail(val playlist: Playlist) : LibrarySection
}

/** 搜索防抖时长：停顿这么久才真正请求服务器，避免每敲一个字都打一次接口。 */
private const val SEARCH_DEBOUNCE_MS = 300L

/** 是否为详情页（专辑 / 播放列表 / 设置）：详情页隐藏顶部玻璃岛、用系统返回键返回。 */
private fun LibrarySection.isDetail(): Boolean =
    this is LibrarySection.AlbumDetail || this is LibrarySection.PlaylistDetail || this is LibrarySection.Settings

/** 顶部玻璃岛的固定占位高度：岛本身 56dp + 上下外边距各 8dp；状态栏高度由各列表的 statusBars inset 另算。 */
private val GlassIslandTopSpacing = 72.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicLibraryScreen(
    state: JellyfinUiState,
    viewModel: JellyfinViewModel,
    controllerFuture: ListenableFuture<MediaController>?
) {
    val controllerState = rememberMediaController(controllerFuture)
    // 播放模式：顺序循环 → 单曲循环 → 随机播放，点按钮循环切换。
    var playbackMode by remember { mutableStateOf(PlaybackMode.SEQUENCE) }
    val localAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        controllerState.controller?.apply { setMediaItem(MediaItem.fromUri(uri)); prepare(); play() }
    }
    // 本地导航：Discover / Tracks / Albums / AlbumDetail，不引入导航库，直接按 state 分支切换内容。
    // 主页默认显示发现页（随机专辑），其余分区通过顶部导航切换。
    var section by remember { mutableStateOf<LibrarySection>(LibrarySection.Discover) }
    // 详情页来源：从列表页还是搜索页进的详情，返回时回到对应来源。
    var detailOrigin by remember { mutableStateOf<LibrarySection>(LibrarySection.Discover) }
    // 统一打开详情入口：打开前记下当前分区，返回键/侧滑能回到原页面（含搜索页）。
    val openAlbum: (Album) -> Unit = { album ->
        detailOrigin = section
        section = LibrarySection.AlbumDetail(album)
    }
    val openPlaylist: (Playlist) -> Unit = { playlist ->
        detailOrigin = section
        section = LibrarySection.PlaylistDetail(playlist)
    }
    // 进入对应分区时懒加载数据；load 方法幂等，重复进入不会重复请求。
    LaunchedEffect(section) {
        when (val s = section) {
            LibrarySection.Discover -> viewModel.loadDiscoverAlbums()
            LibrarySection.Tracks -> Unit
            LibrarySection.Albums -> viewModel.loadAlbums()
            LibrarySection.Playlists -> viewModel.loadPlaylists()
            LibrarySection.Search -> Unit
            LibrarySection.Settings -> Unit
            is LibrarySection.AlbumDetail -> viewModel.loadAlbumTracks(s.album)
            is LibrarySection.PlaylistDetail -> viewModel.loadPlaylistTracks(s.playlist)
        }
    }
    val tracks = state.tracks.orEmpty()
    // 当前播放曲目：放 ViewModel，Activity 从后台回来被重建时信息不丢。
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    DisposableEffect(controllerState.controller) {
        val controller = controllerState.controller ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                viewModel.onMediaItemTransition(mediaItem)
            }
        }
        controller.addListener(listener)
        // 后台播放回来重连时，可能已有正在播放的曲目且不会再触发 transition，主动恢复一次。
        viewModel.onMediaItemTransition(controller.currentMediaItem)
        onDispose { controller.removeListener(listener) }
    }
    // 统一播放入口：记录当前曲目后交给 playTracks。未连接播放服务时直接忽略。
    val onPlayTrack: (List<Track>, String) -> Unit = { trackList, trackId ->
        val controller = controllerState.controller
        if (controller != null) {
            viewModel.setPlaybackQueue(trackList, trackId)
            playTracks(trackList, trackId, controller, playbackMode, viewModel::streamUrl)
        }
    }
    // 添加到播放队列：复用 playTracks 的 MediaItem 构建逻辑，把单曲插到当前曲目之后。
    // 队列为空时直接设为唯一曲目并自动播放；否则插在 currentIndex + 1 处，不打断当前播放。
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val onAddToQueue: (Track) -> Unit = { track ->
        val ctrl = controllerState.controller
        if (ctrl != null) {
            val url = viewModel.streamUrl(track)
            if (url != null) {
                val mediaItem = MediaItem.Builder()
                    .setUri(url)
                    .setMediaId(track.id)
                    .setTag(track.imageTag)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .setAlbumTitle(track.album)
                            .build()
                    )
                    .build()
                if (ctrl.mediaItemCount == 0) {
                    ctrl.setMediaItem(mediaItem)
                    ctrl.prepare()
                    ctrl.play()
                } else {
                    val insertIndex = (ctrl.currentMediaItemIndex + 1).coerceAtMost(ctrl.mediaItemCount)
                    ctrl.addMediaItem(insertIndex, mediaItem)
                }
                snackbarScope.launch { snackbarHostState.showSnackbar("已添加到播放队列") }
            }
        }
    }
    // 系统返回键 + 侧滑手势：详情页返回来源页（列表页或搜索页）；非默认页签（全部音乐/专辑/播放列表/搜索）
    // 先回到默认的发现页；只有已在发现页时按返回才走系统默认行为（退出应用）。
    BackHandler(section != LibrarySection.Discover) {
        when (section) {
            is LibrarySection.AlbumDetail -> {
                viewModel.clearAlbumDetail()
                section = detailOrigin
            }
            is LibrarySection.PlaylistDetail -> {
                viewModel.clearPlaylistDetail()
                section = detailOrigin
            }
            LibrarySection.Settings -> section = detailOrigin
            else -> section = LibrarySection.Discover
        }
    }
    // 背景渐变：液态玻璃风格，顶部浅紫蓝渐变到底部白色，营造空气感和深度。
    val backgroundBrush = Brush.verticalGradient(
        0f to Color(0xFFE0E7FF), // 极浅靛蓝（primaryContainer）
        0.3f to Color(0xFFF1F5F9), // 极浅灰蓝
        1f to Color(0xFFF8FAFC)    // 主背景色
    )
    // 悬浮布局：内容铺满全屏，顶部栏 / 底部播放条是浮在内容上方的玻璃条。
    Box(Modifier.fillMaxSize().background(backgroundBrush)) {
        val backdrop = rememberLayerBackdrop {
            drawRect(brush = backgroundBrush)
            drawContent()
        }
        // 内容全屏，并记录进 backdrop 层（玻璃条才有东西可模糊）。
        Box(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .layerBackdrop(backdrop)
        ) {
            // 页面切换动画：进详情页从右侧推入、返回从左侧退回；列表页之间同款轻量滑动。
            AnimatedContent(
                targetState = section,
                transitionSpec = {
                    val enteringDetail = targetState.isDetail()
                    val enter = slideInHorizontally(tween(320)) { width ->
                        if (enteringDetail) width else -width / 3
                    } + fadeIn(tween(320))
                    val exit = slideOutHorizontally(tween(320)) { width ->
                        if (enteringDetail) -width / 3 else width
                    } + fadeOut(tween(320))
                    enter.togetherWith(exit)
                },
                label = "librarySection"
            ) { s ->
                when (s) {
                    LibrarySection.Discover -> DiscoverContent(
                        state = state,
                        onOpenAlbum = openAlbum,
                        coverFor = viewModel::albumCover,
                        onRefresh = viewModel::refreshDiscoverAlbums,
                        modifier = Modifier.fillMaxSize()
                    )
                    LibrarySection.Tracks -> TracksContent(
                        state = state,
                        tracks = tracks,
                        controllerState = controllerState,
                        coverFor = viewModel::trackCover,
                        onLocalAudio = { localAudioLauncher.launch(arrayOf("audio/*")) },
                        onPlayTrack = onPlayTrack,
                        onAddToQueue = onAddToQueue,
                        modifier = Modifier.fillMaxSize()
                    )
                    LibrarySection.Albums -> AlbumsContent(
                        state = state,
                        onOpenAlbum = openAlbum,
                        coverFor = viewModel::albumCover,
                        modifier = Modifier.fillMaxSize()
                    )
                    LibrarySection.Playlists -> PlaylistsContent(
                        state = state,
                        onOpenPlaylist = openPlaylist,
                        coverFor = viewModel::playlistCover,
                        modifier = Modifier.fillMaxSize()
                    )
                    LibrarySection.Search -> SearchContent(
                        state = state,
                        trackCoverFor = viewModel::trackCover,
                        albumCoverFor = viewModel::albumCover,
                        playlistCoverFor = viewModel::playlistCover,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onPlayTrack = onPlayTrack,
                        onAddToQueue = onAddToQueue,
                        onOpenAlbum = openAlbum,
                        onOpenPlaylist = openPlaylist,
                        modifier = Modifier.fillMaxSize()
                    )
                    is LibrarySection.AlbumDetail -> AlbumDetailContent(
                        state = state,
                        album = s.album,
                        coverFor = viewModel::trackCover,
                        onPlayTrack = onPlayTrack,
                        onAddToQueue = onAddToQueue,
                        albumCoverFor = viewModel::albumCover,
                        modifier = Modifier.fillMaxSize()
                    )
                    is LibrarySection.PlaylistDetail -> PlaylistDetailContent(
                        state = state,
                        playlist = s.playlist,
                        coverFor = viewModel::trackCover,
                        onPlayTrack = onPlayTrack,
                        onAddToQueue = onAddToQueue,
                        playlistCoverFor = viewModel::playlistCover,
                        modifier = Modifier.fillMaxSize()
                    )
                    LibrarySection.Settings -> SettingsContent(
                        controllerState = controllerState,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        // 顶部玻璃岛（悬浮）：列表页显示；详情页隐藏（返回交给系统返回键 / 侧滑手势），上滑淡出。
        AnimatedVisibility(
            visible = !section.isDetail(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
            enter = slideInVertically(tween(300)) { -it } + fadeIn(tween(300)),
            exit = slideOutVertically(tween(300)) { -it } + fadeOut(tween(300))
        ) {
            GlassChipBar(
                backdrop = backdrop,
                section = section,
                onShowDiscover = { section = LibrarySection.Discover },
                onShowTracks = { section = LibrarySection.Tracks },
                onShowAlbums = { section = LibrarySection.Albums },
                onShowPlaylists = { section = LibrarySection.Playlists },
                onShowSearch = { section = LibrarySection.Search },
                onShowSettings = {
                    detailOrigin = section
                    section = LibrarySection.Settings
                },
                onSignOut = viewModel::signOut,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        // 底部玻璃播放条（悬浮）：四角全圆角，完整液态玻璃（模糊 + 透镜 + 高光）。
        MiniPlayerBar(
            backdrop = backdrop,
            controllerState = controllerState,
            playbackMode = playbackMode,
            onPlaybackModeChange = { mode ->
                playbackMode = mode
                controllerState.controller?.applyPlaybackMode(mode)
            },
            currentTrack = currentTrack,
            coverFor = viewModel::trackCover,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
        // Snackbar 提示（悬浮在播放条上方，显示「已添加到播放队列」反馈）。
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
                .padding(horizontal = 12.dp)
        )
    }
}

/**
 * 顶部玻璃岛：悬浮的液态玻璃面板（与播放条同款：四角圆角 + 模糊 + 透镜 + 高光），
 * 装「发现 / 全部音乐 / 专辑 / 播放列表」与搜索 / 设置 / 退出按钮。
 * chip 分组可横向滑动（分区多了会挤不下），滑动区域两侧有渐变提示，选中项切换时自动滚入可见区。
 */
@Composable
private fun GlassChipBar(
    backdrop: Backdrop,
    section: LibrarySection,
    onShowDiscover: () -> Unit,
    onShowTracks: () -> Unit,
    onShowAlbums: () -> Unit,
    onShowPlaylists: () -> Unit,
    onShowSearch: () -> Unit,
    onShowSettings: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 可读性表面色：非组合的 onDrawSurface 里不能读 MaterialTheme，先取出来。
    val glassSurface = MaterialTheme.colorScheme.surface
    val chipScrollState = rememberScrollState()
    // 每个 chip 的可见区请求器：选中项切换时用它把自己滚入可见范围，避免切到被滑出屏幕的分区看不出当前在哪。
    val discoverRequester = remember { BringIntoViewRequester() }
    val tracksRequester = remember { BringIntoViewRequester() }
    val albumsRequester = remember { BringIntoViewRequester() }
    val playlistsRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(section) {
        when (section) {
            LibrarySection.Discover -> discoverRequester.bringIntoView()
            LibrarySection.Tracks -> tracksRequester.bringIntoView()
            LibrarySection.Albums -> albumsRequester.bringIntoView()
            LibrarySection.Playlists -> playlistsRequester.bringIntoView()
            else -> Unit
        }
    }
    Row(
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(20.dp) },
                effects = {
                    vibrancy()
                    blur(16f.dp.toPx())
                    lens(14f.dp.toPx(), 28f.dp.toPx())
                },
                onDrawSurface = { drawRect(glassSurface.copy(alpha = 0.8f)) } // 提高不透明度到 80%
            )
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // chip 分组可横向滑动的区域：两侧叠加渐变遮罩提示"还能划"，避免用户以为只有这几个按钮。
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .horizontalScroll(chipScrollState),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = section == LibrarySection.Discover,
                    onClick = onShowDiscover,
                    label = { Text("发现", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = glassChipColors(selected = section == LibrarySection.Discover),
                    border = glassChipBorder(),
                    modifier = Modifier.bringIntoViewRequester(discoverRequester)
                )
                FilterChip(
                    selected = section == LibrarySection.Tracks,
                    onClick = onShowTracks,
                    label = { Text("全部音乐", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = glassChipColors(selected = section == LibrarySection.Tracks),
                    border = glassChipBorder(),
                    modifier = Modifier.bringIntoViewRequester(tracksRequester)
                )
                FilterChip(
                    selected = section == LibrarySection.Albums,
                    onClick = onShowAlbums,
                    label = { Text("专辑", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = glassChipColors(selected = section == LibrarySection.Albums),
                    border = glassChipBorder(),
                    modifier = Modifier.bringIntoViewRequester(albumsRequester)
                )
                FilterChip(
                    selected = section == LibrarySection.Playlists,
                    onClick = onShowPlaylists,
                    label = { Text("播放列表", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = glassChipColors(selected = section == LibrarySection.Playlists),
                    border = glassChipBorder(),
                    modifier = Modifier.bringIntoViewRequester(playlistsRequester)
                )
            }
            // 左侧渐变遮罩：还能往左滑时显示。
            if (chipScrollState.canScrollBackward) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(16.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(glassSurface.copy(alpha = 0.8f), glassSurface.copy(alpha = 0f))
                            )
                        )
                )
            }
            // 右侧渐变遮罩：还能往右滑时显示。
            if (chipScrollState.canScrollForward) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(16.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(glassSurface.copy(alpha = 0f), glassSurface.copy(alpha = 0.8f))
                            )
                        )
                )
            }
        }
        // 搜索：放大镜图标，进入搜索页时高亮为品牌色。
        IconButton(
            onClick = onShowSearch,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = "搜索音乐",
                tint = if (section == LibrarySection.Search) {
                    MaterialTheme.colorScheme.primary
                } else {
                    LocalContentColor.current
                }
            )
        }
        IconButton(
            onClick = onShowSettings,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "设置",
                tint = if (section == LibrarySection.Settings) {
                    MaterialTheme.colorScheme.primary
                } else {
                    LocalContentColor.current
                }
            )
        }
        IconButton(
            onClick = onSignOut,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Filled.ExitToApp, contentDescription = "退出登录")
        }
    }
}

/** 玻璃切换按钮配色：选中实心主题色，未选中半透明表面。 */
@Composable
private fun glassChipColors(selected: Boolean) = FilterChipDefaults.filterChipColors(
    containerColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
    },
    labelColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
)

/** 玻璃切换按钮细描边。 */
@Composable
private fun glassChipBorder() = BorderStroke(
    1.dp,
    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
)

@Composable
private fun TracksContent(
    state: JellyfinUiState,
    tracks: List<Track>,
    controllerState: ControllerState,
    coverFor: (Track) -> JellyfinRequest?,
    onLocalAudio: () -> Unit,
    onPlayTrack: (List<Track>, String) -> Unit,
    onAddToQueue: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        // 顶部让出状态栏 + 玻璃岛，底部让出悬浮播放条；状态栏随设备动态，玻璃岛用固定占位。
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = GlassIslandTopSpacing, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item { Text("你的音乐", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
        item { Button(onClick = onLocalAudio, Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), enabled = controllerState.controller != null) { Text("选择手机里的音频") } }
        if (tracks.isEmpty()) item { Text("暂无音乐") }
        itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
            TrackRow(
                track = track,
                cover = coverFor(track),
                onClick = { onPlayTrack(tracks, track.id) },
                onAddToQueue = { onAddToQueue(track) },
                showDivider = index != tracks.lastIndex,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 单曲行：小封面 + 标题（大）+ 作者（灰色小字）+ 底部灰色分隔线。取消卡片形式，列表更紧凑。
 * 长按弹出上下文菜单（弹性缩放 + 淡入 + 锚定在长按位置，逐项错峰弹出），支持"添加到播放队列"等操作。 */
@Composable
private fun TrackRow(
    track: Track,
    cover: JellyfinRequest?,
    onClick: () -> Unit,
    showDivider: Boolean,
    onAddToQueue: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }
    var rowWindowOffset by remember { mutableStateOf(Offset.Zero) }
    var menuOffset by remember { mutableStateOf(IntOffset.Zero) }
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { localOffset ->
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            val windowOffset = rowWindowOffset + localOffset
                            menuOffset = IntOffset(windowOffset.x.toInt(), windowOffset.y.toInt())
                            showMenu = true
                        }
                    )
                }
                .onGloballyPositioned { coordinates ->
                    rowWindowOffset = coordinates.positionInWindow()
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlbumCover(cover, track.title, Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // 分隔线从封面右缘开始：封面下方不画线，行之间更干净。
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 60.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
    // 长按弹出菜单：Popup 作为独立窗口渲染，动画在菜单内容上的 graphicsLayer 进行。
    if (showMenu) {
        AddToQueueMenu(
            touchPoint = menuOffset,
            onAddToQueue = { onAddToQueue?.invoke() },
            onDismiss = { showMenu = false }
        )
    }
}

/** 长按弹出的"添加到播放队列"上下文菜单。
 * 菜单以长按行作为锚点，尽量贴近触摸位置；外围保留阴影安全区，避免动画缩放时裁切阴影。 */
@Composable
private fun AddToQueueMenu(
    touchPoint: IntOffset,
    onAddToQueue: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // 整体进入动画：弹性缩放 + 淡入。
    val menuScale = remember { Animatable(0.85f) }
    val menuAlpha = remember { Animatable(0f) }
    val shadowPaddingPx = with(LocalDensity.current) { 16.dp.toPx().toInt() }
    // 菜单项逐条错峰弹出的透明度/位移开关。
    var itemVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        menuScale.snapTo(0.85f)
        menuAlpha.snapTo(0f)
        menuScale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 320f))
        menuAlpha.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 320f))
        itemVisible = true
    }
    Popup(
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val x = (touchPoint.x - shadowPaddingPx).coerceIn(
                    0,
                    (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                )
                val y = (touchPoint.y - popupContentSize.height / 2).coerceIn(
                    0,
                    (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                )
                return IntOffset(x, y)
            }
        },
        onDismissRequest = {
            scope.launch {
                menuScale.animateTo(0.85f, tween(120))
                menuAlpha.animateTo(0f, tween(120))
                onDismiss()
            }
        },
        properties = PopupProperties(focusable = true)
    ) {
        Box(
            modifier = Modifier.padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = menuScale.value
                        scaleY = menuScale.value
                        alpha = menuAlpha.value
                    }
            ) {
                Surface(
                    modifier = Modifier.width(240.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shadowElevation = 16.dp,
                    tonalElevation = 2.dp,
                    border = BorderStroke(
                        0.5.dp,
                        Color.White.copy(alpha = 0.6f) // 浅色主题下的高光描边，玻璃质感。
                    )
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        var menuItemVisible by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { menuItemVisible = true }
                        StaggeredMenuItem(
                            visible = menuItemVisible,
                            delayMs = 0,
                            icon = { Icon(Icons.Filled.PlaylistAdd, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                            text = "添加到播放队列",
                            onClick = {
                                scope.launch {
                                    menuScale.animateTo(0.85f, tween(120))
                                    menuAlpha.animateTo(0f, tween(120))
                                    onAddToQueue()
                                    onDismiss()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 单个错峰弹出的菜单项：从下方冒出 + 淡入，图标轻微旋转汇聚。 */
@Composable
private fun StaggeredMenuItem(
    visible: Boolean,
    delayMs: Int,
    icon: @Composable () -> Unit,
    text: String,
    onClick: () -> Unit
) {
    val transition = updateTransition(visible, label = "menuItem")
    val offsetY by transition.animateDp(
        transitionSpec = { spring(dampingRatio = 0.7f, stiffness = 350f) },
        targetValueByState = { if (it) 0.dp else 12.dp },
        label = "offsetY"
    )
    val alpha by transition.animateFloat(
        transitionSpec = { tween(220, delayMillis = delayMs) },
        label = "alpha"
    ) { if (it) 1f else 0f }
    Row(
        modifier = Modifier
            .graphicsLayer { this.alpha = alpha }
            .offset(y = offsetY)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * 发现页：随机专辑网格 + 顶部标题栏（含"换一批"按钮）。首次进入时懒加载；点击换一批强制刷新。
 * 换一批请求进行中按钮转圈禁用；专辑网格换批后按批次内容做交叉淡入淡出，不硬切。
 */
@Composable
private fun DiscoverContent(
    state: JellyfinUiState,
    onOpenAlbum: (Album) -> Unit,
    coverFor: (Album) -> JellyfinRequest?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 换一批按钮转圈动画：请求进行中持续旋转，结束后停在当前角度。
    val infiniteTransition = rememberInfiniteTransition(label = "discoverRefreshRotation")
    val refreshRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label = "discoverRefreshRotationValue"
    )
    Column(
        modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = GlassIslandTopSpacing - 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("发现", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(
                onClick = onRefresh,
                enabled = !state.isLoading,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "换一批专辑",
                    modifier = if (state.isLoading) Modifier.rotate(refreshRotation) else Modifier
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val albums = state.discoverAlbums
            when {
                albums == null && state.isLoading -> {
                    // 骨架屏：2列网格，与专辑页同款占位样式。
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(6) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        width = 1.dp,
                                        color = Color(0x33FFFFFF),
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                    )
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth(0.7f)
                                                .height(16.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant,
                                                    RoundedCornerShape(4.dp)
                                                )
                                        )
                                        Box(
                                            Modifier
                                                .fillMaxWidth(0.5f)
                                                .height(14.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                                    RoundedCornerShape(4.dp)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                albums == null && state.errorMessage != null -> Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
                albums.orEmpty().isEmpty() -> Text("暂无专辑")
                else -> {
                    val albumList = albums.orEmpty()
                    // 换一批后按新批次内容淡入淡出替换，避免硬切；用本批专辑 id 拼接作为批次 key。
                    val batchKey = albumList.joinToString(",") { it.id }
                    Crossfade(targetState = batchKey, label = "discoverAlbumsBatch") {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 96.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(albumList, key = { it.id }) { album ->
                                Card(
                                    onClick = { onOpenAlbum(album) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(
                                            width = 1.dp,
                                            color = Color(0x33FFFFFF),
                                            shape = RoundedCornerShape(12.dp)
                                        ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column {
                                        AlbumCover(coverFor(album), album.name, Modifier.fillMaxWidth().aspectRatio(1f))
                                        Column(Modifier.padding(12.dp)) {
                                            Text(album.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                album.trackCount?.let { "${album.artist} · $it 首" } ?: album.artist,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumsContent(
    state: JellyfinUiState,
    onOpenAlbum: (Album) -> Unit,
    coverFor: (Album) -> JellyfinRequest?,
    modifier: Modifier = Modifier
) {
    Column(
        // 顶部只让出状态栏；玻璃岛的留位交给 grid 的 contentPadding，
        // 这样滚动时专辑卡能滚到玻璃岛底下透过去（和底部播放条同款行为）。
        modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val albums = state.albums
            when {
                albums == null && state.isLoading -> {
                    // 骨架屏：2列网格，每个卡片显示占位方块
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = GlassIslandTopSpacing, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(6) { // 显示6个骨架占位
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        width = 1.dp,
                                        color = Color(0x33FFFFFF),
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                    )
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth(0.7f)
                                                .height(16.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant,
                                                    RoundedCornerShape(4.dp)
                                                )
                                        )
                                        Box(
                                            Modifier
                                                .fillMaxWidth(0.5f)
                                                .height(14.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                                    RoundedCornerShape(4.dp)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                albums == null && state.errorMessage != null -> Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
                albums.orEmpty().isEmpty() -> Text("暂无专辑")
                else -> {
                    val albumList = albums.orEmpty()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = GlassIslandTopSpacing, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(albumList, key = { it.id }) { album ->
                            Card(
                                onClick = { onOpenAlbum(album) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        width = 1.dp,
                                        color = Color(0x33FFFFFF),
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column {
                                    AlbumCover(coverFor(album), album.name, Modifier.fillMaxWidth().aspectRatio(1f))
                                    Column(Modifier.padding(12.dp)) {
                                        Text(album.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            album.trackCount?.let { "${album.artist} · $it 首" } ?: album.artist,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumDetailContent(
    state: JellyfinUiState,
    album: Album,
    coverFor: (Track) -> JellyfinRequest?,
    onPlayTrack: (List<Track>, String) -> Unit,
    onAddToQueue: (Track) -> Unit,
    albumCoverFor: (Album) -> JellyfinRequest?,
    modifier: Modifier = Modifier
) {
    // 详情页顶部玻璃岛已隐藏：只让出状态栏高度，不用再给浮岛留位。
    Column(
        modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp).padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AlbumCover(albumCoverFor(album), album.name, Modifier.size(120.dp))
            Column {
                Text(album.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    album.trackCount?.let { "${album.artist} · $it 首" } ?: album.artist,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val albumTracks = state.albumTracks
            when {
                albumTracks == null && state.isLoading -> CircularProgressIndicator()
                albumTracks == null && state.errorMessage != null -> Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
                albumTracks.orEmpty().isEmpty() -> Text("该专辑暂无歌曲")
                else -> {
                    val trackList = albumTracks.orEmpty()
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(trackList, key = { _, t -> t.id }) { index, track ->
                            TrackRow(
                                track = track,
                                cover = coverFor(track),
                                onClick = { onPlayTrack(trackList, track.id) },
                                onAddToQueue = { onAddToQueue(track) },
                                showDivider = index != trackList.lastIndex,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistsContent(
    state: JellyfinUiState,
    onOpenPlaylist: (Playlist) -> Unit,
    coverFor: (Playlist) -> JellyfinRequest?,
    modifier: Modifier = Modifier
) {
    Column(
        // 顶部只让出状态栏；玻璃岛的留位交给 grid 的 contentPadding，
        // 这样滚动时播放列表卡能滚到玻璃岛底下透过去（和底部播放条同款行为）。
        modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val playlists = state.playlists
            when {
                playlists == null && state.isLoading -> {
                    // 骨架屏：2列网格
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = GlassIslandTopSpacing, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(6) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        width = 1.dp,
                                        color = Color(0x33FFFFFF),
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                    )
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth(0.7f)
                                                .height(16.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant,
                                                    RoundedCornerShape(4.dp)
                                                )
                                        )
                                        Box(
                                            Modifier
                                                .fillMaxWidth(0.5f)
                                                .height(14.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                                    RoundedCornerShape(4.dp)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                playlists == null && state.errorMessage != null -> Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
                playlists.orEmpty().isEmpty() -> Text("暂无播放列表")
                else -> {
                    val playlistList = playlists.orEmpty()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = GlassIslandTopSpacing, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(playlistList, key = { it.id }) { playlist ->
                            Card(
                                onClick = { onOpenPlaylist(playlist) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        width = 1.dp,
                                        color = Color(0x33FFFFFF),
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column {
                                    AlbumCover(coverFor(playlist), playlist.name, Modifier.fillMaxWidth().aspectRatio(1f))
                                    Column(Modifier.padding(12.dp)) {
                                        Text(playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            playlist.trackCount?.let { "$it 首" } ?: "播放列表",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 搜索结果页：顶部固定搜索框，下方按「歌曲 / 专辑 / 播放列表」分组展示结果；歌曲点播、专辑 / 播放列表进详情。 */
@Composable
private fun SearchContent(
    state: JellyfinUiState,
    trackCoverFor: (Track) -> JellyfinRequest?,
    albumCoverFor: (Album) -> JellyfinRequest?,
    playlistCoverFor: (Playlist) -> JellyfinRequest?,
    onSearchQueryChange: (String) -> Unit,
    onPlayTrack: (List<Track>, String) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp)
            // 让出顶部玻璃岛高度（与列表页同款占位），搜索框固定在岛正下方。
            .padding(top = GlassIslandTopSpacing),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 搜索框固定顶部：输入时 ViewModel 防抖后请求；非空时显示清除按钮。
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            placeholder = { Text("搜索歌曲、专辑、播放列表") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "搜索图标") },
            trailingIcon = if (state.searchQuery.isNotEmpty()) {
                {
                    IconButton(
                        onClick = { onSearchQueryChange("") },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "清除搜索内容")
                    }
                }
            } else {
                null
            },
            singleLine = true
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val results = state.searchResults
            val isEmptyResult = results != null &&
                results.tracks.isEmpty() && results.albums.isEmpty() && results.playlists.isEmpty()
            when {
                state.searchQuery.isBlank() -> Text(
                    "输入关键词搜索音乐库",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.isSearching -> CircularProgressIndicator()
                state.errorMessage != null -> Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
                isEmptyResult -> Text("未找到相关内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
                results == null -> Text("未找到相关内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    val searchResults = results
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (searchResults.tracks.isNotEmpty()) {
                            item { SearchSectionHeader("歌曲") }
                            itemsIndexed(searchResults.tracks, key = { _, t -> t.id }) { index, track ->
                                TrackRow(
                                    track = track,
                                    cover = trackCoverFor(track),
                                    onClick = { onPlayTrack(searchResults.tracks, track.id) },
                                    onAddToQueue = { onAddToQueue(track) },
                                    showDivider = index != searchResults.tracks.lastIndex,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        if (searchResults.albums.isNotEmpty()) {
                            item { SearchSectionHeader("专辑") }
                            itemsIndexed(searchResults.albums, key = { _, a -> a.id }) { index, album ->
                                SearchResultRow(
                                    cover = albumCoverFor(album),
                                    title = album.name,
                                    subtitle = album.trackCount?.let { "${album.artist} · $it 首" } ?: album.artist,
                                    onClick = { onOpenAlbum(album) },
                                    showDivider = index != searchResults.albums.lastIndex,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        if (searchResults.playlists.isNotEmpty()) {
                            item { SearchSectionHeader("播放列表") }
                            itemsIndexed(searchResults.playlists, key = { _, p -> p.id }) { index, playlist ->
                                SearchResultRow(
                                    cover = playlistCoverFor(playlist),
                                    title = playlist.name,
                                    subtitle = playlist.trackCount?.let { "$it 首" } ?: "播放列表",
                                    onClick = { onOpenPlaylist(playlist) },
                                    showDivider = index != searchResults.playlists.lastIndex,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 搜索结果分区标题。 */
@Composable
private fun SearchSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

/** 搜索结果里的专辑 / 播放列表行：小封面 + 名称 + 副标题，点击进详情；视觉与 TrackRow 保持一致。 */
@Composable
private fun SearchResultRow(
    cover: JellyfinRequest?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showDivider: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlbumCover(cover, title, Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 60.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun PlaylistDetailContent(
    state: JellyfinUiState,
    playlist: Playlist,
    coverFor: (Track) -> JellyfinRequest?,
    onPlayTrack: (List<Track>, String) -> Unit,
    onAddToQueue: (Track) -> Unit,
    playlistCoverFor: (Playlist) -> JellyfinRequest?,
    modifier: Modifier = Modifier
) {
    // 详情页顶部玻璃岛已隐藏：只让出状态栏高度，不用再给浮岛留位。
    Column(
        modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp).padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AlbumCover(playlistCoverFor(playlist), playlist.name, Modifier.size(120.dp))
            Column {
                Text(playlist.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    playlist.trackCount?.let { "$it 首" } ?: "播放列表",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val playlistTracks = state.playlistTracks
            when {
                playlistTracks == null && state.isLoading -> CircularProgressIndicator()
                playlistTracks == null && state.errorMessage != null -> Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
                playlistTracks.orEmpty().isEmpty() -> Text("该播放列表暂无歌曲")
                else -> {
                    val trackList = playlistTracks.orEmpty()
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(trackList, key = { _, t -> t.id }) { index, track ->
                            TrackRow(
                                track = track,
                                cover = coverFor(track),
                                onClick = { onPlayTrack(trackList, track.id) },
                                onAddToQueue = { onAddToQueue(track) },
                                showDivider = index != trackList.lastIndex,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 设置页：USB 独占输出、静音跳曲、定时关闭。控件状态与回调都来自 [ControllerState]，与播放条共用同一份数据源。 */
@Composable
private fun SettingsContent(
    controllerState: ControllerState,
    modifier: Modifier = Modifier
) {
    val controller = controllerState.controller
    // USB 音量滑块本地拖拽值：拖拽中用本地值，松手才发 SET_VOLUME；服务确认值
    // 变化时（且非拖拽中）同步回本地，避免滑块跳动。
    var usbVolumeLocal by remember { mutableStateOf(controllerState.usbVolumePercent) }
    var usbVolumeDragging by remember { mutableStateOf(false) }
    LaunchedEffect(controllerState.usbVolumePercent) {
        if (!usbVolumeDragging) usbVolumeLocal = controllerState.usbVolumePercent
    }
    // 定时关闭：进程级单例状态（剩余时间/是否启用），拖动时用本地预览值不跟随倒计时。
    val sleepState by SleepTimer.state.collectAsStateWithLifecycle()
    var sleepSliderMinutes by remember { mutableStateOf(0f) }
    var isSleepDragging by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
        // USB 独占开关：与普通系统 USB 路由信息分开显示。
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("USB 独占输出", style = MaterialTheme.typography.bodyMedium)
                Text(
                    usbExclusiveStateLabel(controllerState),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = controllerState.usbExclusiveEnabled,
                onCheckedChange = controllerState.onUsbExclusiveChange,
                enabled = controller != null && !controllerState.usbExclusivePending
            )
        }
        // USB 独占输出音量：独占路径绕过系统音量、满刻度直送会削顶，
        // 提供 0..100 数字衰减（100 = bit-perfect 原样直送）。
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "USB 音量",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(88.dp)
            )
            Slider(
                value = usbVolumeLocal.toFloat(),
                onValueChange = {
                    usbVolumeLocal = it.roundToInt()
                    usbVolumeDragging = true
                },
                onValueChangeFinished = {
                    controllerState.onUsbVolumeChange(usbVolumeLocal)
                    usbVolumeDragging = false
                },
                valueRange = 0f..100f,
                enabled = controllerState.usbExclusiveEnabled && controller != null,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "$usbVolumeLocal%",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(52.dp)
            )
        }
        // 静音跳曲开关：检测音频尾部静音并自动跳到下一首。
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("静音跳曲", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (controllerState.silenceSkipEnabled) "已开启" else "已关闭",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = controllerState.silenceSkipEnabled,
                onCheckedChange = controllerState.onSilenceSkipChange,
                enabled = controller != null
            )
        }
        // 定时关闭：拖动滑块设置分钟数，松手才真正启动/取消定时器。
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("定时关闭", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(12.dp))
            Slider(
                value = when {
                    isSleepDragging -> sleepSliderMinutes
                    sleepState.isActive -> (sleepState.remainingMs / 60_000f).coerceIn(0f, 60f)
                    else -> 0f
                },
                onValueChange = { sleepSliderMinutes = it; isSleepDragging = true },
                onValueChangeFinished = {
                    val minutes = sleepSliderMinutes.roundToInt()
                    if (minutes <= 0) SleepTimer.cancel() else SleepTimer.start(minutes * 60_000L)
                    isSleepDragging = false
                },
                valueRange = 0f..60f,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = when {
                    isSleepDragging -> "约 ${sleepSliderMinutes.roundToInt()} 分钟"
                    sleepState.isActive -> "剩余 ${formatPosition(sleepState.remainingMs)}"
                    else -> "未开启"
                },
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/** 播放模式对应的图标：顺序循环 / 单曲循环 / 随机播放。 */
private fun playbackModeIcon(mode: PlaybackMode): ImageVector = when (mode) {
    PlaybackMode.SEQUENCE -> Icons.Filled.Repeat
    PlaybackMode.REPEAT_ONE -> Icons.Filled.RepeatOne
    PlaybackMode.SHUFFLE -> Icons.Filled.Shuffle
}

/** 输出信息一行文案：走 USB 小尾巴还是系统默认 + 采样率 + 位深；未播放时显示"未播放"。 */
private fun outputLine(info: PlaybackOutputInfo): String {
    val sample = info.sampleRateHz ?: return "输出：未播放"
    val device = if (info.viaUsb) "USB 小尾巴" else "系统默认"
    val rate = if (sample % 1000 == 0) "${sample / 1000} kHz" else String.format("%.1f kHz", sample / 1000.0)
    val bit = info.encoding?.let { " · ${encodingLabel(it)}" }.orEmpty()
    return "输出：$device · $rate$bit"
}

/** 音频管线实际编码的友好名（C.ENCODING_PCM_*）。 */
private fun encodingLabel(encoding: Int): String = when (encoding) {
    C.ENCODING_PCM_16BIT -> "16-bit"
    C.ENCODING_PCM_24BIT -> "24-bit"
    C.ENCODING_PCM_32BIT -> "32-bit"
    C.ENCODING_PCM_FLOAT -> "float32"
    else -> "编码$encoding"
}

/** 底部播放浮岛：折叠时显示封面 + 歌名 + 播放/暂停 + 下一首；点击展开完整控制面板。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MiniPlayerBar(
    backdrop: Backdrop,
    controllerState: ControllerState,
    playbackMode: PlaybackMode,
    onPlaybackModeChange: (PlaybackMode) -> Unit,
    currentTrack: Track?,
    coverFor: (Track) -> JellyfinRequest?,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val controller = controllerState.controller
    val hasMedia = controller != null && controllerState.durationMs > 0
    // 播放队列：本地顺序（拖拽时优先），松手才同步回播放器；默认折叠避免歌多时占满面板。
    val hapticFeedback = LocalHapticFeedback.current
    var queueExpanded by remember { mutableStateOf(false) }
    var isQueueDragging by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf(controllerState.queue) }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        queue = queue.toMutableList().apply { add(to.index, removeAt(from.index)) }
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    // 播放器快照变化时刷新本地队列；拖拽进行中不覆盖本地顺序，避免排序时列表跳动。
    LaunchedEffect(controllerState.queue) {
        if (!isQueueDragging) queue = controllerState.queue
    }
    // 输出信息（设备 + 采样率），由 PlaybackService 写入诊断单例。
    val outputInfo by PlaybackDiagnostics.info.collectAsStateWithLifecycle()
    // 可读性表面色：非组合的 onDrawSurface 里不能读 MaterialTheme，先取出来。
    val glassSurface = MaterialTheme.colorScheme.surface

    // 液态玻璃容器：模糊 + 透镜 + 高光（库自带 highlight/shadow），半透明表面保证可读性。
    Box(
        modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(20.dp) },
                effects = {
                    vibrancy()
                    blur(16f.dp.toPx())
                    lens(14f.dp.toPx(), 28f.dp.toPx())
                },
                onDrawSurface = { drawRect(glassSurface.copy(alpha = 0.55f)) }
            )
    ) {
        Column {
            // 迷你条（始终可见）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 连接上播放服务即可展开，方便未播放时也能调整播放模式 / USB 输出开关。
                    .clickable(enabled = controller != null) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 专辑封面
                if (currentTrack != null) {
                    AlbumCover(
                        coverFor(currentTrack),
                        currentTrack.title,
                        Modifier.size(40.dp)
                    )
                } else {
                    Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                }
                Spacer(Modifier.width(12.dp))
                // 歌名 · 歌手
                Column(Modifier.weight(1f)) {
                    Text(
                        text = currentTrack?.title ?: "未选择歌曲",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    currentTrack?.let { t ->
                        Text(
                            text = t.artist,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 上一首
                IconButton(
                    onClick = { controller?.seekToPrevious() },
                    enabled = controller != null,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "播放上一首歌曲")
                }
                // 播放/暂停
                IconButton(
                    onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                    enabled = controller != null,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        if (controllerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (controllerState.isPlaying) "暂停播放" else "开始播放"
                    )
                }
                // 下一首
                IconButton(
                    onClick = { controller?.seekToNext() },
                    enabled = controller != null,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "播放下一首歌曲")
                }
                // 展开/收起（more = 展开更多内容，less = 收起更少内容）
                IconButton(
                    onClick = { expanded = !expanded },
                    enabled = controller != null,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                        contentDescription = if (expanded) "收起播放控制面板" else "展开播放控制面板"
                    )
                }
            }

            // 展开面板（完整控制）
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        // 面板内容（含播放队列）超出屏幕时整体可滚动，避免底部浮岛被裁掉。
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 进度条 + 时间
                    val durationSec = (controllerState.durationMs / 1000f).coerceAtLeast(1f)
                    var sliderPosition by remember { mutableStateOf(0f) }
                    var isDragging by remember { mutableStateOf(false) }
                    Slider(
                        value = if (isDragging) sliderPosition else (controllerState.positionMs / 1000f).coerceIn(0f, durationSec),
                        onValueChange = { sliderPosition = it; isDragging = true },
                        onValueChangeFinished = {
                            controller?.seekTo((sliderPosition * 1000).toLong())
                            isDragging = false
                        },
                        valueRange = 0f..durationSec,
                        enabled = hasMedia,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatPosition(controllerState.positionMs), style = MaterialTheme.typography.labelSmall)
                        Text(formatPosition(controllerState.durationMs), style = MaterialTheme.typography.labelSmall)
                    }
                    // 播放模式：图标切换（顺序循环 / 单曲循环 / 随机播放）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(
                            onClick = { onPlaybackModeChange(playbackMode.next()) },
                            enabled = controller != null,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                playbackModeIcon(playbackMode),
                                contentDescription = "切换播放模式，当前：${playbackMode.label}",
                                tint = if (controller != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // 输出信息：走 USB 小尾巴还是系统默认 + 当前采样率。USB 独占 / 静音跳曲 / 定时关闭已移至设置页。
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = outputLine(outputInfo),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    // 播放队列：默认折叠（歌多时占地方）；点开可长按拖动排序、移除歌曲，点歌跳转。
                    // 展开/收起箭头旋转动画：收起朝下、展开转 180° 朝上。
                    val queueChevronRotation by animateFloatAsState(
                        targetValue = if (queueExpanded) 180f else 0f,
                        label = "queueChevronRotation"
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clickable { queueExpanded = !queueExpanded }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "播放队列（${queue.size} 首）",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = if (queueExpanded) "收起播放队列" else "展开播放队列",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.rotate(queueChevronRotation)
                        )
                    }
                    // 展开/收起动画：高度从 0 生长到内容高度 + 淡入淡出；队列长也只占固定高度，其余靠列表自己滚。
                    AnimatedVisibility(
                        visible = queueExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        if (queue.isEmpty()) {
                            Text(
                                text = "暂无队列：从「全部音乐 / 专辑 / 播放列表」里点一首歌开始",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            // 限高 + 内部滚动：队列再长也只占固定高度，其余靠列表自己滚。
                            LazyColumn(
                                state = lazyListState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .wrapContentHeight()
                            ) {
                                items(queue, key = { it.id }) { item ->
                                    ReorderableItem(reorderableState, key = item.id) { dragging ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (dragging) MaterialTheme.colorScheme.secondaryContainer
                                                    else Color.Transparent,
                                                    RoundedCornerShape(8.dp)
                                                )
                                                // 长按整行拖动排序；松手后把新顺序同步给播放器。
                                                .longPressDraggableHandle(
                                                    onDragStarted = { isQueueDragging = true },
                                                    onDragStopped = {
                                                        isQueueDragging = false
                                                        controller?.applyQueueOrder(queue)
                                                    }
                                                )
                                                .clickable(enabled = controller != null) {
                                                    val index = queue.indexOfFirst { it.id == item.id }
                                                    controller?.seekToDefaultPosition(index)
                                                    controller?.play()
                                                }
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(8.dp)
                                                    .background(
                                                        if (item.isCurrent) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.surfaceVariant,
                                                        CircleShape
                                                    )
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    text = item.title,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = if (item.isCurrent) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurface
                                                    },
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = item.artist,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    controller?.removeMediaItem(queue.indexOfFirst { it.id == item.id })
                                                },
                                                enabled = controller != null,
                                                modifier = Modifier.size(40.dp)
                                            ) {
                                                Icon(Icons.Filled.Close, contentDescription = "从队列中移除此歌曲", Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 专辑封面：无请求（未登录或无封面）或加载失败时显示灰色占位块，不影响列表。
 * 封面底部叠加一个微渐变层，增加视觉深度和质感。
 */
@Composable
private fun AlbumCover(
    request: JellyfinRequest?,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    if (request == null) {
        Box(
            modifier
                .clip(shape)
                .shadow(2.dp, shape, ambientColor = Color(0x1A000000), spotColor = Color(0x1A000000))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        return
    }
    val model = ImageRequest.Builder(LocalContext.current)
        .data(request.url)
        .addHeader("Authorization", request.headers["Authorization"].orEmpty())
        .crossfade(true)
        .build()
    Box(modifier.clip(shape).shadow(3.dp, shape, ambientColor = Color(0x26000000), spotColor = Color(0x26000000))) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            placeholder = placeholder,
            error = placeholder,
            modifier = Modifier.fillMaxSize()
        )
        // 底部微渐变：营造封面嵌入感，提升视觉层次
        Box(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color(0x08000000)
                    )
                )
        )
    }
}

/** 把 [tracks] 以 [startTrackId] 为开头旋转成播放队列后交给 [controller]；任一歌曲无流地址则放弃播放。 */
private fun playTracks(
    tracks: List<Track>,
    startTrackId: String,
    controller: MediaController,
    playbackMode: PlaybackMode,
    streamUrlFor: (Track) -> String?
) {
    val order = buildPlaybackOrder(tracks, startTrackId) ?: return
    val mediaItems = order.map { t ->
        val url = streamUrlFor(t) ?: return
        // 把封面 tag 挂到条目上：Activity 被系统回收重建后，仍可从播放器读回封面信息。
        MediaItem.Builder()
            .setUri(url)
            .setMediaId(t.id)
            .setTag(t.imageTag)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle(t.album)
                    .build()
            )
            .build()
    }
    // 一次性监听本次加载失败，避免失败时应用崩溃；成功就绪后自动移除，防止重复累积。
    val errorListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            controller.removeListener(this)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                controller.removeListener(this)
            }
        }
    }
    controller.addListener(errorListener)
    controller.applyPlaybackMode(playbackMode)
    controller.setMediaItems(mediaItems)
    controller.prepare()
    controller.play()
}

/** 播放器当前队列中的一首歌（从播放器真实队列快照而来），含是否为当前播放项。 */
private data class QueueTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val imageTag: String?,
    val isCurrent: Boolean
)

private data class ControllerState(
    val controller: MediaController?,
    val status: String,
    val positionMs: Long = 0L,
    val durationMs: Long = C.TIME_UNSET,
    val isPlaying: Boolean = false,
    val queue: List<QueueTrack> = emptyList(),
    val usbExclusiveEnabled: Boolean = false,
    val usbExclusiveState: String = "disabled",
    val usbExclusiveError: String? = null,
    val usbExclusivePending: Boolean = false,
    val usbVolumePercent: Int = 100,
    val silenceSkipEnabled: Boolean = true,
    val onUsbExclusiveChange: (Boolean) -> Unit = {},
    val onUsbVolumeChange: (Int) -> Unit = {},
    val onSilenceSkipChange: (Boolean) -> Unit = {}
)

/** 读取播放器当前真实队列的快照（含当前播放项标记）。 */
private fun MediaController.queueSnapshot(): List<QueueTrack> {
    val count = mediaItemCount
    val currentIndex = currentMediaItemIndex
    if (count <= 0) return emptyList()
    return List(count) { index ->
        val item = getMediaItemAt(index)
        val metadata = item.mediaMetadata
        QueueTrack(
            id = item.mediaId.orEmpty(),
            title = metadata.title?.toString() ?: "未知歌曲",
            artist = metadata.artist?.toString() ?: "未知歌手",
            album = metadata.albumTitle?.toString().orEmpty(),
            imageTag = item.localConfiguration?.tag as? String,
            isCurrent = index == currentIndex
        )
    }
}

@Composable
private fun rememberMediaController(controllerFuture: ListenableFuture<MediaController>?): ControllerState {
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var status by remember { mutableStateOf("正在连接播放服务…") }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(C.TIME_UNSET) }
    var isPlaying by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf<List<QueueTrack>>(emptyList()) }
    var usbExclusiveEnabled by remember { mutableStateOf(false) }
    var usbExclusiveState by remember { mutableStateOf("disabled") }
    var usbExclusiveError by remember { mutableStateOf<String?>(null) }
    var usbExclusivePending by remember { mutableStateOf(false) }
    var usbVolumePercent by remember { mutableStateOf(100) }
    var silenceSkipEnabled by remember { mutableStateOf(true) }
    DisposableEffect(controllerFuture) {
        if (controllerFuture == null) { status = "播放服务不可用"; onDispose { } } else {
            var isActive = true
            val listener = Runnable { if (isActive) runCatching { controller = controllerFuture.get(); status = controllerStatus(controller) }.onFailure { status = "播放服务连接失败" } }
            controllerFuture.addListener(listener) { it.run() }
            onDispose { isActive = false; controller = null }
        }
    }
    DisposableEffect(controller) {
        val current = controller
        if (current == null) onDispose { } else {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing; status = controllerStatus(current) }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { status = controllerStatus(current) }
                override fun onPlaybackStateChanged(playbackState: Int) { status = controllerStatus(current) }
            }
            current.addListener(listener)
            // 连接后立即同步播放状态，避免图标与播放器不一致（如后台回来正在播放却显示播放箭头）。
            isPlaying = current.isPlaying
            status = controllerStatus(current)
            onDispose { current.removeListener(listener) }
        }
    }
    // 轮询刷新进度与播放状态：每 500ms 读取一次当前位置、总时长、播放中标记、播放队列快照。
    LaunchedEffect(controller) {
        while (true) {
            controller?.let {
                positionMs = it.currentPosition
                durationMs = it.duration
                isPlaying = it.isPlaying
                queue = it.queueSnapshot()
                runCatching {
                    val result = it.sendCustomCommand(
                        UsbExclusiveMediaCommands.getCommand(), Bundle.EMPTY
                    ).get()
                    if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                        val extras = result.extras
                        if (extras.containsKey(UsbExclusiveMediaCommands.KEY_STATE)) {
                            usbExclusiveEnabled = extras.getBoolean(UsbExclusiveMediaCommands.KEY_ENABLED)
                            usbExclusiveState = extras.getString(UsbExclusiveMediaCommands.KEY_STATE).orEmpty()
                            usbExclusiveError = extras.getString(UsbExclusiveMediaCommands.KEY_ERROR)
                            if (extras.containsKey(UsbExclusiveMediaCommands.KEY_VOLUME_PERCENT)) {
                                usbVolumePercent = extras.getInt(
                                    UsbExclusiveMediaCommands.KEY_VOLUME_PERCENT, usbVolumePercent
                                )
                            }
                        }
                    }
                }
                runCatching {
                    val result = it.sendCustomCommand(
                        SilenceSkipMediaCommands.getCommand(), Bundle.EMPTY
                    ).get()
                    if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                        val extras = result.extras
                        if (extras.containsKey(SilenceSkipMediaCommands.KEY_ENABLED)) {
                            silenceSkipEnabled = extras.getBoolean(SilenceSkipMediaCommands.KEY_ENABLED)
                        }
                    }
                }
            }
            delay(500)
        }
    }
    return ControllerState(
        controller = controller,
        status = status,
        positionMs = positionMs,
        durationMs = durationMs,
        isPlaying = isPlaying,
        queue = queue,
        usbExclusiveEnabled = usbExclusiveEnabled,
        usbExclusiveState = usbExclusiveState,
        usbExclusiveError = usbExclusiveError,
        usbExclusivePending = usbExclusivePending,
        usbVolumePercent = usbVolumePercent,
        silenceSkipEnabled = silenceSkipEnabled,
        onUsbExclusiveChange = { enabled ->
            val current = controller ?: return@ControllerState
            usbExclusivePending = true
            current.sendCustomCommand(
                SessionCommand(UsbExclusiveMediaCommands.SET_ENABLED, Bundle.EMPTY),
                UsbExclusiveMediaCommands.setArgs(enabled)
            ).addListener({
                runCatching {
                    val result = current.sendCustomCommand(
                        UsbExclusiveMediaCommands.getCommand(), Bundle.EMPTY
                    ).get()
                    if (result.resultCode != SessionResult.RESULT_SUCCESS) {
                        throw IllegalStateException("USB 独占设置命令失败")
                    }
                    val extras = result.extras
                    usbExclusiveEnabled = extras.getBoolean(UsbExclusiveMediaCommands.KEY_ENABLED)
                    usbExclusiveState = extras.getString(UsbExclusiveMediaCommands.KEY_STATE).orEmpty()
                    usbExclusiveError = extras.getString(UsbExclusiveMediaCommands.KEY_ERROR)
                }.onFailure { usbExclusiveError = "USB 独占设置失败" }
                usbExclusivePending = false
            }, { runnable -> runnable.run() })
        },
        onUsbVolumeChange = { newPercent ->
            usbVolumePercent = newPercent
            val current = controller ?: return@ControllerState
            runCatching {
                current.sendCustomCommand(
                    SessionCommand(UsbExclusiveMediaCommands.SET_VOLUME, Bundle.EMPTY),
                    UsbExclusiveMediaCommands.setVolumeArgs(newPercent)
                ).get()
            }.onFailure { usbExclusiveError = "USB 音量设置失败" }
        },
        onSilenceSkipChange = { enabled ->
            silenceSkipEnabled = enabled
            val current = controller ?: return@ControllerState
            // 用 addListener 而非阻塞的 .get()：PlaybackService 里 SET_ENABLED 命令要靠一个
            // 主线程协程写完 DataStore 才 set 这个 future，如果这里在主线程同步 .get() 等它，
            // 会跟那个协程互相卡死（协程排不上主线程执行，主线程又卡在 .get() 上），触发 ANR。
            current.sendCustomCommand(
                SessionCommand(SilenceSkipMediaCommands.SET_ENABLED, Bundle.EMPTY),
                SilenceSkipMediaCommands.setArgs(enabled)
            ).addListener({}, { runnable -> runnable.run() })
        }
    )
}

private fun usbExclusiveStateLabel(state: ControllerState): String = when (state.usbExclusiveState) {
    "disabled" -> "已关闭"
    "no_device" -> "已开启，等待 USB 设备"
    "awaiting_permission" -> "等待 USB 权限"
    "ready" -> "已就绪"
    "streaming" -> "输出中"
    "error" -> "USB 独占失败：${state.usbExclusiveError.orEmpty()}"
    else -> "状态未知"
}

private fun controllerStatus(controller: MediaController?): String {
    if (controller == null) return "播放服务不可用"
    val title = controller.currentMediaItem?.mediaMetadata?.title ?: "未选择媒体"
    val state = when { controller.isPlaying -> "播放中"; controller.playbackState == Player.STATE_ENDED -> "已结束"; controller.playbackState == Player.STATE_BUFFERING -> "缓冲中"; else -> "已暂停" }
    return "$title · $state"
}

/** 把 [mode] 应用到播放器：设置 repeatMode 与 shuffle 开关。 */
private fun MediaController.applyPlaybackMode(mode: PlaybackMode) {
    val config = playbackModeConfig(mode)
    repeatMode = config.repeatMode
    shuffleModeEnabled = config.shuffleEnabled
}

/**
 * 把队列拖拽后的目标顺序同步到播放器：从前往后逐个把每首移到目标位置。
 * MediaController 的 moveMediaItem 会即时更新本地队列镜像，循环内每次重新查当前位置即可正确收敛。
 */
private fun MediaController.applyQueueOrder(ordered: List<QueueTrack>) {
    val desired = ordered.map { it.id }
    for (targetIndex in desired.indices) {
        val id = desired[targetIndex]
        val currentIndex = (0 until mediaItemCount).firstOrNull { getMediaItemAt(it).mediaId == id } ?: continue
        if (currentIndex != targetIndex) moveMediaItem(currentIndex, targetIndex)
    }
}
