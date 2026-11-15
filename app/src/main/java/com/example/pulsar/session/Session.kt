package com.example.pulsar.session

/**
 * 当前 Jellyfin 会话（候选 6）：服务地址、用户与令牌的唯一值对象。
 *
 * 存储、数据接口、播放上报、下载上游都用这一个模型（#23 合并了旧存储模型
 * 与旧数据层会话类型）。令牌只进 `Authorization` 头，绝不进 URL / 日志 / UI。
 * 这里覆盖 [toString] 是因为 data class 默认会把所有字段（包括令牌）拼进日志；
 * 测试用 `SessionManagerTest` 钉死。
 */
data class Session(
    val serverUrl: String,
    val userId: String,
    val username: String,
    val accessToken: String,
) {
    override fun toString(): String =
        "Session(serverUrl=$serverUrl, userId=$userId, username=$username, accessToken=***)"
}

/** 登录页预填信息（服务地址 + 用户名）。不含令牌，会话过期后也能放心交给界面。 */
data class LoginHint(
    val serverUrl: String,
    val username: String,
)
