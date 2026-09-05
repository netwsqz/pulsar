package com.example.pulsar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pulsar.data.qqdownload.QQDownloadApi
import com.example.pulsar.data.qqdownload.QQDownloadError
import com.example.pulsar.data.qqdownload.QQDownloadSettings
import com.example.pulsar.data.qqdownload.QQJobDto
import com.example.pulsar.data.qqdownload.QQAlbumDto
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

    init {
        viewModelScope.launch(dispatcher) {
            val url = preferences.serviceUrl()
            val quality = preferences.quality()
            _state.value = _state.value.copy(serviceUrl = url, quality = quality)
            if (url.isNotBlank()) {
                applyServiceUrl(url)
                checkHealth()
            }
        }
    }

    /** 服务地址编辑：只在用户明确点「连接」时生效，避免打字过程中狂发请求。 */
    fun updateServiceUrlInput(value: String) {
        _state.value = _state.value.copy(serviceUrlInput = value, errorMessage = null)
    }

    fun connectService() {
        val input = _state.value.serviceUrlInput.trim().trimEnd('/')
        if (input.isBlank()) {
            _state.value = _state.value.copy(errorMessage = "请输入服务地址，例如 http://192.168.1.10:8900")
            return
        }
        if (!input.startsWith("http://") && !input.startsWith("https://")) {
            _state.value = _state.value.copy(errorMessage = "地址需以 http:// 开头")
            return
        }
        viewModelScope.launch(dispatcher) {
            preferences.saveServiceUrl(input)
            applyServiceUrl(input)
            _state.value = _state.value.copy(serviceUrl = input)
            checkHealth()
        }
    }

    fun updateQuality(quality: String) {
        if (quality !in QQDownloadSettings.QUALITIES) return
        _state.value = _state.value.copy(quality = quality)
        viewModelScope.launch(dispatcher) { preferences.saveQuality(quality) }
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
                        _state.value = _state.value.copy(isSearching = false, songs = songs, searchHadResults = songs.isNotEmpty())
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
                        if (generation == searchGeneration) {
                            _state.value = _state.value.copy(albums = albums)
                        }
                    },
                    onFailure = ::handleApiError
                )
            }
        }
    }

    fun downloadSong(song: QQSongDto) {
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
            currentApi.enqueueDownload(song.songMid, quality, title = song.title).fold(
                onSuccess = { startPolling() },
                onFailure = ::handleApiError
            )
        }
    }

    fun downloadAlbum(album: QQAlbumDto) {
        val currentApi = api ?: run {
            _state.value = _state.value.copy(errorMessage = "请先连接下载服务")
            return
        }
        viewModelScope.launch(dispatcher) {
            _state.value = _state.value.copy(isEnqueuingAlbum = true, enqueuingAlbumMid = album.albumMid, errorMessage = null)
            currentApi.enqueueAlbum(album.albumMid, _state.value.quality).fold(
                onSuccess = { count ->
                    _state.value = _state.value.copy(isEnqueuingAlbum = false, enqueuingAlbumMid = null)
                    startPolling()
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(isEnqueuingAlbum = false, enqueuingAlbumMid = null)
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
        currentApi.health().fold(
            onSuccess = { health ->
                _state.value = _state.value.copy(
                    serviceReachable = true,
                    credentialExpired = health.credentialExpired,
                    errorMessage = if (health.ffmpegReady) null
                        else (health.ffmpegError ?: "电脑端 ffmpeg 不可用，无法处理下载")
                )
            },
            onFailure = ::handleApiError
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
        _state.value = _state.value.copy(jobs = jobs)
        return jobs.any { it.status == "queued" || it.status == "running" }
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
        super.onCleared()
    }
}

/** QQ 下载服务的构造门面（测试注入 Fake 用）。 */
interface QQDownloadApiFactory {
    fun create(baseUrl: String): QQDownloadApi
}

data class QQDownloadUiState(
    val serviceUrl: String = "",
    val serviceUrlInput: String = "",
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
    val errorMessage: String? = null
)
