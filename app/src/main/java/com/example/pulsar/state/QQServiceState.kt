package com.example.pulsar.state

/**
 * QQ 导入服务的 slice（`docs/qq-download-state-refactor.md` / #31）：
 * **这台电脑上的那个导入服务本身的状态**——地址、连通性、凭证、曲库索引。
 *
 * 按所有权从 [com.example.pulsar.QQDownloadUiState] 拆出来；拆法与曲库那条线
 * （ADR-0010）一致：组合后仍只对外发一条 `StateFlow<QQDownloadUiState>`。
 *
 * 与 [QQSettingsState] 的分界：这里是**服务回报的**状态（会自己变），那里是**用户设的**偏好。
 *
 * [cleared] 只用于「换了一台电脑上的导入服务」[com.example.pulsar.QQDownloadUiState.cleared]：
 * 上一条服务的连通/凭证/曲库索引全部作废。注意 [libraryReady] 的默认值是 **true**——
 * 界面靠它判断「老服务端没有索引」而不是「正在扫描」，重置成 false 会误报扫描中。
 */
data class QQServiceState(
    /** 服务地址（同主机 + 8900，用户不感知）。它是服务身份，换服务时由调用方写新值。 */
    val serviceUrl: String = "",
    /** 服务可达（健康接口成功过）。false 时搜索页小字与设置页服务卡都会提示「连不上」。 */
    val serviceReachable: Boolean = false,
    /** QQ 音乐凭证过期（PC 侧）。为 true 时亮过期横幅，重试提取凭证靠它出现。 */
    val credentialExpired: Boolean = false,
    val isRetryingCredential: Boolean = false,
    /** 最近一次手动刷新凭证的反馈（如「凭证已刷新」），下一次刷新开始时清除。 */
    val credentialMessage: String? = null,
    /** 曲库索引就绪（false = 服务刚启动还在扫，冷扫约 17 秒）。 */
    val libraryReady: Boolean = true,
    val libraryCount: Int = 0,
    /** 索引扫的目录（排查用：它说没有但你明明有，先看它扫的是哪儿）。 */
    val libraryRoot: String = "",
) {
    /**
     * 换服务时整块作废。直接返回默认实例：约定「字段默认值 = 已清」，
     * 新增字段自动跟上（`QQDownloadStateClearedTest` 用反射钉死）。
     * 服务地址由调用方随后写新值，所以这里**不**保留旧地址。
     */
    fun cleared(): QQServiceState = QQServiceState()
}
