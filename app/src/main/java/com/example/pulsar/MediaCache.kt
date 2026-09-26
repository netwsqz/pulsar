package com.example.pulsar

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 手机本地的音频副本（缓存）。存在的唯一理由是**离线能听**——电脑关机、连不上
 * Jellyfin 时，缓存过的歌照样能放。自动产生、满了自动淘汰最旧的，用户不用管。
 *
 * 键 = 播放流的 URL（`{server}/Videos/{id}/stream?static=true`）。token 只走 header
 * 不进 URL，所以键天然按「服务器」区分归属。
 *
 * **缓存是增强，不是依赖**（spec 铁律 1）：这里的任何一次调用失败都只当作「没有副本」，
 * 绝不把异常抛给播放或界面。缓存挂了顶多没有离线能力，不能变成新的故障源。
 */
interface MediaCache {
    /** 这首歌有没有**完整副本**——听到一半切走留下的半截不算数。 */
    suspend fun hasCompleteCopy(key: String): Boolean

    /** 当前占用字节数。 */
    suspend fun usedBytes(): Long

    /** 存了多少**首**（只算完整副本）。 */
    suspend fun completeCount(): Int

    /**
     * 全部**完整副本**的键。给离线标灰用：一次 IO 往返拿全量，界面拿它跟曲目表
     * 批量比对，不用逐首查询（几百首逐个查是几百次 IO 跳跃）。
     */
    suspend fun completeKeys(): Set<String>

    /**
     * 清空副本，**[keepKeys] 里的除外**。只动手机本地，跟电脑上的曲库无关（铁律 3）。
     * 保留正在播的那首：清空不该把正在听的歌从播放流底下抽走。
     */
    suspend fun clear(keepKeys: Set<String> = emptySet())

    /** 删掉一首的副本（删歌时顺带清）。 */
    suspend fun remove(key: String)
}

/**
 * 「完整副本」的判据：总长已知（`exo_len`）且这一段全在缓存里。
 *
 * 拿不到总长时一律**当作不完整**——`ContentMetadata.getContentLength` 对从没播过的条目、
 * 上游不给 `Content-Length` 的流（转码 / chunked）都返回 -1。宁可标灰，也不能让人
 * 点进去听一半断掉。
 *
 * **调用方必须先用总长做前置判断再调 `cache.isCached(key, 0, total)`**：Media3 对
 * 没 `exo_len` 的条目（打开失败的残留）执行 `isCached(key, 0, -1)` 会直接抛
 * `IllegalArgumentException`（字节码确认 `getCachedBytesLength` 断言 length >= 0），
 * 聚合遍历里一个坏条目会把整轮统计打断。
 */
internal fun isCompleteCopy(totalLength: Long, isCached: Boolean): Boolean =
    totalLength > 0 && isCached

/**
 * 缓存/快照这类「手机本地落盘」操作的公共兜底：挪到 IO 线程、吞掉一切异常只回
 * [fallback]（铁律 1），协程取消照常上抛。MediaCache 与曲库快照两处共用，防漂移。
 */
internal suspend fun <T> guardedIo(fallback: T, block: () -> T): T = withContext(Dispatchers.IO) {
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        fallback
    }
}

/**
 * 生产实现：Media3 的 `SimpleCache`。索引、LRU 淘汰、边读边写都是它自己的事，
 * 这里只做「查完整副本 / 报占用 / 清空 / 删一首」几个动作。
 */
class SimpleCacheMediaCache(private val cache: Cache) : MediaCache {

    override suspend fun hasCompleteCopy(key: String): Boolean = guardedIo(false) {
        val total = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        // total <= 0 时不碰 isCached：残条目 isCached(key, 0, -1) 会抛 IAE（见 isCompleteCopy）
        total > 0 && isCompleteCopy(total, cache.isCached(key, 0, total))
    }

    override suspend fun usedBytes(): Long = guardedIo(0L) { cache.cacheSpace }

    override suspend fun completeCount(): Int = completeKeys().size

    override suspend fun completeKeys(): Set<String> = guardedIo(emptySet()) {
        cache.keys.filterTo(mutableSetOf()) { key ->
            val total = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            // 同 hasCompleteCopy：total <= 0 先挡掉，别让残条目的 IAE 打断整轮统计
            total > 0 && isCompleteCopy(total, cache.isCached(key, 0, total))
        }
    }

    override suspend fun clear(keepKeys: Set<String>) {
        guardedIo(Unit) {
            // 逐个删而不是整目录删：SimpleCache 实例还活着，删目录会把它自己的锁文件一起干掉。
            // keepKeys 跳过——正在播的那首不从读取流底下抽走，清空不破坏正在进行的播放。
            cache.keys.toList().forEach { key ->
                if (key !in keepKeys) cache.removeResource(key)
            }
        }
    }

    override suspend fun remove(key: String) {
        guardedIo(Unit) { cache.removeResource(key) }
    }
}
