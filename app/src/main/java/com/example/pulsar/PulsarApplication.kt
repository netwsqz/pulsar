package com.example.pulsar

import android.app.Application
import com.example.pulsar.data.jellyfin.JellyfinDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class PulsarApplication : Application() {
    /** 当前登录会话的 Jellyfin Authorization 头（仅内存，不持久化）。 */
    @Volatile
    var activeAuthorization: String? = null

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

    suspend fun jellyfinDataSource(serverUrl: String): JellyfinDataSource {
        return JellyfinDataSource(
            client = jellyfinClient,
            urlBuilder = com.example.pulsar.data.jellyfin.JellyfinUrlBuilder(serverUrl),
            deviceId = InstallationIdStore(pulsarPreferencesDataStore).getOrCreate()
        )
    }
}
