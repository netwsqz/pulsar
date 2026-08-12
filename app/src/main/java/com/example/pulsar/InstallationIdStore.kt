package com.example.pulsar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

private const val PULSAR_PREFERENCES_NAME = "pulsar_preferences"
private val installationIdKey = stringPreferencesKey("installation_id")

val android.content.Context.pulsarPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = PULSAR_PREFERENCES_NAME
)

class InstallationIdStore(private val dataStore: DataStore<Preferences>) {
    suspend fun getOrCreate(): String {
        val existingId = dataStore.data.first()[installationIdKey]
        if (!existingId.isNullOrBlank()) return existingId

        return requireNotNull(
            dataStore.edit { preferences ->
                val installationId = preferences[installationIdKey]
                    ?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString()
                preferences[installationIdKey] = installationId
            }[installationIdKey]
        )
    }
}
