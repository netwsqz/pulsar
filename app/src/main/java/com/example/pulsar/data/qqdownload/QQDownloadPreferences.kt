package com.example.pulsar.data.qqdownload

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

private val serviceUrlKey = stringPreferencesKey("qq_download_service_url")
private val qualityKey = stringPreferencesKey("qq_download_quality")
private val fileOpsKey = stringPreferencesKey("library_file_ops_key")

/**
 * QQ 下载服务地址与默认音质的存取接口（测试注入 Fake 用）。
 * 只存局域网服务地址（非敏感），凭证完全在 PC 侧，App 不接触 cookie。
 */
interface QQDownloadSettings {
    suspend fun serviceUrl(): String
    suspend fun quality(): String
    suspend fun fileOpsKey(): String
    suspend fun saveServiceUrl(url: String)
    suspend fun saveQuality(quality: String)
    suspend fun saveFileOpsKey(key: String)

    companion object {
        const val DEFAULT_QUALITY = "flac"
        val QUALITIES = listOf("hires", "flac", "320", "128")
    }
}

/** DataStore 实现。 */
class QQDownloadPreferences(private val dataStore: DataStore<Preferences>) : QQDownloadSettings {
    override suspend fun serviceUrl(): String = dataStore.data.first()[serviceUrlKey].orEmpty()
    override suspend fun quality(): String = dataStore.data.first()[qualityKey] ?: QQDownloadSettings.DEFAULT_QUALITY
    override suspend fun fileOpsKey(): String = dataStore.data.first()[fileOpsKey].orEmpty()

    override suspend fun saveServiceUrl(url: String) {
        dataStore.edit { it[serviceUrlKey] = url.trim() }
    }

    override suspend fun saveQuality(quality: String) {
        dataStore.edit { it[qualityKey] = quality }
    }

    override suspend fun saveFileOpsKey(key: String) {
        dataStore.edit { it[fileOpsKey] = key.trim() }
    }
}
