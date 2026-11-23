package com.example.pulsar.download

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.pulsar.data.jellyfin.JellyfinUrlBuilder
import com.example.pulsar.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

internal data class ExistingDownload(val item: OldDownloadItem, val mediaId: String) {
    fun toTrack() = Track(
        id = mediaId,
        title = item.displayTitle(),
        artist = item.artist?.takeIf(String::isNotBlank) ?: item.downloadNames.joinToString("、"),
        album = item.album ?: item.downloadNames.firstOrNull().orEmpty(),
        durationMs = item.durationMs,
        imageTag = null,
        normalizationGainDb = item.normalizationGainDb,
    )
}

internal sealed interface ExistingDownloadView {
    data object NeedsConfirmation : ExistingDownloadView
    data object NoSavedAccount : ExistingDownloadView
    data object Unavailable : ExistingDownloadView
    data class Ready(
        val playable: List<ExistingDownload>,
        val unfinished: List<OldDownloadItem>,
        val pending: List<OldDownloadItem>,
        val unavailable: List<OldDownloadItem>,
        val needsConfirmation: Boolean,
    ) : ExistingDownloadView
}

internal enum class ExistingDownloadApply { Applied, Partial, PreviewChanged, Unavailable, RecheckRequired }

/** 只登记已确认的旧副本；可播资格仍取下载名单和原字节的交集，不复制名单或音频。 */
internal class ExistingDownloads(
    private val previewer: OldDownloadPreviewer,
    private val store: ExistingDownloadStore,
) {
    private val confirmLock = Mutex()
    private val failed = MutableStateFlow<Set<String>>(emptySet())
    val failedPlaybackIds = failed.asStateFlow()

    /** 解码、读取失败都只标记这一首，不清副本；允许用户主动重新核对后试播。 */
    fun recordPlaybackFailure(mediaId: String) {
        if (isExistingDownloadMediaId(mediaId)) failed.update { it + mediaId }
    }

    fun resetPlaybackFailures() { failed.value = emptySet() }

    suspend fun confirm(preview: OldDownloadPreview.Report): ExistingDownloadApply =
        localResult(ExistingDownloadApply.Unavailable) {
            confirmLock.withLock {
                val fresh = previewer.preview()
                if (fresh == OldDownloadPreview.Unavailable) return@withLock ExistingDownloadApply.Unavailable
                if (fresh != preview || preview.complete.isEmpty() || previewer.currentAccount() != preview.account) {
                    return@withLock ExistingDownloadApply.PreviewChanged
                }
                // 追加已确认的曲目，不以新一轮部分结果盖掉以前的登记。DataStore 整次提交，失败不写半份。
                store.add(preview.account, preview.complete)
                val applied = localResult<ExistingDownloadView?>(null) { loadFor(preview.account) } as? ExistingDownloadView.Ready
                when {
                    applied == null -> ExistingDownloadApply.RecheckRequired
                    applied.unavailable.isNotEmpty() || applied.pending.isNotEmpty() || applied.unfinished.isNotEmpty() ||
                        applied.needsConfirmation -> ExistingDownloadApply.Partial
                    else -> ExistingDownloadApply.Applied
                }
            }
        }

    /** 由播放服务解析本机媒体地址；不接受调用方传入的原缓存键。 */
    suspend fun playbackKey(mediaId: String): String? = localResult(null) {
        if (!isExistingDownloadMediaId(mediaId)) return@localResult null
        val account = previewer.currentAccount() ?: return@localResult null
        if (!existingDownloadBelongsTo(mediaId, account)) return@localResult null
        val builder = JellyfinUrlBuilder(account.serverUrl)
        val copyId = mediaId.substringAfterLast(':')
        val entry = store.read(account).firstOrNull {
            it.trackId.isNotBlank() && copyIdentity(builder.mediaStreamUrl(it.trackId)) == copyId
        } ?: return@localResult null
        previewer.completePlaybackKey(account, entry.trackId)
    }

    suspend fun load(): ExistingDownloadView = localResult(ExistingDownloadView.Unavailable) {
        val account = previewer.currentAccount() ?: return@localResult ExistingDownloadView.NoSavedAccount
        loadFor(account)
    }

    private suspend fun loadFor(account: AccountScope): ExistingDownloadView {
        val confirmed = store.read(account)
        if (confirmed.isEmpty()) return ExistingDownloadView.NeedsConfirmation
        val report = previewer.preview()
        if (report !is OldDownloadPreview.Report || report.account != account || previewer.currentAccount() != account) {
            return ExistingDownloadView.Unavailable
        }
        val completeIds = report.complete.mapTo(hashSetOf(), OldDownloadItem::trackId)
        val confirmedIds = confirmed.mapTo(hashSetOf(), OldDownloadItem::trackId)
        val builder = JellyfinUrlBuilder(account.serverUrl)
        val confirmedItems = confirmed.map { ExistingDownload(it, existingDownloadMediaId(account, builder.mediaStreamUrl(it.trackId))) }
        val failedIds = failed.value
        val (playable, unavailable) = confirmedItems.partition {
            it.item.trackId in completeIds && it.mediaId !in failedIds
        }
        return ExistingDownloadView.Ready(
            playable = playable,
            unfinished = report.unfinished.filterNot { it.trackId in confirmedIds },
            pending = report.pending.filterNot { it.trackId in confirmedIds },
            unavailable = unavailable.map { it.item },
            needsConfirmation = report.complete.any { it.trackId !in confirmedIds },
        )
    }
}

