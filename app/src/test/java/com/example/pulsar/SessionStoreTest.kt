package com.example.pulsar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SessionStoreTest {

    private val session = SavedSession(
        serverUrl = "https://jellyfin.example.com",
        userId = "user-1",
        username = "alice",
        accessToken = "token-abc"
    )

    @Test
    fun `save then load returns the same session`() = runTest {
        val store = SessionStore(tempDataStore())

        store.save(session)

        assertEquals(session, store.load())
    }

    @Test
    fun `load returns null when nothing was saved`() = runTest {
        val store = SessionStore(tempDataStore())

        assertNull(store.load())
    }

    @Test
    fun `clear removes the saved session`() = runTest {
        val store = SessionStore(tempDataStore())
        store.save(session)

        store.clear()

        assertNull(store.load())
    }

    @Test
    fun `toStoreMap round-trips all fields`() {
        assertEquals(session, savedSessionFromStoreMap(session.toStoreMap()))
    }

    @Test
    fun `savedSessionFromStoreMap returns null when a field is missing`() {
        val map = session.toStoreMap() - "session_user_id"

        assertNull(savedSessionFromStoreMap(map))
    }

    @Test
    fun `savedSessionFromStoreMap returns null when values are blank`() {
        val map = session.toStoreMap().mapValues { "  " }

        assertNull(savedSessionFromStoreMap(map))
    }

    private fun tempDataStore(): DataStore<Preferences> {
        val file = File.createTempFile("pulsar-session", ".preferences_pb")
        file.delete()
        return PreferenceDataStoreFactory.create { file }
    }
}
