package com.example.pulsar.library

import com.example.pulsar.LibrarySection
import com.example.pulsar.data.companion.CompanionArtworkRequest
import com.example.pulsar.data.companion.CompanionLibraryConnection
import com.example.pulsar.data.companion.CompanionLibraryError
import com.example.pulsar.data.companion.CompanionPlaylist
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import com.example.pulsar.download.CompanionDownloadCopy
import com.example.pulsar.download.CompanionDownloadTarget
import com.example.pulsar.download.CompanionDownloadedAlbum
import com.example.pulsar.download.LinkedDownload
import com.example.pulsar.download.isCompanionDownloadMediaId
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
    val catalog: CompanionTrackCatalog? = null,
    val isLoading: Boolean = true,
    val error: CompanionLibraryError? = null,
    val needsPairing: Boolean = false,
    val serviceUrl: String? = null,
    val pairingScope: String? = null,
    val search: SearchState = SearchState(),
    val isOffline: Boolean = false,
    val downloadedTrackIds: Set<String> = emptySet(),
    val downloads: List<CompanionDownloadedAlbum> = emptyList(),
) {
    val tracks: List<Track>? get() = catalog?.tracks
    val albums: List<Album>? get() = catalog?.albums

    fun contains(track: Track): Boolean = catalog?.track(track.id) == track
}