internal interface ExistingDownloadStore {
    suspend fun read(account: AccountScope): List<OldDownloadItem>
    suspend fun add(account: AccountScope, items: List<OldDownloadItem>)
}

internal class DataStoreExistingDownloadStore(private val preferences: DataStore<Preferences>) : ExistingDownloadStore {
    override suspend fun read(account: AccountScope): List<OldDownloadItem> =
        preferences.data.first()[confirmationKey(account)]?.let { Json.decodeFromString<List<OldDownloadItem>>(it) }.orEmpty()

    override suspend fun add(account: AccountScope, items: List<OldDownloadItem>) {
        val key = confirmationKey(account)
        preferences.edit { state ->
            val old = state[key]?.let { Json.decodeFromString<List<OldDownloadItem>>(it) }.orEmpty()
            val updated = (old + items).associateBy(OldDownloadItem::trackId).values.toList()
            state[key] = Json.encodeToString(updated)
        }
    }
}

private const val EXISTING_MEDIA_ID_PREFIX = "existing:"
private const val EXISTING_PLAYBACK_SCHEME = "pulsar-existing"

/** 只携带本机媒体 ID，原 URL 不进入队列、日志或网络请求。 */
internal fun existingDownloadPlaybackUri(mediaId: String): String = "$EXISTING_PLAYBACK_SCHEME:$mediaId"

internal fun isExistingDownloadPlaybackUri(uri: String): Boolean =
    uri.startsWith("$EXISTING_PLAYBACK_SCHEME:", ignoreCase = true)

internal fun existingDownloadIdFromUri(uri: String): String? =
    uri.substringAfter(':', "").takeIf { isExistingDownloadPlaybackUri(uri) && isExistingDownloadMediaId(it) }

private fun confirmationKey(account: AccountScope) =
    stringPreferencesKey("existing_downloads_v1_${account.identity()}")

@Serializable
private data class ExistingDownloadAccount(val serverUrl: String, val userId: String)

private fun AccountScope.identity(): String =
    Json.encodeToString(ExistingDownloadAccount(serverUrl, userId)).encodeUtf8().sha256().hex()

internal fun existingDownloadMediaId(account: AccountScope, originalKey: String): String =
    "$EXISTING_MEDIA_ID_PREFIX${account.identity()}:${copyIdentity(originalKey)}"

internal fun existingDownloadBelongsTo(mediaId: String, account: AccountScope?): Boolean =
    account != null && mediaId.startsWith("$EXISTING_MEDIA_ID_PREFIX${account.identity()}:")

/** 字节跨账号共享：即使作用域不同，也不能删掉正在播放的同一个缓存键。 */
internal fun isPlayingDownloadCopy(playingMediaId: String?, trackId: String, key: String): Boolean =
    playingMediaId == trackId || (playingMediaId != null && key.isNotEmpty() && isExistingDownloadMediaId(playingMediaId) &&
        playingMediaId.substringAfterLast(':') == copyIdentity(key))

private fun copyIdentity(key: String): String = key.encodeUtf8().sha256().hex()

internal fun isExistingDownloadMediaId(mediaId: String): Boolean = mediaId.startsWith(EXISTING_MEDIA_ID_PREFIX)

private suspend fun <T> localResult(fallback: T, read: suspend () -> T): T = withContext(Dispatchers.IO) {
    try {
        read()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        fallback
    }
}
