package com.example.pulsar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pulsar.data.qqdownload.QQDownloadAlbumResponse
import com.example.pulsar.data.qqdownload.QQDownloadApi
import com.example.pulsar.data.qqdownload.QQDownloadError
import com.example.pulsar.data.qqdownload.QQDownloadSettings
import com.example.pulsar.data.qqdownload.QQHealthResponse
import com.example.pulsar.data.qqdownload.QQJobDto
import com.example.pulsar.data.qqdownload.QQAlbumDto
import com.example.pulsar.data.qqdownload.QQAlbumSongDto
import com.example.pulsar.data.qqdownload.QQSongDto
import com.example.pulsar.state.QQDownloadState
import com.example.pulsar.state.QQSearchState
import com.example.pulsar.state.QQServiceState
import com.example.pulsar.state.QQSettingsState
import com.example.pulsar.state.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val SEARCH_DEBOUNCE_MS = 300L
private const val JOB_POLL_INTERVAL_MS = 2_000L

/** 「连不上服务」的话术：搜索分区小字、动作 Snackbar、服务状态卡共用一句。 */
private const val SERVICE_UNREACHABLE_MESSAGE =
    "连不上下载服务：确认电脑上已启动（start.bat），且手机和电脑在同一网络"

/** 「还没配服务」的话术（自动填地址之前/未登录）。 */
private const val SERVICE_NOT_CONNECTED_MESSAGE =
    "下载服务未连接：去「设置 → 下载」检查服务状态"

/** 曲库索引没就绪时重查健康接口的间隔（冷扫 595 首约 17 秒）。 */
private const val HEALTH_RECHECK_INTERVAL_MS = 3_000L

/**
 * QQ 音乐下载区块的状态与逻辑。
 *
 * 与 JellyfinViewModel 完全独立：服务地址单独持久化（QQDownloadPreferences），
 * 不参与 Jellyfin 会话生命周期。搜索/任务各自带 generation 防过期协程。
 */
