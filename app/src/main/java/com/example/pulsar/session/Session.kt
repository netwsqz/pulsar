package com.example.pulsar.session

import com.example.pulsar.SavedSession
import com.example.pulsar.data.jellyfin.JellyfinSession

/**
 * 当前 Jellyfin 会话（工单 #20）：服务地址、用户与令牌的唯一值对象。
 *
 * 令牌只进 `Authorization` 头，绝不进 URL / 日志 / UI。这里覆盖 [toString] 是因为
 * data class 默认会把所有字段（包括令牌）拼进日志；测试用 `SessionManagerTest` 钉死。
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

/** #20 过渡桥：旧存储仍用 [SavedSession]，Step 4 合并模型后删除。 */
internal fun SavedSession.toSession(): Session =
    Session(serverUrl, userId, username, accessToken)

/** #21 过渡桥：旧数据层接口仍收 [JellyfinSession]，Step 4 合并模型后删除。 */
internal fun Session.toJellyfinSession(): JellyfinSession =
    JellyfinSession(userId, accessToken)

/** #20 过渡桥：登录成功后先按旧格式落盘，Step 4 合并模型后删除。 */
internal fun Session.toSavedSession(): SavedSession =
    SavedSession(serverUrl, userId, username, accessToken)
