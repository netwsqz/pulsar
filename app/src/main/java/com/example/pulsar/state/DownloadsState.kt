package com.example.pulsar.state

import com.example.pulsar.CachedAlbum

/**
 * 下载 slice（`docs/library-state-refactor.md` Step 1 / #11）：**只装下载派生出来的界面状态**。
 *
 * ⚠️ 名字不叫 `DownloadState`：那个名字已经被 `com.example.pulsar.DownloadState`（调度器的
 * 单任务进度，`DownloadScheduler.kt`）占了。这里的 `DownloadsState` 是 UI 状态 slice。
 *
 * ⚠️ [visibleTrackIds] / [cachedAlbums] 是**过渡字段**，不是新的「已下载」派生：
 * 按 spec 2026-11-09 的改口径，它们最终应当直接来自 `DownloadLibrary.state`
 * （`DownloadSnapshot.visibleTrackIds`），一个真相只允许有一处算法。这一步只搬位置、不换来源，
 * 所以先原样留在这里；接线那张票会把它们换成内嵌 `DownloadSnapshot` 后删除。
 *
 * ⚠️ 登出 / 会话过期**会重置本 slice 的界面字段**（与改前一致：`signOut` 重建整份 state，
 * `expireSession` 显式清掉这几个字段）。ADR-0007 的「下载不清」说的是**字节与名单**，
 * 不是这里的界面状态——别把两条混了。#13 收口清理语义时再统一口径。
 */
data class DownloadsState(
    /**
     * 离线时**能显示也能播**的曲目 id（已下载，ADR-0008）。null = 还不知道（在线时不需要知道）。
     *
     * 口径是**交集**：字节完整 且 在当前账号的下载名单里。半截的不算，听到一半切走的不算。
     * 过渡字段，见类注释。
     */
    val visibleTrackIds: Set<String>? = null,
    /** 已下载列表（设置页进）：按专辑分组的完整副本进度。null = 还没进过这页。过渡字段，见类注释。 */
    val cachedAlbums: List<CachedAlbum>? = null,
    /**
     * 整张专辑的下载正在删（工单 13）。已下载列表的确认框据此显示进度、禁用按钮，
     * 删完落回 false 才关框——删除跑在 `JellyfinViewModel` 里，页面切走也不中断。
     */
    val isDeleting: Boolean = false,
    /**
     * 下载删除完成后的提示（「已删除 N 首」，用户故事 62）。界面弹完调
     * `JellyfinViewModel.dismissDownloadDeleteMessage` 清掉，避免转屏/返回后再弹一次。
     */
    val deleteMessage: String? = null,
)
