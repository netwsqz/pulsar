package com.example.pulsar.download

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.pulsar.DownloadKind
import com.example.pulsar.DownloadScheduler
import com.example.pulsar.DownloadState
import com.example.pulsar.DownloadTarget
import com.example.pulsar.DownloadTrack
import com.example.pulsar.MediaDownloads
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Track
import com.example.pulsar.targetId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

internal data class CompanionDownloadTarget(val pairing: String, val album: Album, val tracks: List<Pair<Track, String>>) {
    val task = DownloadTarget(
        serverUrl = "pc", userId = pairing, kind = DownloadKind.ALBUM, id = "pc:$pairing:${album.id}",
        name = album.name, tracks = tracks.map { (track, url) ->
            DownloadTrack(track.id, url, CompanionDownloadCopy(pairing, track).key)
        },
    )
}

@Serializable
internal data class CompanionDownloadCopy(val pairing: String, val track: Track) {
    val key get() = "$KEY_PREFIX$pairing:${track.id.encodeUtf8().sha256().hex()}:${track.sourceRevision}"
    private val copyId get() = key.encodeUtf8().sha256().hex()
    val playbackUri get() = "pulsar-pc-download:$copyId"
    // 队列 ID 区分版本，但不携带配对归属；和电脑上的同一来源可以一起排队。
    val savedTrack get() = track.copy(id = "pc-copy:${track.id.substringBeforeLast(':').removePrefix("pc:")}:$copyId")
}

internal data class CompanionDownloadedAlbum(
    val pairing: String, val album: Album, val copies: List<CompanionDownloadCopy>, val totalCount: Int,
)

@Serializable
private data class CompanionDownloadEntry(val album: Album, val tracks: List<Track>)

