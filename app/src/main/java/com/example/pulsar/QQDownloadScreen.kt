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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.pulsar.data.qqdownload.QQAlbumDto
import com.example.pulsar.data.qqdownload.QQDownloadSettings
import com.example.pulsar.data.qqdownload.QQJobDto
import com.example.pulsar.data.qqdownload.QQSongDto

/**
 * 「QQ 下载」区块：搜索 QQ 音乐 → 选音质下载（单曲/整专辑/专辑选曲）→ 看任务进度。
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
    onRequestRedownload: (QQSongDto) -> Unit,
    onConfirmRedownload: () -> Unit,
    onDismissRedownload: () -> Unit,
    onDownloadAlbum: (QQAlbumDto) -> Unit,
    onSelectAlbumTracks: (QQAlbumDto) -> Unit,
    onToggleAlbumTrack: (String) -> Unit,
    onSelectAllAlbumTracks: () -> Unit,
    onClearAlbumSelection: () -> Unit,
    onConfirmAlbumDownload: () -> Unit,
    onRetryAlbumTracks: () -> Unit,
    onDismissAlbumPicker: () -> Unit,
    onRetryCredential: () -> Unit,
    onRefreshCredential: () -> Unit,
    onRefreshJobs: () -> Unit,
    onFileOpsKeyInput: (String) -> Unit,
    onSaveFileOpsKey: () -> Unit,
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
        // 文件操作密钥：删歌/改名（长按菜单）要用。与下载服务同一台电脑，配在同一处。
        item { FileOpsKeyCard(state, onFileOpsKeyInput, onSaveFileOpsKey) }
        // 曲库信息：排查用——它说某首没有但你明明有，先看它扫的是哪个目录、扫到多少首。
        // 老服务端没有曲库索引（root 为空且就绪）时不显示这行。
        // 绑 serviceUrl 而不是 serviceReachable：服务不可达时它同样是 false，会把
        // 「连不上服务」和「这是台没索引的老服务端」两种完全不同的病因混成同一句话。
        if (state.serviceUrl.isNotBlank() && (!state.libraryReady || state.libraryRoot.isNotBlank())) {
            item {
                Text(
                    if (state.libraryReady) "曲库：${state.libraryRoot} · ${state.libraryCount} 首"
                    else "曲库：扫描中…（服务刚启动要先扫一遍曲库，约十几秒）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                QQSongRow(
                    song = song,
                    downloading = song.songMid in state.activeDownloadingMids,
                    inLibrary = song.songMid in state.inLibraryMids,
                    onDownload = { onDownloadSong(song) },
                    onRequestRedownload = { onRequestRedownload(song) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
        if (state.albums.isNotEmpty()) {
            item { QQSectionHeader("专辑") }
            items(state.albums, key = { "album-${it.albumMid}" }) { album ->
                QQAlbumRow(
                    album = album,
                    isEnqueuing = state.isEnqueuingAlbum && state.enqueuingAlbumMid == album.albumMid,
                    onSelectTracks = { onSelectAlbumTracks(album) },
                    onDownloadAll = { onDownloadAlbum(album) }
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
    // 选曲弹窗：AlertDialog 自带独立窗口，不占列表布局，直接跟在 LazyColumn 后面即可。
    state.albumPicker?.let { picker ->
        AlbumPickerDialog(
            picker = picker,
            // 按专辑收窄（同专辑行的判法）：别的专辑正在入队时，不能把本弹窗一起冻住。
            isEnqueuing = state.isEnqueuingAlbum && state.enqueuingAlbumMid == picker.albumMid,
            // 凭证过期有专属卡片（同主列表的横幅），避免和 errorMessage 重复说同一件事。
            errorMessage = state.errorMessage?.takeIf { !state.credentialExpired },
            credentialExpired = state.credentialExpired,
            isRetryingCredential = state.isRetryingCredential,
            onRetryCredential = onRetryCredential,
            onToggleTrack = onToggleAlbumTrack,
            onSelectAll = onSelectAllAlbumTracks,
            onClearSelection = onClearAlbumSelection,
            onConfirm = onConfirmAlbumDownload,
            onRetryLoad = onRetryAlbumTracks,
            onDismiss = onDismissAlbumPicker,
            inLibraryMids = state.inLibraryMids,
            downloadingMids = state.activeDownloadingMids
        )
    }
    // 重下确认：已在库的曲目点了下载图标 → 问一句「仍要重新下载？」，确认才带 force 重发。
    state.redownloadPrompt?.let { song ->
        AlertDialog(
            onDismissRequest = onDismissRedownload,
            title = { Text("重新下载？") },
            text = {
                Text("《${song.title}》已在曲库中，仍要重新下载一份吗？")
            },
            confirmButton = {
                TextButton(onClick = onConfirmRedownload) { Text("重新下载") }
            },
            dismissButton = {
                TextButton(onClick = onDismissRedownload) { Text("取消") }
            }
        )
    }
}

/** 文件操作密钥卡：删除/改名接口的锁（工单 06）。密码样式输入 + 显示切换，
 * 点「保存」才落盘。填错不在这里报——删除/改名时会收到 401 的专门提示。 */
