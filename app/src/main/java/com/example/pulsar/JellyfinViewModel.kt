package com.example.pulsar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import com.example.pulsar.data.jellyfin.JellyfinApi
import com.example.pulsar.data.jellyfin.JellyfinApiFactory
import com.example.pulsar.data.jellyfin.JellyfinError
import com.example.pulsar.data.jellyfin.JellyfinRequest
import com.example.pulsar.data.jellyfin.Lyrics
import com.example.pulsar.data.libraryfiles.LibraryFilesApi
import com.example.pulsar.data.libraryfiles.LibraryFilesError
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
import java.io.IOException

private const val SEARCH_DEBOUNCE_MS = 300L

/** 离线探活间隔：网一回来 App 自己回在线，用户不用做任何操作（用户故事 13）。 */
internal const val OFFLINE_RETRY_INTERVAL_MS = 15_000L

/** 探活上限（约 2 小时），见 [startOfflineRetry] 的说明。 */
private const val kOfflineRetryMaxAttempts = 500

class JellyfinViewModel(
    private val sessionStore: SessionRepository,
    private val apiFactory: JellyfinApiFactory,
    private val authorizationSink: AuthorizationSink,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    /** 曲库文件操作的 API 工厂（删除/改名走 PC 伴生服务，与 Jellyfin 会话无关）。测试注入 Fake。 */
    private val libraryFilesFactory: (suspend () -> LibraryFilesApi)? = null,
    /** 手机本地音频副本（离线能听）。null = 没有缓存能力，一切照常走网络。 */
    private val mediaCache: MediaCache? = null,
    /** 曲库快照：断网时撑起界面。null = 没有离线兜底。 */
    private val snapshotRepository: LibrarySnapshotRepository? = null
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
    private var offlineRetryJob: Job? = null

    /** 曲库快照的内存副本：离线兜底与覆写共用，避免每次都读盘。 */
    private var snapshot: LibrarySnapshot? = null

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
            isSearching = false,
            isFileOpsBusy = false,
            fileOpsMessage = null,
            isOffline = false,
            cachedTrackIds = null,
            cachedAlbums = null,
            isOfflineSearch = false,
            multiSelectIds = emptySet()
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
                    // 登录成功 = **整份替换**快照，不沿用旧的：快照里可能还躺着上一个账号的
                    // 专辑列表（token 过期不清快照是铁律 5），merge 进来就是跨账号串数据。
                    // 专辑/发现页之后随用户浏览逐屏补全。
                    replaceSnapshot(tracks)
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
            var saved: SavedSession? = null
            try {
                saved = sessionStore.load()
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
                    persistSnapshot()
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
                    // 断网启动：本地存过会话但连不上服务器。用曲库快照把界面撑起来，
                    // 别让用户打开就是一片空白（用户故事 7）。撑不起来才回落到登录表单。
                    val savedSession = saved
                    if (error is JellyfinError.Network && savedSession != null &&
                        restoreFromSnapshotOffline(savedSession)
                    ) {
                        return@launch
                    }
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isRestoring = false,
                        errorMessage = friendlyError(error)
                    )
                }
            }
        }
    }

    /**
     * 断网启动的兜底：拿本地会话 + 曲库快照直接进主页面，**乐观地**当作会话仍然有效
     * （离线时本来也验证不了）。真失效了，探活的第一次 401 会照常走 [expireSession]
     * 收场——而会话过期不碰缓存（铁律 5）。
     */
    private suspend fun restoreFromSnapshotOffline(saved: SavedSession): Boolean {
        val loaded = snapshotRepository?.load() ?: return false
        // 曲目表是快照的骨架：没有它，「全部音乐」和标灰判定都无从谈起，等于没兜底。
        if (loaded.tracks.isEmpty()) return false
        val source = apiFactory.create(saved.serverUrl)
        snapshot = loaded
        session = com.example.pulsar.data.jellyfin.JellyfinSession(saved.userId, saved.accessToken)
        dataSource = source
        authorizationSink.activeAuthorization = source.authorizationHeader(saved.accessToken)
        _state.value = _state.value.copy(isLoading = false, isRestoring = false)
        applySnapshot(loaded)
        // 标灰集合先算完再进离线态，别让界面在未知集合上拦住能播的歌。
        _state.value = _state.value.copy(
            isOffline = true,
            cachedTrackIds = computeCachedTrackIds(),
            errorMessage = null
        )
        startOfflineRetry()
        return true
    }

    private fun invalidateSessionRequests() {
        albumsJob?.cancel()
        albumsJob = null
        albumsGeneration++
        searchJob?.cancel()
        searchJob = null
        searchGeneration++
        stopOfflineRetry()
    }

    fun signOut() {
        loginGeneration++
        loginJob?.cancel()
        loginJob = null
        stopOfflineRetry()
        invalidateSessionRequests()
        // 缓存键要趁 dataSource 还在时算好：清完会话 streamUrl 就拿不到了。
        val keepKeys = setOfNotNull(_currentTrack.value?.let(::cacheKey))
        session = null
        dataSource = null
        authorizationSink.activeAuthorization = null
        _playbackTracks.value = emptyMap()
        _currentTrack.value = null
        clearLyrics()
        _state.value = JellyfinUiState()
        // 主动退出 = 清空手机上的副本与快照（换账号不串数据）。缓存层出问题也不能
        // 挡住退出登录本身（铁律 1、工单 06）——两个 clear 实现自己吞异常。
        // 正在播的那首保留：退出登录不该把正在听的歌拦腰打断。
        viewModelScope.launch(dispatcher) {
            sessionStore.clear()
            clearLocalCopyAndSnapshot(keepKeys)
        }
    }

    fun streamUrl(track: Track): String? {
        val src = dataSource ?: return null
        val currentSession = session ?: return null
        return src.streamUrl(track.id, currentSession.accessToken)
    }

    /**
     * 这首歌**现在**能不能放。离线时只有完整副本才能放（半截的也算不能，用户故事 9）；
     * 在线一律能放。给 [playTracks] 那类入口用的守门判断。
     */
    fun isPlayable(track: Track): Boolean =
        !_state.value.isOffline || _state.value.cachedTrackIds?.contains(track.id) == true

    /**
     * 离线时点没缓存的歌：返回不能放的原因（给界面弹提示用），可放返回 null。
     * 明确告诉用户「这首歌没缓存」，而不是转圈或「网络错误」（工单 03）。
     */
    fun unplayableReason(track: Track): String? =
        if (isPlayable(track)) null else "这首歌没有缓存，联网后才能播放"

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
            needsNetwork = true,
            request = { src, currentSession -> src.getPlaylists(currentSession.userId, currentSession.accessToken) },
            success = { playlists -> _state.value.copy(isLoading = false, playlists = playlists) }
        )
    }

    fun loadPlaylistTracks(playlist: Playlist) {
        if (_state.value.selectedPlaylist?.id == playlist.id && _state.value.playlistTracks != null) return
        loadLibrary(
            needsNetwork = true,
            before = { _state.value.copy(isLoading = true, errorMessage = null, selectedPlaylist = playlist, playlistTracks = null) },
            request = { src, currentSession -> src.getPlaylistTracks(currentSession.userId, currentSession.accessToken, playlist.id) },
            success = { tracks -> _state.value.copy(isLoading = false, playlistTracks = tracks) }
        )
    }

    /**
     * 库请求的统一入口。
     *
     * [needsNetwork] 标注这一屏**快照撑不起来**（播放列表）：网络不通时明说需要联网，
     * 而不是拿快照硬凑一个半成品。
     */
    private fun <T> loadLibrary(
        needsNetwork: Boolean = false,
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
                if (coroutineContext.isActive && generation == albumsGeneration) {
                    // 先退离线态再赋成功值：markOnline 会清掉快照来源的几屏等界面重拉，
                    // 顺序反了会把刚拉到的新数据当快照数据清掉。
                    markOnline()
                    _state.value = success(result)
                    persistSnapshot()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == albumsGeneration) {
                    when (error) {
                        is JellyfinError.Unauthorized -> expireSession()
                        // 网络不通 ≠ 请求出错：退回快照顶上的离线态，不当成错误显示
                        is JellyfinError.Network -> goOffline(needsNetwork)
                        else -> _state.value =
                            _state.value.copy(isLoading = false, errorMessage = friendlyError(error))
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
            _state.value = _state.value.copy(searchResults = null, isSearching = false, isOfflineSearch = false)
            return
        }
        val generation = ++searchGeneration
        _state.value = _state.value.copy(isSearching = true)
        searchJob = viewModelScope.launch(dispatcher) {
            // 防抖对本地过滤也照旧：搜索框每敲一个字都重算一遍快照没有意义。
            delay(SEARCH_DEBOUNCE_MS)
            if (!coroutineContext.isActive || generation != searchGeneration) return@launch
            // 断网时改成在曲库快照里过滤（用户故事 10）。范围比服务端搜索小，
            // 界面据此标明「离线搜索」。
            if (_state.value.isOffline) {
                val results = snapshot?.search(query) ?: SearchResults()
                if (coroutineContext.isActive && generation == searchGeneration) {
                    _state.value = _state.value.copy(
                        isSearching = false,
                        searchResults = results,
                        isOfflineSearch = true
                    )
                }
                return@launch
            }
            try {
                val results = src.search(currentSession.userId, currentSession.accessToken, query).getOrThrow()
                if (coroutineContext.isActive && generation == searchGeneration) {
                    _state.value = _state.value.copy(
                        isSearching = false,
                        searchResults = results,
                        isOfflineSearch = false
                    )
                    markOnline()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == searchGeneration) {
                    when (error) {
                        is JellyfinError.Unauthorized -> expireSession()
                        // 网断了：拿快照里的结果顶上，并标明这是离线搜索
                        is JellyfinError.Network -> {
                            goOffline(needsNetwork = false)
                            _state.value = _state.value.copy(
                                isSearching = false,
                                searchResults = snapshot?.search(query) ?: SearchResults(),
                                isOfflineSearch = true
                            )
                        }
                        else -> _state.value =
                            _state.value.copy(isSearching = false, errorMessage = friendlyError(error))
                    }
                }
            }
        }
    }

    // ── 曲库文件操作（PC 伴生服务，ADR-0001：删的是真文件、改的是内嵌标签） ──
    // 两个方法都是直接 suspend 而非内部 launch：对话框要等结果决定关不关弹窗，
    // 提交中用 isFileOpsBusy 禁按钮防误关。CancellationException 一律重新抛出。

    /** 删除 = 挪进回收站。成功返回 true；失败原因写在 [JellyfinUiState.fileOpsMessage]。 */
    suspend fun deleteTrack(track: Track): Boolean {
        if (_state.value.isFileOpsBusy) return false
        val fileOps = libraryFilesFactory ?: return falseWithMessage("电脑端服务未配置")
        // 正在播的那首删不得：文件没了播放器缓冲还在，是诡异 bug 高发区（spec 铁律 3）
        if (_currentTrack.value?.id == track.id) return falseWithMessage("正在播放的歌曲不能删除")
        val path = track.filePath?.takeIf { it.isNotBlank() }
            ?: return falseWithMessage("拿不到这首歌的文件路径，无法操作")
        _state.value = _state.value.copy(isFileOpsBusy = true, fileOpsMessage = null)
        val result: Result<com.example.pulsar.data.libraryfiles.LibraryDeleteResponse> = try {
            fileOps().delete(listOf(path))
        } catch (error: CancellationException) {
            _state.value = _state.value.copy(isFileOpsBusy = false)
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
        return result.fold(
            onSuccess = { response ->
                // 服务端逐个处理：deleted 空 = 这一个也没删成，按失败算
                if (response.deleted.isEmpty()) {
                    falseWithMessage(response.failed.firstOrNull()?.error ?: "删除失败")
                } else {
                    removeTrackEverywhere(track.id)
                    // 手机上的副本跟着走（工单 06）：别占着空间。删副本失败不影响删歌结果。
                    removeCachedCopy(track)
                    _state.value = _state.value.copy(isFileOpsBusy = false)
                    true
                }
            },
            onFailure = { error -> falseWithMessage(friendlyFileOpsError(error)) }
        )
    }

    /** 改内嵌标签。三个字段只写传了的（null = 不动，空串 = 清空），成功返回 true。 */
    suspend fun renameTrack(track: Track, title: String?, artist: String? = null, album: String? = null): Boolean {
        if (_state.value.isFileOpsBusy) return false
        val fileOps = libraryFilesFactory ?: return falseWithMessage("电脑端服务未配置")
        val path = track.filePath?.takeIf { it.isNotBlank() }
            ?: return falseWithMessage("拿不到这首歌的文件路径，无法操作")
        _state.value = _state.value.copy(isFileOpsBusy = true, fileOpsMessage = null)
        val result: Result<com.example.pulsar.data.libraryfiles.LibraryRenameResponse> = try {
            fileOps().rename(path, title = title, artist = artist, album = album)
        } catch (error: CancellationException) {
            _state.value = _state.value.copy(isFileOpsBusy = false)
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
        return result.fold(
            onSuccess = { response ->
                if (!response.ok) {
                    falseWithMessage("改名失败")
                } else {
                    applyRename(track, title, artist, album)
                    _state.value = _state.value.copy(isFileOpsBusy = false)
                    true
                }
            },
            onFailure = { error -> falseWithMessage(friendlyFileOpsError(error)) }
        )
    }

    /** 从所有本地列表（曲库/专辑详情/播放列表详情/搜索）与播放队列镜像里摘掉这首歌。 */
    private fun removeTrackEverywhere(trackId: String) {
        fun List<Track>.without() = filterNot { it.id == trackId }
        _state.value = _state.value.let { s ->
            s.copy(
                tracks = s.tracks?.without(),
                albumTracks = s.albumTracks?.without(),
                playlistTracks = s.playlistTracks?.without(),
                searchResults = s.searchResults?.let { it.copy(tracks = it.tracks.without()) }
            )
        }
        _playbackTracks.value = _playbackTracks.value - trackId
    }

    /** 把改好的字段换进所有本地列表与正在播曲目（文件路径不变——改名不挪文件）。 */
    private fun applyRename(track: Track, title: String?, artist: String?, album: String?) {
        val updated = track.copy(
            title = title ?: track.title,
            artist = artist ?: track.artist,
            album = album ?: track.album
        )
        fun List<Track>.replaced() = map { if (it.id == track.id) updated else it }
        _state.value = _state.value.let { s ->
            s.copy(
                tracks = s.tracks?.replaced(),
                albumTracks = s.albumTracks?.replaced(),
                playlistTracks = s.playlistTracks?.replaced(),
                searchResults = s.searchResults?.let { it.copy(tracks = it.tracks.replaced()) }
            )
        }
        _playbackTracks.value = _playbackTracks.value + (track.id to updated)
        if (_currentTrack.value?.id == track.id) _currentTrack.value = updated
    }

    private fun falseWithMessage(message: String): Boolean {
        _state.value = _state.value.copy(isFileOpsBusy = false, fileOpsMessage = message)
        return false
    }

    /** 清掉上一次文件操作的失败提示（打开弹窗时调，避免旧提示残留在新弹窗里）。 */
    fun clearFileOpsMessage() {
        if (_state.value.fileOpsMessage != null) _state.value = _state.value.copy(fileOpsMessage = null)
    }

    // ── 回收站（设置页） ─────────────────────────────────────────────

    /** 拉回收站列表。每次进设置页都重拉——删除/恢复随时会改变它的内容，不缓存。 */
    fun loadRecycle() {
        val fileOps = libraryFilesFactory ?: return
        if (_state.value.isRecycleLoading) return
        viewModelScope.launch(dispatcher) {
            _state.value = _state.value.copy(isRecycleLoading = true, recycleMessage = null)
            val result: Result<com.example.pulsar.data.libraryfiles.LibraryRecycleResponse> = try {
                fileOps().recycle()
            } catch (error: CancellationException) {
                _state.value = _state.value.copy(isRecycleLoading = false)
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            result.fold(
                onSuccess = { response ->
                    _state.value = _state.value.copy(
                        isRecycleLoading = false,
                        recycleFiles = response.files
                    )
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        isRecycleLoading = false,
                        recycleMessage = friendlyFileOpsError(error)
                    )
                }
            )
        }
    }

    /** 把一个文件从回收站挪回原处；成功后立刻从列表移除。 */
    fun restoreRecycle(relPath: String) {
        val fileOps = libraryFilesFactory ?: return
        if (_state.value.isFileOpsBusy) return
        viewModelScope.launch(dispatcher) {
            _state.value = _state.value.copy(isFileOpsBusy = true, recycleMessage = null)
            val result: Result<com.example.pulsar.data.libraryfiles.LibraryRestoreResponse> = try {
                fileOps().restore(listOf(relPath))
            } catch (error: CancellationException) {
                _state.value = _state.value.copy(isFileOpsBusy = false)
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            result.fold(
                onSuccess = { response ->
                    _state.value = if (response.restored.isNotEmpty()) {
                        _state.value.copy(
                            isFileOpsBusy = false,
                            recycleFiles = _state.value.recycleFiles.filterNot { it.relPath == relPath },
                            recycleMessage = "已恢复到原位置"
                        )
                    } else {
                        _state.value.copy(
                            isFileOpsBusy = false,
                            recycleMessage = response.failed.firstOrNull()?.error ?: "恢复失败"
                        )
                    }
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        isFileOpsBusy = false,
                        recycleMessage = friendlyFileOpsError(error)
                    )
                }
            )
        }
    }

    /** 真删回收站（全功能唯一不可逆的操作），UI 已二次确认。 */
    fun purgeRecycle() {
        val fileOps = libraryFilesFactory ?: return
        if (_state.value.isFileOpsBusy) return
        viewModelScope.launch(dispatcher) {
            _state.value = _state.value.copy(isFileOpsBusy = true, recycleMessage = null)
            val result: Result<com.example.pulsar.data.libraryfiles.LibraryPurgeResponse> = try {
                fileOps().purge()
            } catch (error: CancellationException) {
                _state.value = _state.value.copy(isFileOpsBusy = false)
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            result.fold(
                onSuccess = { response ->
                    _state.value = _state.value.copy(
                        isFileOpsBusy = false,
                        recycleFiles = emptyList(),
                        recycleMessage = "已清空（${response.purged} 个文件）"
                    )
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        isFileOpsBusy = false,
                        recycleMessage = friendlyFileOpsError(error)
                    )
                }
            )
        }
    }

    // ── 批量多选删除（工单 05） ────────────────────────────────────────

    /** 进多选模式并先勾上一首（长按菜单进来的）。 */
    fun enterMultiSelect(trackId: String) {
        _state.value = _state.value.copy(multiSelectIds = setOf(trackId), fileOpsMessage = null)
    }

    /** 勾上/取消一首。 */
    fun toggleMultiSelect(trackId: String) {
        val current = _state.value.multiSelectIds
        _state.value = _state.value.copy(
            multiSelectIds = if (trackId in current) current - trackId else current + trackId
        )
    }

    /** 全选：只选 UI 传进来的当前列表（专辑详情页就该只有这张专辑的歌）。 */
    fun selectAllTracks(ids: List<String>) {
        _state.value = _state.value.copy(multiSelectIds = ids.toSet())
    }

    /** 退出多选模式（取消按钮 / 返回键 / 删完 / 切页）。 */
    fun clearMultiSelect() {
        if (_state.value.multiSelectIds.isNotEmpty()) {
            _state.value = _state.value.copy(multiSelectIds = emptySet())
        }
    }

    /**
     * 批量删除。正在播的那首不发请求（spec 铁律 3）；服务端一次请求逐个处理，
     * 返回**成功删掉的数目**。部分失败时不拦截：成功的照常生效，失败的原因写在
     * [JellyfinUiState.fileOpsMessage]。全部成功时顺带退出多选模式。
     */
    suspend fun deleteTracks(tracks: List<Track>): Int {
        if (_state.value.isFileOpsBusy) return 0
        val fileOps = libraryFilesFactory ?: return 0
        val currentId = _currentTrack.value?.id
        val deletable = tracks.filter { it.id != currentId && !it.filePath.isNullOrBlank() }
        if (deletable.isEmpty()) {
            falseWithMessage(
                if (tracks.any { it.id == currentId }) "正在播放的歌曲不能删除" else "拿不到歌曲的文件路径，无法操作"
            )
            return 0
        }
        _state.value = _state.value.copy(isFileOpsBusy = true, fileOpsMessage = null)
        val paths = deletable.map { it.filePath.orEmpty() }
        val result: Result<com.example.pulsar.data.libraryfiles.LibraryDeleteResponse> = try {
            fileOps().delete(paths)
        } catch (error: CancellationException) {
            _state.value = _state.value.copy(isFileOpsBusy = false)
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
        return result.fold(
            onSuccess = { response ->
                // failed[].path 原样回传请求时的路径，用它反推哪些没删成，不用解析 rel path
                val failedRaw = response.failed.map { it.path }.toSet()
                val deletedIds = deletable.zip(paths)
                    .filter { (_, path) -> path !in failedRaw }
                    .map { (track, _) -> track.id }
                if (deletedIds.isEmpty()) {
                    falseWithMessage(response.failed.firstOrNull()?.error ?: "删除失败")
                    0
                } else {
                    deletedIds.forEach(::removeTrackEverywhere)
                    // 副本跟着歌一起走（工单 06）。removeCachedCopy 自己吞失败。
                    deletable.filter { it.id in deletedIds }.forEach(::removeCachedCopy)
                    _state.value = _state.value.copy(
                        isFileOpsBusy = false,
                        multiSelectIds = emptySet()
                    )
                    if (response.failed.isNotEmpty()) {
                        _state.value = _state.value.copy(
                            fileOpsMessage = "${response.failed.size} 首删除失败：" +
                                response.failed.first().error.take(80)
                        )
                    }
                    deletedIds.size
                }
            },
            onFailure = { error ->
                falseWithMessage(friendlyFileOpsError(error))
                0
            }
        )
    }

    private fun friendlyFileOpsError(error: Throwable): String = when (error) {
        is LibraryFilesError.Unauthorized -> "文件操作密钥不对，去「QQ 下载」页检查密钥设置"
        is LibraryFilesError.ServiceUnreachable -> "电脑上的服务没开，或手机不在同一网络"
        is LibraryFilesError.Http -> "服务请求失败（${error.statusCode}）"
        is LibraryFilesError.InvalidResponse -> "服务返回了无法识别的数据"
        else -> "操作失败，请稍后重试"
    }

    // ── 本地缓存与离线（工单 01/02/03/05/06）─────────────────────────
    // 缓存是增强不是依赖（铁律 1）：这一节的所有失败都只表现为「没有离线能力」，
    // 绝不冒泡成播放失败或界面报错。

    /** 缓存键 = 播放流 URL（token 只走 header 不进 URL，所以键里没有凭据）。 */
    private fun cacheKey(track: Track): String? = streamUrl(track)

    /** 曲库快照：第一次用到时读盘，之后走内存副本。 */
    private suspend fun loadSnapshot(): LibrarySnapshot? =
        snapshot ?: snapshotRepository?.load()?.also { snapshot = it }

    /**
     * 在线请求成功时顺手覆写快照（铁律 4：快照只在线下兜底，不在线上抢活）。
     * 还没加载过的那几屏沿用上一次的内容——只是「这次没拉到」，不是「服务器上没有」。
     *
     * 内容没变就不写盘：每次成功请求都全量序列化+落盘，几千首的库是每次好几 MB 的
     * 纯重复 IO（专辑详情/发现页刷新全都走这里）。
     */
    private suspend fun persistSnapshot() {
        val repo = snapshotRepository ?: return
        val current = _state.value
        val previous = loadSnapshot()
        val next = LibrarySnapshot(
            tracks = current.tracks ?: previous?.tracks.orEmpty(),
            albums = current.albums ?: previous?.albums.orEmpty(),
            discoverAlbums = current.discoverAlbums ?: previous?.discoverAlbums.orEmpty()
        )
        if (next == previous) return
        snapshot = next
        repo.save(next)
    }

    /** 登录成功时的整份替换快照：旧账号的数据一个字段都不留（换账号不串数据）。 */
    private suspend fun replaceSnapshot(tracks: List<Track>) {
        val repo = snapshotRepository ?: return
        val next = LibrarySnapshot(tracks = tracks)
        snapshot = next
        repo.save(next)
    }

    /** 用快照补上还空着的那几屏。已经有的不动——服务器返回的永远优先。 */
    private fun applySnapshot(loaded: LibrarySnapshot) {
        val current = _state.value
        _state.value = current.copy(
            tracks = current.tracks ?: loaded.tracks.takeIf { it.isNotEmpty() },
            albums = current.albums ?: loaded.albums.takeIf { it.isNotEmpty() },
            discoverAlbums = current.discoverAlbums ?: loaded.discoverAlbums.takeIf { it.isNotEmpty() },
            albumTracks = current.albumTracks ?: offlineAlbumTracks(loaded)
        )
    }

    /**
     * 专辑详情页的离线兜底：快照里没有「专辑 → 曲目」的索引，按专辑名从全部曲目里挑。
     * 代价是同名专辑（不同歌手）会混在一起——离线时这些歌没缓存照样标灰，换来的是
     * 不用为快照多存一份几乎重复的曲目表。
     */
    private fun offlineAlbumTracks(loaded: LibrarySnapshot): List<Track>? {
        val album = _state.value.selectedAlbum ?: return null
        return loaded.tracks.filter { it.album == album.name }.takeIf { it.isNotEmpty() }
    }

    /**
     * 算出「哪些歌有完整副本」（一次 IO 往返拿全量键，再跟曲目表批量比对）。
     * 失败 = 判不出来 = 全部按没缓存处理，绝不把异常抛给界面（铁律 1）。
     *
     * 只在**进离线态之前**算一次：isOffline 一翻 true 界面马上要用它标灰，拿未算完的
     * null 硬撑会把有副本的歌也拦成「没缓存」——对能播的歌说谎比等一下更糟。
     * 在线时不需要知道（一律能放），所以不提前算。
     */
    private suspend fun computeCachedTrackIds(): Set<String> {
        val cache = mediaCache ?: return emptySet()
        val cachedKeys = try {
            cache.completeKeys()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            emptySet()
        }
        return knownTracks().filter { track ->
            cacheKey(track)?.let { it in cachedKeys } == true
        }.map { it.id }.toSet()
    }

    /** 当前可能出现在界面上的所有曲目（去重）。标灰判定要覆盖每一屏。 */
    private fun knownTracks(): List<Track> {
        val current = _state.value
        return buildList {
            current.tracks?.let(::addAll)
            current.albumTracks?.let(::addAll)
            current.playlistTracks?.let(::addAll)
            current.searchResults?.tracks?.let(::addAll)
            addAll(_playbackTracks.value.values)
        }.distinctBy { it.id }
    }

    /**
     * 网络不通：切到离线态。快照能撑起来的屏照常显示，撑不起来的（播放列表）明说需要
     * 联网——不是转圈、不是「网络错误」。
     */
    private suspend fun goOffline(needsNetwork: Boolean) {
        val loaded = loadSnapshot()
        if (loaded != null) applySnapshot(loaded)
        // 快照连「全部音乐」都撑不起来（没有快照 / 刚被 login 清掉）：没有可显示的库，
        // 退回「连不上服务器」+ 登录表单，跟没有缓存功能时的行为一致——空骨架更糟。
        if (_state.value.tracks == null) {
            _state.value = _state.value.copy(
                isLoading = false,
                errorMessage = friendlyError(JellyfinError.Network(IOException("offline, no snapshot")))
            )
            return
        }
        // 标灰集合先算完再进离线态（见 [computeCachedTrackIds]）。
        val cached = computeCachedTrackIds()
        _state.value = _state.value.copy(
            isLoading = false,
            isOffline = true,
            cachedTrackIds = cached,
            errorMessage = if (needsNetwork) "播放列表需要联网才能查看" else null
        )
        startOfflineRetry()
    }

    /**
     * 探活成功：回在线态，并以服务器返回的曲库为准（铁律 4）。离线期间那几屏是快照来的，
     * 清掉——界面进对应分区时会照常重新拉一遍。
     */
    private suspend fun goOnline(tracks: List<Track>) {
        _state.value = _state.value.copy(
            isOffline = false,
            cachedTrackIds = null,
            isOfflineSearch = false,
            errorMessage = null,
            tracks = tracks,
            albums = null,
            discoverAlbums = null,
            albumTracks = null
        )
        persistSnapshot()
        // 当前这次搜索是在快照里搜的：联网后用同样的词重新搜一遍（用户不用重打）。
        if (_state.value.searchQuery.isNotBlank()) updateSearchQuery(_state.value.searchQuery)
    }

    /**
     * 任意一次请求成功 = 网络通了。退出离线态并停掉探活。
     *
     * 快照来源的那几屏一并清掉：它们不该被幂等守卫当成「最新服务器数据」挡住不刷新
     * （铁律 4）。清掉后界面的 LaunchedEffect（key 含 isOffline）会重新触发对应分区的
     * 加载。调用方要把本次成功结果赋回 state——顺序必须是 markOnline 在前、赋值在后，
     * 不然刚拉到的新数据会被清掉。
     */
    private fun markOnline() {
        stopOfflineRetry()
        if (_state.value.isOffline) {
            _state.value = _state.value.copy(
                isOffline = false,
                cachedTrackIds = null,
                isOfflineSearch = false,
                albums = null,
                discoverAlbums = null,
                albumTracks = null
            )
        }
    }

    /**
     * 离线期间的探活：隔一会儿拿一次曲库（最轻的一次真实请求）。成功就回在线态，
     * 用户不用做任何操作（用户故事 13）。
     *
     * 循环**有上限**（15s × 500 ≈ 2 小时）：到了上限就停，等用户下次操作自然触发
     * 加载。不做成无限循环的原因很实际——单元测试的虚拟时间调度器会把无限 delay
     * 循环喂成活锁（teardown 永远等不到队列清空，worker 烧 CPU 卡死 gradle）。
     * 离线两小时都没网，探活停了也无所谓；联网后随便点一下就回在线了。
     */
    private fun startOfflineRetry() {
        if (offlineRetryJob?.isActive == true) return
        val src = dataSource ?: return
        val currentSession = session ?: return
        offlineRetryJob = viewModelScope.launch(dispatcher) {
            repeat(kOfflineRetryMaxAttempts) {
                if (!isActive || !_state.value.isOffline) return@launch
                delay(OFFLINE_RETRY_INTERVAL_MS)
                val result = try {
                    src.getMusicItems(currentSession.userId, currentSession.accessToken)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    null
                }
                when {
                    // token 已失效：照实走会话过期，别空转到上限——断网兜底时的乐观会话
                    // 就在这里收口（真失效了 401 会说话）。
                    result != null && result.exceptionOrNull() is JellyfinError.Unauthorized -> {
                        expireSession()
                        return@launch
                    }
                    result?.isSuccess == true -> {
                        goOnline(result.getOrThrow())
                        return@launch
                    }
                    // 网络还是不通：下一轮再试
                    else -> Unit
                }
            }
        }
    }

    /**
     * 停掉离线探活协程。探活循环虽有上限（见 [startOfflineRetry]），测试里仍应
     * 在断言完停掉它，免得虚拟时间被 teardown 无谓地推 500 轮。
     */
    internal fun stopOfflineRetry() {
        offlineRetryJob?.cancel()
        offlineRetryJob = null
    }

    /** 拉缓存占用（进设置页时调）。 */
    fun loadCacheStats() {
        viewModelScope.launch(dispatcher) { refreshCacheStats() }
    }

    /** 缓存统计（设置页卡片）：占用 + 完整副本首数。 */
    private suspend fun refreshCacheStats() {
        val cache = mediaCache ?: return
        _state.value = _state.value.copy(
            cacheBytes = cache.usedBytes(),
            cacheTrackCount = cache.completeCount()
        )
    }

    /**
     * 清空手机上的副本（界面已二次确认）。只动手机本地：没网、PC 服务没开都照常成功（铁律 3）。
     * 正在播的那首**保留**：清空不该把正在听的歌从播放流底下抽走（离线时表现为中途断播）。
     */
    fun clearCache() {
        val cache = mediaCache ?: return
        if (_state.value.isCacheBusy) return
        val keepCurrent = setOfNotNull(_currentTrack.value?.let(::cacheKey))
        viewModelScope.launch(dispatcher) {
            _state.value = _state.value.copy(isCacheBusy = true, cacheMessage = null)
            cache.clear(keepKeys = keepCurrent)
            _state.value = _state.value.copy(
                isCacheBusy = false,
                cacheBytes = cache.usedBytes(),
                cacheTrackCount = cache.completeCount(),
                cacheMessage = if (keepCurrent.isEmpty()) {
                    "已清空手机上的副本，电脑里的歌一首没动"
                } else {
                    "已清空手机上的副本（正在播的那首先留着），电脑里的歌一首没动"
                },
                // 刚清完只剩正在播的一首；离线时标灰集合直接重算（不用逐首猜）。
                cachedTrackIds = if (_state.value.isOffline) computeCachedTrackIds() else _state.value.cachedTrackIds
            )
        }
    }

    /** 删歌成功顺带清掉这首的副本（工单 06）。失败不影响删歌结果（铁律 1）。 */
    private fun removeCachedCopy(track: Track) {
        val cache = mediaCache ?: return
        val key = cacheKey(track) ?: return
        viewModelScope.launch(dispatcher) { cache.remove(key) }
    }

    /**
     * 已缓存列表（设置页进）：按专辑分组显示有完整副本的歌。进页面时重拉一次——
     * 播放随时在攒副本，进页时算的才是当下。曲库没到位（恢复会话中/恢复失败）按
     * 空列表处理：列表页转圈没有尽头，空态是更诚实的占位。
     */
    fun loadCachedAlbums() {
        val tracks = _state.value.tracks
        if (tracks == null) {
            _state.value = _state.value.copy(cachedAlbums = emptyList())
            return
        }
        viewModelScope.launch(dispatcher) {
            // 分组需要专辑对象（封面/详情跳转用）。专辑页没逛过、快照里也没有时，
            // 在线就现拉一次（幂等），落地后再分组；离线只能用快照里有的。
            if (_state.value.albums == null && !_state.value.isOffline) {
                loadAlbums()
                albumsJob?.join()
            }
            refreshCachedAlbums(tracks)
        }
    }

    /**
     * 删掉一张专辑所有歌的手机副本（界面已二次确认）。正在播的那首**保留**（同
     * [clearCache]：不能把正在听的歌从播放流底下抽走）。只动手机本地（铁律 3），
     * 失败静默（铁律 1）——删完重算，列表说的永远是当下。
     */
    fun deleteAlbumCopies(album: Album) {
        val cache = mediaCache ?: return
        val tracks = _state.value.tracks ?: return
        // 守卫和置位都留在**发起线程**：置位放协程里，两次快速点击能同时穿过守卫。
        if (_state.value.isCacheBusy) return
        _state.value = _state.value.copy(isCacheBusy = true)
        val keepCurrent = setOfNotNull(_currentTrack.value?.let(::cacheKey))
        val keys = tracks.filter { it.album == album.name }
            .mapNotNull { cacheKey(it) }
            .filter { it !in keepCurrent }
        viewModelScope.launch(dispatcher) {
            keys.forEach { cache.remove(it) }
            refreshCachedAlbums(tracks)
            refreshCacheStats()
            _state.value = _state.value.copy(isCacheBusy = false)
        }
    }

    /** 重算已缓存列表。专辑对象从专辑页拿（没加载过就退快照）按专辑名配对。 */
    private suspend fun refreshCachedAlbums(tracks: List<Track>) {
        val cached = computeCachedTrackIds()
        val albums = _state.value.albums ?: loadSnapshot()?.albums.orEmpty()
        _state.value = _state.value.copy(
            cachedAlbums = groupCachedByAlbum(tracks, albums, cached),
            // 离线时标灰集合跟着删副本走：删掉的那首马上变成「没缓存」不能放。
            cachedTrackIds = if (_state.value.isOffline) cached else _state.value.cachedTrackIds
        )
    }

    /** 退出登录时清空手机上的副本与快照。两个实现都不抛，清不掉也不挡退出登录。 */
    private suspend fun clearLocalCopyAndSnapshot(keepKeys: Set<String>) {
        mediaCache?.clear(keepKeys = keepKeys)
        snapshotRepository?.clear()
        snapshot = null
    }

    /**
     * 会话失效（token 过期）。**不碰缓存与快照**（铁律 5）：一过期就清 5 GB 太亏，
     * 重登回来缓存还在。
     */
    private fun expireSession() {
        loginGeneration++
        loginJob?.cancel()
        loginJob = null
        stopOfflineRetry()
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
            isSearching = false,
            isFileOpsBusy = false,
            fileOpsMessage = null,
            isOffline = false,
            cachedTrackIds = null,
            cachedAlbums = null,
            isOfflineSearch = false,
            multiSelectIds = emptySet()
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
        stopOfflineRetry()
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
