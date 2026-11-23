package com.example.pulsar

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.example.pulsar.data.companion.isCompanionStreamUrl

/** 每次打开时选上游：CacheDataSource 也可能用同一个实例继续读取下一区间。 */
internal class PlaybackUpstreamFactory(
    private val jellyfin: DataSource.Factory,
    private val companion: DataSource.Factory,
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
            val factory = if (isCompanionStreamUrl(dataSpec.uri.toString())) companion else jellyfin
            val source = factory.createDataSource()
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