class QQDownloadViewModel(
    private val preferences: QQDownloadSettings,
    private val apiFactory: QQDownloadApiFactory,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {

    private val _state = MutableStateFlow(QQDownloadUiState())
    val state: StateFlow<QQDownloadUiState> = _state.asStateFlow()

    // ── 状态写入口（#31）：组合根只有四块 slice，写哪个就落到哪个（同 JellyfinViewModel）──
    private fun updateService(transform: (QQServiceState) -> QQServiceState) {
        _state.value = _state.value.let { it.copy(service = transform(it.service)) }
    }

    private fun updateSettings(transform: (QQSettingsState) -> QQSettingsState) {
        _state.value = _state.value.let { it.copy(settings = transform(it.settings)) }
    }

    private fun updateSearch(transform: (QQSearchState) -> QQSearchState) {
        _state.value = _state.value.let { it.copy(search = transform(it.search)) }
    }

    private fun updateDownload(transform: (QQDownloadState) -> QQDownloadState) {
        _state.value = _state.value.let { it.copy(download = transform(it.download)) }
    }

    private var api: QQDownloadApi? = null
    private var searchJob: Job? = null
    private var searchGeneration = 0
    private var pollJob: Job? = null
    private var pollGeneration = 0
    private var pickerGeneration = 0
    private var healthJob: Job? = null
    private var healthGeneration = 0
    private var credentialJob: Job? = null

    init {
        viewModelScope.launch(dispatcher) {
            val url = preferences.serviceUrl()
            val quality = preferences.quality()
            val fileOpsKey = preferences.fileOpsKey()
            updateService { it.copy(serviceUrl = url) }
            updateSettings { it.copy(quality = quality, fileOpsKey = fileOpsKey, fileOpsKeyInput = fileOpsKey) }
            if (url.isNotBlank()) {
                applyServiceUrl(url)
                checkHealth()
            }
        }
    }

    /**
     * 服务地址自动填充：取 Jellyfin 服务器的主机名、端口固定 8900。
     * 登录成功时由界面调用，用户完全不感知地址这回事（不提供手动入口）。
     */
    fun autoFillServiceUrl(jellyfinServerUrl: String) {
        val host = runCatching { java.net.URI(jellyfinServerUrl.trim()).host }.getOrNull()
        if (host.isNullOrBlank()) return
        val url = "http://$host:8900"
        if (url == _state.value.service.serviceUrl) return
        viewModelScope.launch(dispatcher) {
            preferences.saveServiceUrl(url)
            applyServiceUrl(url)
            // 换了另一台电脑上的导入服务：上一条服务的搜索结果、任务、已入库标记与弹窗
            // 全部作废（它们都属于那条服务）；用户偏好（音质 / 文件操作密钥）保留。
            // 先作废在途工作再清状态，否则旧服务的响应会写回新服务的状态里。
            invalidateServiceScopedWork()
            _state.value = _state.value.cleared(serviceUrl = url)
            checkHealth()
        }
    }

    /**
     * 换服务：把**属于上一条服务**的在途工作全部作废（取消 + 代次递增）。
     *
     * 只清状态是不够的——在途协程手里握着旧 api，回来时代次没变就会把旧服务的数据
     * （搜索结果 / 任务列表 / 健康结果 / 凭证反馈）写回新服务的状态里。与曲库那条线
     * 「登录 / 会话过期清空缓存并靠代次挡住过期协程」是同一套口径（#31）。
     */
    private fun invalidateServiceScopedWork() {
        searchJob?.cancel()
        searchGeneration++
        pollJob?.cancel()
        pollGeneration++
        healthJob?.cancel()
        healthGeneration++
        credentialJob?.cancel()
        pickerGeneration++
    }

    fun updateQuality(quality: String) {
        if (quality !in QQDownloadSettings.QUALITIES) return
        updateSettings { it.copy(quality = quality) }
        viewModelScope.launch(dispatcher) { preferences.saveQuality(quality) }
    }

    /** 文件操作密钥输入：只改输入框，点「保存」才落盘（删错一个字符不用去 DataStore 捞）。 */
    fun updateFileOpsKeyInput(value: String) {
        updateSettings { it.copy(fileOpsKeyInput = value) }
    }

    fun saveFileOpsKey() {
        val key = _state.value.settings.fileOpsKeyInput.trim()
        viewModelScope.launch(dispatcher) {
            preferences.saveFileOpsKey(key)
            updateSettings { it.copy(fileOpsKey = key, fileOpsKeyInput = key) }
        }
    }

    fun updateSearchQuery(value: String) {
        updateSearch { it.copy(query = value, error = null) }
        searchJob?.cancel()
        val query = value.trim()
        val currentApi = api
        if (query.isEmpty()) {
            searchGeneration++
            updateSearch { it.copy(songs = emptyList(), albums = emptyList(), isSearching = false) }
            return
        }
        if (currentApi == null) {
            updateSearch { it.copy(error = SERVICE_NOT_CONNECTED_MESSAGE) }
            return
        }
        val generation = ++searchGeneration
        updateSearch { it.copy(isSearching = true) }
        searchJob = viewModelScope.launch(dispatcher) {
            delay(SEARCH_DEBOUNCE_MS)
            if (!coroutineContext.isActive || generation != searchGeneration) return@launch
            // 歌曲搜索失败（凭证过期/服务不可达）时不再发专辑请求：注定同样失败，只报一次错。
            var searchFailed = false
            currentApi.search(query).fold(
                onSuccess = { songs ->
                    if (generation == searchGeneration) {
                        updateSearch { it.copy(isSearching = false, songs = songs) }
                        // 搜索能成功就说明服务是通的（「搜过了有没有结果」看 songs / albums，
                        // 不再单独记一个布尔）：把之前那次「连不上」的常驻小字撤掉。
                        updateService { it.copy(serviceReachable = true) }
                        // 标记并入全局集合（以 song_mid 为键），不挂在单曲对象上——
                        // 搜索结果整批替换，挂在单曲上的标记每搜一次就丢。
                        updateDownload {
                            it.copy(
                                inLibraryMids = it.inLibraryMids + songs.filter { song -> song.inLibrary }.map { song -> song.songMid }.toSet(),
                                downloadingMids = songs.filter { song -> song.downloading }.map { song -> song.songMid }.toSet(),
                            )
                        }
                    }
                },
                onFailure = { error ->
                    searchFailed = true
                    // 搜索期失败只写分区小字（toSearch = true），不弹 Snackbar：
                    // 搜索页同时画着曲库结果，动作反馈那条路不能来抢。
                    showSearchError(error)
                }
            )
            if (!searchFailed && coroutineContext.isActive && generation == searchGeneration) {
                currentApi.searchAlbums(query).fold(
                    onSuccess = { albums ->
                        // 代次已过期就悄悄丢弃：searchFailed 已在这一轮开头定好，
                        // 这里再走 showSearchError 会把新一轮搜索的错误文案冲掉。
                        if (generation == searchGeneration) {
                            updateSearch { it.copy(albums = albums) }
                        }
                    },
                    onFailure = { error ->
                        if (generation == searchGeneration) showSearchError(error)
                    }
                )
            }
        }
    }

    /**
     * 下载单曲。[force] = 已在库仍要重下（走确认弹窗后才是 true）。
     * 请求必须带歌手名：服务端判定「已在库」必须同时有歌名和歌手，缺了它一律不拦。
     */
    fun downloadSong(song: QQSongDto, force: Boolean = false) {
        val currentApi = api ?: run {
            updateDownload { it.copy(actionMessage = SERVICE_NOT_CONNECTED_MESSAGE) }
            return
        }
        val quality = selectQuality(song.availableQualities)
        if (quality == null) {
            updateDownload { it.copy(actionMessage = "该歌曲没有可下载的音质") }
            return
        }
        viewModelScope.launch(dispatcher) {
            currentApi.enqueueDownload(
                song.songMid,
                quality,
                title = song.title,
                artist = song.singer,
                force = force
            ).fold(
                onSuccess = { response ->
                    if (response.skipped) {
                        // 服务端判定已在库、没入队：不是错误，静默并入已在库集合让行变灰。
                        updateDownload { it.copy(inLibraryMids = it.inLibraryMids + song.songMid) }
                    } else {
                        startPolling()
                    }
                },
                onFailure = ::showActionError
            )
        }
    }

    /** 点了已在库曲目的下载图标：先弹确认，用户明确要重下才带 force 重发。 */
    fun requestRedownload(song: QQSongDto) {
        updateDownload { it.copy(redownloadPrompt = song) }
    }

    fun dismissRedownload() {
        updateDownload { it.copy(redownloadPrompt = null) }
    }

    fun confirmRedownload() {
        val song = _state.value.download.redownloadPrompt ?: return
        updateDownload { it.copy(redownloadPrompt = null) }
        downloadSong(song, force = true)
    }

    fun downloadAlbum(album: QQAlbumDto) {
        val currentApi = api ?: run {
            updateDownload { it.copy(actionMessage = SERVICE_NOT_CONNECTED_MESSAGE) }
            return
        }
        viewModelScope.launch(dispatcher) {
            updateDownload { it.copy(isEnqueuingAlbum = true, enqueuingAlbumMid = album.albumMid, actionMessage = null) }
            currentApi.enqueueAlbum(album.albumMid, _state.value.settings.quality).fold(
                onSuccess = { response ->
                    updateDownload {
                        it.copy(
                            isEnqueuingAlbum = false,
                            enqueuingAlbumMid = null,
                            // 跳过了几首必须说出来：默默什么都不做会让人以为按钮坏了。
                            actionMessage = albumSkipMessage(response),
                        )
                    }
                    startPolling()
                },
                onFailure = { error ->
                    updateDownload { it.copy(isEnqueuingAlbum = false, enqueuingAlbumMid = null) }
                    showActionError(error)
                }
            )
        }
    }

    /** 打开专辑选曲弹窗并拉取曲目列表（默认全不选：整张下载有专辑行上的专门按钮）。 */
    fun openAlbumPicker(album: QQAlbumDto) {
        val currentApi = api ?: run {
            updateDownload { it.copy(actionMessage = SERVICE_NOT_CONNECTED_MESSAGE) }
            return
        }
        loadAlbumTracks(currentApi, album.albumMid, album.albumName)
    }

    /** 曲目列表没拉到时的「重新获取」。 */
    fun retryAlbumTracks() {
        val currentApi = api ?: return
        val picker = _state.value.download.albumPicker ?: return
        loadAlbumTracks(currentApi, picker.albumMid, picker.albumName)
    }

    fun toggleAlbumTrack(songMid: String) {
        // 正在下载中的不可勾（正在下还点它没意义）；已在库的可以勾——勾选本身就是强制。
        if (songMid in _state.value.download.activeDownloadingMids) return
        val picker = _state.value.download.albumPicker ?: return
        val selected = if (songMid in picker.selectedMids) {
            picker.selectedMids - songMid
        } else {
            picker.selectedMids + songMid
        }
        updateDownload { it.copy(albumPicker = picker.copy(selectedMids = selected)) }
    }

    /** 「全选未下载」：只勾没在库也没在下载中的；用户手动勾过的（含已在库）保留。 */
    fun selectAllAlbumTracks() {
        val picker = _state.value.download.albumPicker ?: return
        val download = _state.value.download
        val undownloaded = picker.tracks
            .filter { it.songMid !in download.inLibraryMids && it.songMid !in download.activeDownloadingMids }
            .map { it.songMid }
            .toSet()
        updateDownload { it.copy(albumPicker = picker.copy(selectedMids = picker.selectedMids + undownloaded)) }
    }

    fun clearAlbumSelection() {
        val picker = _state.value.download.albumPicker ?: return
        updateDownload { it.copy(albumPicker = picker.copy(selectedMids = emptySet())) }
    }

    fun dismissAlbumPicker() {
        if (_state.value.download.albumPicker == null) return
        pickerGeneration++ // 丢弃在途的曲目加载结果，否则关掉后列表会凭空弹回来
        updateDownload { it.copy(albumPicker = null) }
    }

    /** 下载弹窗里勾选的曲目；成功才关弹窗。 */
    fun confirmAlbumDownload() {
        val picker = _state.value.download.albumPicker ?: return
        if (picker.selectedMids.isEmpty()) return
        val currentApi = api ?: run {
            updateDownload { it.copy(actionMessage = SERVICE_NOT_CONNECTED_MESSAGE) }
            return
        }
        viewModelScope.launch(dispatcher) {
            updateDownload {
                it.copy(isEnqueuingAlbum = true, enqueuingAlbumMid = picker.albumMid, actionMessage = null)
            }
            currentApi.enqueueAlbum(picker.albumMid, _state.value.settings.quality, picker.selectedMids.toList()).fold(
                onSuccess = { response ->
                    // 服务端只入队「确实还在这张专辑里」的曲目：少下了必须说出来，
                    // 否则用户勾了 5 首只下 3 首，全程没有任何提示。
                    //（显式勾选时服务端不跳过已在库的，所以差多少只能是这个原因，
                    //  skipped_count 恒为 0，不用减它。）
                    val missing = picker.selectedMids.size - response.count
                    updateDownload {
                        it.copy(
                            isEnqueuingAlbum = false,
                            enqueuingAlbumMid = null,
                            albumPicker = null,
                            actionMessage = if (missing > 0) "有 $missing 首已不在该专辑内，未加入下载" else null,
                        )
                    }
                    startPolling()
                },
                onFailure = { error ->
                    // 失败保留弹窗与勾选：重选一遍代价太高，用户修好问题（如刷新凭证）可直接再点。
                    updateDownload { it.copy(isEnqueuingAlbum = false, enqueuingAlbumMid = null) }
                    setPickerError(error)
                }
            )
        }
    }

    private fun loadAlbumTracks(currentApi: QQDownloadApi, albumMid: String, albumName: String) {
        val generation = ++pickerGeneration
        // 先亮弹窗再拉列表：加载中也要看得见，用户才知道点到了。
        updateDownload {
            it.copy(
                albumPicker = AlbumPickerState(albumMid = albumMid, albumName = albumName),
                actionMessage = null,
            )
        }
        viewModelScope.launch(dispatcher) {
            currentApi.albumSongs(albumMid).fold(
                onSuccess = { tracks ->
                    if (generation != pickerGeneration) return@fold
                    val picker = _state.value.download.albumPicker ?: return@fold
                    updateDownload {
                        it.copy(
                            albumPicker = picker.copy(isLoading = false, tracks = tracks),
                            inLibraryMids = it.inLibraryMids + tracks.filter { track -> track.inLibrary }.map { track -> track.songMid }.toSet(),
                            downloadingMids = tracks.filter { track -> track.downloading }.map { track -> track.songMid }.toSet(),
                        )
                    }
                },
                onFailure = { error ->
                    if (generation != pickerGeneration) return@fold
                    // 加载失败也要收掉转圈，弹窗里改成「错误 + 重新获取」。
                    // 凭证过期仍要点亮过期横幅（弹窗里的「重试提取凭证」靠它出现）。
                    // 错误文案只写进弹窗：模态弹窗挡着 Snackbar，且 Snackbar 显示完就清。
                    applyErrorFlags(error)
                    val message = errorTextFor(error)
                    val picker = _state.value.download.albumPicker ?: return@fold
                    _state.value = _state.value.let { state ->
                        state.copy(
                            download = state.download.copy(
                                albumPicker = picker.copy(isLoading = false, errorMessage = message)
                            ),
                            search = state.search.copy(isSearching = false),
                        )
                    }
                }
            )
        }
    }

    /** 凭证过期后用户点「重试提取」：PC 需已打开 QQ 音乐客户端并登录。 */
    fun retryCredential() {
        val currentApi = api ?: return
        credentialJob = viewModelScope.launch(dispatcher) {
            _state.value = _state.value.let { state ->
                state.copy(
                    service = state.service.copy(isRetryingCredential = true),
                    download = state.download.copy(actionMessage = null),
                )
            }
            currentApi.retryCredential().fold(
                onSuccess = {
                    _state.value = _state.value.let { state ->
                        state.copy(
                            service = state.service.copy(isRetryingCredential = false, credentialExpired = false),
                            search = state.search.copy(error = null),
                            download = state.download.copy(actionMessage = null),
                        )
                    }
                    // 凭证恢复了，搜索页还停在同一句词：自动重搜，别让分区挂着旧错误。
                    if (_state.value.search.query.isNotBlank()) updateSearchQuery(_state.value.search.query)
                    // 弹窗还开着但没拉到曲目（就是被凭证卡住的）→ 自动重拉，不用用户再点一次
                    if (_state.value.download.albumPicker?.tracks.isNullOrEmpty()) retryAlbumTracks()
                },
                onFailure = { error ->
                    updateService { it.copy(isRetryingCredential = false) }
                    // 分区里的「重试」就是这条路：失败文案回到同一格小字，别又弹一条 Snackbar。
                    if (_state.value.download.albumPicker == null) {
                        showSearchError(error)
                    } else {
                        setPickerError(error)
                    }
                }
            )
        }
    }

    /** 搜索分区「重试」：凭证过期走重提，否则重新探活；查询词还在就顺手重搜一遍。 */
    fun retrySearch() {
        if (_state.value.service.credentialExpired) {
            retryCredential()
            return
        }
        viewModelScope.launch(dispatcher) { checkHealth() }
        if (_state.value.search.query.isNotBlank()) updateSearchQuery(_state.value.search.query)
    }

    /** 手动一键刷新凭证：连接后随时可用（比如刚在电脑上重新登录了 QQ 音乐）。 */
    fun refreshCredential() {
        val currentApi = api ?: return
        credentialJob = viewModelScope.launch(dispatcher) {
            _state.value = _state.value.let { state ->
                state.copy(
                    service = state.service.copy(isRetryingCredential = true, credentialMessage = null),
                    download = state.download.copy(actionMessage = null),
                )
            }
            currentApi.retryCredential().fold(
                onSuccess = {
                    _state.value = _state.value.let { state ->
                        state.copy(
                            service = state.service.copy(
                                isRetryingCredential = false,
                                credentialExpired = false,
                                credentialMessage = "凭证已刷新",
                            ),
                            // 搜索页可能还挂着上一次「凭证过期」的小字：刷新成功一并撤掉。
                            search = state.search.copy(error = null),
                        )
                    }
                    if (_state.value.search.query.isNotBlank()) updateSearchQuery(_state.value.search.query)
                },
                onFailure = { error ->
                    // 提取不到新凭证 ≠ 当前凭证过期（常见：电脑上没开 QQ 音乐客户端），
                    // 所以这里不点亮过期横幅，只提示原因。
                    updateService { it.copy(isRetryingCredential = false) }
                    updateDownload {
                        it.copy(
                            actionMessage = when (error) {
                                is QQDownloadError.CredentialExpired ->
                                    "刷新凭证失败：请确认电脑上 QQ 音乐客户端正在运行并已登录"
                                is QQDownloadError.ServiceUnreachable -> SERVICE_UNREACHABLE_MESSAGE
                                else -> "刷新凭证失败，请稍后重试"
                            }
                        )
                    }
                }
            )
        }
    }

    fun refreshJobs() {
        val currentApi = api ?: return
        viewModelScope.launch(dispatcher) {
            currentApi.jobs().fold(::applyJobs, ::showActionError)
        }
    }

    /** 按用户偏好选音质，音源没有时逐档降级。 */
    private fun selectQuality(available: List<String>): String? {
        if (available.isEmpty()) return null
        val preferred = _state.value.settings.quality
        if (preferred in available) return preferred
        // 降级顺序 hires > flac > 320 > 128
        for (q in QQDownloadSettings.QUALITIES) {
            if (q in available) return q
        }
        return available.first()
    }

    private fun applyServiceUrl(url: String) {
        api = apiFactory.create(url)
    }

    private suspend fun checkHealth() {
        val currentApi = api ?: return
        val generation = ++healthGeneration
        healthJob?.cancel()
        healthJob = viewModelScope.launch(dispatcher) {
            var firstAttempt = true
            while (coroutineContext.isActive && generation == healthGeneration) {
                var settled = true
                currentApi.health().fold(
                    onSuccess = { health ->
                        if (generation != healthGeneration) return@launch
                        if (firstAttempt) {
                            applyHealth(health)
                        } else {
                            // 重查轮只刷新曲库字段：别把界面上的其他错误提示冲掉。
                            updateService {
                                it.copy(
                                    libraryReady = health.library.ready,
                                    libraryCount = health.library.count,
                                    libraryRoot = health.libraryRoot,
                                )
                            }
                        }
                        settled = health.library.ready
                    },
                    onFailure = { error ->
                        // 首查失败才报错；重查失败静默停（服务可能关了），不刷屏。
                        // 探活失败是「服务不可用」的持续状态：放搜索分区小字，不弹 Snackbar。
                        if (firstAttempt) showSearchError(error)
                    }
                )
                firstAttempt = false
                if (settled) break
                // 索引还在扫（冷扫约 17 秒）：等 3 秒再查，就绪后界面上才有目录与歌曲数。
                delay(HEALTH_RECHECK_INTERVAL_MS)
            }
        }
    }

    private fun applyHealth(health: QQHealthResponse) {
        // 一次写入：服务标志与分区小字归两块 slice，分两次写会出现「通了但还挂着旧错误」的中间态。
        _state.value = _state.value.let { state ->
            state.copy(
                service = state.service.copy(
                    serviceReachable = true,
                    credentialExpired = health.credentialExpired,
                    libraryReady = health.library.ready,
                    libraryCount = health.library.count,
                    libraryRoot = health.libraryRoot.ifBlank { health.library.root },
                ),
                // ffmpeg 不可用是「服务不能干活」的持续状态，和连不上/凭证过期同类：放分区小字那一格。
                search = state.search.copy(
                    error = if (health.ffmpegReady) null
                    else (health.ffmpegError ?: "电脑端 ffmpeg 不可用，无法处理下载")
                ),
            )
        }
    }

    /** 有活跃（排队/运行中）任务时启动轮询；没有则停。轮询出错也停（避免错误状态下无限重试）。 */
    private fun startPolling() {
        pollJob?.cancel()
        val currentApi = api ?: return
        val generation = ++pollGeneration
        pollJob = viewModelScope.launch(dispatcher) {
            while (coroutineContext.isActive && generation == pollGeneration) {
                val result = currentApi.jobs()
                val hasActive = result.fold(::applyJobs) { error ->
                    showActionError(error)
                    false
                }
                if (!hasActive) break
                delay(JOB_POLL_INTERVAL_MS)
            }
        }
    }

    /** 应用任务列表到状态；返回是否仍有活跃任务。 */
    private fun applyJobs(jobs: List<QQJobDto>): Boolean {
        // jobs 是服务端队列的完整快照：活跃的就是「下载中」的真源（整体替换，下完自动消失）；
        // 变 done 的立刻并入已在库——零额外请求、零延迟，界面不用重搜就变灰。
        val activeMids = jobs.filter { it.isActive }.map { it.songMid }.toSet()
        updateDownload {
            it.copy(
                jobs = jobs,
                downloadingMids = activeMids,
                inLibraryMids = it.inLibraryMids + jobs.filter { job -> job.status == "done" }
                    .map { job -> job.songMid }
                    .toSet(),
            )
        }
        return activeMids.isNotEmpty()
    }

    /** 整张下载的跳过提示；没跳过时返回 null（不打扰）。 */
    private fun albumSkipMessage(response: QQDownloadAlbumResponse): String? = when {
        response.skippedCount <= 0 -> null
        response.count <= 0 -> "整张 ${response.skippedCount} 首都已在曲库，未重复下载"
        else -> "已跳过 ${response.skippedCount} 首（已在库），新加入下载 ${response.count} 首"
    }

    /**
     * 两类失败必须走两条渲染路径（ADR-0006）：
     * - 搜索期失败（不可达 / 凭证过期 / 服务异常）→ 搜索页 QQ 分区内小字常驻；
     * - 下载**动作**失败 → Snackbar。
     * 共用一格会让「电脑没开」把曲库结果盖掉，或者让一条临时提示赖在结果列表里。
     */
    private fun showSearchError(error: Throwable) {
        applyErrorFlags(error)
        val message = errorTextFor(error)
        updateSearch { it.copy(isSearching = false, error = message) }
    }

    private fun showActionError(error: Throwable) {
        applyErrorFlags(error)
        val message = errorTextFor(error)
        // 一次写入：忙碌位与提示分两块 slice，分两次写会闪一下「按钮好了但没提示」。
        _state.value = _state.value.let { state ->
            state.copy(
                search = state.search.copy(isSearching = false),
                download = state.download.copy(isEnqueuingAlbum = false, actionMessage = message),
            )
        }
    }

    /** 弹窗里也要一份错误文案：模态弹窗挡着 Snackbar，且 Snackbar 显示完就清。 */
    private fun setPickerError(error: Throwable) {
        applyErrorFlags(error)
        val message = errorTextFor(error)
        val picker = _state.value.download.albumPicker ?: return
        updateDownload { it.copy(albumPicker = picker.copy(errorMessage = message)) }
    }

    /** 错误附带的服务状态变化（凭证过期 / 服务不可达）：和文案分开，文案保持纯函数。 */
    private fun applyErrorFlags(error: Throwable) {
        when (error) {
            is CancellationException -> throw error
            is QQDownloadError.CredentialExpired -> updateService { it.copy(credentialExpired = true) }
            is QQDownloadError.ServiceUnreachable -> updateService { it.copy(serviceReachable = false) }
            else -> Unit
        }
    }

    private fun errorTextFor(error: Throwable): String = when (error) {
        is CancellationException -> throw error
        is QQDownloadError.CredentialExpired ->
            "QQ 音乐凭证已过期：请在电脑上打开 QQ 音乐并登录，然后点「重试提取凭证」"
        is QQDownloadError.ServiceUnreachable -> SERVICE_UNREACHABLE_MESSAGE
        is QQDownloadError.Http -> "下载服务返回 ${error.statusCode}：${error.detail.take(80)}"
        is QQDownloadError.InvalidResponse -> "下载服务返回了无法识别的数据"
        else -> "请求失败，请稍后重试"
    }

    /** Snackbar 显示完（或用户划掉）后清掉动作反馈。 */
    fun dismissActionMessage() {
        updateDownload { it.copy(actionMessage = null) }
    }

    override fun onCleared() {
        searchJob?.cancel()
        pollJob?.cancel()
        healthJob?.cancel()
        super.onCleared()
    }
}

/** QQ 下载服务的构造门面（测试注入 Fake 用）。 */
interface QQDownloadApiFactory {
    fun create(baseUrl: String): QQDownloadApi
}

/**
 * QQ 下载界面状态的**组合根**（`docs/qq-download-state-refactor.md` / #31）。
 *
 * 拆法与曲库那条线（ADR-0010）一致：内部按**所有权**分四块 slice，组合后仍只对外发
 * 一条 `StateFlow<QQDownloadUiState>`：
 *
 * | slice | 装什么 | 换服务（[cleared]） |
 * |---|---|---|
 * | [service] | 服务地址、连通性、凭证、曲库索引（服务回报的） | 清空，随后写入新地址 |
 * | [settings] | 默认音质、文件操作密钥（用户设的） | **保留**——那是「怎么用」，不是「看到什么」 |
 * | [search] | 查询词、搜索结果、搜索期错误 | 清空 |
 * | [download] | 任务列表、已在库/下载中标记、选曲弹窗、动作提示 | 清空 |
 *
 * **构造参数只有这四块 slice**（#34 删除平铺读法）：屏幕与测试直接读带归属的路径
 * （`state.service.xxx` / `state.search.xxx` / `state.download.xxx`），想写状态就改对应 slice。
 * `QQDownloadUiStateTest` 钉死形状。
 */
data class QQDownloadUiState(
    val service: QQServiceState = QQServiceState(),
    val settings: QQSettingsState = QQSettingsState(),
    val search: QQSearchState = QQSearchState(),
    val download: QQDownloadState = QQDownloadState(),
) {
    /**
     * 换了一台电脑上的导入服务（`autoFillServiceUrl` 算出的地址变了）：
     * 上一条服务的状态、搜索结果、任务与弹窗整块作废，只保留用户偏好 [settings]，
     * 并把新地址写进 [service]。
     *
     * 「作废」= 每块 slice 的 `cleared()`（默认值即已清，新增字段自动跟上；
     * `QQDownloadStateClearedTest` 用反射钉死覆盖）。新服务的探活由调用方随后发起。
     */
    fun cleared(serviceUrl: String): QQDownloadUiState = QQDownloadUiState(
        service = service.cleared().copy(serviceUrl = serviceUrl),
        settings = settings,
        search = search.cleared(),
        download = download.cleared(),
    )
}

/**
 * 搜索页 QQ 分区顶部那行常驻状态：搜索期失败或凭证过期。
 *
 * 凭证过期即便还没搜（健康接口先报出来的）也要提前说，别等用户点了下载才拦。
 * 与 [QQDownloadState.actionMessage] 是两条渲染路径：这条常驻在分区里，那条走 Snackbar。
 */
internal val QQDownloadUiState.searchStatus: String?
    get() = search.error ?: if (service.credentialExpired) {
        "QQ 音乐凭证已过期：在电脑上打开 QQ 音乐并登录，然后点「重试」"
    } else {
        null
    }

/** 选曲弹窗顶部汇总文案：「共 12 首 · 已有 8 首」。 */
internal fun albumPickerSummary(tracks: List<QQAlbumSongDto>, inLibraryMids: Set<String>): String =
    "共 ${tracks.size} 首 · 已有 ${tracks.count { it.songMid in inLibraryMids }} 首"

/** 专辑选曲弹窗状态：曲目列表 + 已勾选的 mid。 */
data class AlbumPickerState(
    val albumMid: String,
    val albumName: String,
    val isLoading: Boolean = true,
    val tracks: List<QQAlbumSongDto> = emptyList(),
    val selectedMids: Set<String> = emptySet(),
    /** 弹窗内错误（曲目列表拉不到 / 入队失败）：模态弹窗挡着 Snackbar，只能写在这里。 */
    val errorMessage: String? = null
)
