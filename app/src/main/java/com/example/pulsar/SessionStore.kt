package com.example.pulsar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.pulsar.download.AccountScope
import com.example.pulsar.session.Session
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 会话存储。只保存会话凭据（accessToken），不保存密码。
 *
 * **键名与字段名属于升级契约**（#23 / D7）：改 Kotlin 类型不改变这里的键，
 * 老版本落盘的会话在新版本仍能读出来。
 */
private const val KEY_SERVER_URL = "session_server_url"
private const val KEY_USER_ID = "session_user_id"
private const val KEY_USERNAME = "session_username"
private const val KEY_ACCESS_TOKEN = "session_access_token"

private val allKeys = listOf(KEY_SERVER_URL, KEY_USER_ID, KEY_USERNAME, KEY_ACCESS_TOKEN)

/** 把会话编码成存储用的键值对（唯一编码来源，便于往返校验）。 */
fun Session.toStoreMap(): Map<String, String> = mapOf(
    KEY_SERVER_URL to serverUrl,
    KEY_USER_ID to userId,
    KEY_USERNAME to username,
    KEY_ACCESS_TOKEN to accessToken
)

/** 从存储键值对还原会话；缺字段或值为空白时返回 null。 */
fun sessionFromStoreMap(map: Map<String, String>): Session? {
    val serverUrl = map[KEY_SERVER_URL]?.takeIf { it.isNotBlank() } ?: return null
    val userId = map[KEY_USER_ID]?.takeIf { it.isNotBlank() } ?: return null
    val username = map[KEY_USERNAME]?.takeIf { it.isNotBlank() } ?: return null
    val accessToken = map[KEY_ACCESS_TOKEN]?.takeIf { it.isNotBlank() } ?: return null
    return Session(serverUrl, userId, username, accessToken)
}

/** 用 DataStore 保存/读取/清除登录会话。 */
class SessionStore(private val dataStore: DataStore<Preferences>) : SessionRepository {
    override suspend fun load(): Session? {
        val preferences = dataStore.data.first()
        val map = allKeys.associateWith { key -> preferences[stringPreferencesKey(key)].orEmpty() }
        return sessionFromStoreMap(map)
    }

    override fun downloadAccounts() = dataStore.data.map { preferences ->
        val server = preferences[stringPreferencesKey(KEY_SERVER_URL)]
        val userId = preferences[stringPreferencesKey(KEY_USER_ID)]
        if (server.isNullOrBlank() || userId.isNullOrBlank()) null else AccountScope(server, userId)
    }.distinctUntilChanged()

    override suspend fun save(session: Session) {
        dataStore.edit { preferences ->
            session.toStoreMap().forEach { (key, value) ->
                preferences[stringPreferencesKey(key)] = value
            }
        }
    }

    override suspend fun clearExpired() {
        dataStore.edit { preferences ->
            preferences.remove(stringPreferencesKey(KEY_ACCESS_TOKEN))
            preferences.remove(stringPreferencesKey(KEY_USERNAME))
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            allKeys.forEach { key -> preferences.remove(stringPreferencesKey(key)) }
        }
    }
}
