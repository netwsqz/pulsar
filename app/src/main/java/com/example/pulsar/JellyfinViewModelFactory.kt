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
            // 名单读写与调度器 / 下载 module 共用 App 里同一个串行化实例（#6）。
            downloadLedgerStore = application.downloadLedgerStore(),
            // 「已下载」唯一真相（工单 #4）：进程级单例，账号作用域与快照都挂在它身上。
            downloadLibrary = application.downloadLibrary
        ) as T
    }
}
