package com.example.pulsar.domain.model

/** A playable music item exposed to the rest of the application. */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long?,
    val imageTag: String?
)
