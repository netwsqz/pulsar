package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 曲库快照：缓存在手机上的一份「曲库里有哪些歌」的清单，只用来在**断网时**撑起界面。
 *
 * **只在线下兜底，不在线上抢活**（spec 铁律 4）：能连上 Jellyfin 时一律以服务器返回的
 * 为准，快照只在请求失败时顶上。它不是加速缓存，所以「在电脑上改了名手机不更新」这类
 * 问题不会出现。
 *
 * 只覆盖曲库那几屏（发现 / 全部音乐 / 专辑）。**播放列表不进快照**——离线时那一屏
 * 明说「需要联网」。
 */
@Serializable
data class LibrarySnapshot(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val discoverAlbums: List<Album> = emptyList()
)

/**
 * 离线搜索：在快照里按**歌名 / 歌手 / 专辑**过滤（大小写不敏感）。
 *
 * 范围比在线搜索小——在线搜由 Jellyfin 搜，还会捎带播放列表；离线这一屏里没有播放列表，
 * 界面上要标明「离线搜索」，免得以为歌丢了。
 */
fun LibrarySnapshot.search(query: String): SearchResults {
    val needle = query.trim()
    if (needle.isEmpty()) return SearchResults()
    fun String.hits() = contains(needle, ignoreCase = true)
    return SearchResults(
        tracks = tracks.filter { it.title.hits() || it.artist.hits() || it.album.hits() },
        albums = albums.filter { it.name.hits() || it.artist.hits() }
    )
}

/** 曲库快照的读写。接口/实现分离照 [SessionRepository] / [SessionStore] 那套。 */
interface LibrarySnapshotRepository {
    /** 读快照；文件不存在 / 读坏了都返回 null（当「没有快照」，不是错误）。 */
    suspend fun load(): LibrarySnapshot?

    suspend fun save(snapshot: LibrarySnapshot)

    suspend fun clear()
}

/**
 * 快照的编解码（纯函数，单独拎出来测）。[decode] 对坏数据一律返回 null——
 * 快照坏了不能让 App 变成新的故障源。
 */
object LibrarySnapshotCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(snapshot: LibrarySnapshot): String = json.encodeToString(LibrarySnapshot.serializer(), snapshot)

    fun decode(text: String): LibrarySnapshot? = runCatching {
        json.decodeFromString(LibrarySnapshot.serializer(), text)
    }.getOrNull()
}

/**
 * 生产实现：一份 JSON 落在 App 私有目录。
 *
 * 写盘先写临时文件再改名：中途被杀 / 磁盘满时，读到的要么是旧快照、要么是新快照，
 * 不会读到写了一半的 JSON。所有失败静默吞掉（铁律 1 + 工单 02：读不出来不影响在线使用）。
 */
class FileLibrarySnapshotRepository(private val file: File) : LibrarySnapshotRepository {

    override suspend fun load(): LibrarySnapshot? = guarded(null) {
        if (!file.exists()) return@guarded null
        LibrarySnapshotCodec.decode(file.readText())
    }

    override suspend fun save(snapshot: LibrarySnapshot) {
        guarded(Unit) {
            val text = LibrarySnapshotCodec.encode(snapshot)
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                // 改名失败（少见）：退回直接写，至少内容是对的。
                file.writeText(text)
                temp.delete()
            }
        }
    }

    override suspend fun clear() {
        guarded(Unit) { file.delete() }
    }

    /** 落盘在 IO 线程上跑，失败一律吞掉；协程取消照常上抛。 */
    private suspend fun <T> guarded(fallback: T, block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            fallback
        }
    }
}
