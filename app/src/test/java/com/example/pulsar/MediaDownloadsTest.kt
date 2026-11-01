package com.example.pulsar

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDownloadsTest {
    @Test
    fun `总长已知且整段在缓存 = 完整副本`() {
        assertTrue(isCompleteCopy(totalLength = 36_000_000L, isCached = true))
    }

    @Test
    fun `总长未知（-1，从没下过或上游不给 Content-Length）一律当作不完整`() {
        // 工单 01/03：兜底口径宁可标灰，不能让人点进去听一半断掉
        assertFalse(isCompleteCopy(totalLength = -1L, isCached = false))
        // isCached 传 true 也一样：总长都不知道，isCached(key, 0, -1) 的判定不可信
        assertFalse(isCompleteCopy(totalLength = -1L, isCached = true))
    }

    @Test
    fun `总长为 0 也当作不完整`() {
        assertFalse(isCompleteCopy(totalLength = 0L, isCached = true))
    }

    @Test
    fun `有总长但没缓存全 = 不完整`() {
        assertFalse(isCompleteCopy(totalLength = 36_000_000L, isCached = false))
    }

    /**
     * 铁律 4：下载出任何问题都只回 false，不抛给调度器/界面。
     *
     * JVM 单测里 `android.net.Uri.parse` 走 android.jar 桩返回 null，`DataSpec.build()`
     * 因此抛异常——正好落在 guardedIo 的兜底上，这就是本用例要钉的行为。
     */
    @Test
    fun `下载失败返回 false 而不是抛异常`() = runTest {
        val downloads = SimpleCacheMediaDownloads(
            cache = unusedCache,
            upstreamFactory = DataSource.Factory { error("不该走到上游：地址都没构造出来") },
        )

        assertFalse(downloads.download("https://server.test/Videos/x/stream", "stream/x") { _, _ -> })
    }

    /** 只用来占位：本用例的失败发生在碰 Cache 之前，任何调用都算越界。 */
    private val unusedCache: Cache = Proxy.newProxyInstance(
        Cache::class.java.classLoader,
        arrayOf(Cache::class.java),
    ) { _, method, _ -> error("不该碰 Cache：${method.name}") } as Cache
}
