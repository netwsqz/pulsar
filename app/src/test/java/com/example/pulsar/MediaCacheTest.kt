package com.example.pulsar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCacheTest {
    @Test
    fun `总长已知且整段在缓存 = 完整副本`() {
        assertTrue(isCompleteCopy(totalLength = 36_000_000L, isCached = true))
    }

    @Test
    fun `总长未知（-1，从没播过或上游不给 Content-Length）一律当作不完整`() {
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
}
