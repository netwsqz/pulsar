package com.example.pulsar.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * 播放模式的持久化端口。
 *
 * 会话只依赖这个接口：生产实现写 DataStore，单测用手写 Fake。默认的 [NoOp] 不落盘
 * （读到顺序循环、保存丢弃），让既有测试的构造点不必逐个改。
 */
internal interface PlaybackModeStore {
    suspend fun load(): PlaybackMode

    suspend fun save(mode: PlaybackMode)

    /** 不落盘的兜底实现：测试与未注入场景使用。 */
    object NoOp : PlaybackModeStore {
        override suspend fun load(): PlaybackMode = PlaybackMode.SEQUENCE

        override suspend fun save(mode: PlaybackMode) = Unit
    }
}

/**
 * DataStore 实现：只存枚举的 `name`。
 *
 * 读到未知值（旧版本写坏 / 手工改过）时回退 [PlaybackMode.SEQUENCE]，不抛异常——
 * 播放模式读不出来不该阻断启动。
 */
internal class DataStorePlaybackModeStore(
    private val dataStore: DataStore<Preferences>
) : PlaybackModeStore {

    override suspend fun load(): PlaybackMode {
        val name = dataStore.data.first()[modeKey] ?: return PlaybackMode.SEQUENCE
        return PlaybackMode.entries.firstOrNull { it.name == name } ?: PlaybackMode.SEQUENCE
    }

    override suspend fun save(mode: PlaybackMode) {
        dataStore.edit { preferences -> preferences[modeKey] = mode.name }
    }

    private companion object {
        /** 键名沿用 DataStore 的 snake_case 风格；不要复用其他功能的键。 */
        val modeKey = stringPreferencesKey("playback_mode")
    }
}
