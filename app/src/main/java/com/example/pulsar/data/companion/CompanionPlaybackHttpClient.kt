package com.example.pulsar.data.companion

import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** 播放器先核对配对地址，再附密钥；不信任音频服务返回的跳转地址。 */
internal fun companionPlaybackHttpClient(connection: CompanionLibraryConnection): OkHttpClient =
    OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            val auth = connection.playbackAuthorization(request.url)
                ?: throw IOException("电脑曲库的播放地址不属于当前配对")
            chain.proceed(request.newBuilder().header("Authorization", auth).build())
        }
        .build()
