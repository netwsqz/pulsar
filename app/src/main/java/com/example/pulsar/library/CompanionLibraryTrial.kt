package com.example.pulsar.library

import com.example.pulsar.data.companion.CompanionLibraryConnection
import com.example.pulsar.data.companion.CompanionLibraryError
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
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
) {
    private val tracksById by lazy(LazyThreadSafetyMode.NONE) { tracks.orEmpty().associateBy(Track::id) }

    fun contains(track: Track): Boolean = tracksById[track.id] == track
}

/** 一次主动试用的曲库。两页共用电脑的曲目清单，不借用旧会话、快照或下载。入口在主线程调用。 */
internal class CompanionLibraryTrial(
    private val connection: CompanionLibraryConnection,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : PlaybackGate {
    private val _state = MutableStateFlow(CompanionLibraryTrialState())
    val state = _state.asStateFlow()

    private var active = false
    private var loadJob: Job? = null
    private var searchJob: Job? = null

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
        _state.value = CompanionLibraryTrialState()
    }

    fun retry() {
        if (!active || loadJob?.isActive == true) return
        searchJob?.cancel()
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
            applyLoad(result)
        }
        return result.map { Unit }
    }

    private fun applyLoad(result: Result<List<Track>?>) {
        val error = result.exceptionOrNull() as? CompanionLibraryError
        val tracks = result.getOrNull()
        val current = state.value
        val query = current.search.query
        _state.value = current.copy(
            tracks = tracks,
            isLoading = false,
            error = error,
            needsPairing = error == CompanionLibraryError.Unauthorized || (result.isSuccess && tracks == null),
            serviceUrl = connection.serviceUrl,
            // 曲目表与搜索结果一并更换，不发布「新来源 + 旧结果」的中间状态。
            search = SearchState(query = query, isSearching = query.isNotBlank() && tracks != null),
        )
        updateSearchQuery(query)
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

    override fun isPlayable(track: Track): Boolean = state.value.contains(track)

    override fun unplayableReason(track: Track): String? =
        if (isPlayable(track)) null else state.value.error?.message ?: "请从当前电脑曲库重新选择歌曲"

    fun streamUrl(track: Track): String? = if (isPlayable(track)) connection.streamUrl(track.id) else null
}
