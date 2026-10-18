package com.example.pulsar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ServerUrlHistoryTest {
    @Test
    fun `empty by default`() = runTest {
        assertEquals(emptyList<String>(), history().load())
    }

    @Test
    fun `record puts newest first and deduplicates`() = runTest {
        val store = history()
        store.record("http://a:8096")
        store.record("http://b:8096")
        store.record("http://a:8096")

        assertEquals(listOf("http://a:8096", "http://b:8096"), store.load())
    }

    @Test
    fun `record trims to five entries`() = runTest {
        val store = history()
        (1..6).forEach { store.record("http://host$it:8096") }

        assertEquals(
            listOf("http://host6:8096", "http://host5:8096", "http://host4:8096", "http://host3:8096", "http://host2:8096"),
            store.load()
        )
    }

    @Test
    fun `record ignores blank urls`() = runTest {
        val store = history()
        store.record("   ")

        assertEquals(emptyList<String>(), store.load())
    }

    private fun history(): ServerUrlHistory = DataStoreServerUrlHistory(tempDataStore())

    private fun tempDataStore(): DataStore<Preferences> {
        val file = File.createTempFile("pulsar-server-url-history", ".preferences_pb")
        file.delete()
        return PreferenceDataStoreFactory.create { file }
    }
}
