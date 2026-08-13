package com.example.pulsar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * 已保存的登录会话，用于启动时自动恢复登录。
 *
 * 只保存会话凭据（accessToken），不保存密码。
 */
data class SavedSession(
    val serverUrl: String,
    val userId: String,
    val username: String,
    val accessToken: String
)

private const val KEY_SERVER_URL = "session_server_url"
private const val KEY_USER_ID = "session_user_id"
private const val KEY_USERNAME = "session_username"
private const val KEY_ACCESS_TOKEN = "session_access_token"

private val allKeys = listOf(KEY_SERVER_URL, KEY_USER_ID, KEY_USERNAME, KEY_ACCESS_TOKEN)

/** 把会话编码成存储用的键值对（唯一编码来源，便于往返校验）。 */
fun SavedSession.toStoreMap(): Map<String, String> = mapOf(
    KEY_SERVER_URL to serverUrl,
    KEY_USER_ID to userId,
    KEY_USERNAME to username,
    KEY_ACCESS_TOKEN to accessToken
)

/** 从存储键值对还原会话；缺字段或值为空白时返回 null。 */
fun savedSessionFromStoreMap(map: Map<String, String>): SavedSession? {
    val serverUrl = map[KEY_SERVER_URL]?.takeIf { it.isNotBlank() } ?: return null
    val userId = map[KEY_USER_ID]?.takeIf { it.isNotBlank() } ?: return null
    val username = map[KEY_USERNAME]?.takeIf { it.isNotBlank() } ?: return null
    val accessToken = map[KEY_ACCESS_TOKEN]?.takeIf { it.isNotBlank() } ?: return null
    return SavedSession(serverUrl, userId, username, accessToken)
}

/** 用 DataStore 保存/读取/清除登录会话。 */
class SessionStore(private val dataStore: DataStore<Preferences>) : SessionRepository {
    override suspend fun load(): SavedSession? {
        val preferences = dataStore.data.first()
        val map = allKeys.associateWith { key -> preferences[stringPreferencesKey(key)].orEmpty() }
        return savedSessionFromStoreMap(map)
    }

    override suspend fun save(session: SavedSession) {
        dataStore.edit { preferences ->
            session.toStoreMap().forEach { (key, value) ->
                preferences[stringPreferencesKey(key)] = value
            }
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            allKeys.forEach { key -> preferences.remove(stringPreferencesKey(key)) }
        }
    }
}
