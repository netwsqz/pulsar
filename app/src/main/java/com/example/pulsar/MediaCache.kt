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

    /** 清空全部副本。只动手机本地，跟电脑上的曲库无关（铁律 3）。 */
    suspend fun clear()

    /** 删掉一首的副本（删歌时顺带清）。 */
    suspend fun remove(key: String)
}

/**
 * 「完整副本」的判据：总长已知（`exo_len`）且这一段全在缓存里。
 *
 * 拿不到总长时一律**当作不完整**——`ContentMetadata.getContentLength` 对从没播过的条目、
 * 上游不给 `Content-Length` 的流（转码 / chunked）都返回 -1。宁可标灰，也不能让人
 * 点进去听一半断掉。
 */
internal fun isCompleteCopy(totalLength: Long, isCached: Boolean): Boolean =
    totalLength > 0 && isCached

/**
 * 生产实现：Media3 的 `SimpleCache`。索引、LRU 淘汰、边读边写都是它自己的事，
 * 这里只做「查完整副本 / 报占用 / 清空 / 删一首」四个动作。
 */
class SimpleCacheMediaCache(private val cache: Cache) : MediaCache {

    override suspend fun hasCompleteCopy(key: String): Boolean = guarded(false) {
        val total = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        isCompleteCopy(total, cache.isCached(key, 0, total))
    }

    override suspend fun usedBytes(): Long = guarded(0L) { cache.cacheSpace }

    override suspend fun completeCount(): Int = guarded(0) {
        cache.keys.count { key ->
            val total = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            isCompleteCopy(total, cache.isCached(key, 0, total))
        }
    }

    override suspend fun clear() {
        guarded(Unit) {
            // 逐个删而不是整目录删：SimpleCache 实例还活着，删目录会把它自己的锁文件一起干掉。
            cache.keys.toList().forEach { cache.removeResource(it) }
        }
    }

    override suspend fun remove(key: String) {
        guarded(Unit) { cache.removeResource(key) }
    }

    /** 缓存层在 IO 线程上跑，且**吞掉一切异常**（铁律 1）；协程取消照常上抛。 */
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
