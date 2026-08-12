package com.example.pulsar

import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource

/**
 * 为 Jellyfin 流媒体请求附加 Authorization 头。
 *
 * 每次创建数据源时从 [authorizationProvider] 读取当前头的值，因此重新登录后
 * 播放新歌曲会自动带上新的令牌，无需重建播放器。
 */
class AuthHttpDataSourceFactory(
    private val authorizationProvider: () -> String?
) : HttpDataSource.Factory {

    private val delegate = DefaultHttpDataSource.Factory()

    override fun createDataSource(): HttpDataSource {
        return delegate.createDataSource().apply {
            authorizationProvider()?.let { setRequestProperty("Authorization", it) }
        }
    }

    override fun setDefaultRequestProperties(
        defaultRequestProperties: MutableMap<String, String>
    ): HttpDataSource.Factory {
        delegate.setDefaultRequestProperties(defaultRequestProperties)
        return this
    }
}
