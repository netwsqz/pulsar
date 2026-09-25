package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LibrarySnapshotTest {
    private fun tempFile(): File = Files.createTempFile("snapshot", ".json").toFile()

    @Test
    fun `快照往返：encode 后 decode 回到同一份内容`() {
        val snapshot = LibrarySnapshot(
            tracks = listOf(
                Track("t1", "歌名", "歌手", "专辑", 1000L, "tag", normalizationGainDb = -3.5f, filePath = "E:\\x.flac")
            ),
            albums = listOf(Album("a1", "专辑", "歌手", 12, "tag")),
            discoverAlbums = listOf(Album("a1", "专辑", "歌手", 12, "tag"))
        )
        assertEquals(snapshot, LibrarySnapshotCodec.decode(LibrarySnapshotCodec.encode(snapshot)))
    }

    @Test
    fun `坏 JSON 返回 null 而不是抛异常`() {
        assertNull(LibrarySnapshotCodec.decode("这不是 JSON"))
        assertNull(LibrarySnapshotCodec.decode("{\"tracks\": 123}"))
    }

    @Test
    fun `未知字段不挡解码（旧快照兼容新字段）`() {
        val encoded = LibrarySnapshotCodec.encode(LibrarySnapshot())
        val withExtra = encoded.dropLast(1) + ",\"futureField\": 1}"
        assertEquals(LibrarySnapshot(), LibrarySnapshotCodec.decode(withExtra))
    }

    @Test
    fun `文件实现：save 后 load 回来一致，clear 后 load 为 null`() {
        val repo = FileLibrarySnapshotRepository(tempFile())
        val snapshot = LibrarySnapshot(tracks = listOf(Track("t1", "歌", "手", "辑", 1L, null)))
        kotlinx.coroutines.runBlocking {
            repo.save(snapshot)
            assertEquals(snapshot, repo.load())
            repo.clear()
            assertNull(repo.load())
        }
    }

    @Test
    fun `文件不存在时 load 返回 null`() {
        val repo = FileLibrarySnapshotRepository(File(Files.createTempDirectory("d").toFile(), "nope.json"))
        assertNull(kotlinx.coroutines.runBlocking { repo.load() })
    }

    @Test
    fun `文件被搞坏（半截 JSON）时 load 返回 null，不抛异常`() {
        val file = tempFile()
        file.writeText(LibrarySnapshotCodec.encode(LibrarySnapshot()).dropLast(10))
        val repo = FileLibrarySnapshotRepository(file)
        assertNull(kotlinx.coroutines.runBlocking { repo.load() })
    }

    // ── 离线搜索过滤 ──────────────────────────────────────────────

    private val snapshot = LibrarySnapshot(
        tracks = listOf(
            Track("t1", "十年", "陈奕迅", "U87", 1L, null),
            Track("t2", "浮夸", "陈奕迅", "U87", 1L, null),
            Track("t3", "稻香", "周杰伦", "魔杰座", 1L, null)
        ),
        albums = listOf(Album("a1", "U87", "陈奕迅", 2, null))
    )

    @Test
    fun `离线搜索按歌名过滤`() {
        val results = snapshot.search("十年")
        assertEquals(listOf("t1"), results.tracks.map { it.id })
    }

    @Test
    fun `离线搜索按歌手过滤（大小写不敏感）`() {
        val results = snapshot.search("陈奕迅")
        assertEquals(listOf("t1", "t2"), results.tracks.map { it.id })
    }

    @Test
    fun `离线搜索按专辑名过滤，也搜到专辑本体`() {
        val results = snapshot.search("U87")
        assertEquals(listOf("t1", "t2"), results.tracks.map { it.id })
        assertEquals(listOf("a1"), results.albums.map { it.id })
    }

    @Test
    fun `空关键词返回空结果`() {
        val results = snapshot.search("  ")
        assertTrue(results.tracks.isEmpty() && results.albums.isEmpty() && results.playlists.isEmpty())
    }

    @Test
    fun `没命中返回空结果`() {
        val results = snapshot.search("不存在的歌")
        assertTrue(results.tracks.isEmpty())
    }

    @Test
    fun `离线搜索不搜播放列表（快照里没有）`() {
        assertTrue(snapshot.search("任意").playlists.isEmpty())
    }
}
