package com.example.pulsar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ThemePreferencesTest {
    @Test
    fun `没选过时是 null`() = runTest {
        assertNull(ThemePreferences(tempDataStore()).preset.first())
    }

    @Test
    fun `写入后能读回同一个颜色值`() = runTest {
        val store = ThemePreferences(tempDataStore())
        store.setPreset(0xFF2563EB.toInt())
        assertEquals(0xFF2563EB.toInt(), store.preset.first())
    }

    @Test
    fun `清除后回到 null`() = runTest {
        val store = ThemePreferences(tempDataStore())
        store.setPreset(0xFFDC2626.toInt())
        store.setPreset(null)
        assertNull(store.preset.first())
    }

    private fun tempDataStore(): DataStore<Preferences> {
        val file = File.createTempFile("pulsar-theme", ".preferences_pb")
        file.delete()
        return PreferenceDataStoreFactory.create { file }
    }
}
