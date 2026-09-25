package com.example.pulsar.data.qqdownload

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QQDownloadPreferencesTest {
    @Test
    fun `file ops key defaults to blank and round trips trimmed`() = runTest {
        val preferences = QQDownloadPreferences(tempDataStore())

        assertEquals("", preferences.fileOpsKey())
        preferences.saveFileOpsKey("  test-key  ")

        assertEquals("test-key", preferences.fileOpsKey())
    }

    @Test
    fun `file ops key persists independently of service url and quality`() = runTest {
        val preferences = QQDownloadPreferences(tempDataStore())
        preferences.saveServiceUrl("http://192.168.1.10:8900")
        preferences.saveQuality("320")
        preferences.saveFileOpsKey("secret")

        assertEquals("http://192.168.1.10:8900", preferences.serviceUrl())
        assertEquals("320", preferences.quality())
        assertEquals("secret", preferences.fileOpsKey())
    }

    private fun tempDataStore(): DataStore<Preferences> {
        val file = File.createTempFile("pulsar-qq-download", ".preferences_pb")
        file.delete()
        return PreferenceDataStoreFactory.create { file }
    }
}
