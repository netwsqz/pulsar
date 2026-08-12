package com.example.pulsar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** USB 独占输出的持久化设置。DataStore 是开关和音量的唯一真相源。 */
class UsbExclusivePreferences(private val dataStore: DataStore<Preferences>) {
    val enabled: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[enabledKey] ?: false }
        .distinctUntilChanged()

    /** USB 独占输出数字音量百分比（0..100）。默认 80，与 native 满刻度削顶风险的折中值一致。 */
    val volumePercent: Flow<Int> = dataStore.data
        .map { preferences -> preferences[volumeKey] ?: DEFAULT_VOLUME_PERCENT }
        .distinctUntilChanged()

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { preferences -> preferences[enabledKey] = value }
    }

    /** 越界值在写入前夹紧到 0..100，避免非法值落盘。 */
    suspend fun setVolumePercent(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        dataStore.edit { preferences -> preferences[volumeKey] = clamped }
    }

    companion object {
        const val DEFAULT_VOLUME_PERCENT = 80
        private val enabledKey = booleanPreferencesKey("usb_exclusive_enabled")
        private val volumeKey = intPreferencesKey("usb_exclusive_volume_percent")
    }
}
