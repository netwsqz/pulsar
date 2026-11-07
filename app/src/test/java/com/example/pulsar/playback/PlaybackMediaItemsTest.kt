package com.example.pulsar.playback

import com.example.pulsar.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放条目构造的规格：曲目 → Media3 条目。
 *
 * 这些文本字段是**跨进程**传递的全部内容——Activity 被系统回收后，迷你条与全屏页只能
 * 从播放服务里的条目读回标题/歌手/封面，所以少搬一个字段就是真机上少一块显示。
 *
 * **为什么不断言 URI / 封面 tag / extras**：JVM 单测跑在桩化的 android.jar 上，
 * `Uri.parse` 返回 null，Media3 因此不建 `localConfiguration`（tag 与 URI 都挂在它下面）；
 * `Bundle` 的键值读写也是桩（extras 读不出来）。这三项是逐字传递、逻辑为零，靠
 * 真机验收清单覆盖（min 条显示对应封面、换歌响度增益生效）。
 */
class PlaybackMediaItemsTest {

    private fun track(
        id: String = "track-1",
        title: String = "晴天",
        artist: String = "周杰伦",
        album: String = "叶惠美",
        imageTag: String? = "img-1",
        normalizationGainDb: Float? = null
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = 269_000L,
        imageTag = imageTag,
        normalizationGainDb = normalizationGainDb
    )

    @Test
    fun `buildMediaItem 把 id 标题 歌手 专辑搬进播放条目`() {
        val item = buildMediaItem(track(), "https://music.example/stream/track-1")

        assertEquals("track-1", item.mediaId)
        assertEquals("晴天", item.mediaMetadata.title?.toString())
        assertEquals("周杰伦", item.mediaMetadata.artist?.toString())
        assertEquals("叶惠美", item.mediaMetadata.albumTitle?.toString())
    }
}
