package com.example.pulsar.download

import com.example.pulsar.DownloadCopyEvidence
import com.example.pulsar.DownloadLedger
import com.example.pulsar.LibrarySnapshotRepository
import com.example.pulsar.data.jellyfin.JellyfinUrlBuilder
import com.example.pulsar.downloadedPlaylistsFor
import kotlinx.coroutines.CancellationException

internal data class OldDownloadItem(
    val trackId: String,
    val title: String?,
    val downloadNames: List<String>,
)

internal sealed interface OldDownloadPreview {
    data object NoSavedAccount : OldDownloadPreview
    data object Unavailable : OldDownloadPreview
    data class Report(
        val complete: List<OldDownloadItem>,
        val unfinished: List<OldDownloadItem>,
        val pending: List<OldDownloadItem>,
    ) : OldDownloadPreview
}

/** 对照旧账号的下载名单和原播放键。曲库快照只用来显示曲名，不是归属或完整性的证据。 */
internal class OldDownloadPreviewer(
    private val savedAccount: suspend () -> AccountScope?,
    private val readLedger: suspend () -> DownloadLedger,
    private val snapshot: LibrarySnapshotRepository,
    private val readCopies: suspend ((String) -> Boolean) -> Map<String, DownloadCopyEvidence>,
) {
    suspend fun preview(): OldDownloadPreview = try {
        val account = savedAccount()
        if (account == null) OldDownloadPreview.NoSavedAccount else previewAccount(account)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // 本地证据不可读时不能把失败报告成「没有下载」。不把异常或缓存键交给界面。
        OldDownloadPreview.Unavailable
    }

    private suspend fun previewAccount(account: AccountScope): OldDownloadPreview.Report {
        val ledger = readLedger().entries
        val builder = JellyfinUrlBuilder(account.serverUrl)
        val namesById = linkedMapOf<String, MutableSet<String>>()
        val otherIds = mutableSetOf<String>()
        val unattributedIds = linkedSetOf<String>()
        for (entry in ledger) {
            val sameAddress = sameLibrary(entry.serverUrl, builder)
            when {
                entry.serverUrl == account.serverUrl && entry.userId == account.userId -> {
                    for (id in entry.trackIds) namesById.getOrPut(id) { linkedSetOf() }.add(entry.name)
                }
                // 字节按播放键共享；不把别的账号能明确认领的曲目当成无归属副本。
                sameAddress && entry.userId.isNotBlank() && entry.userId != account.userId -> otherIds += entry.trackIds
                sameAddress || (entry.serverUrl.isBlank() && (entry.userId.isBlank() || entry.userId == account.userId)) ->
                    unattributedIds += entry.trackIds
            }
        }
        val copies = readCopies { builder.mediaIdFromStreamKey(it) != null }
        // 曲库全量快照没有账号标记，仅使用有明确归属的已下载列表清单补曲名。
        val titles = snapshot.load()?.downloadedPlaylistsFor(account.serverUrl, account.userId).orEmpty()
            .flatMap { it.tracks }.associate { it.id to it.title }
        val complete = mutableListOf<OldDownloadItem>()
        val unfinished = mutableListOf<OldDownloadItem>()
        val pending = mutableListOf<OldDownloadItem>()
        for ((id, names) in namesById) {
            val item = OldDownloadItem(id, titles[id], names.toList())
            val key = id.takeIf { it.isNotBlank() }?.let(builder::mediaStreamUrl)
            when (copies[key]) {
                DownloadCopyEvidence.COMPLETE -> complete += item
                DownloadCopyEvidence.UNFINISHED -> unfinished += item
                else -> pending += item
            }
        }
        val unclaimedIds = copies.keys.mapNotNull(builder::mediaIdFromStreamKey).sorted()
        for (id in (unattributedIds + unclaimedIds) - namesById.keys - otherIds) {
            // 归属未确认：不从未知账号的曲库快照或名单拿曲名／列表名。
            pending += OldDownloadItem(id, null, emptyList())
        }
        return OldDownloadPreview.Report(complete, unfinished, pending)
    }

    private fun sameLibrary(serverUrl: String, builder: JellyfinUrlBuilder): Boolean =
        serverUrl.trim().trimEnd('/') == builder.serverUrl.trim().trimEnd('/')
}
