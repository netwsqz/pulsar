package com.example.pulsar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 手动配色（工单 13）：用户自选的兜底种子色，存 **ARGB Int 而不是序号**——
 * 预设色表将来增删 / 重排，已选的颜色不会跟着串位。
 * 没选过就是 null（界面显示「默认」），不是某个默认预设。
 */
class ThemePreferences(private val dataStore: DataStore<Preferences>) {
    val preset: Flow<Int?> = dataStore.data
        .map { preferences -> preferences[presetKey] }
        .distinctUntilChanged()

    suspend fun setPreset(argb: Int?) {
        dataStore.edit { preferences ->
            if (argb == null) preferences.remove(presetKey) else preferences[presetKey] = argb
        }
    }

    private companion object {
        val presetKey = intPreferencesKey("theme_preset_argb")
    }
}
