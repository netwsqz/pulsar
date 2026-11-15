package com.example.pulsar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.pulsar.ui.theme.Dimens

/**
 * 列表页的统一外框（#24）：标题 + 离线提示 + 三态 + 内容槽。
 *
 * 收口的是**结构**，不是视觉：标题字号 / 内边距、离线小字、三态渲染都与迁移前逐页的写法一致。
 * 三态由 [LoadState] 判一次、这里画一次，页面不再各写一套 `when`——歌曲页漏掉错误分支那类
 * 问题（#24 Step 0 查实）以后不会再出现。
 *
 * 页面差异留在参数里：
 * - [loading] 是骨架槽：专辑页是网格骨架、播放列表页是行骨架，形状本就不同；
 * - [contentAlignment] 保持改前两页的对齐差异（专辑页居顶、播放列表页居中）；
 * - [actions] 是标题下的页面级操作区，**不参与三态**——歌曲页的「选择手机里的音频」
 *   在曲库为空 / 加载失败时也要能点（本地文件不依赖服务器）；
 * - [content] 是内容槽：网格 / 列表仍由调用方自己摆，本骨架只管外框。
 *
 * 不包含顶部玻璃岛与底部播放条——那些由 `MusicLibraryScreen` 悬浮在页面之上。
 */
@Composable
internal fun LibraryScreenScaffold(
    title: String,
    loadState: LoadState,
    modifier: Modifier = Modifier,
    offlineNotice: String? = null,
    actions: (@Composable () -> Unit)? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    loading: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        // 顶部只让出状态栏；标题行自己给玻璃岛让位（padding top），
        // 列表滚动时内容仍能滚到玻璃岛底下透过去（和底部播放条同款行为）。
        modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Dimens.SpaceMedium),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
    ) {
        // 标题行：与发现页同款层级；列表页没有「换一批」语义，只放标题。
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.GlassIslandTopSpacing - Dimens.SpaceSmall)
        )
        offlineNotice?.let { notice -> OfflineNoticeText(notice) }
        actions?.invoke()
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = contentAlignment) {
            when (loadState) {
                LoadState.Loading -> loading()
                is LoadState.Error -> ErrorState(loadState.message, onRetry = loadState.onRetry)
                is LoadState.Empty -> EmptyState(loadState.message)
                LoadState.Content -> content()
            }
        }
    }
}
