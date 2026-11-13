package com.example.pulsar

import com.example.pulsar.state.DownloadsState
import com.example.pulsar.state.LibraryState
import com.example.pulsar.state.SessionState

/**
 * 界面状态的**组合根**（`docs/library-state-refactor.md`，#11–#16）。
 *
 * 内部按**所有权**拆成三个 slice，组合后仍然只对外发一条 `StateFlow<JellyfinUiState>`
 * （ADR-0010）：
 *
 * | slice | 装什么 | 登出 / 过期 |
 * |---|---|---|
 * | [session] | 登录表单、恢复会话、错误通道 | [SessionState.cleared]：登出全清；过期保留 `serverUrl` / `username`。地址历史永远保留 |
 * | [library] | 曲库、发现、详情、搜索、多选、文件操作、离线可见性 | [LibraryState.cleared]：整块清空 |
 * | [downloads] | 下载派生的界面状态 | [DownloadsState.cleared]：只清界面字段——ADR-0007 的「不清」说的是**字节与名单**，不是这里的 UI 字段 |
 *
 * **这里只有这三个字段。** 屏幕直接读写 `state.library.xxx` / `state.session.xxx` /
 * `state.downloads.xxx`；迁移期的 36 个平铺字段与同名 `copy` 已在 #16 删除，
 * 别再加回来——新状态属于哪个 slice，就加到那个 slice 上（`JellyfinUiStateTest` 钉死形状）。
 */
data class JellyfinUiState(
    val session: SessionState = SessionState(),
    val library: LibraryState = LibraryState(),
    val downloads: DownloadsState = DownloadsState(),
) {
    /**
     * 登出 / 会话过期：三个 slice 一起收进各自 slice 的 `cleared()`，不再手抄字段清单。
     *
     * 两条路径共用本方法（只是 [keepLoginForm] 与 [errorMessage] 不同），所以「过期比登出少清
     * 几个字段」那类不一致不会再出现。给 slice 新增字段会自动跟上——约定是「字段默认值 = 已清」。
     *
     * [errorMessage] 落在 [SessionState]：它是登录与曲库共用的错误通道（#11 / #12 口径未变）。
     */
    fun cleared(keepLoginForm: Boolean = false, errorMessage: String? = null): JellyfinUiState = JellyfinUiState(
        session = session.cleared(keepLoginForm).copy(errorMessage = errorMessage),
        library = library.cleared(),
        downloads = downloads.cleared(),
    )
}
