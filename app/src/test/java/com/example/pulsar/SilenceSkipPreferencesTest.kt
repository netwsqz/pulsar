package com.example.pulsar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SilenceSkipPreferencesTest {
    @Test
    fun `默认开启`() = runTest {
        assertTrue(SilenceSkipPreferences(tempDataStore()).enabled.first())
    }

    @Test
    fun `开关值可以持久化往返`() = runTest {
        val store = SilenceSkipPreferences(tempDataStore())
        store.setEnabled(false)
        assertFalse(store.enabled.first())
        store.setEnabled(true)
        assertTrue(store.enabled.first())
    }

    private fun tempDataStore(): DataStore<Preferences> {
        val file = File.createTempFile("pulsar-silence-skip", ".preferences_pb")
        file.delete()
        return PreferenceDataStoreFactory.create { file }
    }
}