@Composable
private fun FileOpsKeyCard(
    state: QQDownloadUiState,
    onFileOpsKeyInput: (String) -> Unit,
    onSaveFileOpsKey: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("文件操作密钥", style = MaterialTheme.typography.titleMedium)
        Text(
            "长按歌曲删除/改名时要用。密钥在电脑上服务启动日志里，或 E:\\nsmic\\server\\.file_ops_key 文件里。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.fileOpsKeyInput,
                onValueChange = onFileOpsKeyInput,
                modifier = Modifier.weight(1f),
                label = { Text("密钥") },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (visible) "隐藏密钥" else "显示密钥"
                        )
                    }
                }
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onSaveFileOpsKey,
                enabled = state.fileOpsKeyInput.isNotBlank() && state.fileOpsKeyInput.trim() != state.fileOpsKey
            ) { Text(if (state.fileOpsKeyInput.trim() == state.fileOpsKey && state.fileOpsKey.isNotBlank()) "已保存" else "保存") }
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

/**
 * 单曲行：标题/歌手/专辑 + 下载按钮。
 *
 * 三态：普通 → 可下；已在库 → 图标置灰但仍可点（点了弹重下确认），带「已在库」灰标签；
 * 下载中 → 图标禁用，带「下载中」标签。歌名与歌手永远保持正常颜色——整行调暗会让人找不着歌。
 */
@Composable
private fun QQSongRow(
    song: QQSongDto,
    downloading: Boolean,
    inLibrary: Boolean,
    onDownload: () -> Unit,
    onRequestRedownload: () -> Unit
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    qualityLabel(song.availableQualities),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                val tag = when {
                    downloading -> "下载中" to MaterialTheme.colorScheme.tertiary
                    inLibrary -> "已在库" to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> null
                }
                if (tag != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        tag.first,
                        style = MaterialTheme.typography.labelSmall,
                        color = tag.second
                    )
                }
            }
        }
        when {
            downloading -> IconButton(onClick = {}, enabled = false) {
                Icon(Icons.Filled.Download, contentDescription = "下载中 ${song.title}")
            }
            inLibrary -> {
                // 视觉上禁用（灰），但仍可点：点了弹「仍要重新下载？」确认，不让人被默默拦住。
                IconButton(onClick = onRequestRedownload) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "已在库，重新下载 ${song.title}",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }
            else -> IconButton(onClick = onDownload) {
                Icon(Icons.Filled.Download, contentDescription = "下载 ${song.title}")
            }
        }
    }
}

/** 专辑行：专辑名/歌手/曲目数 + 选曲 / 整张下载两个图标按钮。 */
@Composable
private fun QQAlbumRow(
    album: QQAlbumDto,
    isEnqueuing: Boolean,
    onSelectTracks: () -> Unit,
    onDownloadAll: () -> Unit
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
            // 两个图标按钮（同歌曲行的下载钮风格），比一行文字窄，窄屏也放得下。
            IconButton(onClick = onSelectTracks) {
                Icon(Icons.Filled.Checklist, contentDescription = "选择《${album.albumName}》的曲目")
            }
            IconButton(onClick = onDownloadAll) {
                Icon(Icons.Filled.Download, contentDescription = "下载整张《${album.albumName}》")
            }
        }
    }
}

