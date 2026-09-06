package com.example.pulsar.data.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LyricMapper] 纯函数测试：Jellyfin 歌词 DTO → 展示模型的时间换算、同步判定与当前行定位。
 *
 * Jellyfin ticks：100 纳秒单位，10_000_000 ticks = 1 秒。纯文本歌词所有行 Start=0
 * （服务器解析无时间戳的 .txt 歌词时的表现），此时不能做逐行高亮滚动。
 */
class LyricMapperTest {

    // ===== ticks → 微秒换算 =====

    @Test
    fun `零 ticks 换算为零微秒`() {
        assertEquals(0L, ticksToUs(0L))
    }

    @Test
    fun `一秒的 ticks 换算为一百万微秒`() {
        assertEquals(1_000_000L, ticksToUs(10_000_000L))
    }

    @Test
    fun `435 秒 LRC 行的 ticks 换算为 43500000 微秒`() {
        // [00:43.50] → 43.5s = 435_000_000 ticks = 43_500_000 µs
        assertEquals(43_500_000L, ticksToUs(435_000_000L))
    }

    // ===== 同步 / 非同步歌词判定 =====

    @Test
    fun `空歌词列表判定为非同步`() {
        assertFalse(isSyncedLyrics(emptyList()))
    }

    @Test
    fun `所有行 Start 为零判定为非同步纯文本`() {
        val lines = listOf(
            LyricLineDto(text = "纯文本第一行"),
            LyricLineDto(text = "纯文本第二行"),
        )
        assertFalse(isSyncedLyrics(lines))
    }

    @Test
    fun `首行以外的任意行 Start 大于零判定为同步`() {
        val lines = listOf(
            LyricLineDto(text = "第一行"),
            LyricLineDto(text = "第二行", start = 43_500_000L / 10),
        )
        assertTrue(isSyncedLyrics(lines))
    }

    // ===== LyricResponse 转展示模型 =====

    @Test
    fun `同步歌词转换为按时间升序的行列表`() {
        val response = LyricResponseDto(
            lyrics = listOf(
                LyricLineDto(text = "第二行", start = 870_000_000L),
                LyricLineDto(text = "第一行", start = 0L),
            )
        )
        val lyrics = response.toLyrics()
        assertTrue(lyrics.isSynced)
        assertEquals(
            listOf(
                LyricLine(0L, "第一行"),
                LyricLine(87_000_000L, "第二行"),
            ),
            lyrics.lines
        )
    }

    @Test
    fun `非同步纯文本歌词 isSynced 为 false 保留原始顺序`() {
        val response = LyricResponseDto(
            lyrics = listOf(
                LyricLineDto(text = "第一段"),
                LyricLineDto(text = "第二段"),
            )
        )
        val lyrics = response.toLyrics()
        assertFalse(lyrics.isSynced)
        assertEquals(listOf("第一段", "第二段"), lyrics.lines.orEmpty().map { it.text })
    }

    @Test
    fun `响应无歌词字段时返回空歌词`() {
        val lyrics = LyricResponseDto(lyrics = emptyList()).toLyrics()
        assertFalse(lyrics.isSynced)
        assertTrue(lyrics.lines.orEmpty().isEmpty())
    }

    // ===== 按播放位置定位当前行（二分查找） =====

    private fun syncedLyrics(): Lyrics {
        // 模拟 LRC：0s / 4.35s / 8.7s 三行
        return LyricResponseDto(
            lyrics = listOf(
                LyricLineDto(text = "A", start = 0L),
                LyricLineDto(text = "B", start = 43_500_000L),
                LyricLineDto(text = "C", start = 87_000_000L),
            )
        ).toLyrics()
    }

    @Test
    fun `播放位置在首行起点之前定位到负一行`() {
        assertEquals(-1, syncedLyrics().currentIndexFor(0L - 1))
    }

    @Test
    fun `播放位置恰好等于某行起点定位到该行`() {
        assertEquals(1, syncedLyrics().currentIndexFor(4_350_000L))
    }

    @Test
    fun `播放位置落在两行之间定位到较早的一行`() {
        assertEquals(1, syncedLyrics().currentIndexFor(6_000_000L))
    }

    @Test
    fun `播放位置超过最后一行起点定位到最后一行`() {
        assertEquals(2, syncedLyrics().currentIndexFor(99_000_000L))
    }

    @Test
    fun `空歌词定位到负一行`() {
        assertEquals(-1, Lyrics(isSynced = false, lines = emptyList()).currentIndexFor(0L))
    }

    // ===== 找不到歌词的哨兵值 =====

    @Test
    fun `无歌词哨兵的 lines 为空且 isSynced 为 false`() {
        assertNull(Lyrics.NONE.lines)
        assertFalse(Lyrics.NONE.isSynced)
    }
}
