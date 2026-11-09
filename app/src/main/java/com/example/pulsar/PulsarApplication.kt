package com.example.pulsar

import android.app.Application
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
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
import com.example.pulsar.download.DownloadLibrary
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.io.File

/** 下载目录用 `filesDir` 而**不是** `cacheDir`：后者会被系统在空间紧张时清掉，
 * 也不适合放用户主动要的东西——这里的副本是用户点了「下载」才有的，不能自己消失。
 * 目录名沿用 `media_cache`：升级后老副本原地就是「下载」，一首不丢（铁律 7）。 */
private const val kMediaDownloadsDirName = "media_cache"

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
     * 手机本地音频副本（下载）。**进程级单例**：`SimpleCache` 对同一个目录只允许有一个实例
     * （第二个会被锁文件挡下来），而它同时被播放服务（只读）和界面（查完整副本 / 报占用）
     * 用到，所以只能建在这里。也正因为共用，服务销毁时**不能**释放它——那会让界面
     * 随后的查询全变成异常。它的生命周期就是 App 的进程。
     *
     * 淘汰器是 `NoOpCacheEvictor`：**不自动淘汰任何东西**，没有总量上限（ADR-0007）。
     * 用户的下载只有用户能删。
     */
    val mediaCacheStore: Cache by lazy {
        SimpleCache(
            File(filesDir, kMediaDownloadsDirName),
            NoOpCacheEvictor(),
            StandaloneDatabaseProvider(this)
        )
    }

    /** 下载库。上游（网络）源复用播放那条鉴权工厂：token 只进 header，绝不进 URL。 */
    fun mediaDownloads(): MediaDownloads = SimpleCacheMediaDownloads(
        cache = mediaCacheStore,
        upstreamFactory = AuthHttpDataSourceFactory { activeAuthorization },
    )

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

    /**
     * 下载名单（`DownloadLedger`）的持久化。**进程级单例**：调度器、下载 module 与
     * ViewModel 必须共用同一个 [SerializedDownloadLedgerStore]，否则等于各拿各的锁，
     * 并发的「读 → 改 → 写」仍会互相覆盖（#6）。
     */
    private val downloadLedgerStoreInstance: DownloadLedgerStore by lazy {
        SerializedDownloadLedgerStore(DataStoreDownloadLedgerStore(pulsarPreferencesDataStore))
    }

    fun downloadLedgerStore(): DownloadLedgerStore = downloadLedgerStoreInstance

    /**
     * 下载任务（调度器 + 唯一真相 module）共用的进程级作用域。
     * 不混用 [playbackReportScope]：下载任务时长以分钟计，分开更好排查。
     */
    private val downloadScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /**
     * 下载调度器，**进程级单例**：串行队列与进度状态都挂在它身上，界面重建不能重来一遍。
     */
    val downloadScheduler: DownloadScheduler by lazy {
        DownloadScheduler(
            scope = downloadScope,
            downloads = mediaDownloads(),
            ledger = downloadLedgerStore(),
        )
    }

    /**
     * 「已下载」唯一真相（工单 #4）：字节完整 ∩ 当前账号名单。
     * **进程级单例**——View 重建不能重来一份，账号作用域与快照都挂在它身上。
     */
    val downloadLibrary: DownloadLibrary by lazy {
        DownloadLibrary(
            downloads = mediaDownloads(),
            ledger = downloadLedgerStore(),
            scheduler = downloadScheduler,
            scope = downloadScope,
        )
    }
}
