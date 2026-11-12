package com.example.pulsar.state

/**
 * 会话 slice（`docs/library-state-refactor.md` Step 1 / #11）：登录表单、恢复会话与错误通道。
 *
 * 按所有权从 [com.example.pulsar.JellyfinUiState] 拆出来的第一块。**登出 / 会话过期**会清掉
 * 这一块的大部分（[com.example.pulsar.JellyfinUiState] 的旧字段读法仍可用，见兼容层）；
 * [serverUrl] / [username] / [serverUrlHistory] 按现状保留——过期时预填登录表单，退出时保留地址历史。
 *
 * 注意 [isLoading] / [errorMessage] 目前**仍然共用**：登录与曲库加载都写同一个 `isLoading`
 * （#12 改成按在途节计数，但仍是单一通道），登录错误与曲库错误都写同一个 `errorMessage`。
 * 按 D5 拆成类型化错误仍未做（#12 / #13 都没碰它）。
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
) {
    /**
     * 登出 / 会话过期：清登录表单、忙碌位与错误（#13）。
     *
     * - **[serverUrlHistory] 永远保留**：那是本地数据、跟账号无关，清掉登录页的地址下拉就没了。
     * - [keepLoginForm] = true（会话过期）时保留 [serverUrl] / [username]：用户面对的
     *   不是一张空白表单，只需补密码即可重试；登出则一并清空。
     *
     * **实现口径：直接构造默认实例，只挑要保留的字段。** 约定「字段默认值 = 已清」，
     * 所以给本 slice 新增字段会自动跟上，不会漏清（`StateClearedTest` 用反射钉死）。
     * 别改回逐字段 `copy(...)`——那是这次要消灭的东西。
     */
    fun cleared(keepLoginForm: Boolean = false): SessionState = SessionState(
        serverUrl = if (keepLoginForm) serverUrl else "",
        serverUrlHistory = serverUrlHistory,
        username = if (keepLoginForm) username else "",
    )
}
