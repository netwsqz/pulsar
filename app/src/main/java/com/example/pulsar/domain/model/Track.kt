package com.example.pulsar.domain.model

import kotlinx.serialization.Serializable

/** A playable music item exposed to the rest of the application. */
@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long?,
    val imageTag: String?,
    /**
     * 回放增益（ReplayGain）：Jellyfin 按 -18 LUFS 基准算出的响度修正量（dB），
     * 负值表示这首歌比基准响、需要往下压。null = 服务端没有这首歌的响度数据。
     */
    val normalizationGainDb: Float? = null,
    /**
     * 文件在电脑上的绝对路径（Jellyfin 的 Path 字段，仅管理员账号能拿到）。
     * 删歌/改名走 PC 伴生服务（ADR-0001），靠它定位文件；null = 拿不到路径，
     * 文件操作菜单对这首歌不可用。
     */
    val filePath: String? = null
)
