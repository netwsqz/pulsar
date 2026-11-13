package com.example.pulsar.domain.model

import kotlinx.serialization.Serializable

/**
 * 服务器上的播放列表（用户建的歌单，如"跑步""通勤"）。
 * trackCount 为 null 表示服务器未提供歌曲数。
 *
 * 可序列化只服务导航状态的保存（`LibrarySectionSaver`，转屏后回到同一个详情页）；
 * 曲库快照不存播放列表。
 */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val trackCount: Int?,
    val imageTag: String?
)
