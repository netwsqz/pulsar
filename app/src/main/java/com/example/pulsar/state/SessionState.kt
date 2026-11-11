package com.example.pulsar.state

/**
 * 会话 slice（`docs/library-state-refactor.md` Step 1 / #11）：登录表单、恢复会话与错误通道。
 *
 * 按所有权从 [com.example.pulsar.JellyfinUiState] 拆出来的第一块。**登出 / 会话过期**会清掉
 * 这一块的大部分（[com.example.pulsar.JellyfinUiState] 的旧字段读法仍可用，见兼容层）；
 * [serverUrl] / [username] / [serverUrlHistory] 按现状保留——过期时预填登录表单，退出时保留地址历史。
 *
 * 注意 [isLoading] / [errorMessage] 目前**仍然共用**：登录与曲库加载都写同一个 `isLoading`，
 * 登录错误与曲库错误都写同一个 `errorMessage`。按 D5 拆成类型化错误、按 D4 给每节独立加载态
 * 是 #12 / #13 的事，这一步只搬位置，不改语义。
 */
data class SessionState(
    val serverUrl: String = "",
    /** 最近登录成功的服务器地址（最新在前）；登录页输入框获焦时下拉可选。 */
    val serverUrlHistory: List<String> = emptyList(),
    val username: String = "",
    val password: String = "",
    /** 登录/手动加载中（按钮转圈）。曲库加载目前也写它，见类注释。 */
    val isLoading: Boolean = false,
    /**
     * 启动时正在恢复已保存的会话（读本地 token → 拉曲库）。
     *
     * 与 [isLoading] 分开：恢复是应用自己发起的、用户无需交互。为 true 时 UI **不**回到
     * 登录表单，而是直接进主页面、用灰色波浪骨架占位等着——只有恢复结束且曲库的 `tracks`
     * 仍为 null（本地没存过、或恢复失败）才判定"需要登录"。
     */
    val isRestoring: Boolean = false,
    /** 错误通道（登录失败 / 曲库加载失败 / 离线提示共用）。D5 会把它拆成各 slice 的类型化错误。 */
    val errorMessage: String? = null,
)
