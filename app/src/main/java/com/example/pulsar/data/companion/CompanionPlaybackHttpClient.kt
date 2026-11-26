package com.example.pulsar.data.companion

import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** 伴生服务的音频和封面都拒绝跳转和代理。 */
private fun companionHttpClient() = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .proxy(Proxy.NO_PROXY)
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)

internal fun companionDownloadHttpClient(): OkHttpClient = companionHttpClient()
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
    companionHttpClient()
        .addInterceptor { chain ->
            val request = chain.request()
            val auth = connection.playbackAuthorization(request.url)
                ?: throw IOException("电脑曲库的播放地址不属于当前配对")
            chain.proceed(request.newBuilder().header("Authorization", auth).build())
        }
        .build()

/** Coil 只持有不含密钥的请求；真正发出 HTTP 时才按配对和封面接口鉴权。 */
internal fun companionArtworkHttpClient(connection: CompanionLibraryConnection): OkHttpClient =
    companionHttpClient()
        .addInterceptor { chain ->
            val request = chain.request()
            val artwork = request.tag(CompanionArtworkRequest::class.java)
                ?: throw IOException("缺少电脑曲库封面请求")
            val auth = connection.artworkAuthorization(request.url, artwork.scope)
                ?: throw IOException("电脑曲库的封面地址不属于当前配对")
            chain.proceed(request.newBuilder().header("Authorization", auth)
                .header("If-Match", "\"${artwork.revision}\"").build())
        }
        .build()
