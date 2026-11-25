package com.example.pulsar.library

import com.example.pulsar.data.companion.CompanionLibraryConnection
import com.example.pulsar.data.companion.CompanionLibraryError
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import com.example.pulsar.download.LinkedDownload
import com.example.pulsar.offline.offlineUnplayableReason
import com.example.pulsar.playback.PlaybackGate
import com.example.pulsar.state.SearchState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class CompanionLibraryTrialState(
    val tracks: List<Track>? = null,
    val isLoading: Boolean = true,
    val error: CompanionLibraryError? = null,
    val needsPairing: Boolean = false,
    val serviceUrl: String? = null,
    val search: SearchState = SearchState(),
    val isOffline: Boolean = false,
    val reusedTrackIds: Set<String> = emptySet(),
) {
    private val tracksById by lazy(LazyThreadSafetyMode.NONE) { tracks.orEmpty().associateBy(Track::id) }

    fun contains(track: Track): Boolean = tracksById[track.id] == track
}

/** 一次主动试用的曲库。只接入已核对并确认的本机副本，不借用 Jellyfin 在线会话。入口在主线程调用。 */
internal class CompanionLibraryTrial(
    private val connection: CompanionLibraryConnection,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val loadLinked: suspend (String) -> List<LinkedDownload> = { emptyList() },
    private val checkCatalog: suspend (String, List<Track>) -> Unit = { _, _ -> },
) : PlaybackGate {
    private val _state = MutableStateFlow(CompanionLibraryTrialState())
    val state = _state.asStateFlow()

    private var active = false
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var links = emptyMap<String, LinkedDownload>()
    private var linksJob: Job? = null

    fun enter() {
        if (active) return
        active = true
        retry()
    }

    fun leave() {
        active = false
        loadJob?.cancel()
        loadJob = null
        searchJob?.cancel()
        searchJob = null
        linksJob?.cancel()
        links = emptyMap()
        _state.value = CompanionLibraryTrialState()
    }

    fun retry() {
        if (!active || loadJob?.isActive == true) return
        searchJob?.cancel()
        linksJob?.cancel()
        val current = state.value
        _state.value = current.copy(
            tracks = null,
            isLoading = true,
            error = null,
            search = SearchState(query = current.search.query, isSearching = current.search.query.isNotBlank()),
        )
        loadJob = scope.launch(dispatcher) {
            val result = connection.refresh()
            if (isActive) applyLoad(result)
        }
    }

    /** 只有成功才替换配对与曲目；编辑时填错不影响刚才可用的连接。 */
    suspend fun pair(address: String, key: String): Result<Unit> {
        check(active)
        val result = connection.pair(address, key)
        currentCoroutineContext().ensureActive()
        if (active && result.isSuccess) {
            loadJob?.cancel()
            linksJob?.cancel()
            applyLoad(result)
        }
        return result.map { Unit }
    }

    private suspend fun applyLoad(result: Result<List<Track>?>) {
        val error = result.exceptionOrNull() as? CompanionLibraryError
        val onlineTracks = result.getOrNull()
        val pairingScope = connection.savedScopeOrNull()
        if (pairingScope != null && onlineTracks != null) checkCatalog(pairingScope, onlineTracks)
        val loaded = if (error == null || error == CompanionLibraryError.Network) {
            pairingScope?.let { loadLinked(it) }.orEmpty()
        } else emptyList()
        currentCoroutineContext().ensureActive()
        if (!active) return
        val revisions = onlineTracks?.associateBy(Track::id)
        links = loaded.filter { revisions == null || revisions[it.track.id]?.sourceRevision == it.track.sourceRevision }
            .associateBy { it.track.id }
        val offline = error == CompanionLibraryError.Network && links.isNotEmpty()
        val tracks = if (offline) links.values.map(LinkedDownload::track) else onlineTracks
        val current = state.value
        val query = current.search.query
        _state.value = current.copy(
            tracks = tracks,
            isLoading = false,
            error = error.takeUnless { offline },
            isOffline = offline,
            reusedTrackIds = links.keys,
            needsPairing = error == CompanionLibraryError.Unauthorized || (result.isSuccess && onlineTracks == null),
            serviceUrl = connection.serviceUrl,
            // 曲目表与搜索结果一并更换，不发布「新来源 + 旧结果」的中间状态。
            search = SearchState(query = query, isSearching = query.isNotBlank() && tracks != null, isOfflineSearch = offline),
        )
        updateSearchQuery(query)
    }

    /** 旧账号或本机核对结果变化后，只重读本机关联，不重新请求电脑。 */
    fun reloadLinkedDownloads() {
        val current = state.value
        if (!active || current.isLoading || current.needsPairing) return
        val offline = current.isOffline || current.error == CompanionLibraryError.Network
        if (!offline && current.tracks == null) return
        linksJob?.cancel()
        links = emptyMap()
        _state.value = current.copy(
            reusedTrackIds = emptySet(),
            tracks = current.tracks.takeUnless { offline },
            search = if (offline) SearchState(query = current.search.query, isOfflineSearch = true) else current.search,
        )
        if (offline) searchJob?.cancel()
        linksJob = scope.launch(dispatcher) {
            val pairing = connection.savedScopeOrNull()
            val loaded = pairing?.let { loadLinked(it) }.orEmpty()
            if (!isActive || !active) return@launch
            val latest = state.value
            val revisions = latest.tracks.orEmpty().associateBy(Track::id)
            links = loaded.filter { offline || revisions[it.track.id]?.sourceRevision == it.track.sourceRevision }
                .associateBy { it.track.id }
            val showOffline = offline && links.isNotEmpty()
            _state.value = latest.copy(
                tracks = if (showOffline) links.values.map(LinkedDownload::track) else latest.tracks,
                reusedTrackIds = links.keys,
                isOffline = showOffline,
                error = if (offline && !showOffline) CompanionLibraryError.Network else null,
            )
            if (offline) updateSearchQuery(latest.search.query)
        }
    }

    fun updateSearchQuery(value: String) {
        if (!active) return
        searchJob?.cancel()
        val current = state.value
        val needle = value.trim()
        val tracks = current.tracks
        _state.value = current.copy(search = SearchState(
            query = value,
            isSearching = needle.isNotEmpty() && (current.isLoading || tracks != null),
            isOfflineSearch = current.isOffline,
        ))
        if (needle.isEmpty() || tracks == null) return
        searchJob = scope.launch(dispatcher) {
            delay(LIBRARY_SEARCH_DEBOUNCE_MS)
            val matches = tracks.filter {
                it.title.contains(needle, ignoreCase = true) || it.artist.contains(needle, ignoreCase = true) ||
                    it.album.contains(needle, ignoreCase = true)
            }
            if (isActive) {
                val latest = state.value
                _state.value = latest.copy(search = latest.search.copy(
                    results = SearchResults(tracks = matches), isSearching = false,
                ))
            }
        }
    }

    override fun isPlayable(track: Track): Boolean = unplayableReason(track) == null

    override fun unplayableReason(track: Track): String? {
        val current = state.value
        if (!current.contains(track)) return current.error?.message ?: "请从当前电脑曲库重新选择歌曲"
        return offlineUnplayableReason(track, current.reusedTrackIds, current.isOffline)
    }

    fun streamUrl(track: Track): String? = if (!isPlayable(track)) null
        else links[track.id]?.playbackUri ?: connection.streamUrl(track.id)
}
