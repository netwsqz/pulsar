package com.example.pulsar

import com.example.pulsar.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 发现页「每日推荐」挑选逻辑：按天稳定随机、数量封顶、空库安全。 */
class DiscoverPicksTest {

    private fun track(id: String) = Track(
        id = id,
        title = "歌$id",
        artist = "歌手$id",
        album = "专辑$id",
        durationMs = 100_000L,
        imageTag = null
    )

    @Test
    fun `同一天同一批曲目挑出的结果完全一致`() {
        val tracks = (1..50).map { track("t$it") }
        val first = discoverDailyPicks(tracks, dayIndex = 20_260_903, count = 4)
        val second = discoverDailyPicks(tracks, dayIndex = 20_260_903, count = 4)
        assertEquals(first, second)
    }

    @Test
    fun `不同日期倾向给出不同组合`() {
        val tracks = (1..50).map { track("t$it") }
        // 7 天里至少有 6 天的组合与第一天不同（随机挑选几乎必然变化，取宽裕阈值防偶发）。
        val baseline = discoverDailyPicks(tracks, dayIndex = 20_260_901, count = 4)
        val differing = (1..7).count { offset ->
            discoverDailyPicks(tracks, dayIndex = 20_260_901L + offset, count = 4) != baseline
        }
        assertTrue("7 天里只有 $differing 天组合不同", differing >= 6)
    }

    @Test
    fun `挑选数量不超过封顶值`() {
        val tracks = (1..50).map { track("t$it") }
        val picks = discoverDailyPicks(tracks, dayIndex = 1, count = 4)
        assertEquals(4, picks.size)
    }

    @Test
    fun `曲库不足封顶值时返回全部曲目`() {
        val tracks = (1..3).map { track("t$it") }
        val picks = discoverDailyPicks(tracks, dayIndex = 1, count = 4)
        assertEquals(3, picks.size)
        assertEquals(tracks.map { t -> t.id }.toSet(), picks.map { t -> t.id }.toSet())
    }

    @Test
    fun `空曲库返回空列表`() {
        assertTrue(discoverDailyPicks(emptyList(), dayIndex = 1, count = 4).isEmpty())
    }

    @Test
    fun `不重复挑选同一首`() {
        val tracks = (1..20).map { track("t$it") }
        val picks = discoverDailyPicks(tracks, dayIndex = 42, count = 4)
        assertEquals(4, picks.map { track -> track.id }.distinct().size)
    }

    @Test
    fun `同一天同一变体编号结果稳定`() {
        val tracks = (1..50).map { track("t$it") }
        val first = discoverDailyPicks(tracks, dayIndex = 20_260_903, variant = 2, count = 4)
        val second = discoverDailyPicks(tracks, dayIndex = 20_260_903, variant = 2, count = 4)
        assertEquals(first, second)
    }

    @Test
    fun `变体编号变化时倾向给出不同组合`() {
        val tracks = (1..50).map { track("t$it") }
        // 5 个变体里至少 4 个与变体 0 不同（下拉刷新每次都该有可感知的变化，取宽裕阈值防偶发）。
        val baseline = discoverDailyPicks(tracks, dayIndex = 20_260_903, variant = 0, count = 4)
        val differing = (1..5).count { v ->
            discoverDailyPicks(tracks, dayIndex = 20_260_903, variant = v, count = 4) != baseline
        }
        assertTrue("5 个变体里只有 $differing 个组合不同", differing >= 4)
    }
}
