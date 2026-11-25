package com.example.pulsar

import android.net.Uri
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pulsar.download.existingDownloadPlaybackUri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class ExistingDownloadPlaybackTest {
    @Test
    fun `冷启动后读取原副本并从中段继续 缓存缺失时不走网络或写盘`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "existing-download-test-").toFile()
        val key = "https://old.test/Videos/album-track/stream?static=true"
        val audio = byteArrayOf(10, 11, 12, 13, 14, 15)
        try {
            SimpleCache(directory, NoOpCacheEvictor()).let { oldCache ->
                try {
                    val source = CacheDataSource.Factory().setCache(oldCache)
                        .setUpstreamDataSourceFactory { ByteArrayDataSource(audio) }.createDataSourceForDownloading()
                    CacheWriter(source, DataSpec(Uri.parse(key)), null, null).cache()
                } finally {
                    oldCache.release()
                }
            }
            val cache = SimpleCache(directory, NoOpCacheEvictor())
            try {
                var networkCalls = 0
                val network = DataSource.Factory { networkCalls++; error("已有下载不能请求网络") }
                val mediaId = "existing:account:album-track"
                val linkedUri = "pulsar-linked:pc:paired:track/revision/$mediaId"
                val factory = playbackDataSourceFactory(
                    cache, network, network,
                    resolveLinkedKey = { uri -> key.takeIf { uri == linkedUri } },
                    resolveExistingKey = { id -> key.takeIf { id == mediaId } },
                )
                val originalSize = cache.cacheSpace
                val requests = listOf(existingDownloadPlaybackUri(mediaId), linkedUri).map { uri ->
                    DataSpec.Builder().setUri(uri).setKey("不能信任请求自带的键").setPosition(2).setLength(3).build()
                }
                for (request in requests) {
                    val source = factory.createDataSource()
                    try {
                        assertEquals(3L, source.open(request))
                        val bytes = ByteArray(3)
                        assertEquals(3, source.read(bytes, 0, bytes.size))
                        assertArrayEquals(byteArrayOf(12, 13, 14), bytes)
                    } finally {
                        source.close()
                    }
                }
                assertEquals(originalSize, cache.cacheSpace)
                assertEquals(setOf(key), cache.keys)

                cache.removeResource(key)
                for (request in requests) {
                    val source = factory.createDataSource()
                    try {
                        assertThrows(IOException::class.java) { source.open(request) }
                    } finally {
                        source.close()
                    }
                }
                assertEquals(0, networkCalls)
                assertEquals(0L, cache.cacheSpace)
            } finally {
                cache.release()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
