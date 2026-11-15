package com.example.pulsar.data.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 鉴权头只有这一份拼装实现（#23）：URL 构建器与数据源都必须用它，
 * 否则哪天改了 Client/Device/Version 就会一半请求成功一半 401。
 */
class MediaBrowserAuthorizationTest {

    @Test
    fun `header includes token in MediaBrowser format`() {
        assertEquals(
            "MediaBrowser Client=\"Pulsar\", Device=\"Android\", " +
                "DeviceId=\"device-1\", Version=\"0.1.0\", Token=\"token-1\"",
            mediaBrowserAuthorization("device-1", "token-1")
        )
    }

    @Test
    fun `header without token is only used for login`() {
        assertEquals(
            "MediaBrowser Client=\"Pulsar\", Device=\"Android\", " +
                "DeviceId=\"device-1\", Version=\"0.1.0\"",
            mediaBrowserAuthorization("device-1", null)
        )
    }
}
