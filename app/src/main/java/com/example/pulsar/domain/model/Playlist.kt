package com.example.pulsar.domain.model

/**
 * 服务器上的播放列表（用户建的歌单，如"跑步""通勤"）。
 * trackCount 为 null 表示服务器未提供歌曲数。
 */
data class Playlist(
    val id: String,
    val name: String,
    val trackCount: Int?,
    val imageTag: String?
)
