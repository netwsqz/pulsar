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
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Track
import com.example.pulsar.download.CompanionDownloads
import com.example.pulsar.download.CompanionDownloadTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    fun `仅点下载才写入原音频 重开后跳播只读手机 缺口不联网`(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "companion-download-test-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { directory.resolve("test.preferences_pb") }
        val ledger = DataStoreDownloadLedgerStore(preferences)
        val pairing = "c".repeat(64)
        val audio = byteArrayOf(10, 11, 12, 13, 14, 15)
        val url = "http://127.0.0.1:8900/api/library/tracks/${"a".repeat(64)}/audio"
        val track = Track("pc:test:${"a".repeat(64)}", "歌曲", "歌手", "专辑", null, null,
            sourceRevision = "b".repeat(64))
        val cacheDir = directory.resolve("media")
        try {
            val initial = SimpleCache(cacheDir, NoOpCacheEvictor())
            try {
                val network = DataSource.Factory { ByteArrayDataSource(audio) }
                val online = playbackDataSourceFactory(initial, network, network, resolveExistingKey = { null })
                assertArrayEquals(audio, readBytes(online, DataSpec(Uri.parse(url)), audio.size))
                assertEquals(0L, initial.cacheSpace)
                val bytes = SimpleCacheMediaDownloads(initial, network)
                val scheduler = DownloadScheduler(scope, bytes, ledger)
                val downloads = CompanionDownloads(preferences, bytes, scheduler) { pairing }
                val album = Album("专辑", "专辑", "歌手", 1, null)
                downloads.enqueue(CompanionDownloadTarget(pairing, album, listOf(track to url)))
                withTimeout(5_000) { scheduler.state.first { it.values.singleOrNull() == DownloadState.Downloaded } }
                assertEquals(audio.size.toLong(), initial.cacheSpace)
            } finally {
                initial.release()
            }
            val cache = SimpleCache(cacheDir, NoOpCacheEvictor())
            try {
                val network = DataSource.Factory { error("电脑下载不能请求网络") }
                val bytes = SimpleCacheMediaDownloads(cache, network)
                val downloads = CompanionDownloads(preferences, bytes, DownloadScheduler(scope, bytes, ledger)) { pairing }
                val copy = downloads.load(pairing).single().copies.single()
                val factory = playbackDataSourceFactory(cache, network, network,
                    resolveCompanionKey = downloads::playbackKey, resolveExistingKey = { null })
                val request = DataSpec.Builder().setUri(copy.playbackUri).setPosition(2).setLength(3).build()
                assertArrayEquals(byteArrayOf(12, 13, 14), readBytes(factory, request, 3))
                var streamed = 0
                val normalNetwork = DataSource.Factory { streamed++; ByteArrayDataSource(byteArrayOf(1, 2, 3, 4, 5, 6)) }
                val normal = playbackDataSourceFactory(cache, normalNetwork, normalNetwork, resolveExistingKey = { null })
                val unrelated = DataSpec.Builder().setUri(url).setKey(copy.key).setPosition(2).setLength(3).build()
                assertArrayEquals(byteArrayOf(3, 4, 5), readBytes(normal, unrelated, 3))
                assertEquals(1, streamed)
                assertEquals(audio.size.toLong(), cache.cacheSpace)
                cache.removeResource(copy.key)
                assertThrows(IOException::class.java) { readBytes(factory, request, 3) }
            } finally {
                cache.release()
            }
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    private fun readBytes(factory: DataSource.Factory, request: DataSpec, length: Int): ByteArray {
        val source = factory.createDataSource()
        try {
            assertEquals(length.toLong(), source.open(request))
            return ByteArray(length).also { bytes ->
                var offset = 0
                while (offset < length) {
                    val count = source.read(bytes, offset, length - offset)
                    check(count > 0)
                    offset += count
                }
            }
        } finally {
            source.close()
        }
    }
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
