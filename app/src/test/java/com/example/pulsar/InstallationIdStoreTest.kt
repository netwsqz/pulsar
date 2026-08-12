package com.example.pulsar

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class InstallationIdStoreTest {
    @Test
    fun `replaces a blank stored installation ID with a stable UUID`() = runTest {
        val file = File.createTempFile("pulsar-installation-id", ".preferences_pb")
        file.delete()
        val dataStore = PreferenceDataStoreFactory.create { file }
        val key = stringPreferencesKey("installation_id")
        dataStore.edit { preferences -> preferences[key] = "   " }
        val store = InstallationIdStore(dataStore)

        val installationId = store.getOrCreate()

        assertFalse(installationId.isBlank())
        assertEquals(installationId, store.getOrCreate())
        file.delete()
    }
}
