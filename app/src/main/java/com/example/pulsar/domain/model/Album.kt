package com.example.pulsar.domain.model

import kotlinx.serialization.Serializable

/** 服务端没给艺术家时的稳定占位（DTO 转换与艺术家分组共用，避免两边各写一份字面量）。 */
internal const val UNKNOWN_ARTIST = "Unknown artist"

/**
 * 专辑信息。trackCount 为 null 表示服务器未提供歌曲数。
 * genres 为空 = 服务器没给流派；艺术家/流派页靠它做客户端分组（工单 06）。
 */
@Serializable
data class Album(
    val id: String,
    val name: String,
    val artist: String,
    val trackCount: Int?,
    val imageTag: String?,
    val genres: List<String> = emptyList()
)
