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
            mediaCache = application.mediaCache(),
            snapshotRepository = application.librarySnapshotRepository(),
            serverUrlHistory = application.serverUrlHistory()
        ) as T
    }
}
