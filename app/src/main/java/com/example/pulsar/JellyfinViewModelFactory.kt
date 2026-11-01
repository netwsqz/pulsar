package com.example.pulsar

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers

class JellyfinViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(JellyfinViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return JellyfinViewModel(
            sessionStore = (application as PulsarApplication).sessionRepository(),
            apiFactory = application.jellyfinApiFactory(),
            authorizationSink = application,
            dispatcher = Dispatchers.Main.immediate,
            libraryFilesFactory = application.libraryFilesFactory(),
            mediaDownloads = application.mediaDownloads(),
            snapshotRepository = application.librarySnapshotRepository(),
            serverUrlHistory = application.serverUrlHistory(),
            // 存量副本迁移（工单 07）写这里。直接拿 App 的 DataStore 建 store：
            // 不动 PulsarApplication（那文件归别的工单），也不必多一个工厂方法。
            downloadLedgerStore = DataStoreDownloadLedgerStore(application.pulsarPreferencesDataStore)
        ) as T
    }
}
