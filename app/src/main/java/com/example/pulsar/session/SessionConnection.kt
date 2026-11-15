package com.example.pulsar.session

import com.example.pulsar.data.jellyfin.JellyfinApi

/**
 * 当前会话与它对应 API 的原子快照（#22）。
 *
 * `current` 与 `api` 是两个 StateFlow，分别读取时可能在换会话的瞬间拼出
 * 「旧令牌 + 新 API」。凡是要同时用两者的内部读方（ViewModel、播放上报）
 * 一律取这个快照，不要自己拼。
 */
data class SessionConnection(
    val api: JellyfinApi,
    val session: Session,
)
