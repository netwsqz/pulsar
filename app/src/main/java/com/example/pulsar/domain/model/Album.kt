package com.example.pulsar.domain.model

/**
 * 专辑信息。trackCount 为 null 表示服务器未提供歌曲数。
 */
data class Album(
    val id: String,
    val name: String,
    val artist: String,
    val trackCount: Int?,
    val imageTag: String?
)
