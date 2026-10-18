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

    private var api: QQDownloadApi? = null
    private var searchJob: Job? = null
    private var searchGeneration = 0
    private var pollJob: Job? = null
    private var pollGeneration = 0
    private var pickerGeneration = 0
    private var healthJob: Job? = null
    private var healthGeneration = 0

    init {
        viewModelScope.launch(dispatcher) {
            val url = preferences.serviceUrl()
            val quality = preferences.quality()
            val fileOpsKey = preferences.fileOpsKey()
            _state.value = _state.value.copy(serviceUrl = url, quality = quality, fileOpsKey = fileOpsKey, fileOpsKeyInput = fileOpsKey)
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
        if (url == _state.value.serviceUrl) return
        viewModelScope.launch(dispatcher) {
            preferences.saveServiceUrl(url)
            applyServiceUrl(url)
            _state.value = _state.value.copy(serviceUrl = url)
            checkHealth()
        }
    }

    fun updateQuality(quality: String) {
        if (quality !in QQDownloadSettings.QUALITIES) return
        _state.value = _state.value.copy(quality = quality)
        viewModelScope.launch(dispatcher) { preferences.saveQuality(quality) }
    }

    /** 文件操作密钥输入：只改输入框，点「保存」才落盘（删错一个字符不用去 DataStore 捞）。 */
    fun updateFileOpsKeyInput(value: String) {
        _state.value = _state.value.copy(fileOpsKeyInput = value)
    }

    fun saveFileOpsKey() {
        val key = _state.value.fileOpsKeyInput.trim()
        viewModelScope.launch(dispatcher) {
            preferences.saveFileOpsKey(key)
            _state.value = _state.value.copy(fileOpsKey = key, fileOpsKeyInput = key)
        }
    }

    fun updateSearchQuery(value: String) {
        _state.value = _state.value.copy(searchQuery = value, errorMessage = null)
        searchJob?.cancel()
        val query = value.trim()
        val currentApi = api
        if (query.isEmpty()) {
            searchGeneration++
            _state.value = _state.value.copy(songs = emptyList(), albums = emptyList(), isSearching = false)
            return
        }
        if (currentApi == null) {
            _state.value = _state.value.copy(errorMessage = "请先在下方设置并连接下载服务")
            return
        }
        val generation = ++searchGeneration
        _state.value = _state.value.copy(isSearching = true)
        searchJob = viewModelScope.launch(dispatcher) {
            delay(SEARCH_DEBOUNCE_MS)
            if (!coroutineContext.isActive || generation != searchGeneration) return@launch
            // 歌曲搜索失败（凭证过期/服务不可达）时不再发专辑请求：注定同样失败，只报一次错。
            var searchFailed = false
            currentApi.search(query).fold(
                onSuccess = { songs ->
                    if (generation == searchGeneration) {
                        // 标记并入全局集合（以 song_mid 为键），不挂在单曲对象上——
                        // 搜索结果整批替换，挂在单曲上的标记每搜一次就丢。
                        _state.value = _state.value.copy(
                            isSearching = false,
                            songs = songs,
                            searchHadResults = songs.isNotEmpty(),
                            inLibraryMids = _state.value.inLibraryMids + songs.filter { it.inLibrary }.map { it.songMid }.toSet(),
                            downloadingMids = songs.filter { it.downloading }.map { it.songMid }.toSet()
                        )
                    }
                },
                onFailure = { error ->
                    searchFailed = true
                    handleApiError(error)
                }
            )
            if (!searchFailed && coroutineContext.isActive && generation == searchGeneration) {
                currentApi.searchAlbums(query).fold(
                    onSuccess = { albums ->
                        // 代次已过期就悄悄丢弃：searchFailed 已在这一轮开头定好，
                        // 这里再走 handleApiError 会把新一轮搜索的错误文案冲掉。
                        if (generation == searchGeneration) {
                            _state.value = _state.value.copy(albums = albums)
                        }
                    },
                    onFailure = { error ->
                        if (generation == searchGeneration) handleApiError(error)
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
            _state.value = _state.value.copy(errorMessage = "请先连接下载服务")
            return
        }
        val quality = selectQuality(song.availableQualities)
        if (quality == null) {
            _state.value = _state.value.copy(errorMessage = "该歌曲没有可下载的音质")
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
                        _state.value = _state.value.copy(
                            inLibraryMids = _state.value.inLibraryMids + song.songMid
                        )
                    } else {
                        startPolling()
                    }
                },
                onFailure = ::handleApiError
            )
        }
    }

    /** 点了已在库曲目的下载图标：先弹确认，用户明确要重下才带 force 重发。 */
    fun requestRedownload(song: QQSongDto) {
        _state.value = _state.value.copy(redownloadPrompt = song)
    }

    fun dismissRedownload() {
        _state.value = _state.value.copy(redownloadPrompt = null)
    }

    fun confirmRedownload() {
        val song = _state.value.redownloadPrompt ?: return
        _state.value = _state.value.copy(redownloadPrompt = null)
        downloadSong(song, force = true)
    }

    fun downloadAlbum(album: QQAlbumDto) {
        val currentApi = api ?: run {
            _state.value = _state.value.copy(errorMessage = "请先连接下载服务")
            return
        }
        viewModelScope.launch(dispatcher) {
            _state.value = _state.value.copy(isEnqueuingAlbum = true, enqueuingAlbumMid = album.albumMid, errorMessage = null)
            currentApi.enqueueAlbum(album.albumMid, _state.value.quality).fold(
                onSuccess = { response ->
                    _state.value = _state.value.copy(
                        isEnqueuingAlbum = false,
                        enqueuingAlbumMid = null,
                        // 跳过了几首必须说出来：默默什么都不做会让人以为按钮坏了。
                        errorMessage = albumSkipMessage(response)
                    )
                    startPolling()
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(isEnqueuingAlbum = false, enqueuingAlbumMid = null)
                    handleApiError(error)
                }
            )
        }
    }

    /** 打开专辑选曲弹窗并拉取曲目列表（默认全不选：整张下载有专辑行上的专门按钮）。 */
    fun openAlbumPicker(album: QQAlbumDto) {
        val currentApi = api ?: run {
            _state.value = _state.value.copy(errorMessage = "请先连接下载服务")
            return
        }
        loadAlbumTracks(currentApi, album.albumMid, album.albumName)
    }

    /** 曲目列表没拉到时的「重新获取」。 */
    fun retryAlbumTracks() {
        val currentApi = api ?: return
        val picker = _state.value.albumPicker ?: return
        loadAlbumTracks(currentApi, picker.albumMid, picker.albumName)
    }

    fun toggleAlbumTrack(songMid: String) {
        // 正在下载中的不可勾（正在下还点它没意义）；已在库的可以勾——勾选本身就是强制。
        if (songMid in _state.value.activeDownloadingMids) return
        val picker = _state.value.albumPicker ?: return
        val selected = if (songMid in picker.selectedMids) {
            picker.selectedMids - songMid
        } else {
            picker.selectedMids + songMid
        }
        _state.value = _state.value.copy(albumPicker = picker.copy(selectedMids = selected))
    }

    /** 「全选未下载」：只勾没在库也没在下载中的；用户手动勾过的（含已在库）保留。 */
    fun selectAllAlbumTracks() {
        val picker = _state.value.albumPicker ?: return
        val state = _state.value
        val undownloaded = picker.tracks
            .filter { it.songMid !in state.inLibraryMids && it.songMid !in state.activeDownloadingMids }
            .map { it.songMid }
            .toSet()
        _state.value = _state.value.copy(
            albumPicker = picker.copy(selectedMids = picker.selectedMids + undownloaded)
        )
    }

    fun clearAlbumSelection() {
        val picker = _state.value.albumPicker ?: return
        _state.value = _state.value.copy(albumPicker = picker.copy(selectedMids = emptySet()))
    }

    fun dismissAlbumPicker() {
        if (_state.value.albumPicker == null) return
        pickerGeneration++ // 丢弃在途的曲目加载结果，否则关掉后列表会凭空弹回来
        _state.value = _state.value.copy(albumPicker = null)
    }

    /** 下载弹窗里勾选的曲目；成功才关弹窗。 */
    fun confirmAlbumDownload() {
        val picker = _state.value.albumPicker ?: return
        if (picker.selectedMids.isEmpty()) return
        val currentApi = api ?: run {
            _state.value = _state.value.copy(errorMessage = "请先连接下载服务")
            return
        }
        viewModelScope.launch(dispatcher) {
            _state.value = _state.value.copy(
                isEnqueuingAlbum = true, enqueuingAlbumMid = picker.albumMid, errorMessage = null
            )
            currentApi.enqueueAlbum(picker.albumMid, _state.value.quality, picker.selectedMids.toList()).fold(
                onSuccess = { response ->
                    // 服务端只入队「确实还在这张专辑里」的曲目：少下了必须说出来，
                    // 否则用户勾了 5 首只下 3 首，全程没有任何提示。
                    //（显式勾选时服务端不跳过已在库的，所以差多少只能是这个原因，
                    //  skipped_count 恒为 0，不用减它。）
                    val missing = picker.selectedMids.size - response.count
                    _state.value = _state.value.copy(
                        isEnqueuingAlbum = false,
                        enqueuingAlbumMid = null,
                        albumPicker = null,
                        errorMessage = if (missing > 0) "有 $missing 首已不在该专辑内，未加入下载" else null
                    )
                    startPolling()
                },
                onFailure = { error ->
                    // 失败保留弹窗与勾选：重选一遍代价太高，用户修好问题（如刷新凭证）可直接再点。
                    _state.value = _state.value.copy(isEnqueuingAlbum = false, enqueuingAlbumMid = null)
                    handleApiError(error)
                }
            )
        }
    }

    private fun loadAlbumTracks(currentApi: QQDownloadApi, albumMid: String, albumName: String) {
        val generation = ++pickerGeneration
        // 先亮弹窗再拉列表：加载中也要看得见，用户才知道点到了。
        _state.value = _state.value.copy(
            albumPicker = AlbumPickerState(albumMid = albumMid, albumName = albumName),
            errorMessage = null
        )
        viewModelScope.launch(dispatcher) {
            currentApi.albumSongs(albumMid).fold(
                onSuccess = { tracks ->
                    if (generation != pickerGeneration) return@fold
                    val picker = _state.value.albumPicker ?: return@fold
                    _state.value = _state.value.copy(
                        albumPicker = picker.copy(isLoading = false, tracks = tracks),
                        inLibraryMids = _state.value.inLibraryMids + tracks.filter { it.inLibrary }.map { it.songMid }.toSet(),
                        downloadingMids = tracks.filter { it.downloading }.map { it.songMid }.toSet()
                    )
                },
                onFailure = { error ->
                    if (generation != pickerGeneration) return@fold
                    // 加载失败也要收掉转圈，弹窗里改成「错误 + 重新获取」。
                    val picker = _state.value.albumPicker ?: return@fold
                    _state.value = _state.value.copy(albumPicker = picker.copy(isLoading = false))
                    handleApiError(error)
                }
            )
        }
    }

    /** 凭证过期后用户点「重试提取」：PC 需已打开 QQ 音乐客户端并登录。 */
    fun retryCredential() {
        val currentApi = api ?: return
        viewModelScope.launch(dispatcher) {
            _state.value = _state.value.copy(isRetryingCredential = true, errorMessage = null)
            currentApi.retryCredential().fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        isRetryingCredential = false,
                        credentialExpired = false,
                        errorMessage = null
                    )
                    // 弹窗还开着但没拉到曲目（就是被凭证卡住的）→ 自动重拉，不用用户再点一次
                    if (_state.value.albumPicker?.tracks.isNullOrEmpty()) retryAlbumTracks()
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(isRetryingCredential = false)
                    handleApiError(error)
                }
            )
        }
    }

    /** 手动一键刷新凭证：连接后随时可用（比如刚在电脑上重新登录了 QQ 音乐）。 */
    fun refreshCredential() {
        val currentApi = api ?: return
        viewModelScope.launch(dispatcher) {
            _state.value = _state.value.copy(isRetryingCredential = true, credentialMessage = null, errorMessage = null)
            currentApi.retryCredential().fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        isRetryingCredential = false,
                        credentialExpired = false,
                        credentialMessage = "凭证已刷新"
                    )
                },
                onFailure = { error ->
                    // 提取不到新凭证 ≠ 当前凭证过期（常见：电脑上没开 QQ 音乐客户端），
                    // 所以这里不点亮过期横幅，只提示原因。
                    _state.value = _state.value.copy(isRetryingCredential = false)
                    _state.value = _state.value.copy(
                        errorMessage = when (error) {
                            is QQDownloadError.CredentialExpired ->
                                "刷新凭证失败：请确认电脑上 QQ 音乐客户端正在运行并已登录"
                            is QQDownloadError.ServiceUnreachable ->
                                "连不上下载服务：确认电脑上已启动（start.bat），且手机和电脑在同一网络"
                            else -> "刷新凭证失败，请稍后重试"
                        }
                    )
                }
            )
        }
    }

    fun refreshJobs() {
        val currentApi = api ?: return
        viewModelScope.launch(dispatcher) {
            currentApi.jobs().fold(::applyJobs, ::handleApiError)
        }
    }

    /** 按用户偏好选音质，音源没有时逐档降级。 */
    private fun selectQuality(available: List<String>): String? {
        if (available.isEmpty()) return null
        val preferred = _state.value.quality
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
                            _state.value = _state.value.copy(
                                libraryReady = health.library.ready,
                                libraryCount = health.library.count,
                                libraryRoot = health.libraryRoot
                            )
                        }
                        settled = health.library.ready
                    },
                    onFailure = { error ->
                        // 首查失败才报错；重查失败静默停（服务可能关了），不刷屏。
                        if (firstAttempt) handleApiError(error)
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
        _state.value = _state.value.copy(
            serviceReachable = true,
            credentialExpired = health.credentialExpired,
            libraryReady = health.library.ready,
            libraryCount = health.library.count,
            libraryRoot = health.libraryRoot.ifBlank { health.library.root },
            errorMessage = if (health.ffmpegReady) null
                else (health.ffmpegError ?: "电脑端 ffmpeg 不可用，无法处理下载")
        )
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
                    handleApiError(error)
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
        _state.value = _state.value.copy(
            jobs = jobs,
            downloadingMids = jobs.filter { it.status == "queued" || it.status == "running" }.map { it.songMid }.toSet(),
            inLibraryMids = _state.value.inLibraryMids + jobs.filter { it.status == "done" }.map { it.songMid }.toSet()
        )
        return jobs.any { it.status == "queued" || it.status == "running" }
    }

    /** 整张下载的跳过提示；没跳过时返回 null（不打扰）。 */
    private fun albumSkipMessage(response: QQDownloadAlbumResponse): String? = when {
        response.skippedCount <= 0 -> null
        response.count <= 0 -> "整张 ${response.skippedCount} 首都已在曲库，未重复下载"
        else -> "已跳过 ${response.skippedCount} 首（已在库），新加入下载 ${response.count} 首"
    }

    private fun handleApiError(error: Throwable) {
        when (error) {
            is CancellationException -> throw error
            is QQDownloadError.CredentialExpired -> {
                _state.value = _state.value.copy(
                    credentialExpired = true,
                    isSearching = false,
                    isEnqueuingAlbum = false,
                    errorMessage = "QQ 音乐凭证已过期：请在电脑上打开 QQ 音乐并登录，然后点「重试提取凭证」"
                )
            }
            is QQDownloadError.ServiceUnreachable -> {
                _state.value = _state.value.copy(
                    serviceReachable = false,
                    isSearching = false,
                    errorMessage = "连不上下载服务：确认电脑上已启动（start.bat），且手机和电脑在同一网络"
                )
            }
            is QQDownloadError.Http -> {
                _state.value = _state.value.copy(
                    isSearching = false,
                    errorMessage = "下载服务返回 ${error.statusCode}：${error.detail.take(80)}"
                )
            }
            is QQDownloadError.InvalidResponse -> {
                _state.value = _state.value.copy(isSearching = false, errorMessage = "下载服务返回了无法识别的数据")
            }
            else -> {
                _state.value = _state.value.copy(isSearching = false, errorMessage = "请求失败，请稍后重试")
            }
        }
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

data class QQDownloadUiState(
    val serviceUrl: String = "",
    val serviceReachable: Boolean = false,
    val credentialExpired: Boolean = false,
    val quality: String = QQDownloadSettings.DEFAULT_QUALITY,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val songs: List<QQSongDto> = emptyList(),
    val albums: List<QQAlbumDto> = emptyList(),
    val searchHadResults: Boolean = false,
    val jobs: List<QQJobDto> = emptyList(),
    val isEnqueuingAlbum: Boolean = false,
    /** 正在入队的专辑 mid：只有对应专辑行显示转圈，避免多专辑同时点全转。 */
    val enqueuingAlbumMid: String? = null,
    val isRetryingCredential: Boolean = false,
    /** 最近一次手动刷新凭证的反馈（如「凭证已刷新」），下一次刷新开始时清除。 */
    val credentialMessage: String? = null,
    /** 专辑选曲弹窗；null = 未打开。 */
    val albumPicker: AlbumPickerState? = null,
    /** 已在库的曲目（键 song_mid）：搜索/弹窗响应、任务完成、服务端跳过都并进来；会话内只增不减。 */
    val inLibraryMids: Set<String> = emptySet(),
    /** 服务端最新一次回传的「下载中」标记（每次响应整体替换；渲染一律用 activeDownloadingMids）。 */
    val downloadingMids: Set<String> = emptySet(),
    /** 曲库索引状态（排查用：它说没有但你明明有，先看它扫的是哪个目录、扫到多少首）。 */
    val libraryReady: Boolean = true,
    val libraryCount: Int = 0,
    val libraryRoot: String = "",
    /** 非 null = 用户点了已在库曲目的下载图标，等确认「仍要重新下载」。 */
    val redownloadPrompt: QQSongDto? = null,
    /** 已保存的文件操作密钥（删歌/改名用，见工单 06）。 */
    val fileOpsKey: String = "",
    /** 密钥输入框的当前值；与 [fileOpsKey] 不同才允许保存。 */
    val fileOpsKeyInput: String = "",
    val errorMessage: String? = null
) {
    /** 正在下载的 mid：服务端最新标记 ∪ 本地任务队列 queued/running（刚点下去还没轮询到也要立刻准）。 */
    val activeDownloadingMids: Set<String>
        get() = downloadingMids + jobs.filter { it.status == "queued" || it.status == "running" }
            .map { it.songMid }
            .toSet()
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
    val selectedMids: Set<String> = emptySet()
)