/** 电脑下载独立记账，但与原下载共用字节存储和串行调度器。 */
internal class CompanionDownloads(
    private val preferences: DataStore<Preferences>,
    private val bytes: MediaDownloads,
    private val scheduler: DownloadScheduler,
    private val savedPairing: suspend () -> String?,
) {
    private val mutation = Mutex()
    private val completedCopies = MutableStateFlow(0L)

    val changes = combine(
        preferences.data.map { prefs -> prefs.asMap().filterKeys { it.name.startsWith(LEDGER_PREFIX) } }.distinctUntilChanged(),
        scheduler.state.map { states ->
            states.filterKeys { it.id.startsWith("pc:") }.mapValues { downloadStateSignature(it.value) }
        }.distinctUntilChanged(),
        completedCopies,
    ) { _, _, _ -> Unit }

    fun enqueue(target: CompanionDownloadTarget) {
        if (target.task.tracks.isEmpty() || target.task.tracks.any { companionDownloadVersion(it.key) == null }) return
        scheduler.enqueue(target.task, onCopyComplete = { completedCopies.update { it + 1L } }) {
            localResult(false) {
                mutation.withLock {
                    if (savedPairing() != target.pairing) return@withLock false
                    update(target.pairing) { entries ->
                        val existing = entries.firstOrNull { it.album.id == target.album.id }
                        val tracks = (existing?.tracks.orEmpty() + target.tracks.map { it.first })
                            .distinctBy { it.id to it.sourceRevision }
                        entries.filterNot { it.album.id == target.album.id } + CompanionDownloadEntry(target.album, tracks)
                    }
                    true
                }
            }
        }
    }

    fun cancel(target: CompanionDownloadTarget) = scheduler.cancel(target.task)

    fun actionStates(target: CompanionDownloadTarget) = changes.combine(
        scheduler.state.map { it[target.task.targetId] }.distinctUntilChanged(),
    ) { _, _ -> actionState(target) }.distinctUntilChanged()

    private suspend fun actionState(target: CompanionDownloadTarget): DownloadActionState = localResult(DownloadActionState.Checking) {
        if (savedPairing() != target.pairing) return@localResult DownloadActionState.NotDownloaded
        val live = scheduler.state.value[target.task.targetId]
        if (live is DownloadState.Downloading || live is DownloadState.PartiallyFailed) {
            return@localResult resolveDownloadActionState(live, null)
        }
        val complete = load(target.pairing).flatMap { it.copies }.mapTo(hashSetOf()) { it.key }
        resolveDownloadActionState(live, target.task.tracks.isNotEmpty() && target.task.tracks.all { it.key in complete })
    }

    suspend fun load(pairing: String): List<CompanionDownloadedAlbum> = localResult(emptyList()) {
        if (savedPairing() != pairing) return@localResult emptyList()
        val complete = bytes.completeKeys()
        read(pairing).mapNotNull { entry ->
            val copies = entry.tracks.map { CompanionDownloadCopy(pairing, it) }.filter { it.key in complete }
            if (copies.isEmpty()) null else CompanionDownloadedAlbum(pairing, entry.album, copies, entry.tracks.size)
        }.takeIf { savedPairing() == pairing }.orEmpty()
    }

    suspend fun playbackKey(uri: String): String? = localResult(null) {
        val pairing = savedPairing() ?: return@localResult null
        val copy = read(pairing).asSequence().flatMap { it.tracks.asSequence() }
            .map { CompanionDownloadCopy(pairing, it) }.firstOrNull { it.playbackUri == uri } ?: return@localResult null
        copy.key.takeIf { bytes.hasCompleteCopy(it) && savedPairing() == pairing }
    }

    suspend fun deleteAlbum(pairing: String, albumId: String, playingId: String?): Int = localResult(0) {
        mutation.withLock {
            if (savedPairing() != pairing) return@withLock 0
            val entry = read(pairing).firstOrNull { it.album.id == albumId } ?: return@withLock 0
            if (hasRunningTask(pairing)) return@withLock 0 // 不和正在写入的副本争同一份字节。
            val complete = bytes.completeKeys()
            val keys = entry.tracks.map { CompanionDownloadCopy(pairing, it) }
                .filter { it.key in complete && !it.isPlaying(playingId) }.mapTo(hashSetOf()) { it.key }
            remove(pairing, keys)
            keys.size
        }
    }

    suspend fun deleteTrack(copy: CompanionDownloadCopy, playingId: String?): Boolean = localResult(false) {
        mutation.withLock {
            if (savedPairing() != copy.pairing || copy.isPlaying(playingId) ||
                hasRunningTask(copy.pairing) ||
                playbackKey(copy.playbackUri) == null
            ) return@withLock false
            remove(copy.pairing, setOf(copy.key))
            true
        }
    }

    private fun hasRunningTask(pairing: String) = scheduler.state.value.any { (id, state) ->
        id.id.startsWith("pc:$pairing:") && state is DownloadState.Downloading
    }

    private suspend fun remove(pairing: String, keys: Set<String>) {
        if (keys.isEmpty()) return
        keys.forEach { bytes.remove(it) }
        update(pairing) { entries ->
            entries.mapNotNull { entry ->
                val remaining = entry.tracks.filterNot { CompanionDownloadCopy(pairing, it).key in keys }
                entry.copy(tracks = remaining).takeIf { remaining.isNotEmpty() }
            }
        }
    }

    private fun CompanionDownloadCopy.isPlaying(id: String?) = id == track.id || id == savedTrack.id

    private suspend fun read(pairing: String) = decode(preferences.data.first()[ledgerKey(pairing)])

    private suspend fun update(pairing: String, change: (List<CompanionDownloadEntry>) -> List<CompanionDownloadEntry>) {
        val key = ledgerKey(pairing)
        preferences.edit { prefs -> prefs[key] = Json.encodeToString(change(decode(prefs[key]))) }
    }

    private fun decode(value: String?) = value?.let { Json.decodeFromString<List<CompanionDownloadEntry>>(it) }.orEmpty()

    private fun ledgerKey(pairing: String) = stringPreferencesKey("$LEDGER_PREFIX$pairing")
}

private const val LEDGER_PREFIX = "companion_download_ledger_v1_"
private const val KEY_PREFIX = "pc-download:"
private val digestPattern = Regex("[0-9a-f]{64}")

internal data class CompanionDownloadVersion(val pairing: String, val revision: String)

internal fun companionDownloadVersion(key: String): CompanionDownloadVersion? {
    if (!key.startsWith(KEY_PREFIX)) return null
    val parts = key.removePrefix(KEY_PREFIX).split(':', limit = 4)
    return if (parts.size == 3 && parts.all(digestPattern::matches)) {
        CompanionDownloadVersion(parts[0], parts[2])
    } else null
}

internal fun isCompanionDownloadPlaybackUri(uri: String) = uri.startsWith("pulsar-pc-download:", ignoreCase = true)
internal fun isCompanionDownloadMediaId(id: String) = id.startsWith("pc-copy:")
