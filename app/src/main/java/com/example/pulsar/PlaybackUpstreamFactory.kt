package com.example.pulsar

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import com.example.pulsar.data.companion.isCompanionStreamUrl
import com.example.pulsar.download.existingDownloadIdFromUri
import com.example.pulsar.download.isExistingDownloadPlaybackUri
import com.example.pulsar.download.isLinkedDownloadPlaybackUri
import java.io.IOException
import kotlinx.coroutines.runBlocking

/** 原曲库可退回网络；「已有下载」单走没有网络上游的只读源。 */
internal fun playbackDataSourceFactory(
    cache: Cache,
    jellyfin: DataSource.Factory,
    companion: DataSource.Factory,
    resolveLinkedKey: suspend (String) -> String? = { null },
    resolveExistingKey: suspend (String) -> String?,
): DataSource.Factory {
    val online = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(PlaybackUpstreamFactory { uri ->
            if (isCompanionStreamUrl(uri)) companion else jellyfin
        })
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    val existing = ResolvingDataSource.Factory(
        CacheDataSource.Factory().setCache(cache).setCacheWriteDataSinkFactory(null),
    ) { spec ->
        // Media3 在加载线程打开数据源。重新校验本机归属和完整性，包括从队列续播或拖进度。
        val uri = spec.uri.toString()
        val key = runBlocking {
            if (isLinkedDownloadPlaybackUri(uri)) resolveLinkedKey(uri)
            else existingDownloadIdFromUri(uri)?.let { resolveExistingKey(it) }
        } ?: throw IOException("已有下载的原副本暂不可读")
        // 只替换读取定位，保留 Media3 请求的字节范围；不采用调用方传来的键。
        spec.buildUpon().setKey(key).build()
    }
    return PlaybackUpstreamFactory { uri ->
        if (isExistingDownloadPlaybackUri(uri) || isLinkedDownloadPlaybackUri(uri)) existing else online
    }
}

/** 每次打开时选数据源：Media3 也可能用同一个实例继续读取下一区间。 */
private class PlaybackUpstreamFactory(
    private val factoryForUri: (String) -> DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = object : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var upstream: DataSource? = null

        override fun addTransferListener(listener: TransferListener) {
            listeners += listener
            upstream?.addTransferListener(listener)
        }

        override fun open(dataSpec: DataSpec): Long {
            check(upstream == null)
            val source = factoryForUri(dataSpec.uri.toString()).createDataSource()
            upstream = source
            listeners.forEach(source::addTransferListener)
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            checkNotNull(upstream).read(buffer, offset, length)

        override fun getUri(): Uri? = upstream?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders.orEmpty()

        override fun close() {
            val source = upstream
            upstream = null
            source?.close()
        }
    }
}
