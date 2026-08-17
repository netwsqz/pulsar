package com.example.pulsar

import android.app.Application
import com.example.pulsar.data.jellyfin.JellyfinDataSource
import com.example.pulsar.data.jellyfin.JellyfinApi
import com.example.pulsar.data.jellyfin.JellyfinApiFactory
import com.example.pulsar.data.qqdownload.QQDownloadApi
import com.example.pulsar.data.qqdownload.QQDownloadDataSource
import com.example.pulsar.data.qqdownload.QQDownloadPreferences
import com.example.pulsar.data.qqdownload.QQDownloadSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class PulsarApplication : Application(), AuthorizationSink {
    /** 当前登录会话的 Jellyfin Authorization 头（仅内存，不持久化）。 */
    @Volatile
    override var activeAuthorization: String? = null

    private val jellyfinClient: HttpClient by lazy {
        HttpClient(Android) {
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    /** QQ 下载服务客户端：内网自建服务，超时给短一点（连不上快速失败）。 */
    private val qqDownloadClient: HttpClient by lazy {
        HttpClient(Android) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 15_000
            }
        }
    }

    suspend fun jellyfinDataSource(serverUrl: String): JellyfinDataSource {
        return JellyfinDataSource(
            client = jellyfinClient,
            urlBuilder = com.example.pulsar.data.jellyfin.JellyfinUrlBuilder(serverUrl),
            deviceId = InstallationIdStore(pulsarPreferencesDataStore).getOrCreate()
        )
    }

    fun sessionRepository(): SessionRepository = SessionStore(pulsarPreferencesDataStore)

    fun jellyfinApiFactory(): JellyfinApiFactory = object : JellyfinApiFactory {
        override suspend fun create(serverUrl: String): JellyfinApi = jellyfinDataSource(serverUrl)
    }

    fun qqDownloadSettings(): QQDownloadSettings = QQDownloadPreferences(pulsarPreferencesDataStore)

    fun qqDownloadApiFactory(): QQDownloadApiFactory = object : QQDownloadApiFactory {
        override fun create(baseUrl: String): QQDownloadApi =
            QQDownloadDataSource(client = qqDownloadClient, baseUrl = baseUrl)
    }
}
