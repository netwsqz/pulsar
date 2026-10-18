package com.example.pulsar

import android.app.Application
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.example.pulsar.data.jellyfin.JellyfinDataSource
import com.example.pulsar.data.jellyfin.JellyfinApi
import com.example.pulsar.data.jellyfin.JellyfinApiFactory
import com.example.pulsar.data.libraryfiles.LibraryFilesApi
import com.example.pulsar.data.libraryfiles.LibraryFilesDataSource
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
import java.io.File

/** 缓存目录用 `filesDir` 而**不是** `cacheDir`：后者会被系统在空间紧张时清掉，
 * 也会被设置里的「清除缓存」整锅端——而这份缓存是专门为「出门能听」攒的。 */
private const val kMediaCacheDirName = "media_cache"

/** 缓存上限 5 GB（按 FLAC 平均 36 MB/首算约 140 首）。超了自动淘汰**最久没听的**。 */
private const val kMediaCacheMaxBytes = 5L * 1024 * 1024 * 1024

private const val kLibrarySnapshotFileName = "library-snapshot.json"

class PulsarApplication : Application(), AuthorizationSink {
    /** 当前登录会话的 Jellyfin Authorization 头（仅内存，不持久化）。 */
    @Volatile
    override var activeAuthorization: String? = null

    /**
     * 播放上报专用的进程级作用域。播放服务销毁时最后一条「停止」上报还得发出去，
     * 所以不能用服务的 serviceScope（会被 cancel）；放这里也避免每次建服务漏一个作用域。
     */
    val playbackReportScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

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

    /** 登录页的服务器地址历史（最近 5 条，省得重新手敲）。 */
    fun serverUrlHistory(): ServerUrlHistory = DataStoreServerUrlHistory(pulsarPreferencesDataStore)

    fun jellyfinApiFactory(): JellyfinApiFactory = object : JellyfinApiFactory {
        override suspend fun create(serverUrl: String): JellyfinApi = jellyfinDataSource(serverUrl)
    }

    fun qqDownloadSettings(): QQDownloadSettings = QQDownloadPreferences(pulsarPreferencesDataStore)

    /**
     * 手机本地音频副本。**进程级单例**：`SimpleCache` 对同一个目录只允许有一个实例
     * （第二个会被锁文件挡下来），而它同时被播放服务（写）和界面（查完整副本 / 报占用）
     * 用到，所以只能建在这里。也正因为共用，服务销毁时**不能**释放它——那会让界面
     * 随后的查询全变成异常。它的生命周期就是 App 的进程。
     */
    val mediaCacheStore: Cache by lazy {
        SimpleCache(
            File(filesDir, kMediaCacheDirName),
            LeastRecentlyUsedCacheEvictor(kMediaCacheMaxBytes),
            StandaloneDatabaseProvider(this)
        )
    }

    fun mediaCache(): MediaCache = SimpleCacheMediaCache(mediaCacheStore)

    /** 曲库快照：一份 JSON 落在 App 私有目录，断网时顶上。 */
    fun librarySnapshotRepository(): LibrarySnapshotRepository =
        FileLibrarySnapshotRepository(File(filesDir, kLibrarySnapshotFileName))

    fun qqDownloadApiFactory(): QQDownloadApiFactory = object : QQDownloadApiFactory {
        override fun create(baseUrl: String): QQDownloadApi =
            QQDownloadDataSource(client = qqDownloadClient, baseUrl = baseUrl)
    }

    fun libraryFilesApi(baseUrl: String, fileOpsKey: String): LibraryFilesApi =
        LibraryFilesDataSource(
            client = qqDownloadClient,
            baseUrl = baseUrl,
            fileOpsKey = fileOpsKey
        )

    /** 删歌/改名的 API 工厂：地址与密钥每次现读（用户可能在设置里改过密钥）。 */
    fun libraryFilesFactory(): suspend () -> LibraryFilesApi {
        val settings = qqDownloadSettings()
        return { libraryFilesApi(settings.serviceUrl(), settings.fileOpsKey()) }
    }
}