/**
 * 专辑选曲弹窗：勾选要下载的曲目。
 *
 * 默认全不选（整张下载有专辑行上的按钮），底部按钮实时显示已选首数。
 * 顶部汇总「共 N 首 · 已有 M 首」；已在库的曲目能勾但默认不勾（勾选本身就是强制），
 * 下载中的不可勾。加载中 / 拉不到曲目 / 入队失败都在弹窗内反馈，不让用户对着一片空白猜。
 *
 * **凭证过期的「重试提取」必须放在弹窗里**：主列表那条横幅被模态弹窗挡着点不到，
 * 只提示「去点重试提取凭证」等于把用户堵死（只能取消，勾选全丢）。
 */
@Composable
private fun AlbumPickerDialog(
    picker: AlbumPickerState,
    isEnqueuing: Boolean,
    errorMessage: String?,
    credentialExpired: Boolean,
    isRetryingCredential: Boolean,
    onRetryCredential: () -> Unit,
    onToggleTrack: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onConfirm: () -> Unit,
    onRetryLoad: () -> Unit,
    onDismiss: () -> Unit,
    inLibraryMids: Set<String>,
    downloadingMids: Set<String>
) {
    AlertDialog(
        // 入队中不许关：正在提交选择，关掉会让人以为没提交
        onDismissRequest = { if (!isEnqueuing) onDismiss() },
        title = {
            Text(
                picker.albumName.ifBlank { "选择曲目" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (credentialExpired) {
                    CredentialExpiredCard(isRetryingCredential, onRetryCredential)
                }
                when {
                    picker.isLoading -> Box(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                    picker.tracks.isEmpty() -> Column {
                        // 加载失败时上面已有报错，这里不重复解释；真的空列表才需要说明白
                        //（否则用户会对着「重新获取曲目」一直点——旧版服务端就是这种情况）。
                        if (errorMessage == null && !credentialExpired) {
                            Text(
                                "没有取到曲目：专辑可能是空的；如果刚更新过 App，重启电脑上的 start.bat 后再试。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = onRetryLoad) { Text("重新获取曲目") }
                    }
                    else -> {
                        Text(
                            albumPickerSummary(picker.tracks, inLibraryMids),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "已选 ${picker.selectedMids.size}/${picker.tracks.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row {
                                TextButton(
                                    onClick = onSelectAll,
                                    // 名字必须叫「全选未下载」：只勾没下过的，名不副实会让人以为有 bug。
                                    // 没有可勾的（全在库/下载中）或全勾上了时置灰。
                                    enabled = picker.tracks.any {
                                        it.songMid !in picker.selectedMids &&
                                            it.songMid !in inLibraryMids && it.songMid !in downloadingMids
                                    }
                                ) { Text("全选未下载") }
                                TextButton(
                                    onClick = onClearSelection,
                                    enabled = picker.selectedMids.isNotEmpty()
                                ) { Text("全不选") }
                            }
                        }
                        // 高上限 + 内部滚动：整张 50 首也不会把弹窗撑出屏幕
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                            items(picker.tracks) { track ->
                                val downloading = track.songMid in downloadingMids
                                QQTrackPickRow(
                                    name = track.name.ifBlank { track.songMid },
                                    tag = when {
                                        downloading -> "下载中" to MaterialTheme.colorScheme.tertiary
                                        track.songMid in inLibraryMids -> "已在库" to MaterialTheme.colorScheme.onSurfaceVariant
                                        else -> null
                                    },
                                    checked = track.songMid in picker.selectedMids,
                                    enabled = !downloading,
                                    onToggle = { onToggleTrack(track.songMid) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isEnqueuing && picker.selectedMids.isNotEmpty()) {
                if (isEnqueuing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (picker.selectedMids.isEmpty()) "下载选中"
                    else "下载选中（${picker.selectedMids.size} 首）"
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isEnqueuing) { Text("取消") }
        }
    )
}

/** 曲目勾选行：整行可点（勾选框自身不单独接收点击，避免小目标难点中）。下载中的整行禁用。 */
@Composable
private fun QQTrackPickRow(
    name: String,
    tag: Pair<String, Color>?,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                enabled = enabled,
                onValueChange = { onToggle() }
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 曲目名不整体调暗（和搜索行同理）；禁用的下载中行用默认禁用色即可。
            color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
        if (tag != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                tag.first,
                style = MaterialTheme.typography.labelSmall,
                color = tag.second
            )
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
