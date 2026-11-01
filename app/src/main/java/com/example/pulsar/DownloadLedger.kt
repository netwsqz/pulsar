package com.example.pulsar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.pulsar.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 下载名单：记住「这张专辑 / 这个列表当初下了哪几首」。
 *
 * **名单只回答「当初下了哪些」，不回答「下完了没」**——完成状态一律现算（字节真相，
 * spec 铁律 3）。有了它，删除只删当初那批：服务器后来往专辑里加了新歌，删这张专辑时
 * 那些新歌不在名单里，不会被连带删掉。
 *
 * 按「服务器地址 + 用户 ID」归属：换账号看不见别人的名单（用户故事 17），但音频字节
 * 是共享的（缓存键是播放 URL，不带用户 ID）。
 *
 * **退出登录不清名单**（用户故事 16）：下载是用户主动存的，跟会话生命周期无关。
 * 见 `DownloadLedgerTest` 里钉死这条的测试。
 */
@Serializable
data class DownloadEntry(
    val serverUrl: String,
    val userId: String,
    val kind: DownloadKind,
    val id: String,
    val name: String,
    /** 当初下载的那批曲目 id。服务器后来新增的不在这里。 */
    val trackIds: List<String>,
    val createdAt: Long
)

/** 名单条目的种类：专辑 / 播放列表。 */
@Serializable
enum class DownloadKind {
    @SerialName("album")
    ALBUM,

    @SerialName("playlist")
    PLAYLIST
}

/** 整张名单。读写都是**整表覆写**：数据量小（一个账号几十条），不做增量。 */
@Serializable
data class DownloadLedger(val entries: List<DownloadEntry> = emptyList())

/**
 * 名单的编解码（纯函数，单独拎出来测）。[decode] 对坏数据一律返回 null——
 * 名单坏了只能降级成「没有名单」，不能变成新的故障源。
 */
object DownloadLedgerCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(ledger: DownloadLedger): String = json.encodeToString(DownloadLedger.serializer(), ledger)

    fun decode(text: String): DownloadLedger? = runCatching {
        json.decodeFromString(DownloadLedger.serializer(), text)
    }.getOrNull()
}

/**
 * 下载名单的读写。接口/实现分离照 [SessionRepository] / [SessionStore] 那套。
 *
 * 所有方法都不抛异常：读盘/解码失败降级成空名单，写失败静默吞掉（[guardedIo]）。
 * 名单挂了顶多界面上看不见已下载的分组，不能把界面拖垮。
 */
interface DownloadLedgerStore {
    suspend fun load(): DownloadLedger

    /** 整表覆写。 */
    suspend fun replaceAll(entries: List<DownloadEntry>)

    /** 只返回属于 [serverUrl] + [userId] 的条目——换账号看不见别人的名单。 */
    suspend fun entriesFor(serverUrl: String, userId: String): List<DownloadEntry>
}

private val ledgerJsonKey = stringPreferencesKey("download_ledger_json")

/**
 * [guardedIo] 的 suspend 版：DataStore 的读写本身是 suspend，塞不进那个非 suspend 的 block。
 * 兜底口径完全一致——挪到 IO 线程、吞掉一切异常只回 [fallback]、协程取消照常上抛。
 * 放这里而不是改 `MediaCache.kt`：那个文件归别的工单，不越界。
 */
private suspend fun <T> guardedSuspendIo(fallback: T, block: suspend () -> T): T = withContext(Dispatchers.IO) {
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        fallback
    }
}

/** DataStore 实现：整张名单序列化成一段 JSON 存在一个键里。 */
class DataStoreDownloadLedgerStore(private val dataStore: DataStore<Preferences>) : DownloadLedgerStore {

    override suspend fun load(): DownloadLedger = guardedSuspendIo(DownloadLedger()) {
        val text = dataStore.data.first()[ledgerJsonKey] ?: return@guardedSuspendIo DownloadLedger()
        DownloadLedgerCodec.decode(text) ?: DownloadLedger()
    }

    override suspend fun replaceAll(entries: List<DownloadEntry>) {
        guardedSuspendIo(Unit) {
            val text = DownloadLedgerCodec.encode(DownloadLedger(entries))
            dataStore.edit { preferences -> preferences[ledgerJsonKey] = text }
        }
    }

    override suspend fun entriesFor(serverUrl: String, userId: String): List<DownloadEntry> =
        load().entries.filter { it.serverUrl == serverUrl && it.userId == userId }

    /**
     * 剔除归属为空的坏条目。历史上曾有一版迁移在 `_state.value.serverUrl` 还是空串时
     * 就写名单（离线启动 → `goOnline()` 那条路），留下了一批 `serverUrl == ""` 的条目：
     * 它们永远匹配不上 `entriesFor(真实地址, userId)`，界面上看不见却又占着名单。
     *
     * 只在读到的名单里**确实有**这种条目时才写回一次，正常情况是一次纯读。返回剔除的条数。
     */
    suspend fun pruneUnattributed(): Int = guardedSuspendIo(0) {
        val current = load()
        val kept = current.entries.filter { it.serverUrl.isNotBlank() }
        val removed = current.entries.size - kept.size
        if (removed > 0) replaceAll(kept)
        removed
    }
}
