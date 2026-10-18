package com.example.pulsar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/** 服务器地址历史最多存几条。 */
private const val kServerUrlHistoryLimit = 5

private val serverUrlHistoryKey = stringPreferencesKey("server_url_history")

/**
 * 登录页的服务器地址历史：最近登录成功的地址，最新在前，最多 5 条。
 * 只是省得用户重新手敲地址——凭据（token/密码）照旧只走 SessionStore，这里没有敏感信息。
 */
interface ServerUrlHistory {
    suspend fun load(): List<String>
    suspend fun record(url: String)
}

/** DataStore 实现：换行分隔的有序列表（URL 不含换行，安全）。 */
class DataStoreServerUrlHistory(private val dataStore: DataStore<Preferences>) : ServerUrlHistory {
    override suspend fun load(): List<String> =
        dataStore.data.first()[serverUrlHistoryKey]?.split('\n').orEmpty()

    override suspend fun record(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { preferences ->
            val order = (listOf(trimmed) + preferences[serverUrlHistoryKey]?.split('\n').orEmpty())
                .distinct()
                .take(kServerUrlHistoryLimit)
            preferences[serverUrlHistoryKey] = order.joinToString("\n")
        }
    }
}
