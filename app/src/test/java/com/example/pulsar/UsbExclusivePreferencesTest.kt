package com.example.pulsar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UsbExclusivePreferencesTest {
    @Test
    fun `默认关闭`() = runTest {
        assertFalse(UsbExclusivePreferences(tempDataStore()).enabled.first())
    }

    @Test
    fun `开关值可以持久化往返`() = runTest {
        val store = UsbExclusivePreferences(tempDataStore())
        store.setEnabled(true)
        assertTrue(store.enabled.first())
        store.setEnabled(false)
        assertFalse(store.enabled.first())
    }

    @Test
    fun `音量默认值为 80`() = runTest {
        assertEquals(80, UsbExclusivePreferences(tempDataStore()).volumePercent.first())
    }

    @Test
    fun `音量值可以持久化往返`() = runTest {
        val store = UsbExclusivePreferences(tempDataStore())
        store.setVolumePercent(45)
        assertEquals(45, store.volumePercent.first())
        store.setVolumePercent(100)
        assertEquals(100, store.volumePercent.first())
    }

    @Test
    fun `超出上限的音量值写入前被夹紧到 100`() = runTest {
        val store = UsbExclusivePreferences(tempDataStore())
        store.setVolumePercent(150)
        assertEquals(100, store.volumePercent.first())
    }

    @Test
    fun `负数音量值写入前被夹紧到 0`() = runTest {
        val store = UsbExclusivePreferences(tempDataStore())
        store.setVolumePercent(-20)
        assertEquals(0, store.volumePercent.first())
    }

    private fun tempDataStore(): DataStore<Preferences> {
        val file = File.createTempFile("pulsar-usb", ".preferences_pb")
        file.delete()
        return PreferenceDataStoreFactory.create { file }
    }
}