internal data class CompanionPlaylistsState(
    val entries: List<CompanionPlaylist>? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: CompanionLibraryError? = null,
) {
    val items: List<Playlist>? get() = entries?.map(CompanionPlaylist::playlist)
    fun entry(playlist: Playlist): CompanionPlaylist? = entries?.firstOrNull { it.playlist.id == playlist.id }
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
    private val _playlists = MutableStateFlow(CompanionPlaylistsState())
    val playlists = _playlists.asStateFlow()
    private var playlistLoadJob: Job? = null
    private var showingPlaylists = false
    private var playlistGeneration = 0L

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
        showingPlaylists = false
        resetPlaylists()
        _state.value = CompanionLibraryTrialState()
    }

    fun retry() = reload { applyLoad(connection.refresh()) }

    private fun reload(load: suspend () -> Unit) {
        if (!active || loadJob?.isActive == true) return
        playlistLoadJob?.cancel()
        playlistLoadJob = null
        _playlists.value = playlists.value.copy(isLoading = false)
        searchJob?.cancel()
        localJob?.cancel()
        val current = state.value
        _state.value = current.copy(
            catalog = null,
            isLoading = true,
            error = null,
            search = SearchState(query = current.search.query, isSearching = current.search.query.isNotBlank()),
        )
        loadJob = scope.launch(dispatcher) { load() }
    }

    /** 只有成功才替换配对与曲目；编辑时填错不影响刚才可用的连接。 */
    suspend fun pair(address: String, key: String): Result<Unit> {
        check(active)
        val result = connection.pair(address, key)
        currentCoroutineContext().ensureActive()
        if (active && result.isSuccess) {
            loadJob?.cancel()
            localJob?.cancel()
            searchJob?.cancel()
            resetPlaylists()
            pairingScope = null
            onlineTracks = null
            links = emptyMap()
            copies = emptyMap()
            _state.value = CompanionLibraryTrialState(
                pairingScope = state.value.pairingScope, search = SearchState(query = state.value.search.query),
            )
            applyLoad(result)
        }
        return result.map { Unit }
    }

    private suspend fun applyLoad(result: Result<List<Track>?>) {
        val error = result.exceptionOrNull() as? CompanionLibraryError
        val catalog = result.getOrNull()
        val pairing = connection.savedScopeOrNull()
        if (pairing != pairingScope) resetPlaylists()
        if (pairing != null && catalog != null) checkCatalog(pairing, catalog)
        applyLocalCopies(pairing, catalog, error)
        if (showingPlaylists) refreshPlaylists()
    }

    fun onSectionEntered(section: LibrarySection) {
        showingPlaylists = section == LibrarySection.Playlists || section is LibrarySection.PlaylistDetail
        if (showingPlaylists) refreshPlaylists()
    }

    fun refreshPlaylists() {
        val pairing = onlinePlaylistScope() ?: return
        if (playlistLoadJob?.isActive == true || playlists.value.isSaving) return
        val generation = playlistGeneration
        _playlists.value = playlists.value.copy(isLoading = true, error = null)
        playlistLoadJob = scope.launch(dispatcher) {
            val result = connection.playlists(pairing)
            if (!isActive || generation != playlistGeneration) return@launch
            _playlists.value = CompanionPlaylistsState(
                entries = result.getOrNull(), error = result.exceptionOrNull() as? CompanionLibraryError,
            )
            handlePlaylistConnectionFailure(result.exceptionOrNull())
        }
    }

    private fun handlePlaylistConnectionFailure(error: Throwable?) {
        val connectionError = when (error) {
            CompanionLibraryError.Network -> CompanionLibraryError.Network
            CompanionLibraryError.Unauthorized -> CompanionLibraryError.Unauthorized
            else -> return
        }
        val pairing = onlinePlaylistScope() ?: return
        // 让整份曲库按同一规则转离线或提示配对，而不是只在播放列表节留下网络错误。
        reload { applyLocalCopies(pairing, null, connectionError) }
    }

    private fun onlinePlaylistScope(): String? {
        val current = state.value
        return pairingScope.takeIf { active && !current.isLoading && !current.isOffline && current.error == null }
    }

    private fun resetPlaylists() {
        playlistGeneration++
        playlistLoadJob?.cancel()
        playlistLoadJob = null
        _playlists.value = CompanionPlaylistsState()
    }

    fun playlistTracks(playlist: Playlist): List<Track>? {
        if (onlinePlaylistScope() == null) return null
        val catalog = state.value.catalog ?: return null
        return playlists.value.entry(playlist)?.trackIds?.mapNotNull(catalog::track)
    }

    suspend fun createPlaylist(name: String): Result<Playlist> {
        val normalized = name.trim()
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length) > 120) {
            return Result.failure(CompanionLibraryError.PlaylistName)
        }
        return editPlaylist { pairing -> connection.createPlaylist(pairing, normalized) }
    }

    suspend fun savePlaylistOrder(
        playlist: Playlist, order: List<String>, previous: List<String>? = null,
    ): Result<Playlist> {
        val entry = playlists.value.entry(playlist) ?: return Result.failure(CompanionLibraryError.PlaylistMissing)
        val base = previous ?: entry.trackIds
        if (base != entry.trackIds) return Result.failure(CompanionLibraryError.PlaylistConflict)
        val retained = base.toSet()
        val catalog = state.value.catalog
        if (order.size > 10000 || order.distinct().size != order.size ||
            order.any { it !in retained && catalog?.track(it) == null }
        ) return Result.failure(CompanionLibraryError.PlaylistTracks)
        return editPlaylist { pairing -> connection.savePlaylistOrder(pairing, playlist, base, order) }
    }

    private suspend fun editPlaylist(request: suspend (String) -> Result<CompanionPlaylist>): Result<Playlist> {
        val pairing = onlinePlaylistScope() ?: return Result.failure(CompanionLibraryError.Unavailable)
        val current = playlists.value
        if (current.isSaving || current.isLoading || current.entries == null) {
            return Result.failure(CompanionLibraryError.Unavailable)
        }
        val generation = playlistGeneration
        _playlists.value = current.copy(isSaving = true)
        val result = try {
            request(pairing).also { currentCoroutineContext().ensureActive() }
        } finally {
            if (generation == playlistGeneration) _playlists.value = playlists.value.copy(isSaving = false)
        }
        if (!active || generation != playlistGeneration) return Result.failure(CompanionLibraryError.Unavailable)
        result.onSuccess { saved ->
            val entries = current.entries
            val updated = if (entries.any { it.playlist.id == saved.playlist.id }) {
                entries.map { if (it.playlist.id == saved.playlist.id) saved else it }
            } else entries + saved
            _playlists.value = CompanionPlaylistsState(entries = updated)
        }.onFailure { error ->
            when (error) {
                CompanionLibraryError.Unauthorized, CompanionLibraryError.Network -> handlePlaylistConnectionFailure(error)
                CompanionLibraryError.PlaylistConflict, CompanionLibraryError.PlaylistMissing -> refreshPlaylists()
                else -> Unit
            }
        }
        return result.map(CompanionPlaylist::playlist)
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
        val albumsChanged = offline && groups != current.downloads
        val browseCatalog = if (resultsChanged || albumsChanged) tracks?.let {
            val knownTotals = if (offline) groups.associate { group -> group.album.id to group.totalCount }
                else emptyMap()
            CompanionTrackCatalog(it, knownTotals)
        } else current.catalog
        _state.value = current.copy(
            catalog = browseCatalog,
            isLoading = false,
            error = error.takeUnless { offline },
            isOffline = offline,
            downloadedTrackIds = links.keys + browseCopies.keys,
            downloads = groups,
            needsPairing = error == CompanionLibraryError.Unauthorized || (error == null && catalog == null),
            serviceUrl = connection.serviceUrl,
            pairingScope = pairing,
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

    fun albumFor(track: Track): Album? = state.value.catalog?.takeIf { it.track(track.id) == track }?.albumFor(track)

    fun albumTracks(album: Album): List<Track>? = state.value.catalog?.members(album)

    fun trackArtwork(track: Track): CompanionArtworkRequest? {
        val pairing = pairingScope ?: return null
        val current = state.value
        if (current.isOffline || current.isLoading) return null
        // 播放会话恢复的曲目只有元数据；仍用当前清单的身份与版本解析封面。
        return current.catalog?.track(track.id)?.let { connection.artwork(it, pairing) }
    }

    fun albumArtwork(album: Album): CompanionArtworkRequest? =
        state.value.catalog?.artworkTrack(album)?.let(::trackArtwork)

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
