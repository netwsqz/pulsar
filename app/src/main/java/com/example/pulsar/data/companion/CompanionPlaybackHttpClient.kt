package com.example.pulsar.data.companion

import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** 两种音频请求都拒绝跳转和代理；下载还要确认服务端支持版本校验。 */
private fun companionAudioClient() = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .proxy(Proxy.NO_PROXY)
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)

internal fun companionDownloadHttpClient(): OkHttpClient = companionAudioClient()
    .addInterceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.isSuccessful && (request.header("If-Match") == null || response.header("ETag") != request.header("If-Match"))) {
            response.close()
            throw IOException("电脑服务不支持安全续传，或电脑上的文件已变化")
        }
        response
    }
    .build()

/** 播放器先核对配对地址，再附密钥；不信任音频服务返回的跳转地址。 */
internal fun companionPlaybackHttpClient(connection: CompanionLibraryConnection): OkHttpClient =
    companionAudioClient()
        .addInterceptor { chain ->
            val request = chain.request()
            val auth = connection.playbackAuthorization(request.url)
                ?: throw IOException("电脑曲库的播放地址不属于当前配对")
            chain.proceed(request.newBuilder().header("Authorization", auth).build())
        }
        .build()
