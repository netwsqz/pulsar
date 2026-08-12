package com.example.pulsar.data.jellyfin

sealed class JellyfinError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidCredentials : JellyfinError("Jellyfin credentials were rejected")
    class Unauthorized : JellyfinError("Jellyfin authorization is no longer valid")
    class Http(val statusCode: Int) : JellyfinError("Jellyfin request failed with HTTP $statusCode")
    class InvalidResponse(cause: Throwable) : JellyfinError("Jellyfin returned an invalid response", cause)
    class Network(cause: Throwable) : JellyfinError("Unable to reach Jellyfin", cause)
}
