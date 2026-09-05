package com.example.pulsar

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.pulsar.data.qqdownload.QQAlbumDto
import com.example.pulsar.data.qqdownload.QQDownloadSettings
import com.example.pulsar.data.qqdownload.QQJobDto
import com.example.pulsar.data.qqdownload.QQSongDto

/**
 * 「QQ 下载」区块：搜索 QQ 音乐 → 选音质下载（单曲/整专辑）→ 看任务进度。
 *
 * 未连接服务时显示服务地址设置卡；连上后显示搜索 + 任务列表。
 * 凭证过期时顶部横幅提示，用户在电脑上打开 QQ 音乐客户端后点重试。
 */
@Composable
internal fun QQDownloadScreen(
    state: QQDownloadUiState,
    onServiceUrlInput: (String) -> Unit,
    onConnectService: () -> Unit,
    onQualityChange: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onDownloadSong: (QQSongDto) -> Unit,
    onDownloadAlbum: (QQAlbumDto) -> Unit,
    onRetryCredential: () -> Unit,
    onRefreshCredential: () -> Unit,
    onRefreshJobs: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp)
            .padding(top = GlassIslandTopSpacing),
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.credentialExpired) {
            item { CredentialExpiredCard(state.isRetryingCredential, onRetryCredential) }
        }
        // 错误反馈：ViewModel 几乎所有失败路径都写 errorMessage，这里统一展示
        //（凭证过期已有专属卡片，跳过避免重复）。
        val errorText = state.errorMessage?.takeIf { !state.credentialExpired }
        if (errorText != null) {
            item {
                Text(
                    errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (!state.serviceReachable || state.serviceUrl.isBlank()) {
            item {
                ServiceSetupCard(
                    state = state,
                    onServiceUrlInput = onServiceUrlInput,
                    onConnectService = onConnectService
                )
            }
        }
        item {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索 QQ 音乐歌曲、专辑") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "搜索图标") },
                trailingIcon = if (state.searchQuery.isNotEmpty()) {
                    {
                        IconButton(
                            onClick = { onSearchQueryChange("") },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "清除搜索内容")
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                enabled = state.serviceReachable
            )
        }
        // 默认下载音质：下载时优先用这档，音源没有则自动降级。
        item {
            Row(
                // 标签 + 4 个 chip 在窄屏放不下，整行可横向滚动（同顶部玻璃岛 chip 行的做法）。
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "音质",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                listOf(
                    "hires" to "Hi-Res",
                    "flac" to "无损",
                    "320" to "320K",
                    "128" to "128K"
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = state.quality == value,
                        onClick = { onQualityChange(value) },
                        label = { Text(label, maxLines = 1) }
                    )
                }
            }
        }
        // 手动一键刷新凭证：只在已连接且未过期时显示（过期时顶部横幅已有重试按钮）。
        if (state.serviceReachable && !state.credentialExpired) {
            item { CredentialRefreshRow(state, onRefreshCredential) }
        }
        if (state.isSearching) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        if (state.songs.isNotEmpty()) {
            item { QQSectionHeader("歌曲") }
            items(state.songs, key = { song -> "song-${song.songMid}-${song.title}" }) { song ->
                QQSongRow(song = song, onDownload = { onDownloadSong(song) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
        if (state.albums.isNotEmpty()) {
            item { QQSectionHeader("专辑") }
            items(state.albums, key = { "album-${it.albumMid}" }) { album ->
                QQAlbumRow(
                    album = album,
                    isEnqueuing = state.isEnqueuingAlbum && state.enqueuingAlbumMid == album.albumMid,
                    onDownload = { onDownloadAlbum(album) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
        if (state.searchQuery.isNotBlank() && !state.isSearching &&
            state.songs.isEmpty() && state.albums.isEmpty()
        ) {
            item {
                Text(
                    "没有搜到相关内容（也可能是凭证或网络问题）",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        if (state.jobs.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QQSectionHeader("下载任务")
                    TextButton(onClick = onRefreshJobs) { Text("刷新") }
                }
            }
            items(state.jobs, key = { it.jobId }) { job ->
                QQJobRow(job)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

/** 服务地址设置卡：未连接时显示。 */
@Composable
private fun ServiceSetupCard(
    state: QQDownloadUiState,
    onServiceUrlInput: (String) -> Unit,
    onConnectService: () -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("连接 QQ 下载服务", style = MaterialTheme.typography.titleMedium)
        Text(
            "先在电脑上双击 E:\\nsmic\\server\\start.bat 启动服务，再填入电脑的局域网地址。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = state.serviceUrlInput.ifBlank { state.serviceUrl },
            onValueChange = onServiceUrlInput,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("http://192.168.1.10:8900") },
            singleLine = true
        )
        Button(onClick = onConnectService, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.serviceReachable) "已连接 · 重新连接" else "连接")
        }
    }
}

/** 凭证过期横幅。 */
@Composable
private fun CredentialExpiredCard(
    isRetrying: Boolean,
    onRetryCredential: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "QQ 音乐凭证已过期",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            "在电脑上打开 QQ 音乐客户端并登录，然后点下方按钮重新提取。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = onRetryCredential, enabled = !isRetrying) {
            if (isRetrying) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("重试提取凭证")
        }
    }
}

/** 手动刷新凭证行：连接成功后随时可点；成功反馈显示在按钮旁。 */
@Composable
private fun CredentialRefreshRow(
    state: QQDownloadUiState,
    onRefreshCredential: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(onClick = onRefreshCredential, enabled = !state.isRetryingCredential) {
            if (state.isRetryingCredential) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text("刷新凭证")
        }
        if (state.credentialMessage != null) {
            Text(
                state.credentialMessage,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun QQSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/** 单曲行：标题/歌手/专辑 + 下载按钮。 */
@Composable
private fun QQSongRow(
    song: QQSongDto,
    onDownload: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${song.singer} · ${song.album}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                qualityLabel(song.availableQualities),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = onDownload) {
            Icon(Icons.Filled.Download, contentDescription = "下载 ${song.title}")
        }
    }
}

/** 专辑行：专辑名/歌手/曲目数 + 整张下载。 */
@Composable
private fun QQAlbumRow(
    album: QQAlbumDto,
    isEnqueuing: Boolean,
    onDownload: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(album.albumName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(album.singer)
                    append(" · ${album.songCount} 首")
                    if (album.publicTime.isNotBlank()) append(" · ${album.publicTime}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isEnqueuing) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onDownload) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("整张下载")
            }
        }
    }
}

/** 任务行：状态 + 曲名/错误。 */
@Composable
private fun QQJobRow(job: QQJobDto) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (job.status) {
                "queued", "running" -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                "done" -> Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                else -> Text("×", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                job.title ?: job.songMid,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = when (job.status) {
                "queued" -> "排队中 · ${qualityLabel(listOf(job.quality))}"
                "running" -> "下载中 · ${qualityLabel(listOf(job.quality))}"
                "done" -> "完成 · 已入库"
                else -> job.error ?: "失败"
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (job.status == "failed") {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 可选音质标签文案。 */
private fun qualityLabel(qualities: List<String>): String = when {
    qualities.isEmpty() -> "无可用音质"
    "hires" in qualities -> "Hi-Res 无损"
    "flac" in qualities -> "无损 FLAC"
    "320" in qualities -> "320K"
    else -> "128K"
}
