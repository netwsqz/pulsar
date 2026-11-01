package com.example.pulsar

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手机本地的音频副本（**下载**）。存在的唯一理由是**离线能听**——电脑关机、连不上
 * Jellyfin 时，下载过的歌照样能放。
 *
 * **用户主动下载，永不自动淘汰**（ADR-0007）：只有调 [download] 才会往盘上写一个字节，
 * 播放链路纯只读；删只能一首一首调 [remove]。没有总量上限，也没有「一键清空」。
 *
 * 键 = 播放流的 URL（`{server}/Videos/{id}/stream?static=true`）。token 只走 header
 * 不进 URL，所以键天然按「服务器」区分归属。
 *
 * **下载是增强，不是依赖**（spec 铁律 4）：这里任何一次调用失败都只当作「没有下载」，
 * 绝不把异常抛给播放或界面。
 */
interface MediaDownloads {
    /** 这首歌有没有**完整副本**——下到一半的不算数。 */
    suspend fun hasCompleteCopy(key: String): Boolean

    /** 当前占用字节数。 */
    suspend fun usedBytes(): Long

    /** 下了多少**首**（只算完整副本）。 */
    suspend fun completeCount(): Int

    /**
     * 全部**完整副本**的键。给离线可见性用：一次 IO 往返拿全量，界面拿它跟曲目表
     * 批量比对，不用逐首查询（几百首逐个查是几百次 IO 跳跃）。
     */
    suspend fun completeKeys(): Set<String>

    /**
     * 把一首歌整个下到手机上。已下完的部分会跳过（CacheWriter 自带），所以取消后重下只补缺口。
     * 失败返回 false，绝不抛（铁律 4）；协程取消照常上抛。
     *
     * [onProgress] 在 IO 线程回调，[totalBytes] 未知时为 -1。
     */
    suspend fun download(
        url: String,
        key: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Boolean

    /** 删掉一首的下载（删歌时顺带清）。 */
    suspend fun remove(key: String)
}

/**
 * 「完整副本」的判据：总长已知（`exo_len`）且这一段全在缓存里。
 *
 * 拿不到总长时一律**当作不完整**——`ContentMetadata.getContentLength` 对从没下过的条目、
 * 上游不给 `Content-Length` 的流（转码 / chunked）都返回 -1。宁可当作没下载，也不能让人
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
 * 下载/快照这类「手机本地落盘」操作的公共兜底：挪到 IO 线程、吞掉一切异常只回
 * [fallback]（铁律 4），协程取消照常上抛。MediaDownloads 与曲库快照两处共用，防漂移。
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
 * 生产实现：Media3 的 `SimpleCache`。字节与索引都是它自己的事，这里只做
 * 「查完整副本 / 报占用 / 下载一首 / 删一首」几个动作。
 *
 * 淘汰器是 `NoOpCacheEvictor`（在 [PulsarApplication] 里配）：**什么都不自动删**。
 */
class SimpleCacheMediaDownloads(
    private val cache: Cache,
    /** 下载的上游（网络）源。生产传 `AuthHttpDataSourceFactory`：token 只进 header。 */
    private val upstreamFactory: DataSource.Factory,
) : MediaDownloads {

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

    override suspend fun download(
        url: String,
        key: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val writer = CacheWriter(
                // createDataSourceForDownloading() 无视播放侧的只读标记（bytecode 已确认），
                // 所以下载和播放可以共用同一个 SimpleCache 实例。
                CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(upstreamFactory)
                    .createDataSourceForDownloading(),
                // key 必须显式设：不设的话 DataSpec 的 key 为 null，CacheKeyFactory 会退回
                // 用 URL 当键，就跟播放侧算出来的 cacheKey 对不上了。
                DataSpec.Builder().setUri(url).setKey(key).build(),
                null,
                CacheWriter.ProgressListener { requestLength, bytesCached, _ ->
                    onProgress(bytesCached, requestLength)
                },
            )
            // cache() 是同步阻塞的读循环，它只看自己的 isCanceled 标记、不看协程状态。
            // 这个协程被取消时，看门狗立刻把循环掐停，不用等这一块 buffer 读完。
            val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    writer.cancel()
                }
            }
            try {
                writer.cache()
                true
            } finally {
                watchdog.cancel()
                // 幂等：取消时再掐一次，正常跑完也把数据源关掉（CacheWriter 只关自己的循环）。
                writer.cancel()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // 铁律 4：下载失败只回 false，绝不抛给调度器/界面
            false
        }
    }

    override suspend fun remove(key: String) {
        guardedIo(Unit) { cache.removeResource(key) }
    }
}
