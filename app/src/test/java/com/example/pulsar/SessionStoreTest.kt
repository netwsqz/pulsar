package com.example.pulsar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.example.pulsar.session.Session
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SessionStoreTest {

    private val session = Session(
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
        assertEquals(session, sessionFromStoreMap(session.toStoreMap()))
    }

    @Test
    fun `store keys stay stable so upgrades keep the session`() {
        // 升级不丢会话的底线：DataStore 键名与序列化字段名一个都不能改。
        assertEquals(
            mapOf(
                "session_server_url" to "https://jellyfin.example.com",
                "session_user_id" to "user-1",
                "session_username" to "alice",
                "session_access_token" to "token-abc",
            ),
            session.toStoreMap()
        )
    }

    @Test
    fun `sessionFromStoreMap returns null when a field is missing`() {
        val map = session.toStoreMap() - "session_user_id"

        assertNull(sessionFromStoreMap(map))
    }

    @Test
    fun `sessionFromStoreMap returns null when values are blank`() {
        val map = session.toStoreMap().mapValues { "  " }

        assertNull(sessionFromStoreMap(map))
    }

    private fun tempDataStore(): DataStore<Preferences> {
        val file = File.createTempFile("pulsar-session", ".preferences_pb")
        file.delete()
        return PreferenceDataStoreFactory.create { file }
    }
}
