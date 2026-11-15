package com.example.pulsar

import com.example.pulsar.session.Session

interface SessionRepository {
    suspend fun load(): Session?
    suspend fun save(session: Session)
    suspend fun clear()
}
