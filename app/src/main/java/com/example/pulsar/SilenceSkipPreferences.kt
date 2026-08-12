package com.example.pulsar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 静音跳曲开关的持久化设置。默认开启，DataStore 是唯一真相源。 */
class SilenceSkipPreferences(private val dataStore: DataStore<Preferences>) {
    val enabled: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[enabledKey] ?: true }
        .distinctUntilChanged()

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { preferences -> preferences[enabledKey] = value }
    }

    companion object {
        private val enabledKey = booleanPreferencesKey("silence_skip_enabled")
    }
}
