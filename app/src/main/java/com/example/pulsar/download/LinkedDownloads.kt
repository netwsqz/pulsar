package com.example.pulsar.download

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.pulsar.CompleteCopyDigest
import com.example.pulsar.LibrarySnapshotRepository
import com.example.pulsar.data.companion.CompanionDownloadEvidenceSource
import com.example.pulsar.data.jellyfin.JellyfinUrlBuilder
import com.example.pulsar.domain.model.Track
import com.example.pulsar.downloadedPlaylistsFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class LinkedDownload(val track: Track, val originalId: String, val stamp: String) {
    val playbackUri: String get() = "$LINKED_PREFIX${track.id}/${track.sourceRevision}/$originalId"
}

internal data class LinkedDownloadPreview(
    val scope: String,
    val account: AccountScope,
    val matches: List<LinkedDownload>,
    val unmatched: Int,
)

/** 只登记指向已确认原副本的关联；不把旧曲目填进电脑下载名单，也不写音频。 */
internal class LinkedDownloads(
    private val previewer: OldDownloadPreviewer,
    private val existing: ExistingDownloads,
    private val snapshot: LibrarySnapshotRepository,
    private val store: DataStoreLinkedDownloadStore,
    private val source: CompanionDownloadEvidenceSource,
    private val savedScope: suspend () -> String?,
    private val digest: suspend (String) -> CompleteCopyDigest?,
    private val copyStamp: suspend (String) -> String?,
) {
    private val confirmation = Mutex()

    suspend fun preview(): LinkedDownloadPreview? = localResult(null) { checkMatches() }

    suspend fun confirm(preview: LinkedDownloadPreview): Boolean = localResult(false) {
        confirmation.withLock {
            if (preview.matches.isEmpty() || checkMatches() != preview) return@withLock false
            val saved = store.read(preview.account, preview.scope)
            store.save(preview.account, preview.scope, (saved + preview.matches).associateBy { it.track.id }.values.toList())
            true
        }
    }

    private suspend fun checkMatches(): LinkedDownloadPreview? {
        val account = previewer.currentAccount() ?: return null
        val scope = savedScope() ?: return null
        val view = existing.load()
        val playable = when (view) {
            is ExistingDownloadView.Ready -> view.playable
            ExistingDownloadView.NeedsConfirmation -> emptyList()
            else -> return null
        }
        val library = snapshot.load()
        // 快照路径只用来找候选。归属来自名单，能否关联只由完整字节摘要决定。
        val known = library?.tracks.orEmpty() + library?.downloadedPlaylistsFor(account.serverUrl, account.userId)
            .orEmpty().flatMap { it.tracks }
        val pathsById = known.groupBy(Track::id).mapValues { (_, tracks) ->
            tracks.mapNotNull { it.filePath?.takeIf(String::isNotBlank) }.distinct().singleOrNull()
        }
        val paths = playable.mapNotNull { pathsById[it.item.trackId] }.distinct()
        val candidates = if (paths.isEmpty()) emptyMap() else {
            val evidence = source.evidence(paths)?.takeIf { it.scope == scope } ?: return null
            evidence.candidates.associateBy { it.path }
        }
        val builder = JellyfinUrlBuilder(account.serverUrl)
        val matches = playable.mapNotNull { old ->
            val candidate = candidates[pathsById[old.item.trackId]] ?: return@mapNotNull null
            val key = builder.mediaStreamUrl(old.item.trackId)
            val bytes = digest(key) ?: return@mapNotNull null
            if (candidate.size != bytes.size || candidate.sha256 != bytes.sha256 ||
                candidate.track.sourceRevision == null
            ) return@mapNotNull null
            LinkedDownload(candidate.track, old.mediaId, bytes.stamp)
        }
        if (savedScope() != scope || previewer.currentAccount() != account) return null
        // 等同字节的多个旧副本任选一份；若电脑同一 ID 在核对中变了版本，宁可不关联。
        val unique = matches.groupBy { it.track.id }.values.mapNotNull { options ->
            options.firstOrNull()?.takeIf { first -> options.all { it.track == first.track } }
        }
        val linkedIds = unique.mapTo(hashSetOf()) { it.track.id }
        return LinkedDownloadPreview(scope, account, unique, playable.size - matches.count { it.track.id in linkedIds })
    }

    /** 在线成功刷新时撤下已移走或已替换文件的关联；不动旧副本或旧名单。 */
    suspend fun checkCatalog(scope: String, tracks: List<Track>) = localResult(Unit) {
        confirmation.withLock {
            val account = previewer.currentAccount() ?: return@withLock
            if (savedScope() != scope) return@withLock
            val byId = tracks.associateBy(Track::id)
            val saved = store.read(account, scope)
            val current = saved.filter { link ->
                val track = byId[link.track.id] ?: return@filter false
                track.sourceRevision == null || track.sourceRevision == link.track.sourceRevision
            }
            if (current != saved) store.save(account, scope, current)
        }
    }

    /** 冷启动只读手机本机数据。关联仍须能找到同一份完整且有归属的原副本。 */
    suspend fun load(scope: String): List<LinkedDownload> = localResult(emptyList()) {
        val account = previewer.currentAccount() ?: return@localResult emptyList()
        val stored = store.read(account, scope)
        if (stored.isEmpty()) return@localResult emptyList()
        val originals = (existing.load() as? ExistingDownloadView.Ready)?.playable.orEmpty()
            .associateBy(ExistingDownload::mediaId)
        val builder = JellyfinUrlBuilder(account.serverUrl)
        val playable = stored.filter { link ->
            val original = originals[link.originalId] ?: return@filter false
            copyStamp(builder.mediaStreamUrl(original.item.trackId)) == link.stamp
        }
        playable.takeIf { savedScope() == scope && previewer.currentAccount() == account }.orEmpty()
    }

    suspend fun playbackKey(uri: String): String? = localResult(null) {
        val scope = savedScope() ?: return@localResult null
        val account = previewer.currentAccount() ?: return@localResult null
        val link = store.read(account, scope).firstOrNull { it.playbackUri == uri } ?: return@localResult null
        val key = existing.playbackKey(link.originalId) ?: return@localResult null
        key.takeIf { copyStamp(it) == link.stamp && savedScope() == scope && previewer.currentAccount() == account }
    }
}

internal class DataStoreLinkedDownloadStore(private val preferences: DataStore<Preferences>) {
    suspend fun read(account: AccountScope, scope: String): List<LinkedDownload> =
        preferences.data.first()[key(account, scope)]?.let { Json.decodeFromString<List<LinkedDownload>>(it) }.orEmpty()

    suspend fun save(account: AccountScope, scope: String, links: List<LinkedDownload>) {
        preferences.edit { it[key(account, scope)] = Json.encodeToString(links) }
    }

    private fun key(account: AccountScope, scope: String) =
        stringPreferencesKey("linked_downloads_v1_${account.identity()}_$scope")
}

private const val LINKED_PREFIX = "pulsar-linked:"
internal fun isLinkedDownloadPlaybackUri(uri: String): Boolean = uri.startsWith(LINKED_PREFIX, ignoreCase = true)
internal fun linkedOriginalId(uri: String): String? =
    uri.substringAfterLast('/').takeIf { isLinkedDownloadPlaybackUri(uri) && isExistingDownloadMediaId(it) }
