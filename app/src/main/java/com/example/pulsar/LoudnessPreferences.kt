package com.example.pulsar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 回放增益（ReplayGain）开关的持久化设置。默认开启，DataStore 是唯一真相源：
 * 界面直接读写它，PlaybackService 订阅它，两边不需要走 MediaSession 自定义命令。
 */
class LoudnessPreferences(private val dataStore: DataStore<Preferences>) {
    val enabled: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[enabledKey] ?: DEFAULT_ENABLED }
        .distinctUntilChanged()

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { preferences -> preferences[enabledKey] = value }
    }

    companion object {
        const val DEFAULT_ENABLED = true
        private val enabledKey = booleanPreferencesKey("loudness_gain_enabled")
    }
}
