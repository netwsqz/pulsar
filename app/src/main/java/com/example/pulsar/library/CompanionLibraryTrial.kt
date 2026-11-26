package com.example.pulsar.library

import com.example.pulsar.data.companion.CompanionLibraryConnection
import com.example.pulsar.data.companion.CompanionLibraryError
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import com.example.pulsar.download.CompanionDownloadCopy
import com.example.pulsar.download.CompanionDownloadTarget
import com.example.pulsar.download.CompanionDownloadedAlbum
import com.example.pulsar.download.LinkedDownload
import com.example.pulsar.download.isCompanionDownloadMediaId
import com.example.pulsar.offline.albumIdentityOf
import com.example.pulsar.offline.normalizedNameKey
import com.example.pulsar.offline.offlineUnplayableReason
import com.example.pulsar.offline.toAlbumCard
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
    val downloadedTrackIds: Set<String> = emptySet(),
    val downloads: List<CompanionDownloadedAlbum> = emptyList(),
) {
    private val tracksById by lazy(LazyThreadSafetyMode.NONE) { tracks.orEmpty().associateBy(Track::id) }

    fun contains(track: Track): Boolean = tracksById[track.id] == track
}

/** 电脑曲库试用：在线读电脑曲库，断网只展示完整的本机下载，不借用 Jellyfin 会话。入口在主线程调用。 */
internal class CompanionLibraryTrial(
    private val connection: CompanionLibraryConnection,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val loadLinked: suspend (String) -> List<LinkedDownload> = { emptyList() },
    private val checkCatalog: suspend (String, List<Track>) -> Unit = { _, _ -> },
    private val loadDownloads: suspend (String) -> List<CompanionDownloadedAlbum> = { emptyList() },
) : PlaybackGate {
    private val _state = MutableStateFlow(CompanionLibraryTrialState())
    val state = _state.asStateFlow()

    private var active = false
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var links = emptyMap<String, LinkedDownload>()
    private var copies = emptyMap<String, CompanionDownloadCopy>()
    private var localJob: Job? = null
    private var pairingScope: String? = null
    private var onlineTracks: List<Track>? = null
    private var loadError: CompanionLibraryError? = null

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
        localJob?.cancel()
        pairingScope = null
        onlineTracks = null
        loadError = null
        links = emptyMap()
        copies = emptyMap()
        _state.value = CompanionLibraryTrialState()
    }

    fun retry() {
        if (!active || loadJob?.isActive == true) return
        searchJob?.cancel()
        localJob?.cancel()
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
            localJob?.cancel()
            applyLoad(result)
        }
        return result.map { Unit }
    }

    private suspend fun applyLoad(result: Result<List<Track>?>) {
        val error = result.exceptionOrNull() as? CompanionLibraryError
        val catalog = result.getOrNull()
        val pairing = connection.savedScopeOrNull()
        if (pairing != null && catalog != null) checkCatalog(pairing, catalog)
        applyLocalCopies(pairing, catalog, error)
    }

    private suspend fun applyLocalCopies(pairing: String?, catalog: List<Track>?, error: CompanionLibraryError?) {
        val groups = pairing?.let { loadDownloads(it) }.orEmpty()
        val linked = if (error == null || error == CompanionLibraryError.Network) {
            pairing?.let { loadLinked(it) }.orEmpty()
        } else emptyList()
        currentCoroutineContext().ensureActive()
        if (!active) return
        pairingScope = pairing
        onlineTracks = catalog
        loadError = error
        val revisions = catalog?.associateBy(Track::id)
        links = linked.filter { revisions == null || revisions[it.track.id]?.sourceRevision == it.track.sourceRevision }
            .associateBy { it.track.id }
        val allCopies = groups.flatMap { it.copies }
        val browseCopies = allCopies.filter {
            revisions == null || revisions[it.track.id]?.sourceRevision == it.track.sourceRevision
        }.associateBy { it.track.id }
        copies = allCopies.associateBy { it.savedTrack.id } + browseCopies
        val offline = pairing != null && error == CompanionLibraryError.Network
        val tracks = if (offline) {
            (browseCopies.values.map { it.track } + links.values.map { it.track }).distinctBy(Track::id)
        } else catalog
        val current = state.value
        val query = current.search.query
        val resultsChanged = tracks != current.tracks || offline != current.isOffline
        _state.value = current.copy(
            tracks = tracks,
            isLoading = false,
            error = error.takeUnless { offline },
            isOffline = offline,
            downloadedTrackIds = links.keys + browseCopies.keys,
            downloads = groups,
            needsPairing = error == CompanionLibraryError.Unauthorized || (error == null && catalog == null),
            serviceUrl = connection.serviceUrl,
            search = if (resultsChanged) SearchState(
                query = query, isSearching = query.isNotBlank() && tracks != null, isOfflineSearch = offline,
            ) else current.search,
        )
        if (resultsChanged) updateSearchQuery(query)
    }

    /** 名单、完整副本或旧账号变化时，只重读本机，不重新请求电脑。 */
    fun reloadLocalDownloads() {
        if (!active || state.value.isLoading) return
        localJob?.cancel()
        localJob = scope.launch(dispatcher) {
            applyLocalCopies(connection.savedScopeOrNull(), onlineTracks, loadError)
        }
    }

    fun albumFor(track: Track): Album? {
        if (!state.value.contains(track)) return null
        val identity = albumIdentityOf(track)
        return if (identity.key.isNotEmpty()) identity.toAlbumCard().copy(id = albumId(track))
            else Album(albumId(track), "未标注专辑", track.artist, null, null)
    }

    // PC 清单还没有专辑 ID。至少用歌手区分同名专辑，避免点下载时混进别人的歌。
    private fun albumId(track: Track): String {
        val artist = normalizedNameKey(track.artist).orEmpty()
        return "${artist.length}:$artist:${albumIdentityOf(track).key}"
    }

    fun albumTracks(album: Album): List<Track>? = state.value.tracks?.filter { albumId(it) == album.id }

    fun downloadTarget(album: Album, tracks: List<Track>): CompanionDownloadTarget? {
        val pairing = pairingScope ?: return null
        if (state.value.isLoading || state.value.isOffline || state.value.error != null) return null
        val sources = tracks.mapNotNull { track ->
            if (!state.value.contains(track) || track.sourceRevision == null) return@mapNotNull null
            connection.streamUrl(track.id)?.let { track to it }
        }
        return CompanionDownloadTarget(pairing, album, sources).takeIf { sources.isNotEmpty() && sources.size == tracks.size }
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
        if (isCompanionDownloadMediaId(track.id)) {
            return if (copies[track.id]?.savedTrack == track) null else "电脑曲库的下载暂不可播"
        }
        if (!current.contains(track)) return current.error?.message ?: "请从当前电脑曲库重新选择歌曲"
        return offlineUnplayableReason(track, current.downloadedTrackIds, current.isOffline)
    }

    fun streamUrl(track: Track): String? = if (!isPlayable(track)) null
        else copies[track.id]?.playbackUri ?: links[track.id]?.playbackUri ?: connection.streamUrl(track.id)
}
