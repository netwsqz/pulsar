package com.example.pulsar.ui.components

/**
 * 列表页的三态判据（#24）：骨架 / 错误 / 空态 / 内容。
 *
 * 只描述「这一刻该画什么」，不持有数据、不产生副作用。[LibraryScreenScaffold] 按类型渲染，
 * 页面不再各写一套 `when`——漏掉错误分支这类问题在结构上不可能再出现。
 *
 * [Error] 带可选重试：断网 / 请求失败给按钮；视图边界（例如离线且没下载）用 [Empty]，不喂必然失败的按钮。
 */
internal sealed interface LoadState {
    /** 首屏数据还没到。 */
    data object Loading : LoadState

    /** 首屏数据没到且最后一次请求失败；[onRetry] 为 null 时不画重试按钮。 */
    data class Error(val message: String, val onRetry: (() -> Unit)? = null) : LoadState

    /** 没有可画的内容：数据到了但列表为空，或没拉到数据且既不在加载也没有错误
     *  （改前两页都会落到空态），也包括视图边界不该发请求的情况（离线且没下载）。 */
    data class Empty(val message: String) : LoadState

    /** 有内容可画。 */
    data object Content : LoadState
}

/**
 * 把列表页的原始状态映射成 [LoadState]（#24 的纯逻辑接缝，单测见 `LibraryLoadStateTest`）。
 *
 * [items] 为 null = 还没拉到，空列表 = 拉到了确实没有——与曲库缓存的三态语义一致。
 * 分支顺序刻意保持改前专辑页 / 播放列表页 `when` 的顺序：
 * 1. 没数据且加载中 → 骨架（已有数据时后台刷新仍显示内容）；
 * 2. 没数据且离线说明型空态非 null → 说明型空态（播放列表页「需要联网」，优先于错误——
 *    断网时重试必然失败，不给按钮）；
 * 3. 没数据且报错 → 错误 + 重试；
 * 4. 列表为空 → 在线空态；
 * 5. 其余 → 内容。
 *
 * [emptyMessage] 与 [offlineUnavailableMessage] 的字面量由调用方从现有文案处取，本函数不新造字。
 */
internal fun <T> libraryLoadState(
    items: List<T>?,
    isLoading: Boolean,
    errorMessage: String?,
    emptyMessage: String,
    offlineUnavailableMessage: String? = null,
    onRetry: (() -> Unit)? = null
): LoadState = when {
    items == null && isLoading -> LoadState.Loading
    items == null && offlineUnavailableMessage != null -> LoadState.Empty(offlineUnavailableMessage)
    items == null && errorMessage != null -> LoadState.Error(errorMessage, onRetry)
    items.orEmpty().isEmpty() -> LoadState.Empty(emptyMessage)
    else -> LoadState.Content
}
