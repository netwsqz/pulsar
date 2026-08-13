package com.example.pulsar

interface SessionRepository {
    suspend fun load(): SavedSession?
    suspend fun save(session: SavedSession)
    suspend fun clear()
}

interface AuthorizationSink {
    var activeAuthorization: String?
}
