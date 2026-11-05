package com.example.pulsar

import android.app.Application
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import com.example.pulsar.ui.components.DeleteProgressNotice
import com.example.pulsar.ui.components.GlassPanel
import com.example.pulsar.ui.components.LiquidGlassSlider
import com.example.pulsar.ui.components.MediaRowLayout
import com.example.pulsar.ui.components.MediaRowText
import com.example.pulsar.ui.components.PlayingBarCount
import com.example.pulsar.ui.components.PlayingBarGap
import com.example.pulsar.ui.components.PlayingBarMaxHeight
import com.example.pulsar.ui.components.PlayingBarWidth
import com.example.pulsar.ui.components.PlayingBarsWidth
import com.example.pulsar.ui.components.SurfaceCard
import com.example.pulsar.ui.components.VolumeFeedback
import com.example.pulsar.ui.components.VolumeFeedbackOverlay
import com.example.pulsar.ui.components.VolumeFeedbackState
import com.example.pulsar.ui.components.coverScrimHeight
import com.example.pulsar.ui.components.glassBarSliderColors
import com.example.pulsar.ui.components.menuTopLeft
import com.example.pulsar.ui.components.pausedPlayingBarProgress
import com.example.pulsar.ui.components.playingBarHeight
import com.example.pulsar.ui.theme.BrandPrimary
import com.example.pulsar.ui.theme.CoverGradient
import com.example.pulsar.ui.theme.CoverTheme
import com.example.pulsar.ui.theme.Dimens
import com.example.pulsar.ui.theme.PulsarTheme
import com.example.pulsar.ui.theme.THEME_TRANSITION_MS
import com.example.pulsar.ui.theme.ThemePresets
import com.example.pulsar.ui.theme.ThemeSeedSource
import com.example.pulsar.ui.theme.deepened
import com.example.pulsar.ui.theme.rememberCoverGradient
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.pulsar.data.jellyfin.JellyfinDataSource
import com.example.pulsar.data.jellyfin.JellyfinError
import com.example.pulsar.data.jellyfin.JellyfinRequest
import com.example.pulsar.data.jellyfin.JellyfinUrlBuilder
import com.example.pulsar.data.qqdownload.QQAlbumDto
import com.example.pulsar.data.qqdownload.QQSongDto
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import com.example.pulsar.playback.ForegroundVolumeKeys
import com.example.pulsar.playback.PlaybackDiagnostics
import com.example.pulsar.playback.PlaybackMode
import com.example.pulsar.playback.SEEK_SETTLE_TIMEOUT_MS
import com.example.pulsar.playback.SilenceSkipMediaCommands
import com.example.pulsar.playback.putLoudnessGain
import com.example.pulsar.playback.SleepTimer
import com.example.pulsar.playback.UsbExclusiveMediaCommands
import java.time.LocalDate
import com.example.pulsar.playback.buildPlaybackOrder
import com.example.pulsar.playback.formatPosition
import com.example.pulsar.playback.playbackModeConfig
import com.example.pulsar.playback.positionReachedSeekTarget
import com.google.common.util.concurrent.ListenableFuture
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null

    /**
     * 前台音量键接管。MIUI 不把音量键派发给远程音量会话（见 [ForegroundVolumeKeys] 与
     * docs/usb-volume-key-interception.md §10），只有 Activity 在前台时自己截下来这一条路，
     * 所以拦截点必须在 Activity 上，而不是 Compose 里（Compose 的按键回调要先拿到焦点，
     * 更晚、也更不可靠）。
     */
    private val volumeKeys = ForegroundVolumeKeys()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // edge-to-edge（工单 14）：内容绘制到系统栏下方，状态栏/导航栏透明。
        // targetSdk 35 在 Android 15+ 上本来就强制，这里显式打开让低版本行为一致。
        // 两个 style 都显式给透明 scrim：默认的导航栏 scrim 会在 API 26–28 上画出一条色带。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        setContent { PulsarApp(controllerFuture = controllerFuture, volumeKeys = volumeKeys) }
    }

    /** 独占开启时吃掉音量键交给 App，其余情况一律放行（[ForegroundVolumeKeys.handle]）。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (volumeKeys.handle(event.keyCode, event.action)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        volumeKeys.snapshot = null
        volumeKeys.sink = null
        controllerFuture?.let { future ->
            if (future.isDone) runCatching { future.get().release() } else future.cancel(false)
        }
        controllerFuture = null
        super.onDestroy()
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PulsarApp(
    controllerFuture: ListenableFuture<MediaController>?,
    volumeKeys: ForegroundVolumeKeys
) {
    val application = LocalContext.current.applicationContext as PulsarApplication
    val viewModelFactory = remember(application) { JellyfinViewModelFactory(application) }
    val viewModel: JellyfinViewModel = viewModel(factory = viewModelFactory)
    val qqViewModel: QQDownloadViewModel = viewModel(
        factory = remember(application) {
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(QQDownloadViewModel::class.java))
                    return QQDownloadViewModel(
                        preferences = application.qqDownloadSettings(),
                        apiFactory = application.qqDownloadApiFactory()
                    ) as T
                }
            }
        }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val qqState by qqViewModel.state.collectAsStateWithLifecycle()
    // 当前播放曲目提到主题之上取：色板要跟着它变，登录页和主页面都得在同一套主题里。
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    // QQ 下载服务与 Jellyfin 同机、端口固定 8900：登录成功后自动填地址，用户不用管。
    LaunchedEffect(state.tracks != null, state.serverUrl) {
        if (state.tracks != null && state.serverUrl.isNotBlank()) {
            qqViewModel.autoFillServiceUrl(state.serverUrl)
        }
    }
    val darkTheme = isSystemInDarkTheme()
    // 手动配色（工单 13）：预设色只是**没有封面时的兜底种子**，有封面色时仍以封面为准。
    val themeContext = LocalContext.current
    val themePreferences = remember { ThemePreferences(themeContext.pulsarPreferencesDataStore) }
    val presetArgb by themePreferences.preset.collectAsStateWithLifecycle(initialValue = null)
    val preset = presetArgb?.let(::Color)
    val coverTheme = rememberCoverGradient(
        coverRequest = currentTrack?.let(viewModel::trackCover),
        darkTheme = darkTheme,
        preset = preset
    )
    // 无封面 / 全灰封面 → 不传种子，沿用改造前的固定靛蓝色板（工单 11 的回退要求）。
    val seed = coverTheme.seed.color.takeIf { coverTheme.seed.source != ThemeSeedSource.Default }
    PulsarTheme(darkTheme = darkTheme, seed = seed) {
        // 启动直接进主页面：恢复会话期间曲库页用灰色波浪骨架占位（页面框架、玻璃岛、
        // 迷你播放条都立刻可用），不另起一屏加载页。只有"确实没有可用会话"——本地没存
        // 过、或恢复失败——才落到登录表单。
        if (!state.isRestoring && state.tracks == null) {
            LoginScreen(state, viewModel)
        } else {
            MusicLibraryScreen(
                state = state,
                viewModel = viewModel,
                controllerFuture = controllerFuture,
                volumeKeys = volumeKeys,
                qqState = qqState,
                qqViewModel = qqViewModel,
                currentTrack = currentTrack,
                coverTheme = coverTheme,
                themePreferences = themePreferences
            )
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    // 撑满宽度 + 居中：空态/错误态在 LazyColumn item 里也要从一侧到另一侧居中，
    // 不能只在自身固有宽度里居中（spec §5）。调用方不用再各自传 fillMaxWidth。
    Column(
        modifier = modifier.fillMaxWidth().padding(Dimens.SpaceMedium),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
    ) {
        Icon(
            imageVector = Icons.Filled.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(Dimens.TouchTargetSmall),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        onRetry?.let { retry ->
            TextButton(onClick = retry) { Text("重新加载") }
        }
    }
}

@Composable
private fun EmptyState(
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Dimens.SpaceMedium),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(Dimens.TouchTargetSmall),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
/** 离线过滤生效时标题下的一行小字（ADR-0008）：解释歌为什么变少了。
 * 字号跟行内歌手副标题一致（bodySmall）——离线提示比副标题还小就看不清（spec §11）。 */
@Composable
private fun OfflineNoticeText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * 艺术家列表页（工单 06）：由已加载的专辑在客户端分组而来（零新 API）。
 * 行语言沿用 Auxio 列表项：封面 + 组名 + 「N 张专辑」副信息。
 * 封面取组内第一张专辑的封面——服务端没给艺术家单独的图。
 */
@Composable
private fun LibraryGroupsContent(
    state: JellyfinUiState,
    albums: List<Album>?,
    title: String,
    offlineNotice: String?,
    onOpenGroup: (String) -> Unit,
    coverFor: (Album) -> JellyfinRequest?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val groups = remember(albums) { libraryGroups(albums.orEmpty()) }
    Column(
        modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Dimens.SpaceMedium),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.GlassIslandTopSpacing - Dimens.SpaceSmall)
        )
        offlineNotice?.let { notice -> OfflineNoticeText(notice) }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                // 骨架要撑满：外层 Box 是居中对齐，wrap-content 的骨架会竖直居中，加载完从顶部开始就跳了。
                albums == null && state.isLoading -> TrackListSkeleton(count = 8, modifier = Modifier.fillMaxSize())
                albums == null && state.errorMessage != null -> ErrorState(state.errorMessage, onRetry = onRetry)
                groups.isEmpty() -> EmptyState(
                    if (offlineNotice != null) "离线 · 手机里还没有下载的专辑" else "暂无艺术家"
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = Dimens.BottomContentInset),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceTiny)
                ) {
                    itemsIndexed(groups, key = { _, g -> g.name }) { index, group ->
                        SearchResultRow(
                            cover = group.albums.firstOrNull()?.let(coverFor),
                            title = group.name,
                            subtitle = "${group.albums.size} 张专辑",
                            onClick = { onOpenGroup(group.name) },
                            showDivider = index != groups.lastIndex,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/**
 * 艺术家组详情（工单 06 / 11）：与专辑/播放列表详情同源——共用 [MediaDetailScaffold]，
 * 只是内容区换成专辑网格（spec §6「艺术家组用网格变体」）。
 *
 * 这一页没有「播放 / 随机」语义（列的是专辑，不是曲目），所以不画操作行（`showActions = false`）；
 * 头部其余部分（大封面、两行标题、副标题、摘要）与另外两页完全一致，摘要「N 张专辑」由
 * [artistGroupSummary] 从网格实际卡片数算出，离线时改「已下载 N 张 · 共 M 张」。
 *
 * 组名按大小写不敏感匹配：显示名取「首次出现的写法」，输入顺序一变精确匹配就会失手。
 * 数据全从已加载的 [albums] 现算，不复制一份进导航状态。
 */
@Composable
private fun LibraryGroupDetailContent(
    state: JellyfinUiState,
    albums: List<Album>?,
    groupName: String,
    offlineNotice: String?,
    onOpenAlbum: (Album) -> Unit,
    coverFor: (Album) -> JellyfinRequest?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 组名来自导航状态，专辑列表可能还在加载/刷新：按组名重新分组，找不到就显示空态。
    val group = remember(albums, groupName) {
        libraryGroups(albums.orEmpty())
            .firstOrNull { it.name.equals(groupName, ignoreCase = true) }
    }
    val groupAlbums = group?.albums.orEmpty()
    // 离线视图（ADR-0008）：只在真正过滤了列表时（offlineNotice 非空）才改摘要口径、隐藏网格里
    // 不该出现的东西——下载名单还没算出来时 albums 还是全量，不能当成「已下载」。
    val isOfflineView = offlineNotice != null
    // 摘要里的「共 M 张」用服务端口径：过滤前那一份（原始 state.albums）里同名的组。
    // 离线时 `albums` 已被 offlineVisibleLibrary 滤过，拿它当总数会写成「已下载 3 张 · 共 3 张」。
    val serverTotal = remember(state.albums, groupName) {
        libraryGroups(state.albums.orEmpty())
            .firstOrNull { it.name.equals(groupName, ignoreCase = true) }
            ?.albums?.size
    }
    MediaDetailScaffold(
        // 组本身没有封面图：借组内第一张专辑的封面上大图（与艺术家列表行的做法一致）。
        cover = groupAlbums.firstOrNull()?.let(coverFor),
        coverDescription = groupName,
        title = groupName,
        subtitle = "艺术家",
        summary = artistGroupSummary(groupAlbums, isOfflineView = isOfflineView, serverTotal = serverTotal),
        // 艺术家组没有「播放 / 随机」语义，下面这几个参数不渲染（showActions = false）。
        playLabel = "",
        playable = false,
        onPlay = {},
        onShuffle = {},
        // 这一页列的是专辑，没有整组下载的概念：不画下载槽。
        download = null,
        offlineNotice = offlineNotice,
        // 这一页列的是专辑，没有「整组播放」语义：不画操作行（头部其余部分照常）。
        showActions = false,
        // 网格变体：内容区是 2 列专辑网格，头部照常跨满整行（真实头部永远画一次）。
        gridColumns = 2,
        contentArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium),
        modifier = modifier,
        gridBody = {
            when {
                // 骨架只画卡片、从顶部开始；换真实内容时卡片从同一位置开始，不跳（spec §5）。
                albums == null && state.isLoading -> fullRow("skeleton") {
                    AlbumGridSkeleton()
                }
                albums == null && state.errorMessage != null -> fullRow("error") {
                    ErrorState(state.errorMessage, onRetry = onRetry)
                }
                groupAlbums.isEmpty() -> fullRow("empty") {
                    EmptyState(
                        if (isOfflineView) "离线 · 这个艺术家还没有下载的专辑" else "这里还没有专辑"
                    )
                }
                else -> items(groupAlbums, key = { it.id }) { album ->
                    AlbumGridCard(album, coverFor(album), onClick = { onOpenAlbum(album) })
                }
            }
        }
    )
}

/** 骨架占位块的共享扫光进度（0f..1f 循环）：一处骨架屏取一次、传给它所有的占位块，
 * 避免每个占位块各起一套 infinite transition；所有块同步扫光，视觉上是一整片微光扫过。
 *
 * 返回 [State] 而不是裸 Float——这是**必须**的：动画值只有在**使用处**读（[SkeletonBox]
 * 的 draw 块里读 `progress.value`）才会让那一处订阅动画帧。若在这里 `by` 解包成裸 Float
 * 返回，读取发生在本函数的 recompose 作用域内，调用方不会因动画帧而重组，拿到的永远是
 * 首次组合时的值（0f）——实测表现就是"骨架有、扫光永远不动"。 */
@Composable
private fun rememberSkeletonShimmer(): State<Float> {
    val transition = rememberInfiniteTransition(label = "skeletonShimmer")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "skeletonShimmerProgress"
    )
}

/** 带微光扫过的骨架占位块：surfaceVariant 底色 + 一条左→右扫过的微光。
 * 所有骨架屏（网格卡片、详情页头部/曲目行）统一用它。shape 传 null 时保持直角。
 *
 * 扫光色随主题反转：浅色主题底色 surfaceVariant(#F1F5F9) 已经接近纯白，白色光带叠上去
 * 差值约 2/255 完全看不出来，必须换成更深的暗色光带；深色主题底色暗，才用白色微光。 */
@Composable
private fun SkeletonBox(
    modifier: Modifier = Modifier,
    progress: State<Float> = rememberSkeletonShimmer(),
    shape: Shape? = null
) {
    // 扫光色跟随色板：浅色下底色接近白，用 onSurface 压暗；深色下用 onSurface 提亮。
    // 不再写死白 / 0xFF0F172A——色板换成封面推导色后，写死的颜色不会跟着走。
    val shimmerColor = MaterialTheme.colorScheme.onSurface
    val shimmerPeakAlpha = if (isSystemInDarkTheme()) 0.15f else 0.09f
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, shape ?: RectangleShape)
            .drawWithCache {
                // 扫光位置从 -0.5 到 1.5：让光带完整进出占位块，两端不出现生硬截断。
                // 在 draw 块里读 progress.value（而不是外面算好传进来）才能让这层 draw
                // 订阅动画帧、逐帧重绘——放在外面读的话扫光会定住不动。
                val x = progress.value * 2f - 0.5f
                val bandWidth = size.width.coerceAtLeast(1f)
                val start = Offset(x * size.width - bandWidth * 0.5f, 0f)
                val end = Offset(start.x + bandWidth, size.height)
                val scrim = Brush.linearGradient(
                    colors = listOf(
                        shimmerColor.copy(alpha = 0f),
                        shimmerColor.copy(alpha = shimmerPeakAlpha),
                        shimmerColor.copy(alpha = 0f)
                    ),
                    start = start,
                    end = end
                )
                onDrawBehind { drawRect(scrim) }
            }
    )
}

/** 专辑/播放列表卡片的 1dp 描边色：取色板 onSurface 的低透明度——
 * 浅色下是比表面深一点的细线，深色下是比表面浅一点的细线，跟着封面换色走。 */
@Composable
private fun cardBorderColor(): Color =
    MaterialTheme.colorScheme.onSurface.copy(alpha = if (isSystemInDarkTheme()) 0.10f else 0.08f)

/** 曲目列表的灰色波浪骨架：与 [TrackRow] 共用 [MediaRowLayout]（48/12/48 槽位 + 8dp 内边距 +
 * 从封面右缘起的分隔线），共用一份扫光进度。会话恢复期间用它填满列表，视觉上是
 * "这个页面正在加载"，而不是一片空白或"暂无内容"的空态文案。
 * [trailingSpace] 为 true 时给行尾「更多」按钮留出同样的 48dp 位——歌曲页 / 发现页 /
 * 播放列表详情的真实行有该按钮，不留位加载完文字列会突然变窄；播放列表列表页（行尾无按钮）
 * 传 false。 */
@Composable
private fun TrackListSkeleton(
    count: Int = 6,
    trailingSpace: Boolean = false,
    /** 行首槽的种类，与真实行同构。专辑详情真实行行首是**曲目序号**（不是封面），
     * 骨架也得画序号槽，否则加载完行首会从假封面跳成数字。 */
    leading: SkeletonLeading = SkeletonLeading.Cover,
    /** 行间距：与真实列表的 `verticalArrangement` 对齐。歌曲 / 播放列表 / 艺术家页都是
     * [Dimens.SpaceTiny]；专辑详情的真实列表没有额外间距，传 `0.dp`。 */
    rowSpacing: Dp = Dimens.SpaceTiny,
    modifier: Modifier = Modifier
) {
    val shimmer = rememberSkeletonShimmer()
    // 8dp / 4dp 是组件自身尺寸（与 AlbumCover 默认圆角、灰条圆角一致），按工单 01 约定不进 Dimens。
    val coverShape = RoundedCornerShape(8.dp)
    val trailingSlot: (@Composable () -> Unit)? = if (trailingSpace) ({ }) else null
    Column(modifier, verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
        repeat(count) { index ->
            MediaRowLayout(
                leading = {
                    when (leading) {
                        // 封面槽：整块 48dp 圆角灰块。
                        SkeletonLeading.Cover -> SkeletonBox(
                            Modifier.size(Dimens.TouchTargetSmall).clip(coverShape),
                            progress = shimmer
                        )
                        // 序号槽：与 TrackRow 的序号列同构——48dp 盒内右对齐、右留 8dp，
                        // 灰条宽度接近「两位数字」的观感，不画成封面方块。
                        SkeletonLeading.Number -> Box(
                            Modifier.size(Dimens.TouchTargetSmall).padding(end = Dimens.SpaceSmall),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            SkeletonBox(
                                Modifier.width(Dimens.SpaceMedium).height(12.dp),
                                progress = shimmer,
                                shape = RoundedCornerShape(4.dp)
                            )
                        }
                    }
                },
                trailing = trailingSlot,
                showDivider = index != count - 1
            ) {
                SkeletonBox(
                    Modifier.fillMaxWidth(0.55f).height(14.dp),
                    progress = shimmer,
                    shape = RoundedCornerShape(4.dp)
                )
                Spacer(Modifier.height(Dimens.SpaceTiny))
                SkeletonBox(
                    Modifier.fillMaxWidth(0.35f).height(11.dp),
                    progress = shimmer,
                    shape = RoundedCornerShape(4.dp)
                )
            }
        }
    }
}

/** 曲目骨架的行首槽种类（工单 10）。专辑详情用 [Number]，其余列表用 [Cover]。 */
private enum class SkeletonLeading { Cover, Number }


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginScreen(state: JellyfinUiState, viewModel: JellyfinViewModel) {
    Column(
        // edge-to-edge 后登录页同样要躲开状态栏 / 导航栏（工单 14）。
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(Dimens.SpaceLarge),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium)
    ) {
            Text("连接 Jellyfin", style = MaterialTheme.typography.headlineSmall)
            // 地址历史下拉：输过的地址点一下回填。没历史时就是个普通输入框。
            var historyExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = historyExpanded && state.serverUrlHistory.isNotEmpty(),
                onExpandedChange = { historyExpanded = it }
            ) {
                OutlinedTextField(
                    state.serverUrl,
                    viewModel::updateServerUrl,
                    Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                    label = { Text("服务器地址") },
                    placeholder = { Text("https://example.com 或 http://192.168.1.2:8096") },
                    singleLine = true
                )
                ExposedDropdownMenu(
                    expanded = historyExpanded && state.serverUrlHistory.isNotEmpty(),
                    onDismissRequest = { historyExpanded = false }
                ) {
                    state.serverUrlHistory.forEach { url ->
                        DropdownMenuItem(
                            text = { Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = {
                                viewModel.updateServerUrl(url)
                                historyExpanded = false
                            }
                        )
                    }
                }
            }
            OutlinedTextField(state.username, viewModel::updateUsername, Modifier.fillMaxWidth(), label = { Text("用户名") }, singleLine = true)
            OutlinedTextField(state.password, viewModel::updatePassword, Modifier.fillMaxWidth(), label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            state.errorMessage?.let { ErrorState(it) }
            Button(
                onClick = viewModel::login,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading
            ) {
                if (state.isLoading) CircularProgressIndicator(strokeWidth = 2.dp) else Text("登录")
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun MusicLibraryScreen(
    state: JellyfinUiState,
    viewModel: JellyfinViewModel,
    controllerFuture: ListenableFuture<MediaController>?,
    volumeKeys: ForegroundVolumeKeys,
    qqState: QQDownloadUiState,
    qqViewModel: QQDownloadViewModel,
    currentTrack: Track?,
    coverTheme: CoverTheme,
    themePreferences: ThemePreferences
) {
    val controllerState = rememberMediaController(controllerFuture, volumeKeys)
    // 播放模式：顺序循环 → 单曲循环 → 随机播放，点按钮循环切换。
    var playbackMode by remember { mutableStateOf(PlaybackMode.SEQUENCE) }
    val localAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        controllerState.controller?.apply { setMediaItem(MediaItem.fromUri(uri)); prepare(); play() }
    }
    // 本地导航：Discover / Tracks / Albums / AlbumDetail，不引入导航库，直接按 state 分支切换内容。
    // 主页默认显示发现页（随机专辑），其余分区通过顶部导航切换。
    var section by remember { mutableStateOf<LibrarySection>(LibrarySection.Discover) }
    // 详情页来源：从列表页还是搜索页进的详情，返回时回到对应来源。
    var detailOrigin by remember { mutableStateOf<LibrarySection>(LibrarySection.Discover) }
    // 统一打开详情入口：打开前记下当前分区，返回键/侧滑能回到原页面（含搜索页）。
    val openAlbum: (Album) -> Unit = { album ->
        detailOrigin = section
        section = LibrarySection.AlbumDetail(album)
    }
    val openPlaylist: (Playlist) -> Unit = { playlist ->
        detailOrigin = section
        section = LibrarySection.PlaylistDetail(playlist)
    }
    // 进入对应分区时懒加载数据；load 方法幂等，重复进入不会重复请求。
    // `sessionReady` 也作为 key：主页面现在在恢复会话期间就已经组合了，首次触发时
    // 还没有 dataSource，loadLibrary 会直接 return（静默无操作）。必须等会话到位再补
    // 跑一次，否则发现页/专辑页会永远停在骨架屏上。
    // `isOffline` 也作为 key：探活回在线时 ViewModel 会清掉快照来源的那几屏
    // （albums/discoverAlbums/albumTracks），靠 key 变化重新触发加载，拉服务器的新值。
    // `historyEpoch` 作 key：切歌后 ViewModel 只递增版本号、不清数据（工单 09），
    // 这里据此静默重拉；旧列表留在屏幕上，不闪骨架。
    val sessionReady = state.tracks != null
    LaunchedEffect(section, sessionReady, state.isOffline, state.historyEpoch) {
        when (val s = section) {
            LibrarySection.Discover -> if (sessionReady) {
                viewModel.loadDiscoverAlbums()
                viewModel.loadRecentlyPlayed()
                viewModel.loadMostPlayed()
                viewModel.loadRecentlyAdded()
            }
            LibrarySection.Tracks -> Unit
            LibrarySection.Albums -> if (sessionReady) viewModel.loadAlbums()
            // 艺术家页由已加载的专辑客户端分组而来（工单 06），零新 API：
            // 只确保专辑列表到位，其余全在 UI 侧现算。
            LibrarySection.Artists -> if (sessionReady) viewModel.loadAlbums()
            is LibrarySection.GroupDetail -> if (sessionReady) viewModel.loadAlbums()
            LibrarySection.Playlists -> if (sessionReady) viewModel.loadPlaylists()
            LibrarySection.Search -> Unit
            // 下载服务页（设置 → 下载）：进来时刷新一次任务列表，和原来挂在顶层 QQ 页时一致。
            LibrarySection.DownloadService -> qqViewModel.refreshJobs()
            LibrarySection.Settings -> Unit
            LibrarySection.CachedCopies -> viewModel.loadCachedAlbums()
            is LibrarySection.AlbumDetail -> if (sessionReady) viewModel.loadAlbumTracks(s.album)
            is LibrarySection.PlaylistDetail -> if (sessionReady) viewModel.loadPlaylistTracks(s.playlist)
        }
    }
    // 离线可见性（ADR-0008）：离线时各屏只显示已下载的东西，不再「全量 + 标灰」。
    // 原始 state 一律不动，这里派生一份渲染用的列表——回在线自动恢复全量，不用重拉。
    // cachedTrackIds 为 null（还没算出来）时不筛，免得把能播的歌藏掉。
    val offlineFilter = state.isOffline && state.cachedTrackIds != null
    val visible = if (offlineFilter) {
        offlineVisibleLibrary(
            tracks = state.tracks,
            albums = state.albums,
            discoverAlbums = state.discoverAlbums,
            albumTracks = state.albumTracks,
            playlistTracks = state.playlistTracks,
            searchResults = state.searchResults,
            visibleTrackIds = state.cachedTrackIds.orEmpty()
        )
    } else {
        VisibleLibrary(
            tracks = state.tracks,
            albums = state.albums,
            discoverAlbums = state.discoverAlbums,
            albumTracks = state.albumTracks,
            playlistTracks = state.playlistTracks,
            searchResults = state.searchResults
        )
    }
    val tracks = visible.tracks.orEmpty()
    // 歌词：随当前曲目自动加载（全屏页歌词页用）。加载时机在全屏页开关声明之后。
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    // 音量浮窗的显示窗口：独占模式下音量键被 App 截获、系统音量条不会弹，靠它给唯一的视觉反馈。
    val volumeFeedback by VolumeFeedback.state.collectAsStateWithLifecycle()
    DisposableEffect(controllerState.controller) {
        val controller = controllerState.controller ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                viewModel.onMediaItemTransition(mediaItem)
            }
        }
        controller.addListener(listener)
        // 后台播放回来重连时，可能已有正在播放的曲目且不会再触发 transition，主动恢复一次。
        viewModel.onMediaItemTransition(controller.currentMediaItem)
        onDispose { controller.removeListener(listener) }
    }
    // 统一播放入口：记录当前曲目后交给 playTracks。未连接播放服务时直接忽略。
    // 离线时 playTracks 内部会跳过没下载的歌（队列里混着能放/不能放时按工单 03 口径处理）。
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    // 动作结果的统一 Snackbar 出口（详情页的下载按钮删除完「已删除 N 首」走它）。
    val showMessage: (String) -> Unit = { message ->
        snackbarScope.launch { snackbarHostState.showSnackbar(message) }
    }
    // 已下载列表的整张删除跑在 ViewModel 里（导航切走也不中断），结果经 state 送回来。
    // **先消费再弹**：留在 state 里的话，转屏重建页面会把同一条提示再弹一次。
    LaunchedEffect(state.downloadDeleteMessage) {
        val message = state.downloadDeleteMessage ?: return@LaunchedEffect
        viewModel.dismissDownloadDeleteMessage()
        // 用页面级的 snackbarScope 弹：本 effect 的 key 刚变成 null 会重启，
        // 放在 effect 里直接 showSnackbar 会被立刻取消掉。
        snackbarScope.launch { snackbarHostState.showSnackbar(message) }
    }
    // QQ 下载动作反馈（音质不可用 / 入队失败 / 整专跳过 N 首）走 Snackbar——那是动作反馈。
    // 搜索期失败（服务不可用 / 凭证过期）不走这里，它在搜索页 QQ 分区里常驻（ADR-0006）。
    LaunchedEffect(qqState.actionMessage) {
        val message = qqState.actionMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        qqViewModel.dismissActionMessage()
    }
    // 离线没下载的歌的统一话术（文案只此一处，经 ViewModel 的 unplayableReason 出口）。
    val onPlayTrack: (List<Track>, String) -> Unit = { trackList, trackId ->
        val controller = controllerState.controller
        if (controller != null) {
            val start = trackList.firstOrNull { it.id == trackId }
            val reason = start?.let(viewModel::unplayableReason)
            if (reason != null) {
                // 先守门再进队列：点了没下载的歌只弹提示，不把它写进「正在播」镜像——
                // 不然迷你条/高亮/歌词都会指向一首根本没在放的歌。
                snackbarScope.launch { snackbarHostState.showSnackbar(reason) }
            } else {
                // 队列里只留能放的：离线播到没下载的歌只会中途断掉。
                val playable = trackList.filter(viewModel::isPlayable)
                viewModel.setPlaybackQueue(playable, trackId)
                playTracks(playable, trackId, controller, playbackMode, viewModel::streamUrl)
            }
        }
    }
    // 离线过滤生效时给各内容页的统一小字（ADR-0008）：歌变少了得有一句解释。
    val offlineNotice = if (offlineFilter) "离线 · 只显示已下载" else null
    val onAddToQueue: (Track) -> Unit = { track ->
        val ctrl = controllerState.controller
        if (ctrl != null) {
            // 离线没下载的歌进不了队列——插进去等到它播也是断，不如当场说清。
            val reason = viewModel.unplayableReason(track)
            if (reason != null) {
                snackbarScope.launch { snackbarHostState.showSnackbar(reason) }
            } else {
                val url = viewModel.streamUrl(track)
                if (url != null) {
                    val mediaItem = MediaItem.Builder()
                        .setUri(url)
                        .setMediaId(track.id)
                        .setTag(track.imageTag)
                        .setMediaMetadata(track.toPlaybackMetadata())
                        .build()
                    if (ctrl.mediaItemCount == 0) {
                        ctrl.setMediaItem(mediaItem)
                        ctrl.prepare()
                        ctrl.play()
                    } else {
                        val insertIndex = (ctrl.currentMediaItemIndex + 1).coerceAtMost(ctrl.mediaItemCount)
                        ctrl.addMediaItem(insertIndex, mediaItem)
                    }
                    snackbarScope.launch { snackbarHostState.showSnackbar("已添加到播放队列") }
                }
            }
        }
    }
    // ── 曲库文件操作（长按菜单 → 删除 / 改名，走 PC 伴生服务）──────────────
    // 回调经 CompositionLocal 发给所有 TrackRow（五个列表页共用，不逐页传参）；
    // 对话框收在本层函数末尾——只有这里同时拿得到 ViewModel 和播放器控制器。
    var renameTarget by remember { mutableStateOf<Track?>(null) }
    var deleteTarget by remember { mutableStateOf<List<Track>>(emptyList()) }
    val trackFileOps = remember {
        TrackFileOps(
            onRename = {
                viewModel.clearFileOpsMessage()
                renameTarget = it
            },
            onDelete = {
                viewModel.clearFileOpsMessage()
                deleteTarget = listOf(it)
            }
        )
    }
    // 多选模式：状态在 VM（跨页面结构），UI 只做转发。active = 非空集合。
    val multiSelect = MultiSelectState(
        active = state.multiSelectIds.isNotEmpty(),
        selected = state.multiSelectIds,
        onToggle = viewModel::toggleMultiSelect,
        onEnter = viewModel::enterMultiSelect
    )
    // 长按菜单状态提升到本层（工单 15）：只有这里拿得到主窗口 backdrop，菜单才能与玻璃岛同材质。
    // TrackRow 通过 LocalTrackMenu 上报「哪首歌 + 锚点」，不再各自持有 Popup。
    val trackMenu = remember { TrackMenuState() }
    // 切页时收起菜单：菜单是常驻 overlay，不跟着行一起离开组合，得显式收。
    LaunchedEffect(section) { trackMenu.dismiss() }
    // 多选模式下的「删除」按钮目标：勾选的全部曲目，进同一个确认框。
    var batchDeleteTargets by remember { mutableStateOf<List<Track>>(emptyList()) }
    // 当前列表（多选条的全选用）：按所在页面取对应列表，别页（设置/下载）没有行。
    val currentListTracks: List<Track> = when (val s = section) {
        LibrarySection.Tracks, LibrarySection.Discover -> tracks
        is LibrarySection.AlbumDetail -> state.albumTracks.orEmpty()
        is LibrarySection.PlaylistDetail -> state.playlistTracks.orEmpty()
        LibrarySection.Search -> state.searchResults?.tracks.orEmpty()
        else -> emptyList()
    }
    // 系统返回键 + 侧滑手势：详情页返回来源页（列表页或搜索页）；非默认页签（歌曲/专辑/播放列表/搜索）
    // 先回到默认的发现页；只有已在发现页时按返回才走系统默认行为（退出应用）。
    BackHandler(section != LibrarySection.Discover) {
        when (val s = section) {
            is LibrarySection.AlbumDetail -> {
                viewModel.clearAlbumDetail()
                section = detailOrigin
                // 来源已消费：二级链（设置 → 已下载 → 专辑）返回后不留陈旧来源，
                // 否则再往回退会卡在 设置↔已下载 的循环里。
                detailOrigin = LibrarySection.Discover
            }
            is LibrarySection.PlaylistDetail -> {
                viewModel.clearPlaylistDetail()
                section = detailOrigin
                detailOrigin = LibrarySection.Discover
            }
            is LibrarySection.GroupDetail -> {
                // 组详情没有可清的服务端状态（专辑列表还在用）。来源固定是艺术家页——
                // 不借 detailOrigin：它可能被「组详情 → 专辑详情」覆盖过。
                section = LibrarySection.Artists
                detailOrigin = LibrarySection.Discover
            }
            LibrarySection.Settings -> {
                section = detailOrigin
                detailOrigin = LibrarySection.Discover
            }
            LibrarySection.CachedCopies -> section = LibrarySection.Settings
            // 下载服务是设置里的二级页：返回键回设置，不是回默认的发现页。
            LibrarySection.DownloadService -> section = LibrarySection.Settings
            else -> section = LibrarySection.Discover
        }
    }
    // 多选模式下返回键先退出多选，而不是退出页面（后声明的 BackHandler 优先响应）。
    if (state.multiSelectIds.isNotEmpty()) {
        BackHandler { viewModel.clearMultiSelect() }
    }
    // 切到别的页面自动退出多选，别让它跨页面残留。
    LaunchedEffect(section) {
        if (state.multiSelectIds.isNotEmpty()) viewModel.clearMultiSelect()
    }
    // 全屏播放页开关：迷你播放条点击打开，收起按钮 / 返回键关闭。
    var showNowPlaying by remember { mutableStateOf(false) }
    // 底部浮层锚点栈（工单 04）：迷你条实测高度（折叠 64 / 展开时含整个小面板）。
    // Snackbar 与音量胶囊都锚在「导航栏 inset + 这个高度 + 外边距」，不再用固定 80/320dp 猜。
    // 首帧还没测到时用 PlayerDockHeight 兜底——它本来就等于折叠态高度，所以看不到跳动。
    // 这里只声明状态、不在本层读值：展开动画每帧都会刷新它，读点放在浮层小组件内部，
    // 否则整个主界面会跟着每帧重组。
    val playerDockHeight = remember { mutableStateOf(Dimens.PlayerDockHeight) }
    // 全屏播放页打开次数编号：作为 NowPlayingScreen 的 key，每次打开强制重建组合，
    // 页内手势状态（dragOffset 等）从零开始，不会被上一次退出残留污染。
    var nowPlayingSession by remember { mutableStateOf(0) }
    // 全屏页打开期间跟随曲目拉歌词；切歌时因 key 变化重新触发，关闭时清掉常驻内存。
    LaunchedEffect(showNowPlaying, currentTrack?.id) {
        if (showNowPlaying && currentTrack != null) viewModel.loadLyricsForCurrentTrack()
    }
    // 全屏播放页打开时，返回键先收起全屏页（后组合的 BackHandler 优先响应）。
    if (showNowPlaying) {
        BackHandler { showNowPlaying = false }
    }
    // 主界面背景：色板 surface 三档渐变（带封面色调的中性底），不再是高饱和封面渐变。
    // 颜色由 PulsarTheme 统一做 800ms 过渡，这里直接读动画后的色板。
    // 色板本身接近白（浅色 surface l≈0.99），铺满全屏会发飘，所以只对**背景渐变**压深；
    // 色板角色本身不动——按钮/文字的对比度靠它保证，深色模式已足够深，压得更少。
    val scheme = MaterialTheme.colorScheme
    val depth = if (isSystemInDarkTheme()) 0.90f else 0.86f
    val backgroundBrush = Brush.verticalGradient(
        0f to scheme.surface.deepened(depth),
        0.3f to scheme.surfaceVariant.deepened(depth),
        1f to scheme.background.deepened(depth)
    )
    // 全屏播放页背景保持原有的封面三色渐变（工单 12 再处理），三色 + 强调色一起 800ms 过渡。
    val gradientSpec = tween<Color>(THEME_TRANSITION_MS)
    val coverTop by animateColorAsState(coverTheme.gradient.top, gradientSpec, label = "coverGradientTop")
    val coverMiddle by animateColorAsState(coverTheme.gradient.middle, gradientSpec, label = "coverGradientMiddle")
    val coverBottom by animateColorAsState(coverTheme.gradient.bottom, gradientSpec, label = "coverGradientBottom")
    val coverAccentAnimated by animateColorAsState(coverTheme.gradient.accent, gradientSpec, label = "coverGradientAccent")
    val animatedCoverGradient = CoverGradient(coverTop, coverMiddle, coverBottom, coverAccentAnimated)
    // 悬浮布局：内容铺满全屏，顶部栏 / 底部播放条是浮在内容上方的玻璃条。
    // SharedTransitionLayout 包住整个悬浮布局：迷你条小封面 ↔ 全屏页大封面共用
    // 同一 key 做 morph（打开/收起全屏页时封面跨布局连续飞行动画）。
    CompositionLocalProvider(
        LocalTrackFileOps provides trackFileOps,
        LocalMultiSelect provides multiSelect,
        LocalCoverAccent provides coverAccentAnimated,
        LocalOfflineActive provides state.isOffline,
        // 长按菜单状态：TrackRow 上报锚点，菜单本体由本层渲染（工单 15）。
        LocalTrackMenu provides trackMenu
    ) {
    SharedTransitionLayout {
        Box(Modifier.fillMaxSize().background(backgroundBrush)) {
        val backdrop = rememberLayerBackdrop {
            drawRect(brush = backgroundBrush)
            drawContent()
        }
        // 内容全屏，并记录进 backdrop 层（玻璃条才有东西可模糊）。
        Box(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .layerBackdrop(backdrop)
        ) {
            // 页面切换动画：进详情页 / 搜索页从右侧推入、返回从左侧退回；
            // 顶层页签之间按 tab 顺序左右滑（与 Auxio 的 ViewPager 方向一致）。
            AnimatedContent(
                targetState = section,
                transitionSpec = {
                    val fromIndex = initialState.navIndex()
                    val toIndex = targetState.navIndex()
                    val forward = when {
                        // 顶层页签之间：序号增大 = 往右翻。
                        fromIndex >= 0 && toIndex >= 0 -> toIndex > fromIndex
                        // 顶层 → 非顶层（详情 / 搜索 / 设置）：往里走，从右侧推入。
                        fromIndex >= 0 -> true
                        // 非顶层 → 任何页：往外退，从左侧退回。
                        else -> false
                    }
                    val enter = slideInHorizontally(tween(320)) { width ->
                        if (forward) width else -width
                    } + fadeIn(tween(320))
                    val exit = slideOutHorizontally(tween(320)) { width ->
                        if (forward) -width else width
                    } + fadeOut(tween(320))
                    enter.togetherWith(exit)
                },
                label = "librarySection"
            ) { s ->
                when (s) {
                    LibrarySection.Discover -> DiscoverContent(
                        state = state,
                        tracks = tracks,
                        discoverAlbums = visible.discoverAlbums,
                        isOfflineFiltered = offlineFilter,
                        onOpenAlbum = openAlbum,
                        coverFor = viewModel::albumCover,
                        trackCoverFor = viewModel::trackCover,
                        onPlayTrack = onPlayTrack,
                        onAddToQueue = onAddToQueue,
                        currentTrackId = currentTrack?.id,
                        isPlaying = controllerState.isPlaying,
                        onRefresh = viewModel::refreshDiscover,
                        onRefreshAlbums = viewModel::refreshDiscoverAlbums,
                        onRetry = viewModel::loadDiscoverAlbums,
                        modifier = Modifier.fillMaxSize()
                    )
                    LibrarySection.Tracks -> TracksContent(
                        state = state,
                        tracks = tracks,
                        title = s.navLabel().orEmpty(),
                        offlineNotice = offlineNotice,
                        controllerState = controllerState,
                        coverFor = viewModel::trackCover,
                        onLocalAudio = { localAudioLauncher.launch(arrayOf("audio/*")) },
                        onPlayTrack = onPlayTrack,
                        onAddToQueue = onAddToQueue,
                        currentTrackId = currentTrack?.id,
                        modifier = Modifier.fillMaxSize()
                    )
                    LibrarySection.Albums -> AlbumsContent(
                        state = state,
                        albums = visible.albums,
                        offlineNotice = offlineNotice,
                        onOpenAlbum = openAlbum,
                        coverFor = viewModel::albumCover,
                        onRetry = viewModel::loadAlbums,
                        modifier = Modifier.fillMaxSize()
                    )
                    LibrarySection.Playlists -> PlaylistsContent(
                        state = state,
                        onOpenPlaylist = openPlaylist,
                        coverFor = viewModel::playlistCover,
                        onRetry = viewModel::loadPlaylists,
                        modifier = Modifier.fillMaxSize()
                    )
                    LibrarySection.Artists -> LibraryGroupsContent(
                        state = state,
                        albums = visible.albums,
                        title = s.navLabel().orEmpty(),
                        offlineNotice = offlineNotice,
                        onOpenGroup = { name ->
                            detailOrigin = section
                            section = LibrarySection.GroupDetail(name)
                        },
                        coverFor = viewModel::albumCover,
                        onRetry = viewModel::loadAlbums,
                        modifier = Modifier.fillMaxSize()
                    )
                    LibrarySection.Search -> SearchContent(
                        state = state,
                        results = visible.searchResults,
                        isOfflineFiltered = offlineFilter,
                        qqState = qqState,
                        // 一次输入同时问两边：两个 ViewModel 各跑各的 300ms 防抖，同时到期 = 一次防抖。
                        onSearchQueryChange = { query ->
                            viewModel.updateSearchQuery(query)
                            qqViewModel.updateSearchQuery(query)
                        },
                        trackCoverFor = viewModel::trackCover,
                        albumCoverFor = viewModel::albumCover,
                        playlistCoverFor = viewModel::playlistCover,
                        onRetry = { viewModel.updateSearchQuery(state.searchQuery) },
                        onPlayTrack = onPlayTrack,
                        onAddToQueue = onAddToQueue,
                        onOpenAlbum = openAlbum,
                        onOpenPlaylist = openPlaylist,
                        onDownloadSong = { qqViewModel.downloadSong(it) },
                        onRequestRedownload = qqViewModel::requestRedownload,
                        onConfirmRedownload = qqViewModel::confirmRedownload,
                        onDismissRedownload = qqViewModel::dismissRedownload,
                        onDownloadAlbum = qqViewModel::downloadAlbum,
                        onSelectAlbumTracks = qqViewModel::openAlbumPicker,
                        onToggleAlbumTrack = qqViewModel::toggleAlbumTrack,
                        onSelectAllAlbumTracks = qqViewModel::selectAllAlbumTracks,
                        onClearAlbumSelection = qqViewModel::clearAlbumSelection,
                        onConfirmAlbumDownload = qqViewModel::confirmAlbumDownload,
                        onRetryAlbumTracks = qqViewModel::retryAlbumTracks,
                        onDismissAlbumPicker = qqViewModel::dismissAlbumPicker,
                        onRetryCredential = qqViewModel::retryCredential,
                        onRetryQqSearch = qqViewModel::retrySearch,
                        currentTrackId = currentTrack?.id,
                        isPlaying = controllerState.isPlaying,
                        modifier = Modifier.fillMaxSize()
                    )
                    LibrarySection.DownloadService -> DownloadServiceScreen(
                        state = qqState,
                        onQualityChange = qqViewModel::updateQuality,
                        onRetryCredential = qqViewModel::retryCredential,
                        onRefreshCredential = qqViewModel::refreshCredential,
                        onRefreshJobs = qqViewModel::refreshJobs,
                        onFileOpsKeyInput = qqViewModel::updateFileOpsKeyInput,
                        onSaveFileOpsKey = qqViewModel::saveFileOpsKey,
                        modifier = Modifier.fillMaxSize()
                    )
                    is LibrarySection.AlbumDetail -> AlbumDetailContent(
                        state = state,
                        album = s.album,
                        albumTracks = visible.albumTracks,
                        offlineNotice = offlineNotice,
                        coverFor = viewModel::trackCover,
                        onPlayTrack = onPlayTrack,
                        onAddToQueue = onAddToQueue,
                        albumCoverFor = viewModel::albumCover,
                        onRetry = { viewModel.loadAlbumTracks(s.album) },
                        currentTrackId = currentTrack?.id,
                        isPlaying = controllerState.isPlaying,
                        streamUrl = viewModel::streamUrl,
                        onDownloadMessage = showMessage,
                        modifier = Modifier.fillMaxSize()
                    )
                    is LibrarySection.PlaylistDetail -> PlaylistDetailContent(
                        state = state,
                        playlist = s.playlist,
                        playlistTracks = visible.playlistTracks,
                        isOffline = state.isOffline,
                        offlineNotice = offlineNotice,
                        coverFor = viewModel::trackCover,
                        onPlayTrack = onPlayTrack,
                        onAddToQueue = onAddToQueue,
                        playlistCoverFor = viewModel::playlistCover,
                        onRetry = { viewModel.loadPlaylistTracks(s.playlist) },
                        currentTrackId = currentTrack?.id,
                        isPlaying = controllerState.isPlaying,
                        streamUrl = viewModel::streamUrl,
                        onDownloadMessage = showMessage,
                        modifier = Modifier.fillMaxSize()
                    )
                    is LibrarySection.GroupDetail -> LibraryGroupDetailContent(
                        state = state,
                        albums = visible.albums,
                        groupName = s.name,
                        offlineNotice = offlineNotice,
                        onOpenAlbum = openAlbum,
                        coverFor = viewModel::albumCover,
                        onRetry = viewModel::loadAlbums,
                        modifier = Modifier.fillMaxSize()
                    )
                    LibrarySection.CachedCopies -> CachedCopiesContent(
                        state = state,
                        viewModel = viewModel,
                        albumCoverFor = viewModel::albumCover,
                        modifier = Modifier.fillMaxSize()
                    )
                    LibrarySection.Settings -> SettingsContent(
                        state = state,
                        viewModel = viewModel,
                        controllerState = controllerState,
                        onOpenCachedCopies = { section = LibrarySection.CachedCopies },
                        onOpenDownloadService = { section = LibrarySection.DownloadService },
                        themePreferences = themePreferences,
                        seedSource = coverTheme.seed.source,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        // 顶部玻璃岛（悬浮）：列表页显示；详情页隐藏（返回交给系统返回键 / 侧滑手势），上滑淡出。
        AnimatedVisibility(
            visible = !section.isDetail(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
            enter = slideInVertically(tween(300)) { -it } + fadeIn(tween(300)),
            exit = slideOutVertically(tween(300)) { -it } + fadeOut(tween(300))
        ) {
            GlassChipBar(
                backdrop = backdrop,
                section = section,
                onSelectSection = { section = it },
                onShowSearch = { section = LibrarySection.Search },
                onShowSettings = {
                    detailOrigin = section
                    section = LibrarySection.Settings
                },
                onSignOut = viewModel::signOut,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(
                        horizontal = Dimens.FloatingHorizontalMargin,
                        vertical = Dimens.FloatingVerticalMargin
                    )
            )
        }
        // 底部玻璃播放条（悬浮）：四角全圆角，完整液态玻璃（模糊 + 透镜 + 高光）。
        // 点整条 / 面板内全屏按钮打开全屏播放页；右侧箭头切换小展开面板（与全屏页并存）。
        // sharedTransitionScope 传下去：迷你条小封面与全屏页大封面注册同一个 key，
        // 打开/收起全屏页时封面跨布局连续飞行。对齐仍走本层 BoxScope 的 align——
        // 早前把 align 放进内容里丢过一次，迷你条跑到屏幕顶部（真机已复现）。
        MiniPlayerBar(
            backdrop = backdrop,
            controllerState = controllerState,
            playbackMode = playbackMode,
            onPlaybackModeChange = { mode ->
                playbackMode = mode
                controllerState.controller?.applyPlaybackMode(mode)
            },
            currentTrack = currentTrack,
            accentColor = coverAccentAnimated,
            coverFor = viewModel::trackCover,
            sharedTransitionScope = this@SharedTransitionLayout,
            // 全屏页展开时迷你封面取反隐藏：两侧都真正参与一次进出场，sharedBounds
            // 才会把「原地绘制」让给 overlay 里的飞行副本，否则会出现两份封面。
            fullScreenOpen = showNowPlaying,
            onOpenFullScreen = {
                nowPlayingSession++
                showNowPlaying = true
            },
            onDockHeightChange = { playerDockHeight.value = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(
                    horizontal = Dimens.FloatingHorizontalMargin,
                    vertical = Dimens.FloatingVerticalMargin
                )
        )
        // Snackbar 提示：锚在迷你播放条上方（底部浮层栈，工单 04）。
        // 高度用实测值，小展开面板展开时跟着逐帧上移，不再固定抬 320dp。
        DockAnchoredSnackbar(
            dockHeight = playerDockHeight,
            hostState = snackbarHostState
        )
        // 全屏播放页：盖在所有内容最上层，从底部滑入 / 滑出。
        // session 编号作为 key：下滑手势收起时 dragOffset 停在松手位置不归零（供 exit 从松手处
        // 无缝续滑），exit 期间重开页面会复用同一组合实例——key 变化强制重建，
        // dragOffset 从 0 开始，页面不会停在屏外不可见。
        // 只留一个运动：整页不再从底部滑入（那是第二套位移，与封面飞行打架、到达时间对不齐），
        // 改成淡入淡出，让封面从迷你条飞到大封面成为唯一的位移来源。下滑手势收起时页面本就
        // 跟着 dragOffset 位移、并随位移降 alpha，exit 淡出与松手位置衔接自然。
        AnimatedVisibility(
            visible = showNowPlaying,
            enter = fadeIn(tween(NOW_PLAYING_FADE_MS)),
            exit = fadeOut(tween(NOW_PLAYING_FADE_MS))
        ) {
            key(nowPlayingSession) {
                NowPlayingScreen(
                    controllerState = controllerState,
                    currentTrack = currentTrack,
                    coverRequest = currentTrack?.let(viewModel::trackCover),
                    gradientColors = animatedCoverGradient,
                    playbackMode = playbackMode,
                    onPlaybackModeChange = { mode ->
                        playbackMode = mode
                        controllerState.controller?.applyPlaybackMode(mode)
                    },
                    lyrics = lyrics,
                    // 点歌词行 seek 到该行时间（微秒 → 毫秒转 MediaController）。
                    onLineSeek = { positionUs -> controllerState.controller?.seekTo(positionUs / 1000) },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    // key(nowPlayingSession) 包着本内容，AnimatedVisibilityScope 就是本层。
                    animatedVisibilityScope = this,
                    onClose = {
                        showNowPlaying = false
                        viewModel.clearLyrics()
                    }
                )
            }
        }
        // 音量浮窗：按音量键时从**底部**冒出来的液态玻璃胶囊（与 Snackbar 同一个底部锚点栈）。
        // 曾经挂在顶部玻璃岛下方，多选条一起来就把它完全盖住；移到底部后两者互不遮挡。
        // 声明在全屏播放页**之后** = 压在全屏页之上（全屏页背景不透明，先声明就被它盖住了）；
        // 用 backdrop 采样它下面的内容，所以又必须在记进 backdrop 的那层之外——本层正是。
        // 独占关闭时不会弹：那时音量键根本不进 App（ForegroundVolumeKeys 一律放行给系统）。
        DockAnchoredVolumeFeedback(
            dockHeight = playerDockHeight,
            state = volumeFeedback,
            backdrop = backdrop,
            accent = coverAccentAnimated
        )
        // 多选操作条（工单 04）：固定在玻璃岛下方，材质 / 圆角 / 高度与玻璃岛一致。
        // 顶部槽位只放导航与选择，底部槽位只放瞬时反馈（Snackbar / 音量胶囊）——互不打架。
        if (state.multiSelectIds.isNotEmpty()) {
            GlassPanel(
                backdrop = backdrop,
                // 与玻璃岛同 alpha：条下面压着滚动曲库，太透会看不清按钮文字。
                surface = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(
                        top = Dimens.GlassIslandTopSpacing + Dimens.FloatingVerticalMargin,
                        start = Dimens.FloatingHorizontalMargin,
                        end = Dimens.FloatingHorizontalMargin
                    )
                    .fillMaxWidth()
                    .height(Dimens.SelectionBarHeight)
            ) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(start = Dimens.SpaceMedium, end = Dimens.SpaceTiny),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已选 ${state.multiSelectIds.size} 首",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { viewModel.selectAllTracks(currentListTracks.map { it.id }) },
                        enabled = currentListTracks.isNotEmpty(),
                        modifier = Modifier.height(Dimens.TouchTargetSmall)
                    ) { Text("全选") }
                    TextButton(
                        onClick = viewModel::clearMultiSelect,
                        modifier = Modifier.height(Dimens.TouchTargetSmall)
                    ) { Text("取消") }
                    TextButton(
                        onClick = {
                            // 同一个确认框：目标换成勾选的全部曲目
                            deleteTarget = currentListTracks.filter { it.id in state.multiSelectIds }
                        },
                        enabled = state.multiSelectIds.isNotEmpty() && !state.isFileOpsBusy,
                        modifier = Modifier.height(Dimens.TouchTargetSmall)
                    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        // 长按菜单 overlay（工单 15）：声明在最后 = 压在所有浮层之上（z 序）。
        // 在主窗口内渲染，才能用同一个 backdrop 走 GlassPanel（Popup 是独立窗口，采样不到）。
        TrackContextMenu(
            state = trackMenu,
            currentTrackId = currentTrack?.id,
            backdrop = backdrop,
            windowSize = LocalWindowInfo.current.containerSize
        )
        }
    }
    }

    // 文件操作对话框（删除确认 / 改名输入），压在所有内容之上。
    // 单曲与批量删除走同一个确认框：deleteTarget 装的是一批 Track。
    fun removeFromQueue(ids: Set<String>) {
        val ctrl = controllerState.controller ?: return
        // 倒序摘：removeMediaItem 会让后面的索引前移；正在播的索引跳过（VM 守卫已挡单曲，这里兜底）
        for (i in ctrl.mediaItemCount - 1 downTo 0) {
            if (i != ctrl.currentMediaItemIndex && ctrl.getMediaItemAt(i).mediaId in ids) {
                ctrl.removeMediaItem(i)
            }
        }
    }
    TrackFileOpsDialogs(
        renameTarget = renameTarget,
        deleteTarget = deleteTarget,
        state = state,
        onRename = { target, title, artist, album ->
            snackbarScope.launch {
                if (viewModel.renameTrack(target, title, artist, album)) {
                    renameTarget = null
                    snackbarHostState.showSnackbar("已保存")
                }
            }
        },
        onDelete = { targets ->
            snackbarScope.launch {
                if (targets.size == 1) {
                    val target = targets.first()
                    if (viewModel.deleteTrack(target)) {
                        removeFromQueue(setOf(target.id))
                        deleteTarget = emptyList()
                        snackbarHostState.showSnackbar("已删除，可在设置页的回收站恢复")
                    }
                } else {
                    val deletedCount = viewModel.deleteTracks(targets)
                    if (deletedCount > 0) {
                        removeFromQueue(targets.map { it.id }.toSet())
                        deleteTarget = emptyList()
                        // 部分失败时 VM 已写 fileOpsMessage，一并带进提示
                        val extra = viewModel.state.value.fileOpsMessage?.let { "；$it" }.orEmpty()
                        snackbarHostState.showSnackbar("已删除 $deletedCount 首$extra")
                    }
                }
            }
        },
        onDismissRename = { renameTarget = null },
        onDismissDelete = { deleteTarget = emptyList() }
    )
}

/**
 * 底部浮层栈（工单 04）的公共锚点：导航栏 inset + 播放条高度 + 两段外边距。
 *
 * 实测高度只含玻璃条本身（不含它的 8dp 外边距），所以补两次 [Dimens.FloatingVerticalMargin]：
 * 一次是播放条距屏幕的下边距，一次是浮层与播放条之间的空隙。
 *
 * 读 [dockHeight] 发生在调用方（两个浮层小组件）的组合作用域，展开动画不会拖着主界面每帧重组。
 */
private fun Modifier.bottomDockAnchor(dockHeight: State<Dp>): Modifier = this
    // 先躲开导航栏（手势约 24dp、三键约 48dp），再抬到播放条上方；
    // 固定底距在两种导航模式下会各被挡住一截。
    .navigationBarsPadding()
    .padding(bottom = dockHeight.value + Dimens.FloatingVerticalMargin * 2)

/**
 * 底部浮层栈（工单 04）成员一：Snackbar，锚在迷你播放条上方。
 *
 * 播放条高度以 [State] 传入、在这里才读值：小展开面板的展开/收起动画每帧都会刷新它，
 * 若在 `PulsarApp` 本体里读，整个主界面会跟着每帧重组。
 */
@Composable
private fun BoxScope.DockAnchoredSnackbar(
    dockHeight: State<Dp>,
    hostState: SnackbarHostState
) {
    SnackbarHost(
        hostState = hostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .bottomDockAnchor(dockHeight)
            .padding(horizontal = Dimens.FloatingHorizontalMargin)
    )
}

/**
 * 底部浮层栈（工单 04）成员二：音量胶囊。与 Snackbar 共用锚点，所以多选条占据顶部槽位时
 * 两者依然同时可见。读 [dockHeight] 的原因同 [DockAnchoredSnackbar]。
 */
@Composable
private fun BoxScope.DockAnchoredVolumeFeedback(
    dockHeight: State<Dp>,
    state: VolumeFeedbackState,
    backdrop: Backdrop,
    accent: Color
) {
    VolumeFeedbackOverlay(
        state = state,
        backdrop = backdrop,
        accent = accent,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .bottomDockAnchor(dockHeight)
    )
}

/**
 * 顶部玻璃岛（工单 06）：悬浮的液态玻璃面板（与播放条同款：四角圆角 + 模糊 + 透镜 + 高光）。
 *
 * 三段式布局，从左到右：搜索（48dp）→ 可横滑页签（`weight(1f)`）→「更多」（48dp）。
 * 页签顺序与文案只由 [TopLevelSections] 决定，不在这里写死；超出宽度可横向滑动，
 * 选中项切换时自动滚入可见区，两侧渐变提示「还能划」。
 *
 * 设置 / 退出登录收进「更多」菜单，不再和页签抢宽度；退出登录先弹确认，取消即保持登录。
 * 已下载列表与 QQ 音乐导入仍从设置页进入（ADR-0006 / ADR-0007），不另开全局入口。
 */
@Composable
private fun GlassChipBar(
    backdrop: Backdrop,
    section: LibrarySection,
    onSelectSection: (LibrarySection) -> Unit,
    onShowSearch: () -> Unit,
    onShowSettings: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 可读性表面色：非组合的 onDrawSurface 里不能读 MaterialTheme，先取出来。
    val glassSurface = MaterialTheme.colorScheme.surface
    val chipScrollState = rememberScrollState()
    // 每个 chip 的可见区请求器：选中项切换时用它把自己滚入可见范围，避免切到被滑出屏幕的分区看不出当前在哪。
    val requesters = remember { TopLevelSections.map { BringIntoViewRequester() } }
    val selectedIndex = section.navIndex()
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in requesters.indices) requesters[selectedIndex].bringIntoView()
    }
    // 「更多」菜单与退出确认：确认前不碰会话，取消 = 什么都没发生。
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    // 玻璃面前景色由 GlassPanel 统一提供（onSurface）：深色主题下 chip 图标不再是默认黑。
    // 选中 chip 的 onPrimary 标签由 glassChipColors 显式设置，不受影响。
    GlassPanel(
        backdrop = backdrop,
        // 表面色比播放条更实：岛下压着滚动的曲库，太透会看不清 chip 文字。
        surface = glassSurface.copy(alpha = 0.8f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .height(Dimens.GlassIslandHeight)
                .padding(horizontal = Dimens.SpaceSmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall)
        ) {
            // 左：搜索。放大镜图标，进入搜索页时高亮为品牌色。
            IconButton(
                onClick = onShowSearch,
                modifier = Modifier.size(Dimens.TouchTargetSmall)
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "搜索音乐",
                    tint = if (section == LibrarySection.Search) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        LocalContentColor.current
                    }
                )
            }
            // 中：chip 分组可横向滑动的区域：两侧叠加渐变遮罩提示"还能划"，避免用户以为只有这几个按钮。
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .horizontalScroll(chipScrollState),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall)
                ) {
                    TopLevelSections.forEachIndexed { index, (target, label) ->
                        val selected = section == target
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectSection(target) },
                            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = glassChipColors(selected = selected),
                            border = glassChipBorder(),
                            modifier = Modifier.bringIntoViewRequester(requesters[index])
                        )
                    }
                }
                // 左侧渐变遮罩：还能往左滑时显示。
                if (chipScrollState.canScrollBackward) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            .width(Dimens.SpaceMedium)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(glassSurface.copy(alpha = 0.8f), glassSurface.copy(alpha = 0f))
                                )
                            )
                    )
                }
                // 右侧渐变遮罩：还能往右滑时显示。
                if (chipScrollState.canScrollForward) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(Dimens.SpaceMedium)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(glassSurface.copy(alpha = 0f), glassSurface.copy(alpha = 0.8f))
                                )
                            )
                    )
                }
            }
            // 右：「更多」。只收设置和退出登录；菜单项先关菜单再动作，避免菜单盖在新页面上。
            Box {
                IconButton(
                    onClick = { moreMenuExpanded = true },
                    modifier = Modifier.size(Dimens.TouchTargetSmall)
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(
                    expanded = moreMenuExpanded,
                    onDismissRequest = { moreMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("设置") },
                        onClick = {
                            moreMenuExpanded = false
                            onShowSettings()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("退出登录") },
                        onClick = {
                            moreMenuExpanded = false
                            confirmSignOut = true
                        }
                    )
                }
            }
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("退出登录？") },
            text = { Text("退出后需要重新登录。已下载的歌曲会保留在手机上。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    onSignOut()
                }) { Text("退出登录") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("取消") }
            }
        )
    }
}

/** 玻璃切换按钮配色：选中实心主题色，未选中半透明表面。 */
@Composable
private fun glassChipColors(selected: Boolean) = FilterChipDefaults.filterChipColors(
    containerColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
    },
    labelColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
)

/** 玻璃切换按钮细描边。 */
@Composable
private fun glassChipBorder() = BorderStroke(
    1.dp,
    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
)

@Composable
private fun TracksContent(
    state: JellyfinUiState,
    tracks: List<Track>,
    title: String,
    offlineNotice: String?,
    controllerState: ControllerState,
    coverFor: (Track) -> JellyfinRequest?,
    onLocalAudio: () -> Unit,
    onPlayTrack: (List<Track>, String) -> Unit,
    onAddToQueue: (Track) -> Unit,
    currentTrackId: String?,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        // 顶部让出状态栏 + 玻璃岛，底部让出悬浮播放条；状态栏随设备动态，玻璃岛用固定占位。
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(
            start = Dimens.SpaceMedium,
            end = Dimens.SpaceMedium,
            top = Dimens.GlassIslandTopSpacing,
            bottom = Dimens.BottomContentInset
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceTiny)
    ) {
        // 标题和顶部页签同一出处（TopLevelSections），不再写「你的音乐」（spec §11）。
        item { Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = Dimens.SpaceSmall)) }
        offlineNotice?.let { notice ->
            item { OfflineNoticeText(notice) }
        }
        item {
            Button(
                onClick = onLocalAudio,
                Modifier.fillMaxWidth().padding(top = Dimens.SpaceTiny, bottom = Dimens.SpaceSmall),
                enabled = controllerState.controller != null
            ) { Text("选择手机里的音频") }
        }
        // 会话还没恢复完用灰色波浪；已连上但曲库为空才是"暂无音乐"。
        // 离线且一首都没下载：专用空态（「曲库空了」和「离线只能看下载」是两回事）。
        if (state.tracks == null) item { TrackListSkeleton(count = 7, trailingSpace = true) } else if (tracks.isEmpty()) {
            item {
                EmptyState(
                    if (offlineNotice != null) "离线 · 手机里还没有下载的歌曲" else "暂无音乐"
                )
            }
        }
        itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
            TrackRow(
                track = track,
                cover = coverFor(track),
                onClick = { onPlayTrack(tracks, track.id) },
                onAddToQueue = { onAddToQueue(track) },
                showDivider = index != tracks.lastIndex,
                isCurrent = currentTrackId == track.id,
                isPlaying = controllerState.isPlaying,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 长按菜单的文件操作入口（删除 / 改名，走 PC 伴生服务）：由 [MusicLibraryScreen] 提供——
 * 只有它同时拿得到 ViewModel（发请求）和播放器控制器（删完清队列）——TrackRow 消费。
 * 五个列表页共用同一组回调，专辑详情 / 播放列表详情 / 搜索 / 发现页的长按菜单一并生效。
 * null = 菜单里不显示这两项。 */
private class TrackFileOps(
    val onRename: (Track) -> Unit,
    val onDelete: (Track) -> Unit
)

private val LocalTrackFileOps = compositionLocalOf<TrackFileOps?> { null }

/** 多选模式（工单 05）的行级入口：由 [MusicLibraryScreen] 提供，TrackRow 消费。
 * active = 是否处于多选模式（选中行为从「播放」切到「勾选」，行首出现勾选框）。 */
private class MultiSelectState(
    val active: Boolean,
    val selected: Set<String>,
    val onToggle: (String) -> Unit,
    val onEnter: (String) -> Unit
)

private val LocalMultiSelect = compositionLocalOf<MultiSelectState?> { null }

/** 当前封面的取色强调色（[rememberCoverGradient] 的 accent，无封面回退品牌靛蓝）。由 [MusicLibraryScreen]
 * 提供——只有它拿得到当前播放曲目——正在播放的均衡器条读它，和迷你条进度、全屏进度条同色。 */
private val LocalCoverAccent = compositionLocalOf { BrandPrimary }

/** 是否处于离线态：由 [MusicLibraryScreen] 提供，TrackRow 消费——离线时藏掉长按菜单里的
 * 删除 / 改名（要走 PC 伴生服务，断网必然失败，ADR-0008）。 */
private val LocalOfflineActive = compositionLocalOf { false }

/** 打开长按菜单的共享状态（工单 15）：由 [MusicLibraryScreen] `remember` 一次，TrackRow 通过
 * CompositionLocal 拿到同一个实例，只上报「哪首歌 + 锚点在哪」。
 *
 * 菜单从 `Popup`（独立窗口）提到主窗口 overlay 之后就**必须**由 [MusicLibraryScreen] 统一渲染——
 * 只有它拿得到主窗口的 `backdrop`，玻璃面才有东西可采样（这是当初手写玻璃、菜单只能退化成半透明
 * `Surface` 的技术原因）。
 *
 * 为什么是状态对象而不是 CompositionLocal 里直接放 lambda：lambda 每次重组都是新实例，会把所有读它的
 * TrackRow 全部失效；播放器 500ms 轮询会持续触发主屏重组，整张列表跟着白刷。 */
private class TrackMenuState {
    var request by mutableStateOf<TrackMenuRequest?>(null)
        private set

    fun open(track: Track, touchPoint: IntOffset, onAddToQueue: () -> Unit) {
        request = TrackMenuRequest(track, touchPoint, onAddToQueue)
    }

    fun dismiss() {
        request = null
    }
}

private val LocalTrackMenu = compositionLocalOf<TrackMenuState?> { null }

/** 单曲行：小封面 + 标题（大）+ 作者（灰色小字）+ 底部灰色分隔线。取消卡片形式，列表更紧凑。
 * 当前播放曲目：标题高亮为品牌色，封面上叠一层半透明遮罩 + 均衡器跳动条（暂停时静止）。
 * 长按弹出上下文菜单（弹性缩放 + 淡入 + 锚定在长按位置，逐项错峰弹出），支持"添加到播放队列"等操作。
 * 手势用 combinedClickable（Material 水波纹按压反馈），长按触觉由框架内置；触点坐标由旁挂的
 * 轻量 pointerInput 只观察不消费地记录，菜单仍锚定真实触点。
 * 离线时列表里只剩已下载的歌（ADR-0008），所以这里不再需要标灰分支。 */
@Composable
private fun TrackRow(
    track: Track,
    cover: JellyfinRequest?,
    onClick: () -> Unit,
    showDivider: Boolean,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    onAddToQueue: (() -> Unit)? = null,
    /** 专辑详情页用：行首显示曲目序号而不是小封面（Apple Music 列表语言）。null = 保持封面。 */
    leadingNumber: Int? = null,
    modifier: Modifier = Modifier
) {
    var rowWindowOffset by remember { mutableStateOf(Offset.Zero) }
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    var lastDownOffset by remember { mutableStateOf(Offset.Zero) }
    val multiSelect = LocalMultiSelect.current
    val multiSelectActive = multiSelect?.active == true
    // 分隔线起点跟着实际前槽走（spec §5）：多选 = 勾选框 48 + 4 + 封面 48 + 12 = 112dp，
    // 从文字左缘开始、不穿过勾选框；无多选 / 序号槽 = 48 + 12 = 60dp，从封面右缘开始。
    val leadingWidth = if (multiSelectActive) {
        Dimens.TouchTargetSmall + Dimens.SpaceTiny + Dimens.TouchTargetSmall
    } else {
        Dimens.TouchTargetSmall
    }
    // 长按与「更多」按钮共用同一个菜单，锚点不同而已（菜单本体在 MusicLibraryScreen 渲染）。
    val trackMenu = LocalTrackMenu.current
    fun openMenuAt(point: IntOffset) {
        // 行没提供「加队列」时也照旧弹菜单（其余项仍可用）；动作是空操作，与旧实现一致。
        trackMenu?.open(track, point, onAddToQueue ?: {})
    }
    val trailingSlot: (@Composable () -> Unit)? = if (onAddToQueue != null && !multiSelectActive) {
        {
            // Auxio 列表项的「更多」按钮：与长按弹同一个菜单（工单 05）。
            // 锚点取行右端中部，菜单照样贴着按钮弹；多选模式下整行是勾选语义，不显示。
            IconButton(
                onClick = {
                    openMenuAt(
                        IntOffset(
                            rowWindowOffset.x.toInt() + rowSize.width,
                            rowWindowOffset.y.toInt() + rowSize.height / 2
                        )
                    )
                },
                modifier = Modifier.size(Dimens.TouchTargetSmall)
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多操作：${track.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        null
    }
    MediaRowLayout(
        modifier = modifier,
        rowModifier = Modifier
            // combinedClickable 提供 Material 水波纹 + 长按；长按触觉（LongPress）由其内部默认开启。
            .combinedClickable(
                // 多选模式下点击整行 = 勾选，不再触发播放（工单 05）。
                onClick = {
                    if (multiSelectActive) multiSelect?.onToggle(track.id) else onClick()
                },
                onLongClick = {
                    // 触点坐标已在旁挂 pointerInput 里记录为最近一次按下位置，这里直接取用。
                    val windowOffset = rowWindowOffset + lastDownOffset
                    openMenuAt(IntOffset(windowOffset.x.toInt(), windowOffset.y.toInt()))
                }
            )
            // 轻量触点观察：只记下最近一次按下的局部坐标，不消费事件，不干扰 combinedClickable 的手势判定。
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    lastDownOffset = down.position
                }
            }
            .onGloballyPositioned { coordinates ->
                rowWindowOffset = coordinates.positionInWindow()
                rowSize = coordinates.size
            },
        leadingWidth = leadingWidth,
        showDivider = showDivider,
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 多选模式：行首勾选框只做指示，勾选动作在整行点击上。
                // 显式给 48dp：勾选框热区（也是分隔线与文字列的对齐基准）不小于触摸目标。
                if (multiSelectActive) {
                    Checkbox(
                        checked = multiSelect?.selected?.contains(track.id) == true,
                        onCheckedChange = null,
                        modifier = Modifier.size(Dimens.TouchTargetSmall)
                    )
                    Spacer(Modifier.width(Dimens.SpaceTiny))
                }
                if (leadingNumber != null) {
                    // 专辑详情：序号列与下方封面槽同高，右对齐贴着标题（Apple Music 的曲目行）。
                    // 末尾留 8dp：序号往左收一点，不顶死在标题前。
                    Box(
                        Modifier.size(Dimens.TouchTargetSmall).padding(end = Dimens.SpaceSmall),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text(
                            "$leadingNumber",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                } else {
                    Box {
                        AlbumCover(cover, track.title, Modifier.size(Dimens.TouchTargetSmall))
                        // 正在播放指示：封面叠一层淡深色遮罩，浅色封面上均衡器条也看得清。
                        // 遮罩必须跟着封面一起裁圆角——matchParentSize 是方块，不裁四角会盖到封面圆角外面。
                        if (isCurrent) {
                            PlayingIndicatorOverlay(
                                isPlaying = isPlaying,
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(8.dp)) // 与 AlbumCover 默认圆角一致
                            )
                        }
                    }
                }
            }
        },
        trailing = trailingSlot
    ) {
        MediaRowText(
            title = track.title,
            subtitle = track.artist,
            // 当前曲目标题用品牌色，与播放队列面板的高亮风格一致。
            titleColor = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified
        )
    }
}

/** 正在播放的封面指示层：淡深色遮罩 + 居中三根均衡器条。
 * 播放中：条高在 4~14dp 间循环跳动（三根相位错开，单周期 800ms）；
 * 暂停中：三根静止为 4/9/6dp，条改白色、遮罩压深一档，一眼看得出没在放（spec §9）。
 *
 * 两条性能约定（工单 14，报告 #71）：
 * · 无限动画**只在播放时创建**——`rememberInfiniteTransition` 只要被组合就一直请求动画帧，
 *   暂停时留在组合里等于让一个静止的封面继续跑帧；
 * · 条高只在**绘制阶段**变（见 [drawPlayingBars]），画布尺寸恒定，不再逐帧改布局高度。 */
@Composable
private fun PlayingIndicatorOverlay(
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    // 播放中取封面主题色（与迷你条进度、全屏进度条同源）；暂停时改白色——压在深色遮罩上，
    // 多花的封面也辨得出「停住了」。
    val barColor = if (isPlaying) LocalCoverAccent.current else Color.White
    // 暂停态遮罩从 0.35 提到 0.48（spec §9 的 0.45~0.5）：静止的白条要立得住。
    val scrimAlpha = if (isPlaying) 0.35f else 0.48f
    Box(
        modifier
            // 均衡器底衬：用色板 scrim（恒黑），不写死黑色。
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha)),
        contentAlignment = Alignment.Center
    ) {
        val progress = rememberPlayingBarProgress(isPlaying)
        // 画布固定 13×14dp（14 = 最高的一根条）：条高怎么跳，布局尺寸都不动。
        Canvas(Modifier.size(PlayingBarsWidth, PlayingBarMaxHeight)) {
            drawPlayingBars(progress, barColor)
        }
    }
}

/** 三根条的归一化进度 0f..1f（按根号取）。
 *
 * 返回「按下标取值」的函数而不是现成高度：真正的读取（`state.value`）发生在 [drawPlayingBars]
 * 的绘制块里，于是动画帧只引起**重绘**——既不重组，也不像以前 `Modifier.height(...)` 那样逐帧重排
 * （滚动掉帧的根因）。
 *
 * 暂停分支**不组合** InfiniteTransition，直接返回静态进度（4/9/6dp），一帧动画都不跑。 */
@Composable
private fun rememberPlayingBarProgress(isPlaying: Boolean): (Int) -> Float {
    if (!isPlaying) {
        // 暂停：静态高度 4/9/6dp，组合里连 InfiniteTransition 都没有。
        return { index -> pausedPlayingBarProgress[index] }
    }
    val transition = rememberInfiniteTransition(label = "playingBars")
    // 三根相位错开 0/400/200ms，单周期 800ms（偏慢一点，避免在列表里显得躁）。
    val bars: List<State<Float>> = listOf(
        transition.animateFloat(0f, 1f, playingBarSpec(startOffsetMs = 0), label = "bar1"),
        transition.animateFloat(0f, 1f, playingBarSpec(startOffsetMs = 400), label = "bar2"),
        transition.animateFloat(0f, 1f, playingBarSpec(startOffsetMs = 200), label = "bar3")
    )
    return { index -> bars[index].value }
}

/** 单根条的单周期规格：800ms 一上一下（偏慢一点，避免在列表里显得躁），三根用相位错开。 */
private fun playingBarSpec(startOffsetMs: Int) = infiniteRepeatable<Float>(
    animation = tween<Float>(800, easing = LinearEasing),
    repeatMode = RepeatMode.Reverse,
    initialStartOffset = StartOffset(startOffsetMs)
)

/** 画三根条：高度在绘制阶段由 [progress] 决定，宽高与画布尺寸恒定——布局稳定，滚动不因它重排。 */
private fun DrawScope.drawPlayingBars(progress: (Int) -> Float, color: Color) {
    val barWidth = PlayingBarWidth.toPx()
    val gap = PlayingBarGap.toPx()
    repeat(PlayingBarCount) { index ->
        val barHeight = playingBarHeight(progress(index)).toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(index * (barWidth + gap), (size.height - barHeight) / 2f),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(barWidth / 2f)
        )
    }
}

/**
 * 长按 / 「更多」弹出的上下文菜单（工单 15）：**主窗口内的 overlay**，不再是 `Popup` 独立窗口。
 *
 * 为什么要提上来：`Popup` 是独立窗口，采样不到主窗口的 `backdrop`，所以菜单只能自己画半透明
 * `Surface`（当初手写玻璃的技术原因）。提到主窗口后菜单与玻璃岛 / 迷你条走**同一个** [GlassPanel]、
 * 同一种材质与前景色；宽 240、行高 48、圆角 `CornerLarge`、内边距 `MenuEdgePadding` 也与其它浮层一致。
 *
 * overlay 要**手动补齐** `Popup` 原本免费提供的行为（spec §10）：
 * · **点外关闭**——菜单背后铺一层全屏透明 scrim，点它即关闭；
 * · **返回键**——[BackHandler] 关菜单（后声明于页面导航的平级声明，优先响应，不会退出页面）；
 * · **焦点**——菜单卡片可获焦并在出现时主动请求焦点（[FocusRequester]），键盘 / 无障碍焦点落在菜单内；
 * · **z 序**——本 overlay 在主窗口 Box 里**最后声明**，压在内容与所有浮层之上。
 *
 * 选中项后：动作立刻执行 + 立刻关菜单，没有退场动画（等动画播完才动作手感发黏，真机反馈）。
 * 进入动画只有弹性缩放、没有整体淡入：菜单带阴影，同一个 layer 上跑 alpha 会让 Android 走离屏合成、
 * 阴影在动画里闪；菜单项自己逐条淡入，整体再淡一遍看不出来。
 *
 * @param state 菜单状态（哪首歌 + 长按触点窗口坐标）；`request == null` 时不渲染。
 * @param currentTrackId 当前播放曲目：正在播的那首删除项置灰（播放中的文件删了缓冲还在，bug 高发）。
 * @param backdrop 主窗口采样源，玻璃面据此取景。
 * @param windowSize 主窗口尺寸，用于把菜单夹在可见区内（[menuTopLeft]）。
 */
@Composable
private fun BoxScope.TrackContextMenu(
    state: TrackMenuState,
    currentTrackId: String?,
    backdrop: Backdrop,
    windowSize: IntSize
) {
    // 在这里读状态（而不是在调用方读、再作为参数传进来）：菜单没开时读出来是 null，
    // 状态一变只会让本函数重组，不会把整个主界面拖进来。
    val request = state.request ?: return
    val track = request.track
    // 离线时不提供「改名 / 删除」：走 PC 伴生服务，断网必然失败（ADR-0008）。
    val fileOps = LocalTrackFileOps.current.takeUnless { LocalOfflineActive.current }
    val multiSelect = LocalMultiSelect.current
    val onAddToQueue = request.onAddToQueue
    val onDismiss = state::dismiss
    val density = LocalDensity.current
    val edgePaddingPx = with(density) { Dimens.MenuEdgePadding.toPx().toInt() }
    // 主窗口是 edge-to-edge：菜单不能画到状态栏 / 导航栏底下，定位时要把两侧 inset 让出来。
    // 这里读的是**当前窗口**的 inset（与 windowSize 同一坐标系）；读值发生在菜单打开期间，
    // 键盘弹出改变 inset 时菜单会跟着重新夹取。
    val insets = WindowInsets.systemBars
    val topInsetPx = with(density) { insets.getTop(density).toInt() }
    val bottomInsetPx = with(density) { insets.getBottom(density).toInt() }
    // 与玻璃岛同一档表面色（0.8）：菜单同样压在滚动的曲库上，太透会看不清文字。
    val glassSurface = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
    val menuScale = remember { Animatable(0.85f) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        menuScale.snapTo(0.85f)
        menuScale.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 650f))
    }
    // 等一帧再请求焦点：FocusRequester 要等节点完成布局才可寻址，立刻调用会拿不到焦点。
    LaunchedEffect(Unit) {
        withFrameNanos { }
        focusRequester.requestFocus()
    }
    fun closeAnd(action: () -> Unit) {
        action()
        onDismiss()
    }
    // 菜单卡片尺寸（宽固定 240，高度按项数实测）：定位要用它。
    // 首帧量不到时整张菜单先隐身，量到后再显现——否则会先在错误位置闪一下再跳过去。
    var menuCardSize by remember { mutableStateOf(IntSize.Zero) }
    val measured = menuCardSize != IntSize.Zero
    val anchor = menuTopLeft(
        touchPoint = request.touchPoint,
        menuSize = menuCardSize,
        windowSize = windowSize,
        edgePaddingPx = edgePaddingPx,
        topInsetPx = topInsetPx,
        bottomInsetPx = bottomInsetPx
    )
    Box(Modifier.fillMaxSize()) {
        // 点外关闭：全屏透明 scrim。无涟漪、无颜色，纯命中区。
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )
        Box(
            modifier = Modifier
                // anchor 是**卡片**左上角；外面还有一圈阴影安全区，所以整体往左上退一个 padding。
                .offset { IntOffset(anchor.x - edgePaddingPx, anchor.y - edgePaddingPx) }
                .padding(Dimens.MenuEdgePadding)
                // 吃掉卡片自身的点击（含内边距空白区），别让它穿透到后面的 scrim 把菜单关掉。
                // 行内按钮是更内层的 clickable，点击仍归它们。
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .focusRequester(focusRequester)
                .focusable()
                .graphicsLayer {
                    scaleX = menuScale.value
                    scaleY = menuScale.value
                    // 只有量到尺寸后才可见（见上）；alpha 恒为 1 时不触发离屏合成，阴影不会闪。
                    alpha = if (measured) 1f else 0f
                }
        ) {
            GlassPanel(
                backdrop = backdrop,
                surface = glassSurface,
                shape = RoundedCornerShape(Dimens.CornerLarge),
                modifier = Modifier
                    .width(MenuWidth)
                    // 量卡片本身（不含阴影安全区），定位按它算。
                    .onSizeChanged { menuCardSize = it }
            ) {
                Column(Modifier.fillMaxWidth()) {
                    var menuItemVisible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { menuItemVisible = true }
                    val menuView = LocalView.current
                    fun tapFeedback() = menuView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    StaggeredMenuItem(
                        visible = menuItemVisible,
                        delayMs = 0,
                        icon = { Icon(Icons.Filled.PlaylistAdd, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                        text = "添加到播放队列",
                        onClick = {
                            tapFeedback()
                            closeAnd { onAddToQueue() }
                        }
                    )
                    fileOps?.let { ops ->
                        StaggeredMenuItem(
                            visible = menuItemVisible,
                            delayMs = 40,
                            icon = { Icon(Icons.Filled.Edit, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                            text = "改名",
                            onClick = {
                                tapFeedback()
                                closeAnd { ops.onRename(track) }
                            }
                        )
                        // 正在播的那首删除置灰：文件没了播放器缓冲还在，是诡异 bug 高发区（spec 铁律 3）
                        val deleteEnabled = track.id != currentTrackId
                        StaggeredMenuItem(
                            visible = menuItemVisible,
                            delayMs = 80,
                            icon = {
                                Icon(
                                    Icons.Filled.Delete,
                                    null,
                                    Modifier.size(20.dp),
                                    // 删除项一律 error 色（spec §10/§11 的「删除类操作统一 error 色」）；
                                    // 置灰是整项降透明，颜色本身不再换成 onSurfaceVariant，免得看起来像普通项。
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            text = "删除",
                            enabled = deleteEnabled,
                            textColor = MaterialTheme.colorScheme.error,
                            onClick = {
                                if (deleteEnabled) {
                                    tapFeedback()
                                    closeAnd { ops.onDelete(track) }
                                }
                            }
                        )
                    }
                    multiSelect?.let { ms ->
                        StaggeredMenuItem(
                            visible = menuItemVisible,
                            delayMs = 120,
                            icon = { Icon(Icons.Filled.Checklist, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                            text = "多选",
                            onClick = {
                                tapFeedback()
                                closeAnd { ms.onEnter(track.id) }
                            }
                        )
                    }
                }
            }
        }
        // 返回键关菜单：声明在页面导航的 BackHandler 之后（组合顺序靠后 = 优先响应），
        // 所以菜单开着时按返回只关菜单，不会退回上一页。
        BackHandler { onDismiss() }
    }
}

/** 长按菜单的一次弹出请求：哪首歌、锚在哪个触点、以及「加到队列」这个动作。
 * 文件操作 / 多选等回调不进这里——那些由 overlay 处从 CompositionLocal 现取，避免每行都带一份；
 * 「是不是当前曲目」也不在这里定死，比较发生在渲染时（播放中切歌不会留下过期状态）。 */
private class TrackMenuRequest(
    val track: Track,
    val touchPoint: IntOffset,
    val onAddToQueue: () -> Unit
)

/** 长按菜单的内容宽度（spec §10：240dp）。 */
private val MenuWidth = 240.dp

/** 单曲行的文件操作对话框：改名（三个预填输入框）+ 删除二次确认。
 * 提交中禁用按钮且不许关闭（照 QQ 下载弹窗的防误关写法）；失败原因——含「密钥不对」
 * 的专门提示——就显示在弹窗里，关掉弹窗才算放弃。 */
@Composable
private fun TrackFileOpsDialogs(
    renameTarget: Track?,
    deleteTarget: List<Track>,
    state: JellyfinUiState,
    onRename: (Track, String?, String?, String?) -> Unit,
    onDelete: (List<Track>) -> Unit,
    onDismissRename: () -> Unit,
    onDismissDelete: () -> Unit
) {
    renameTarget?.let { target ->
        // 预填当前值；按曲目 id 记状态，换目标自动重置
        var title by remember(target.id) { mutableStateOf(target.title) }
        var artist by remember(target.id) { mutableStateOf(target.artist) }
        var album by remember(target.id) { mutableStateOf(target.album) }
        AlertDialog(
            onDismissRequest = { if (!state.isFileOpsBusy) onDismissRename() },
            // 键盘处理（spec §10）：Compose 的 Dialog 在 decorFitsSystemWindows = true（默认）时
            // 不设 adjustResize，IME 会直接盖住弹窗底部。关掉它 + 给弹窗面加 imePadding，
            // 键盘弹出时整块内容上移，确认 / 取消按钮始终留在键盘上方。
            properties = DialogProperties(decorFitsSystemWindows = false),
            modifier = Modifier.imePadding(),
            title = { Text("修改歌曲信息") },
            text = {
                // 输入区可滚动（spec §10）：键盘弹出后窗口变矮，三个输入框 + 提示会撑不下。
                // 按钮区在 AlertDialog 的按钮槽里——material3 把 text 槽放进 `weight(1f, fill = false)`
                // 的 Box，先保证按钮拿到位置、剩余空间才给内容，所以按钮天然在滚动区外、不会被键盘顶走。
                // 这里不再额外写高度上限：上限来自可用空间，写死 dp 反而会让大屏无谓地早滚动。
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall)
                ) {
                    OutlinedTextField(title, { title = it }, label = { Text("歌名") }, singleLine = true, enabled = !state.isFileOpsBusy)
                    OutlinedTextField(artist, { artist = it }, label = { Text("歌手") }, singleLine = true, enabled = !state.isFileOpsBusy)
                    OutlinedTextField(album, { album = it }, label = { Text("专辑") }, singleLine = true, enabled = !state.isFileOpsBusy)
                    state.fileOpsMessage?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        "写入文件的内置标签，文件本身不动",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    // 至少一个字段非空才允许提交；提交中禁用
                    enabled = !state.isFileOpsBusy &&
                        (title.isNotBlank() || artist.isNotBlank() || album.isNotBlank()),
                    onClick = {
                        // 只发改过的字段：没动的传 null，服务端不碰那个标签
                        onRename(
                            target,
                            title.trim().takeIf { it != target.title },
                            artist.trim().takeIf { it != target.artist },
                            album.trim().takeIf { it != target.album }
                        )
                    }
                ) { Text(if (state.isFileOpsBusy) "保存中…" else "保存") }
            },
            dismissButton = {
                TextButton(enabled = !state.isFileOpsBusy, onClick = onDismissRename) { Text("取消") }
            }
        )
    }
    if (deleteTarget.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { if (!state.isFileOpsBusy) onDismissDelete() },
            title = { Text(if (deleteTarget.size == 1) "删除这首歌？" else "删除这 ${deleteTarget.size} 首歌？") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (deleteTarget.size == 1) {
                            "《${deleteTarget.first().title}》将从电脑曲库移除，挪进回收站——可在设置页恢复。"
                        } else {
                            "选中的 ${deleteTarget.size} 首将从电脑曲库移除，挪进回收站——可在设置页恢复。"
                        }
                    )
                    state.fileOpsMessage?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !state.isFileOpsBusy, onClick = { onDelete(deleteTarget) }) {
                    // 删除确认用 error 色（spec §10/§11）：与长按菜单里的删除项一致。
                    Text(
                        if (state.isFileOpsBusy) "删除中…" else "删除",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !state.isFileOpsBusy, onClick = onDismissDelete) { Text("取消") }
            }
        )
    }
}

/** 单个错峰弹出的菜单项：从下方冒出 + 淡入，图标轻微旋转汇聚。置灰时整体半透明且不可点。 */
@Composable
private fun StaggeredMenuItem(
    visible: Boolean,
    delayMs: Int,
    icon: @Composable () -> Unit,
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    /** 文字色。默认跟随玻璃面的 onSurface（[GlassPanel] 提供）；删除项传 error 色。 */
    textColor: Color = Color.Unspecified
) {
    val transition = updateTransition(visible, label = "menuItem")
    val offsetY by transition.animateDp(
        transitionSpec = { spring(dampingRatio = 0.7f, stiffness = 350f) },
        targetValueByState = { if (it) 0.dp else 12.dp },
        label = "offsetY"
    )
    val alpha by transition.animateFloat(
        transitionSpec = { tween(150, delayMillis = delayMs) },
        label = "alpha"
    ) { if (it) 1f else 0f }
    Row(
        modifier = Modifier
            // fillMaxWidth 必须在 clickable 外侧：不然行宽只有文字那点，菜单右半边点了没反应（真机反馈）。
            .fillMaxWidth()
            // 行高 48dp（spec §10）：正文行高 24 + 上下各 12 正好 48，字体放大时靠 heightIn 兜底。
            .heightIn(min = Dimens.TouchTargetSmall)
            .graphicsLayer { this.alpha = if (enabled) alpha else alpha * 0.38f }
            .offset(y = offsetY)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = textColor)
    }
}

/**
 * 发现页（工单 03）：个人化聚合首页，自上而下——
 * 最近播放 / 最常播放 / 最近添加 / 每日推荐 / 随机专辑。
 *
 * 布局语言取 Auxio 的列表项规格（封面 + 主副文本），尺寸走 [Dimens]；
 * 这一页在 Auxio 里没有参照（Auxio 的 Home 就是曲库列表本身），属自创。
 * 各 section 独立降级：某一节失败/为空只是那一节不出现，整页照常可看。
 * 每日推荐是本地计算（按天稳定随机 + 下拉换批），其余走服务端。
 */
@Composable
private fun DiscoverContent(
    state: JellyfinUiState,
    tracks: List<Track>,
    discoverAlbums: List<Album>?,
    isOfflineFiltered: Boolean,
    onOpenAlbum: (Album) -> Unit,
    coverFor: (Album) -> JellyfinRequest?,
    trackCoverFor: (Track) -> JellyfinRequest?,
    onPlayTrack: (List<Track>, String) -> Unit,
    onAddToQueue: (Track) -> Unit,
    currentTrackId: String?,
    isPlaying: Boolean,
    onRefresh: () -> Unit,
    onRefreshAlbums: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 换一批按钮转圈（spec §9）：**只在加载时**转，停的时候补到最近的 360° 整倍数再停。
    // 不用 rememberInfiniteTransition——它只要被组合就一直在跑动画帧（报告 #70，空转耗电），
    // 而且以前是 isLoading 一落 false 就把 rotate 摘掉，图标从当前角度瞬间回正（报告 #30）。
    val refreshRotation = remember { Animatable(0f) }
    val albumsLoading = state.isLoading
    LaunchedEffect(albumsLoading) {
        if (albumsLoading) {
            // 一直转到被取消（albumsLoading 翻回 false）为止：把当前角度当成新起点，连续转圈不跳。
            while (true) {
                refreshRotation.animateTo(
                    refreshRotation.value + 360f,
                    animationSpec = tween(800, easing = LinearEasing)
                )
            }
        } else {
            // 停下时补完整圈：目标取「下一个不小于当前角度的 360° 整倍数」，视觉上仍然在转、
            // 只是不再有下一次——不会突然回正。转完把角度归一化回 [0,360)，免得刷一天刷成大数。
            val target = ceil(refreshRotation.value / 360f) * 360f
            refreshRotation.animateTo(target, animationSpec = tween(240, easing = LinearEasing))
            refreshRotation.snapTo(refreshRotation.value % 360f)
        }
    }
    // 每日推荐：按当天日期稳定随机挑 4 首（同一天内跨重启不变）。
    // 下拉刷新递增变体编号，立刻换一批（挑选是纯本地计算，不等网络）。
    var picksVariant by rememberSaveable { mutableStateOf(0) }
    val dailyPicks = remember(tracks, picksVariant) {
        discoverDailyPicks(tracks, dayIndex = LocalDate.now().toEpochDay(), count = 4, variant = picksVariant)
    }
    // 这一页到底有哪些节（工单 12 / spec §7）：三节服务器历史离线时整个不显示，
    // 整页空态也是从这里来——不能像以前那样直接读原始 state 字段（报告 #22）。
    //
    // 这里传的是 `state.isOffline` 而不是 `isOfflineFiltered`，两个不是一回事，别换：
    // · `isOfflineFiltered` = 离线**且**已算出已下载 id 集合，表示"列表真的被筛过了"。
    //   下面「离线 · 只显示已下载」的提示和专辑空态用它——只在真筛过时才敢这么说。
    // · `state.isOffline` = 就是断网了。三节历史的隐藏规则是「离线就不显示」，
    //   与 id 集合算没算出来无关（spec §7：这是组合条件，不走 `VisibleLibrary` 过滤）。
    val sections = discoverSections(
        dailyPicks = dailyPicks,
        recentlyPlayed = state.recentlyPlayed,
        mostPlayed = state.mostPlayed,
        recentlyAdded = state.recentlyAdded,
        discoverAlbums = discoverAlbums,
        isOffline = state.isOffline,
        tracksLoaded = state.tracks != null,
        isLoading = state.isLoading
    )
    // 下拉刷新：随机专辑 + 三节播放历史一起重拉（ViewModel 里统一清字段放行幂等守卫），
    // 每日推荐是本地计算，递增变体编号立刻换一批。
    // 离线时不发网络请求（必然失败）：只重排本地的每日推荐与专辑墙（ADR-0008）。
    val pullRefreshState = rememberPullToRefreshState()
    // 刷新圈要落在状态栏和玻璃岛下方（报告 #23）。PullToRefreshBox 从自己的顶边排版，
    // 而它整个铺在屏幕顶端（父层只有 navigationBarsPadding），所以默认位置会被状态栏和玻璃岛盖住。
    //
    // 修法用 indicator 自带的 `maxDistance`，而不是再加一层 padding：indicator 会**自己**
    // 按拖拽进度从顶边往下平移，平移上限就是这个 `maxDistance`（默认值只够到状态栏附近）。
    // 再叠固定 padding 就成双重偏移——拖到底时会被顶到离岛很远的下方。
    // 把上限提到「状态栏 + 玻璃岛占位（岛高 + 上下外边距）」，落位正好露出在岛下面（spec §2）。
    val refreshIndicatorDistance = with(LocalDensity.current) {
        (WindowInsets.statusBars.getTop(this) + Dimens.GlassIslandTopSpacing.toPx()).toDp()
    }
    PullToRefreshBox(
        isRefreshing = state.isDiscoverRefreshing,
        onRefresh = {
            picksVariant++
            if (!isOfflineFiltered) onRefresh()
        },
        state = pullRefreshState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullRefreshState,
                isRefreshing = state.isDiscoverRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                maxDistance = refreshIndicatorDistance
            )
        },
        modifier = modifier.fillMaxSize()
    ) {
    LazyColumn(
        modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(
            start = Dimens.SpaceMedium,
            end = Dimens.SpaceMedium,
            top = Dimens.GlassIslandTopSpacing,
            bottom = Dimens.BottomContentInset
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceTiny)
    ) {
        // 三节服务器历史来自服务端，各自独立加载/失败（工单 02）：拉到了就显示，为空就整节隐藏；
        // 还没拉到且这一节没失败时显示横滑骨架——失败过的不再转圈（否则永远等下去）。
        //
        // **离线时这三节整节不组合**（连骨架和空态都不给，spec §7）：数据留在 state 里，回在线自动回来。
        // 「要不要画」= 有内容（[DiscoverSections] 判的，报告 #22）或 该给骨架——两者都不成立才整节缺席。
        fun historyLoading(section: String) =
            !state.isOffline && section !in state.discoverFailedSections
        if (sections.showRecentlyPlayed || historyLoading("recentlyPlayed")) {
            item(key = "recentlyPlayed") {
                DiscoverTrackStrip(
                    title = "最近播放",
                    tracks = state.recentlyPlayed,
                    showSkeleton = historyLoading("recentlyPlayed"),
                    trackCoverFor = trackCoverFor,
                    onPlayTrack = onPlayTrack,
                    onAddToQueue = onAddToQueue,
                    currentTrackId = currentTrackId,
                    isPlaying = isPlaying
                )
            }
        }
        if (sections.showMostPlayed || historyLoading("mostPlayed")) {
            item(key = "mostPlayed") {
                DiscoverTrackStrip(
                    title = "最常播放",
                    tracks = state.mostPlayed,
                    showSkeleton = historyLoading("mostPlayed"),
                    trackCoverFor = trackCoverFor,
                    onPlayTrack = onPlayTrack,
                    onAddToQueue = onAddToQueue,
                    currentTrackId = currentTrackId,
                    isPlaying = isPlaying
                )
            }
        }
        if (sections.showRecentlyAdded || historyLoading("recentlyAdded")) {
            item(key = "recentlyAdded") {
                DiscoverAlbumStrip(
                    title = "最近添加",
                    albums = state.recentlyAdded,
                    showSkeleton = historyLoading("recentlyAdded"),
                    onOpenAlbum = onOpenAlbum,
                    coverFor = coverFor
                )
            }
        }
        // 三节历史全部失败、且确实没东西可显示：单节失败是静默缺席（不打扰），全挂了才给一次错误 + 重试。
        // 有旧数据时（后台静默刷新失败）不弹错误——屏幕上还好好的，弹错误反而吓人。
        // 离线时不判：那三节本来就不显示，谈不上"加载失败"。
        if (!state.isOffline &&
            state.discoverFailedSections.size == 3 &&
            state.recentlyPlayed == null && state.mostPlayed == null && state.recentlyAdded == null
        ) {
            item(key = "historyError") {
                ErrorState("推荐内容暂时加载失败", onRetry = onRefresh)
            }
        }
        // ── 每日推荐 ─────────────────────────────────────────────
        item(key = "dailyHeader") {
            DiscoverSectionHeader("每日推荐", Modifier.padding(top = Dimens.SpaceMidLarge))
        }
        if (isOfflineFiltered) {
            item(key = "offlineNotice") { OfflineNoticeText("离线 · 只显示已下载") }
        }
        if (state.tracks == null) {
            // 会话还没恢复完：灰色波浪占位。不能走空态文案——那是"确实没有内容"，
            // 而这里是"还在连"，两者语义完全不同。
            item(key = "dailySkeleton") {
                TrackListSkeleton(count = 3, trailingSpace = true, modifier = Modifier.padding(top = Dimens.SpaceTiny))
            }
        } else if (!sections.showDailyPicks && sections.hasAnySection) {
            // 每日推荐没内容、但这一页别处还有东西可看（比如专辑墙有内容）：
            // 就地给一行小字说明为什么这一节是空的。
            //
            // **整页都空的时候这里不写**（报告 #25）：那由页面底部的空态独占
            // （`hasAnySection` 为 false 时走下面 `discoverEmpty`），
            // 否则同一个页面上会出现上下两段一模一样的提示。
            item(key = "dailyEmpty") {
                Text(
                    discoverEmptyNotice(isOffline = state.isOffline).message(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Dimens.SpaceMidMedium)
                )
            }
        }
        itemsIndexed(dailyPicks, key = { _, t -> "daily-${t.id}" }) { index, track ->
            TrackRow(
                track = track,
                cover = trackCoverFor(track),
                onClick = { onPlayTrack(dailyPicks, track.id) },
                onAddToQueue = { onAddToQueue(track) },
                showDivider = index != dailyPicks.lastIndex,
                isCurrent = currentTrackId == track.id,
                isPlaying = isPlaying,
                modifier = Modifier.fillMaxWidth()
            )
        }
        // ── 随机专辑（横滑封面墙）──────────────────────────────────
        item(key = "albumHeader") {
            DiscoverSectionHeader(
                "随机专辑",
                Modifier.padding(top = Dimens.SpaceMidLarge),
                action = {
                    IconButton(
                        onClick = onRefreshAlbums,
                        enabled = !state.isLoading,
                        modifier = Modifier.size(Dimens.TouchTargetSmall)
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "换一批专辑",
                            modifier = Modifier.rotate(refreshRotation.value)
                        )
                    }
                }
            )
        }
        val albums = discoverAlbums
        when {
            albums == null && state.isLoading -> {
                // 骨架屏：与历史 section 同一份横滑骨架（同宽同造型）。
                item(key = "albumSkeleton") {
                    DiscoverStripSkeleton()
                }
            }
            albums == null && state.errorMessage != null -> {
                item(key = "albumError") { ErrorState(state.errorMessage, onRetry = onRetry) }
            }
            albums.orEmpty().isEmpty() -> {
                item(key = "albumEmpty") {
                    EmptyState(if (isOfflineFiltered) "离线 · 手机里还没有下载的专辑" else "暂无专辑")
                }
            }
            else -> {
                val albumList = albums.orEmpty()
                // 换一批后按新批次内容淡入淡出替换，避免硬切；用本批专辑 id 拼接作为批次 key。
                val batchKey = albumList.joinToString(",") { it.id }
                item(key = "albumWall") {
                    Crossfade(targetState = batchKey, label = "discoverAlbumsBatch") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium),
                            contentPadding = PaddingValues(horizontal = 2.dp)
                        ) {
                            items(albumList, key = { it.id }) { album ->
                                DiscoverAlbumCard(album, coverFor(album), onOpenAlbum)
                            }
                        }
                    }
                }
            }
        }
        // 整页都没有可看的内容（登录了、服务端也确实没歌 / 离线且一首都没下载）：
        // 给一句明确空态，而不是一片空白。判定走 discoverSections（报告 #22）——
        // 离线时三节历史的残留缓存**不算**「有内容」，否则整页空态永远不出现。
        //
        // **空态全页只此一处**（报告 #25）：上面的 `dailyEmpty` 分支在整页空时会自动让位。
        // 文案里的入口必须真实存在（报告 #26）：搜索页是真的，「设置里刷新曲库」压根没有。
        if (sections.showPageEmpty) {
            item(key = "discoverEmpty") {
                EmptyState(discoverEmptyNotice(isOffline = state.isOffline).message())
            }
        }
    }
    }
}

/** 发现页 section 标题行（可选右侧动作），与库页标题同一层级。 */
@Composable
private fun DiscoverSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        action?.invoke()
    }
}

/**
 * 发现页的曲目横滑 section（最近播放 / 最常播放）：Auxio 的列表项语言压成竖卡——
 * 方形封面 + 歌名 + 歌手，点击直接播放（整条队列 = 这一节）。
 * tracks == null 且 [showSkeleton] 时显示骨架；为 null 且不显示骨架（失败/离线）时整节隐藏。
 */
@Composable
private fun DiscoverTrackStrip(
    title: String,
    tracks: List<Track>?,
    showSkeleton: Boolean,
    trackCoverFor: (Track) -> JellyfinRequest?,
    onPlayTrack: (List<Track>, String) -> Unit,
    onAddToQueue: (Track) -> Unit,
    currentTrackId: String?,
    isPlaying: Boolean
) {
    if (tracks != null && tracks.isEmpty()) return
    if (tracks == null && !showSkeleton) return
    Column {
        DiscoverSectionHeader(title, Modifier.padding(top = Dimens.SpaceMidLarge))
        if (tracks == null) {
            DiscoverStripSkeleton()
            return@Column
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            items(tracks, key = { it.id }) { track ->
                DiscoverTrackCard(
                    track = track,
                    cover = trackCoverFor(track),
                    onClick = { onPlayTrack(tracks, track.id) },
                    onAddToQueue = { onAddToQueue(track) },
                    isCurrent = currentTrackId == track.id,
                    isPlaying = isPlaying
                )
            }
        }
    }
}

/** 发现页的专辑横滑 section（最近添加）。 */
@Composable
private fun DiscoverAlbumStrip(
    title: String,
    albums: List<Album>?,
    showSkeleton: Boolean,
    onOpenAlbum: (Album) -> Unit,
    coverFor: (Album) -> JellyfinRequest?
) {
    if (albums != null && albums.isEmpty()) return
    if (albums == null && !showSkeleton) return
    Column {
        DiscoverSectionHeader(title, Modifier.padding(top = Dimens.SpaceMidLarge))
        if (albums == null) {
            DiscoverStripSkeleton()
            return@Column
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            items(albums, key = { it.id }) { album ->
                DiscoverAlbumCard(album, coverFor(album), onOpenAlbum)
            }
        }
    }
}

/** 横滑 section 的骨架：三张与真卡同宽的占位（封面方块 + 两根文字条）。
 *
 * 用**不滚动**的 [LazyRow]：普通 `Row` 在宽度不够时会把子项**压窄**（报告 #24——3×140+2×12=444dp
 * 塞不进 328dp 的内容宽，第三张被压成一条），而 LazyRow 保持子项自身宽度、由视口裁切，
 * 第三张露出大半截——与真卡横滑时被屏幕裁掉一部分的样子一致（用户故事 52）。 */
@Composable
private fun DiscoverStripSkeleton() {
    val shimmer = rememberSkeletonShimmer()
    LazyRow(
        modifier = Modifier.padding(top = Dimens.SpaceSmall),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium),
        userScrollEnabled = false
    ) {
        items(3) {
            Column(Modifier.width(DiscoverCardWidth)) {
                SkeletonBox(
                    Modifier.fillMaxWidth().aspectRatio(1f),
                    progress = shimmer,
                    shape = RoundedCornerShape(Dimens.CornerLarge)
                )
                Spacer(Modifier.height(Dimens.SpaceSmall))
                SkeletonBox(
                    Modifier.fillMaxWidth(0.8f).height(14.dp),
                    progress = shimmer,
                    shape = RoundedCornerShape(4.dp)
                )
                Spacer(Modifier.height(Dimens.SpaceTiny))
                SkeletonBox(
                    Modifier.fillMaxWidth(0.5f).height(11.dp),
                    progress = shimmer,
                    shape = RoundedCornerShape(4.dp)
                )
            }
        }
    }
}

/** 发现页横滑卡片的统一宽度（Auxio hero_small 128dp 附近，Pulsar 沿用 140dp 的既有手感）。 */
private val DiscoverCardWidth = 140.dp

@Composable
private fun DiscoverTrackCard(
    track: Track,
    cover: JellyfinRequest?,
    onClick: () -> Unit,
    onAddToQueue: () -> Unit,
    isCurrent: Boolean,
    isPlaying: Boolean
) {
    Column(
        modifier = Modifier
            .width(DiscoverCardWidth)
            .clip(RoundedCornerShape(Dimens.CornerLarge))
            .combinedClickable(onClick = onClick, onLongClick = onAddToQueue)
    ) {
        Box {
            AlbumCover(
                cover,
                track.title,
                Modifier.fillMaxWidth().aspectRatio(1f),
                cornerRadius = Dimens.CornerLarge
            )
            // 正在播放：封面右下角的均衡器指示，与列表行同一套语言。
            if (isCurrent) {
                PlayingIndicatorOverlay(
                    isPlaying = isPlaying,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(Dimens.SpaceSmall)
                        .size(24.dp)
                        .clip(CircleShape)
                )
            }
        }
        Text(
            track.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Dimens.SpaceSmall)
        )
        Text(
            track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DiscoverAlbumCard(
    album: Album,
    cover: JellyfinRequest?,
    onOpenAlbum: (Album) -> Unit
) {
    Column(
        modifier = Modifier
            .width(DiscoverCardWidth)
            .clip(RoundedCornerShape(Dimens.CornerLarge))
            .clickable { onOpenAlbum(album) }
    ) {
        AlbumCover(
            cover,
            album.name,
            Modifier.fillMaxWidth().aspectRatio(1f),
            cornerRadius = Dimens.CornerLarge
        )
        Text(
            album.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Dimens.SpaceSmall)
        )
        Text(
            album.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 专辑网格卡（Auxio 封面网格语言）：方形封面 + 专辑名 + 「歌手 · N 首」。
 * 专辑页与艺术家详情页共用，避免两处各写一份卡片样式。 */
@Composable
private fun AlbumGridCard(
    album: Album,
    cover: JellyfinRequest?,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = cardBorderColor(),
                shape = RoundedCornerShape(Dimens.CornerLarge)
            ),
        shape = RoundedCornerShape(Dimens.CornerLarge)
    ) {
        Column {
            AlbumCover(
                cover,
                album.name,
                Modifier.fillMaxWidth().aspectRatio(1f),
                cornerRadius = Dimens.CornerLarge
            )
            Column(
                Modifier.padding(Dimens.SpaceMidMedium),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceTiny)
            ) {
                // 标题固定两行（spec §5）：短名也占两行高度，网格底边不会参差。
                Text(
                    album.name,
                    style = MaterialTheme.typography.titleMedium,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                // 副标题次要色、只占一行，不和标题抢注意力（spec §11）。
                Text(
                    album.trackCount?.let { "${album.artist} · $it 首" } ?: album.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 专辑网格卡骨架：与 [AlbumGridCard] 同构——方形封面 + 12dp 内边距 + 两行标题 + 一行副标题。
 * 文字区高度直接取真实排版的行高（`titleMedium` / `bodyMedium` 的 lineHeight，随系统字体缩放走），
 * 间距同为 [Dimens.SpaceTiny]——专辑页与艺术家详情共用，加载完成卡高不跳（spec §5）。
 * [progress] 由调用方取一次传给整屏所有卡，扫光同步。
 */
@Composable
private fun AlbumCardSkeleton(progress: State<Float>) {
    // 用真实行高而不是写死 24/20dp：系统字号调大时，真实卡和骨架仍一起变高。
    val titleLineHeight = with(LocalDensity.current) { MaterialTheme.typography.titleMedium.lineHeight.toDp() }
    val subtitleLineHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = cardBorderColor(),
                shape = RoundedCornerShape(Dimens.CornerLarge)
            ),
        shape = RoundedCornerShape(Dimens.CornerLarge)
    ) {
        Column {
            SkeletonBox(
                Modifier.fillMaxWidth().aspectRatio(1f),
                progress = progress,
                shape = RoundedCornerShape(Dimens.CornerLarge)
            )
            Column(
                Modifier.padding(Dimens.SpaceMidMedium),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceTiny)
            ) {
                // 两条标题行高的灰条紧贴，合起来正好是标题固定两行的高度。
                Column {
                    SkeletonBox(
                        Modifier.fillMaxWidth(0.7f).height(titleLineHeight),
                        progress = progress,
                        shape = RoundedCornerShape(4.dp)
                    )
                    SkeletonBox(
                        Modifier.fillMaxWidth(0.45f).height(titleLineHeight),
                        progress = progress,
                        shape = RoundedCornerShape(4.dp)
                    )
                }
                SkeletonBox(
                    Modifier.fillMaxWidth(0.5f).height(subtitleLineHeight),
                    progress = progress,
                    shape = RoundedCornerShape(4.dp)
                )
            }
        }
    }
}

/** 专辑网格骨架：2 列、间距与真实网格一致，卡片与 [AlbumGridCard] 同构。
 * 专辑页与艺术家详情页共用，两处骨架不会各走各的（工单 08）。
 *
 * 内部用普通 `Column` + `Row` 摆 2 列网格，而不是再嵌一个 `LazyVerticalGrid`：这一块要能放进
 * 详情骨架的网格 item 里（工单 11）——同向嵌套的可滚动容器在无界高度下会崩，而且骨架本来就
 * 只需要画有限的几张卡。
 *
 * **不画假头部**（工单 10 原则）：详情页的头部（封面 + 标题 + 副标题）永远画真的，这里只管卡片。
 */
@Composable
private fun AlbumGridSkeleton(modifier: Modifier = Modifier, cards: Int = 6) {
    val shimmer = rememberSkeletonShimmer()
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
    ) {
        // 每行两张卡：横向间距与真实网格一致。
        repeat((cards + 1) / 2) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)) {
                repeat(2) { column ->
                    val index = row * 2 + column
                    // 奇数张时最后一张只占一半宽，另一半点个等宽的不可见占位保住列宽。
                    Box(Modifier.weight(1f)) {
                        if (index < cards) AlbumCardSkeleton(shimmer)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumsContent(
    state: JellyfinUiState,
    albums: List<Album>?,
    offlineNotice: String?,
    onOpenAlbum: (Album) -> Unit,
    coverFor: (Album) -> JellyfinRequest?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        // 顶部只让出状态栏；标题行自己给玻璃岛让位（padding top），
        // grid 滚动时专辑卡仍能滚到玻璃岛底下透过去（和底部播放条同款行为）。
        modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Dimens.SpaceMedium),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
    ) {
        // 标题行：与发现页同款层级；专辑页没有「换一批」语义，只放标题。
        Text(
            "专辑",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.GlassIslandTopSpacing - Dimens.SpaceSmall)
        )
        offlineNotice?.let { notice -> OfflineNoticeText(notice) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                albums == null && state.isLoading -> {
                    // 骨架屏：与 AlbumGridCard 同构（封面方块 + 两行标题 + 一行副标题），切换不跳变。
                    // 骨架在 Box 里从**顶部**开始排（默认 TopStart），不加居中——居中会让它加载完瞬移。
                    // 底部留白与真实网格一致，最后一行不会钻到迷你播放条下面。
                    AlbumGridSkeleton(
                        modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.BottomContentInset)
                    )
                }
                albums == null && state.errorMessage != null -> ErrorState(state.errorMessage, onRetry = onRetry)
                albums.orEmpty().isEmpty() -> EmptyState(
                    if (offlineNotice != null) "离线 · 手机里还没有下载的专辑" else "暂无专辑"
                )
                else -> {
                    val albumList = albums.orEmpty()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = Dimens.BottomContentInset),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
                    ) {
                        items(albumList, key = { it.id }) { album ->
                            AlbumGridCard(album, coverFor(album), onClick = { onOpenAlbum(album) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * 详情页共用骨架（工单 10，spec §6）：专辑详情与播放列表详情用同一套页面语言。
 *
 * 整页一个 [LazyColumn]，顺序固定：「真实头部（永远不骨架）→ 操作行 → 下载槽 → 离线提示 →
 * 曲目状态（骨架 / 错误 / 空 / 真实行）」。真实头部只画一次——加载时不重复画假头部再换掉。
 *
 * 头部规格：封面 `fillMaxWidth(0.62f)` + 1:1 + [Dimens.CornerLarge]；标题 `headlineSmall`
 * 粗体居中最多两行；副标题 `bodyLarge` 次要色一行；摘要 `bodySmall` 次要色；播放全部 + 随机
 * 播放按钮，间距 [Dimens.SpaceMidMedium]；下载槽在其下 [Dimens.SpaceMidMedium]；离线提示再往下。
 *
 * 差异交给调用方：背景（专辑有封面色晕、播放列表没有）、播放按钮文案（「播放」/「播放全部」）、
 * 下载槽（离线时传 null 即隐藏）、曲目的骨架 / 错误 / 空态（[body]）。
 *
 * [showActions] 为 false 时不画「播放 / 随机播放」操作行（工单 11）：艺术家组详情列的是专辑，
 * 没有「整页播放」这个语义，头部其余部分与专辑/播放列表完全一致。这时 [playLabel] /
 * [playable] / [onPlay] / [onShuffle] 都不参与渲染，调用方传占位值即可。
 */
@Composable
private fun MediaDetailScaffold(
    cover: JellyfinRequest?,
    coverDescription: String?,
    title: String,
    subtitle: String,
    summary: String,
    playLabel: String,
    playable: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    download: (@Composable () -> Unit)?,
    offlineNotice: String?,
    /** false 时不画「播放 / 随机播放」操作行（工单 11：艺术家组详情列的是专辑，没有整组播放语义）。 */
    showActions: Boolean = true,
    /** 曲目行之间的间距；必须与骨架一致，加载完成不跳。专辑详情不额外留距（[Arrangement.spacedBy] 0）。 */
    tracksArrangement: Arrangement.Vertical = Arrangement.spacedBy(0.dp),
    /** 头部下方的间距：曲目列表的骨架自己带行距、传 0dp；网格骨架是一张整块、传 [Dimens.SpaceMidMedium]。 */
    contentArrangement: Arrangement.Vertical = tracksArrangement,
    /** 网格变体（工单 11）：非 null 时内容区改用 [gridColumns] 列的网格，调用方改传 [gridBody]。
     * 头部照常跨满整行。列表变体（专辑/播放列表）留 null，继续走 [body]。 */
    gridColumns: Int? = null,
    background: (@Composable BoxScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** 网格变体的内容（艺术家组详情用）：调用方同时传 [gridColumns]。
     * 放在 [body] 之前——尾随 lambda 永远绑到最后一个参数 [body]，列表调用方不用改写法。 */
    gridBody: LazyGridScope.() -> Unit = {},
    body: LazyListScope.() -> Unit = {}
) {
    Box(modifier.fillMaxSize()) {
        background?.invoke(this)
        val contentPadding = PaddingValues(
            start = Dimens.SpaceMedium,
            end = Dimens.SpaceMedium,
            top = Dimens.SpaceMedium,
            bottom = Dimens.BottomContentInset
        )
        if (gridColumns != null) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns),
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = contentPadding,
                verticalArrangement = contentArrangement,
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
            ) {
                // 头部跨满整行：网格变体的头部与列表变体是同一份（抽取成 [MediaDetailHeader]）。
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    MediaDetailHeader(
                        cover, coverDescription, title, subtitle, summary,
                        playLabel, playable, onPlay, onShuffle, download, offlineNotice, showActions
                    )
                }
                // 网格语义的 body：整屏状态块（骨架/错误/空态）用 [fullRow] 跨满整行，
                // 免得被切成半屏宽。
                gridBody()
            }
        } else {
            LazyColumn(
                // 详情页顶部玻璃岛已隐藏：只让出状态栏高度。
                Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = contentPadding,
                verticalArrangement = contentArrangement
            ) {
                item(key = "header") {
                    MediaDetailHeader(
                        cover, coverDescription, title, subtitle, summary,
                        playLabel, playable, onPlay, onShuffle, download, offlineNotice, showActions
                    )
                }
                body()
            }
        }
    }
}

/** 网格里占满整行的一项（跨 [maxLineSpan] 列）：骨架屏 / 错误态 / 空态这类整屏内容用它，
 * 免得被多列网格切成半屏宽（工单 11）。本文件内的详情骨架专用。 */
private fun LazyGridScope.fullRow(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/** 详情页头部（工单 10 / 11）：大封面 + 居中标题 + 副标题 + 摘要 + 可选的播放/随机操作行、
 * 下载槽、离线提示。列表变体与网格变体共用同一份，头部永远不骨架。 */
@Composable
private fun MediaDetailHeader(
    cover: JellyfinRequest?,
    coverDescription: String?,
    title: String,
    subtitle: String,
    summary: String,
    playLabel: String,
    playable: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    download: (@Composable () -> Unit)?,
    offlineNotice: String?,
    showActions: Boolean
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AlbumCover(
            cover,
            coverDescription,
            Modifier.fillMaxWidth(0.62f).aspectRatio(1f),
            cornerRadius = Dimens.CornerLarge
        )
        Spacer(Modifier.height(Dimens.SpaceLarge))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            // 长名字最多两行：头部在内容流里会随页面滚走，不再把下方内容挤没。
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(Dimens.SpaceTiny))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(Dimens.SpaceTiny))
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (showActions) {
            Spacer(Modifier.height(Dimens.SpaceMedium))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)) {
                Button(onClick = onPlay, enabled = playable) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Dimens.SpaceSmall))
                    Text(playLabel)
                }
                FilledTonalButton(onClick = onShuffle, enabled = playable) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Dimens.SpaceSmall))
                    Text("随机播放")
                }
            }
        }
        // 下载槽（工单 05 / 10）：四态由 DownloadActionButton 自己算。离线时调用方传 null。
        if (download != null) {
            Spacer(Modifier.height(Dimens.SpaceMidMedium))
            download()
        }
        offlineNotice?.let { notice ->
            Spacer(Modifier.height(Dimens.SpaceMidMedium))
            OfflineNoticeText(notice)
        }
        Spacer(Modifier.height(Dimens.SpaceLarge))
    }
}

@Composable
private fun AlbumDetailContent(
    state: JellyfinUiState,
    album: Album,
    albumTracks: List<Track>?,
    offlineNotice: String?,
    coverFor: (Track) -> JellyfinRequest?,
    onPlayTrack: (List<Track>, String) -> Unit,
    onAddToQueue: (Track) -> Unit,
    albumCoverFor: (Album) -> JellyfinRequest?,
    onRetry: () -> Unit,
    currentTrackId: String?,
    isPlaying: Boolean,
    streamUrl: (Track) -> String?,
    onDownloadMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 详情页顶部玻璃岛已隐藏：只让出状态栏高度，不用再给浮岛留位。
    // Apple Music 式专辑页：整页染上这张专辑的封面主色（顶部柔光向下淡出），
    // 大封面 + 居中的专辑信息 + 播放/随机按钮，然后才是曲目列表；整页一起滚。
    val darkTheme = isSystemInDarkTheme()
    val albumTheme = rememberCoverGradient(albumCoverFor(album), darkTheme)
    val trackList = albumTracks.orEmpty()
    val playable = trackList.isNotEmpty()
    // 离线视图（ADR-0008）：只在真正过滤了列表时（offlineNotice 非空）才隐藏下载按钮、
    // 摘要改「已下载 N 首 · 共 M 首」、头部补一句离线提示——名单还没算出来时不筛，
    // 免得把全量当成已下载。
    val isOfflineView = offlineNotice != null
    MediaDetailScaffold(
        cover = albumCoverFor(album),
        coverDescription = album.name,
        title = album.name,
        subtitle = album.artist,
        // 曲目数优先取已加载的列表（服务端的 trackCount 可能滞后），时长按已加载曲目求和。
        summary = albumSummary(album, albumTracks, isOfflineView = isOfflineView),
        playLabel = "播放",
        playable = playable,
        onPlay = { onPlayTrack(trackList, trackList.first().id) },
        onShuffle = {
            val shuffled = trackList.shuffled()
            onPlayTrack(shuffled, shuffled.first().id)
        },
        download = if (isOfflineView) {
            null
        } else {
            {
                DownloadActionButton(
                    kind = DownloadKind.ALBUM,
                    id = album.id,
                    name = album.name,
                    tracks = trackList,
                    serverUrl = state.serverUrl,
                    streamUrl = streamUrl,
                    currentTrackId = currentTrackId,
                    onMessage = onDownloadMessage
                )
            }
        },
        offlineNotice = offlineNotice,
        // 专辑详情的真实曲目行紧挨着排（LazyColumn 无额外间距），骨架也别留行距。
        tracksArrangement = Arrangement.spacedBy(0.dp),
        background = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f)
                    .background(
                        Brush.verticalGradient(
                            // 主题色压得更实、铺得更满：上半屏基本就是这块专辑色，再向下淡出。
                            0f to albumTheme.gradient.top,
                            0.5f to albumTheme.gradient.top.copy(alpha = 0.8f),
                            1f to Color.Transparent
                        )
                    )
            )
        },
        modifier = modifier
    ) {
        when {
            albumTracks == null && state.isLoading -> item(key = "skeleton") {
                TrackListSkeleton(
                    count = 6,
                    leading = SkeletonLeading.Number,
                    rowSpacing = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            albumTracks == null && state.errorMessage != null -> item(key = "error") {
                ErrorState(state.errorMessage, onRetry = onRetry)
            }
            trackList.isEmpty() -> item(key = "empty") {
                EmptyState(if (isOfflineView) "离线 · 这张专辑没有下载的歌曲" else "该专辑暂无歌曲")
            }
            else -> itemsIndexed(trackList, key = { _, t -> t.id }) { index, track ->
                TrackRow(
                    track = track,
                    cover = coverFor(track),
                    onClick = { onPlayTrack(trackList, track.id) },
                    onAddToQueue = { onAddToQueue(track) },
                    showDivider = index != trackList.lastIndex,
                    isCurrent = currentTrackId == track.id,
                    isPlaying = isPlaying,
                    leadingNumber = index + 1,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 播放列表页（工单 05）：按 Auxio 的列表语言呈现，与歌曲页 / 搜索页同构——
 * 封面位 + 名称 + 「N 首」+ 分隔线，不再用卡片网格（封面网格只留给专辑）。 */
@Composable
private fun PlaylistsContent(
    state: JellyfinUiState,
    onOpenPlaylist: (Playlist) -> Unit,
    coverFor: (Playlist) -> JellyfinRequest?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playlists = state.playlists
    val playlistList = playlists.orEmpty()
    Column(
        modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Dimens.SpaceMedium),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
    ) {
        Text(
            "播放列表",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.GlassIslandTopSpacing - Dimens.SpaceSmall)
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                // 骨架要撑满：外层 Box 是居中对齐，wrap-content 的骨架会竖直居中，加载完从顶部开始就跳了。
                playlists == null && state.isLoading -> TrackListSkeleton(count = 8, modifier = Modifier.fillMaxSize())
                // 播放列表不进快照（规格「明确不做」）：没数据又断网时明说需要联网。
                // 已加载的照常显示——断网不该把现成的列表藏掉。
                playlists == null && state.isOffline -> EmptyState("播放列表需要联网才能查看")
                playlists == null && state.errorMessage != null -> ErrorState(state.errorMessage, onRetry = onRetry)
                playlistList.isEmpty() -> EmptyState("暂无播放列表")
                else -> {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = Dimens.BottomContentInset),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceTiny)
                    ) {
                        itemsIndexed(playlistList, key = { _, p -> p.id }) { index, playlist ->
                            SearchResultRow(
                                cover = coverFor(playlist),
                                title = playlist.name,
                                subtitle = playlist.trackCount?.let { "$it 首" } ?: "播放列表",
                                onClick = { onOpenPlaylist(playlist) },
                                showDivider = index != playlistList.lastIndex,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 合并搜索页（ADR-0006）：一个输入框，一次输入同时问曲库和 QQ 音乐。
 *
 * 结果固定顺序：曲库歌曲 → 曲库专辑 → 曲库播放列表 → 「QQ 音乐」总标题 → QQ 歌曲 → QQ 专辑。
 * QQ 分区永远排在曲库之后，不做位置漂移；QQ 侧失败只在分区内降级，绝不盖住曲库结果。
 */
@Composable
private fun SearchContent(
    state: JellyfinUiState,
    results: SearchResults?,
    isOfflineFiltered: Boolean,
    qqState: QQDownloadUiState,
    onSearchQueryChange: (String) -> Unit,
    trackCoverFor: (Track) -> JellyfinRequest?,
    albumCoverFor: (Album) -> JellyfinRequest?,
    playlistCoverFor: (Playlist) -> JellyfinRequest?,
    onRetry: () -> Unit,
    onPlayTrack: (List<Track>, String) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
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
    onRetryQqSearch: () -> Unit,
    currentTrackId: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Dimens.SpaceMedium)
            // 让出顶部玻璃岛高度（与列表页同款占位），搜索框固定在岛正下方。
            .padding(top = Dimens.GlassIslandTopSpacing),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
    ) {
        // 搜索框固定顶部：输入时两个 ViewModel 各自防抖后请求；非空时显示清除按钮。
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.SpaceSmall),
            shape = RoundedCornerShape(Dimens.CornerLarge),
            placeholder = { Text("搜索歌曲、专辑、播放列表，也能搜 QQ 音乐") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "搜索图标") },
            trailingIcon = if (state.searchQuery.isNotEmpty()) {
                {
                    IconButton(
                        onClick = { onSearchQueryChange("") },
                        modifier = Modifier.size(Dimens.TouchTargetSmall)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "清除搜索内容")
                    }
                }
            } else {
                null
            },
            singleLine = true
        )
        // 离线搜索标识：这轮曲库结果来自手机上的快照，且只含已下载（ADR-0008）。
        // QQ 是另一个服务，不受影响。
        if (state.isOfflineSearch) {
            Text(
                if (isOfflineFiltered) "曲库离线 · 只搜已下载；QQ 音乐不受影响"
                else "曲库离线 · 只搜手机上的快照；QQ 音乐不受影响",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // 「搜过了确实没有」= 结果非 null 且三类全空。null 是「还没拉到 / 拉失败」，
            // 不能当成曲库没有——那会谎报「曲库中没找到」。
            val librarySearchedEmpty = results != null &&
                results.tracks.isEmpty() && results.albums.isEmpty() && results.playlists.isEmpty()
            val libraryEmpty = results == null || librarySearchedEmpty
            // 「全空」= 两边都没结果。曲库空但 QQ 有结果时不能报空——那正是要下载的时候。
            val qqHasResults = qqState.songs.isNotEmpty() || qqState.albums.isNotEmpty()
            // 只有两边都空时才允许整页空态/错误态；QQ 有结果时曲库侧的问题不能把它盖掉。
            val bothEmpty = libraryEmpty && !qqHasResults
            val qqStatus = qqState.searchStatus
            when {
                state.searchQuery.isBlank() -> Text(
                    "输入关键词搜索音乐库",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.isSearching -> CircularProgressIndicator()
                libraryEmpty && qqState.isSearching -> CircularProgressIndicator()
                // 曲库侧的失败优先说：QQ 的错误不能让「曲库没搜成」看起来像「曲库没有」。
                bothEmpty && state.errorMessage != null -> ErrorState(state.errorMessage, onRetry = onRetry)
                bothEmpty && qqStatus != null -> Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall)
                ) {
                    Text(
                        if (librarySearchedEmpty) "曲库中没找到「${state.searchQuery}」"
                        else "曲库搜索未完成",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        qqStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    TextButton(onClick = onRetryQqSearch) { Text("重试") }
                }
                bothEmpty && state.isOfflineSearch ->
                    EmptyState(
                        if (isOfflineFiltered) "已下载的歌曲里没找到「${state.searchQuery}」\n联网后能搜全曲库"
                        else "快照里没找到「${state.searchQuery}」\n联网后能搜全曲库"
                    )
                bothEmpty && !qqState.isSearching -> EmptyState("未找到相关内容")
                else -> {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = Dimens.BottomContentInset),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceTiny)
                    ) {
                        if (results != null) {
                            if (results.tracks.isNotEmpty()) {
                                item { SearchSectionHeader("歌曲") }
                                itemsIndexed(results.tracks, key = { _, t -> t.id }) { index, track ->
                                    TrackRow(
                                        track = track,
                                        cover = trackCoverFor(track),
                                        onClick = { onPlayTrack(results.tracks, track.id) },
                                        onAddToQueue = { onAddToQueue(track) },
                                        showDivider = index != results.tracks.lastIndex,
                                        isCurrent = currentTrackId == track.id,
                                        isPlaying = isPlaying,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            if (results.albums.isNotEmpty()) {
                                item { SearchSectionHeader("专辑") }
                                itemsIndexed(results.albums, key = { _, a -> a.id }) { index, album ->
                                    SearchResultRow(
                                        cover = albumCoverFor(album),
                                        title = album.name,
                                        subtitle = album.trackCount?.let { "${album.artist} · $it 首" } ?: album.artist,
                                        onClick = { onOpenAlbum(album) },
                                        showDivider = index != results.albums.lastIndex,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            if (results.playlists.isNotEmpty()) {
                                item { SearchSectionHeader("播放列表") }
                                itemsIndexed(results.playlists, key = { _, p -> p.id }) { index, playlist ->
                                    SearchResultRow(
                                        cover = playlistCoverFor(playlist),
                                        title = playlist.name,
                                        subtitle = playlist.trackCount?.let { "$it 首" } ?: "播放列表",
                                        onClick = { onOpenPlaylist(playlist) },
                                        showDivider = index != results.playlists.lastIndex,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                        // QQ 分区永远在曲库之后。
                        qqSearchSection(
                            state = qqState,
                            libraryEmpty = librarySearchedEmpty,
                            onDownloadSong = onDownloadSong,
                            onRequestRedownload = onRequestRedownload,
                            onDownloadAlbum = onDownloadAlbum,
                            onSelectAlbumTracks = onSelectAlbumTracks,
                            onRetrySearch = onRetryQqSearch
                        )
                    }
                }
            }
        }
    }
    // 两个弹窗挂搜索页：只有这里能发起下载。状态仍由 QQDownloadViewModel 持有。
    QQSearchDialogs(
        state = qqState,
        onConfirmRedownload = onConfirmRedownload,
        onDismissRedownload = onDismissRedownload,
        onToggleAlbumTrack = onToggleAlbumTrack,
        onSelectAllAlbumTracks = onSelectAllAlbumTracks,
        onClearAlbumSelection = onClearAlbumSelection,
        onConfirmAlbumDownload = onConfirmAlbumDownload,
        onRetryAlbumTracks = onRetryAlbumTracks,
        onDismissAlbumPicker = onDismissAlbumPicker,
        onRetryCredential = onRetryCredential
    )
}

/** 搜索结果分区标题（与 QQ 下载页的区块标题同语言：小号、半粗、次要色）。 */
@Composable
private fun SearchSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Dimens.SpaceSmall, bottom = Dimens.SpaceTiny, start = Dimens.SpaceTiny)
    )
}

/** 搜索结果里的专辑 / 播放列表行（艺术家列表也用它）：和 [TrackRow] 共用 [MediaRowLayout]
 * 的槽位与分隔线基准，标题/副标题走 [MediaRowText]（工单 07）。 */
@Composable
private fun SearchResultRow(
    cover: JellyfinRequest?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showDivider: Boolean,
    modifier: Modifier = Modifier
) {
    MediaRowLayout(
        modifier = modifier,
        rowModifier = Modifier.clickable(onClick = onClick),
        showDivider = showDivider,
        leading = { AlbumCover(cover, title, Modifier.size(Dimens.TouchTargetSmall)) }
    ) {
        MediaRowText(title = title, subtitle = subtitle)
    }
}

@Composable
private fun PlaylistDetailContent(
    state: JellyfinUiState,
    playlist: Playlist,
    playlistTracks: List<Track>?,
    isOffline: Boolean,
    offlineNotice: String?,
    coverFor: (Track) -> JellyfinRequest?,
    onPlayTrack: (List<Track>, String) -> Unit,
    onAddToQueue: (Track) -> Unit,
    playlistCoverFor: (Playlist) -> JellyfinRequest?,
    onRetry: () -> Unit,
    currentTrackId: String?,
    isPlaying: Boolean,
    streamUrl: (Track) -> String?,
    onDownloadMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 与专辑详情共用同一套页面骨架（spec §6 / 工单 10）：大封面 + 居中标题 + 播放全部/随机 + 下载。
    // 区别只有专辑带封面色晕，播放列表保持本色背景。
    val trackList = playlistTracks.orEmpty()
    val playable = trackList.isNotEmpty()
    // 「已下载」口径只在真正过滤了列表时用（offlineNotice 非空），免得下载名单还没算出来时
    // 把全量当成已下载。
    val offlineView = offlineNotice != null
    MediaDetailScaffold(
        cover = playlistCoverFor(playlist),
        coverDescription = playlist.name,
        title = playlist.name,
        subtitle = "播放列表",
        // 曲目数与下方实际行数同源：已加载用列表长度，未加载才退回服务端计数。
        summary = playlistSummary(playlist, playlistTracks, isOfflineView = offlineView),
        playLabel = "播放全部",
        playable = playable,
        onPlay = { onPlayTrack(trackList, trackList.first().id) },
        onShuffle = {
            val shuffled = trackList.shuffled()
            onPlayTrack(shuffled, shuffled.first().id)
        },
        // 下载（工单 05）：曲目还没加载时按钮禁用（target 为 null）。
        // 离线时藏起来（ADR-0008）：下不了，且列表里已经全是下载过的。
        download = if (isOffline) {
            null
        } else {
            {
                DownloadActionButton(
                    kind = DownloadKind.PLAYLIST,
                    id = playlist.id,
                    name = playlist.name,
                    tracks = trackList,
                    serverUrl = state.serverUrl,
                    streamUrl = streamUrl,
                    currentTrackId = currentTrackId,
                    onMessage = onDownloadMessage
                )
            }
        },
        offlineNotice = offlineNotice,
        // 曲目行之间保持原来的 4dp：与下方骨架的 rowSpacing 一致，加载完成不跳。
        tracksArrangement = Arrangement.spacedBy(Dimens.SpaceTiny),
        modifier = modifier
    ) {
        when {
            // 离线读取本地下载清单期间给骨架；别先闪一句「没有下载」再变成曲目。
            playlistTracks == null && (state.isLoading || (isOffline && state.errorMessage == null)) ->
                item(key = "skeleton") {
                    // 只补曲目行骨架，头部已在上面；行首是封面槽、尾部留「更多」占位，
                    // 与真实 TrackRow 同构，加载完成不跳。
                    TrackListSkeleton(
                        count = 6,
                        trailingSpace = true,
                        rowSpacing = Dimens.SpaceTiny,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            // 离线且这个列表没下载过：这是视图边界，不是网络错误。
            // 重试必然失败，所以只给说明型空态，不给重试按钮（工单 09）。
            playlistTracks == null && isOffline -> item(key = "offline-empty") {
                EmptyState("离线 · 这个播放列表没有下载，需要联网才能查看")
            }
            playlistTracks == null && state.errorMessage != null -> item(key = "error") {
                ErrorState(state.errorMessage, onRetry = onRetry)
            }
            trackList.isEmpty() -> item(key = "empty") {
                EmptyState(
                    if (offlineView) "离线 · 这个播放列表还没有下载的歌曲" else "该播放列表暂无歌曲"
                )
            }
            else -> itemsIndexed(trackList, key = { _, t -> t.id }) { index, track ->
                TrackRow(
                    track = track,
                    cover = coverFor(track),
                    onClick = { onPlayTrack(trackList, track.id) },
                    onAddToQueue = { onAddToQueue(track) },
                    showDivider = index != trackList.lastIndex,
                    isCurrent = currentTrackId == track.id,
                    isPlaying = isPlaying,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 设置页：USB 独占输出、静音跳曲、定时关闭。控件状态与回调都来自 [ControllerState]，与播放条共用同一份数据源。 */
@Composable
private fun SettingsContent(
    state: JellyfinUiState,
    viewModel: JellyfinViewModel,
    controllerState: ControllerState,
    onOpenCachedCopies: () -> Unit,
    onOpenDownloadService: () -> Unit,
    themePreferences: ThemePreferences,
    seedSource: ThemeSeedSource,
    modifier: Modifier = Modifier
) {
    val controller = controllerState.controller
    // USB 音量滑块本地拖拽值：拖拽中用本地值，松手才发 SET_VOLUME；服务确认值
    // 变化时（且非拖拽中）同步回本地，避免滑块跳动。
    var usbVolumeLocal by remember { mutableStateOf(controllerState.usbVolumePercent) }
    var usbVolumeDragging by remember { mutableStateOf(false) }
    LaunchedEffect(controllerState.usbVolumePercent) {
        if (!usbVolumeDragging) usbVolumeLocal = controllerState.usbVolumePercent
    }
    // 定时关闭：进程级单例状态（剩余时间/是否启用），拖动时用本地预览值不跟随倒计时。
    val sleepState by SleepTimer.state.collectAsStateWithLifecycle()
    var sleepSliderMinutes by remember { mutableStateOf(0f) }
    var isSleepDragging by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = Dimens.SpaceMedium)
            // 底部留白放在 verticalScroll **之后**：加在可视区上的话是固定空隙，滚不动；
            // 加在滚动内容里，最后一张「下载」卡才能滚到迷你播放条上方（工单 01）。
            .verticalScroll(rememberScrollState())
            .padding(bottom = Dimens.BottomContentInset),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = Dimens.SpaceSmall, bottom = Dimens.SpaceSmall)
        )
        // 外观：手动兜底配色。永远显示（设置项不该忽隐忽现），有封面色时说明它只是兜底。
        SettingsSectionHeader("外观")
        AppearanceSection(themePreferences = themePreferences, seedSource = seedSource)
        // 播放相关开关收进一张卡：与 QQ 下载页的凭证/服务/密钥卡同一套卡片语言。
        SettingsSectionHeader("播放")
        SurfaceCard {
            // USB 独占开关：与普通系统 USB 路由信息分开显示。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("USB 独占输出", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        usbExclusiveStateLabel(controllerState),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = controllerState.usbExclusiveEnabled,
                    onCheckedChange = controllerState.onUsbExclusiveChange,
                    enabled = controller != null && !controllerState.usbExclusivePending
                )
            }
            // USB 独占输出音量：独占路径绕过系统音量、满刻度直送会削顶，
            // 提供 0..100 数字衰减（100 = bit-perfect 原样直送）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "USB 音量",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(88.dp)
                )
                Slider(
                    value = usbVolumeLocal.toFloat(),
                    onValueChange = {
                        usbVolumeLocal = it.roundToInt()
                        usbVolumeDragging = true
                    },
                    onValueChangeFinished = {
                        controllerState.onUsbVolumeChange(usbVolumeLocal)
                        usbVolumeDragging = false
                    },
                    valueRange = 0f..100f,
                    enabled = controllerState.usbExclusiveEnabled && controller != null,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Dimens.SpaceMidMedium))
                Text(
                    text = "$usbVolumeLocal%",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(52.dp)
                )
            }
            // 静音跳曲开关：检测音频尾部静音并自动跳到下一首。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("静音跳曲", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (controllerState.silenceSkipEnabled) "已开启" else "已关闭",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = controllerState.silenceSkipEnabled,
                    onCheckedChange = controllerState.onSilenceSkipChange,
                    enabled = controller != null
                )
            }
            // 回放增益：按每首歌的响度数据统一音量。直接读写 DataStore——播放服务订阅的是
            // 同一个 DataStore，不需要为它再走一套 MediaSession 自定义命令。
            val loudnessContext = LocalContext.current
            val loudnessPreferences = remember {
                LoudnessPreferences(loudnessContext.pulsarPreferencesDataStore)
            }
            val loudnessEnabled by loudnessPreferences.enabled
                .collectAsStateWithLifecycle(initialValue = LoudnessPreferences.DEFAULT_ENABLED)
            val loudnessScope = rememberCoroutineScope()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("回放增益", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (loudnessEnabled) "已开启（响的歌会被压低，整体安静一些）" else "已关闭",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = loudnessEnabled,
                    onCheckedChange = { enabled ->
                        // 写失败（磁盘满/DataStore 损坏）不能让协程带着异常冲出去：
                        // checked 读的是 DataStore，写失败时开关会自己弹回，日志留个痕迹。
                        loudnessScope.launch {
                            runCatching { loudnessPreferences.setEnabled(enabled) }
                                .onFailure { error ->
                                    if (error is CancellationException) throw error
                                    Log.w("MainActivity", "保存回放增益开关失败", error)
                                }
                        }
                    }
                )
            }
            // 定时关闭：拖动滑块设置分钟数，松手才真正启动/取消定时器。5 分钟一档（steps=11 表示 0-60 共 12 档）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("定时关闭", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(Dimens.SpaceMidMedium))
                Slider(
                    value = when {
                        isSleepDragging -> sleepSliderMinutes
                        sleepState.isActive -> (sleepState.remainingMs / 60_000f).coerceIn(0f, 60f)
                        else -> 0f
                    },
                    onValueChange = { sleepSliderMinutes = it; isSleepDragging = true },
                    onValueChangeFinished = {
                        val minutes = sleepSliderMinutes.roundToInt()
                        if (minutes <= 0) SleepTimer.cancel() else SleepTimer.start(minutes * 60_000L)
                        isSleepDragging = false
                    },
                    valueRange = 0f..60f,
                    steps = 11,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Dimens.SpaceMidMedium))
                Text(
                    text = when {
                        isSleepDragging -> "约 ${sleepSliderMinutes.roundToInt()} 分钟"
                        sleepState.isActive -> "剩余 ${formatPosition(sleepState.remainingMs)}"
                        else -> "未开启"
                    },
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        // QQ 音乐导入（ADR-0006 / ADR-0007）：QQ 下载退出顶层导航后从这里进，二级页与「已下载」同一套模式。
        // 不叫「下载」——那个词已经归手机上的下载功能了（ADR-0007）。
        SettingsSectionHeader("QQ 音乐导入")
        SurfaceCard(onClick = onOpenDownloadService) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("QQ 音乐导入", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "服务状态、音质、密钥与下载任务",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 回收站：删除的歌都先挪到这里（E:\file\Te_Music_deleted），能恢复、能真清空。
        SettingsSectionHeader("存储")
        SurfaceCard { RecycleBinSection(state = state, viewModel = viewModel) }
        // 下载（ADR-0007）：占用 + 首数 + 「已下载」入口。没有清空按钮——没有一键清空。
        SurfaceCard {
            DownloadsSection(state = state, viewModel = viewModel, onOpenCachedCopies = onOpenCachedCopies)
        }
    }
}

/** 设置页的「外观」区块（工单 13）：16 个预设色块 + 当前生效来源说明。
 * 色块选中立刻写 DataStore，PulsarTheme 重新推导，不用重启。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection(themePreferences: ThemePreferences, seedSource: ThemeSeedSource) {
    val current by themePreferences.preset.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    SurfaceCard {
        Text("配色", style = MaterialTheme.typography.titleMedium)
        Text(
            "仅在没有封面时生效；播放有封面的歌时以封面颜色为准",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // FlowRow 而不是固定 8 列：360dp 屏上 8 个 36dp 色块 + 间距会超出卡片宽度，
        // 换行交给布局自己算，窄屏自然折成 7+7+2。
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
            maxItemsInEachRow = 8
        ) {
            ThemePresets.forEach { preset ->
                val selected = current == preset.color.toArgb()
                Box(
                    modifier = Modifier
                        .size(Dimens.ThemeSwatch)
                        .clip(CircleShape)
                        .background(preset.color)
                        .border(
                            BorderStroke(
                                // 选中项用主题主色描边：灰/黄这类浅色块也有明显指示。
                                width = if (selected) Dimens.ThemeSwatchBorderSelected
                                else Dimens.ThemeSwatchBorder,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            ),
                            CircleShape
                        )
                        .clickable(
                            // 色块本身没有文字：把预设名交给读屏。
                            onClickLabel = preset.label
                        ) {
                            // 再点一次已选颜色 = 取消，回到「默认」。
                            scope.launch {
                                runCatching {
                                    themePreferences.setPreset(
                                        if (selected) null else preset.color.toArgb()
                                    )
                                }.onFailure { error ->
                                    if (error is CancellationException) throw error
                                    Log.w("MainActivity", "保存配色预设失败", error)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            // 勾必须压在任意预设色上：黑白按 WCAG 相对亮度的等对比点
                            // （0.179）二选一，不是按 0.5 直觉分界——橙/绿/青在 0.5 以下
                            // 但白勾仍然只有 2.x:1。
                            tint = if (preset.color.luminance() > 0.179f) Color.Black else Color.White,
                            modifier = Modifier.size(Dimens.ThemeSwatchCheck)
                        )
                    }
                }
            }
        }
        Text(
            "当前生效：" + when {
                seedSource == ThemeSeedSource.CoverVibrant ||
                    seedSource == ThemeSeedSource.CoverDominant -> "封面"
                seedSource == ThemeSeedSource.UserPreset -> "手动"
                else -> "默认"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 设置页分组标题（与 QQ 下载页的区块标题同语言：小号、半粗、次要色）。 */
@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Dimens.SpaceSmall, start = Dimens.SpaceTiny)
    )
}

/** 设置页的回收站区块：列表 + 单条恢复 + 清空（唯一不可逆的操作，二次确认写明后果）。 */
@Composable
private fun RecycleBinSection(state: JellyfinUiState, viewModel: JellyfinViewModel) {
    // 每次进设置页重拉一次：删除/恢复随时会改变回收站内容，不留旧值。
    LaunchedEffect(Unit) { viewModel.loadRecycle() }
    var showPurgeConfirm by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("回收站", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.recycleFiles.isEmpty()) "删除的歌曲在这里，可恢复"
                    else "${state.recycleFiles.size} 个文件 · ${formatBytes(state.recycleFiles.sumOf { it.size })}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = { showPurgeConfirm = true },
                enabled = state.recycleFiles.isNotEmpty() && !state.isFileOpsBusy
            ) { Text("清空", color = MaterialTheme.colorScheme.error) }
        }
        when {
            state.isRecycleLoading -> Text(
                "加载中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 加载失败/清空/恢复的反馈都在这格；列表为空且没消息才是真空态
            state.recycleFiles.isEmpty() && state.recycleMessage == null -> Text(
                "回收站是空的",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> {
                state.recycleMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.recycleFiles.forEach { file ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                file.relPath,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                formatBytes(file.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = { viewModel.restoreRecycle(file.relPath) },
                            enabled = !state.isFileOpsBusy
                        ) { Text("恢复") }
                    }
                }
            }
        }
    }
    if (showPurgeConfirm) {
        AlertDialog(
            onDismissRequest = { if (!state.isFileOpsBusy) showPurgeConfirm = false },
            title = { Text("清空回收站？") },
            text = {
                Text(
                    "将永久删除回收站里的 ${state.recycleFiles.size} 个文件，此操作不可恢复。"
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.isFileOpsBusy,
                    onClick = {
                        showPurgeConfirm = false
                        viewModel.purgeRecycle()
                    }
                ) { Text("清空（不可恢复）", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !state.isFileOpsBusy, onClick = { showPurgeConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 已下载列表页（CONTEXT.md「已下载列表」）：按专辑分组的完整副本。
 * 每张专辑可展开逐首删，也能整张删——删的是手机副本，歌还在电脑上（确认框说清，同 ADR-0003）。
 * 整张删按下载名单删（用户故事 10）：服务器后来新增的歌不在名单里，不会跟着没。 */
@Composable
private fun CachedCopiesContent(
    state: JellyfinUiState,
    viewModel: JellyfinViewModel,
    albumCoverFor: (Album) -> JellyfinRequest?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = Dimens.SpaceMedium)
            .padding(top = Dimens.SpaceSmall)
    ) {
        Text(
            "已下载",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.SpaceMidMedium)
        )
        val cachedAlbums = state.cachedAlbums
        when {
            cachedAlbums == null -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            cachedAlbums.isEmpty() -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) { EmptyState("还没有下载的音乐\n点专辑或播放列表上的「下载」，就能离线听") }
            else -> {
                var deleteTarget by remember { mutableStateOf<CachedAlbum?>(null) }
                val playingTrackId = viewModel.currentTrack.collectAsStateWithLifecycle().value?.id
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
                    contentPadding = PaddingValues(bottom = Dimens.BottomContentInset)
                ) {
                    items(cachedAlbums, key = { it.album.id }) { entry ->
                        DownloadedAlbumRow(
                            entry = entry,
                            cover = albumCoverFor(entry.album),
                            trackCoverFor = viewModel::trackCover,
                            playingTrackId = playingTrackId,
                            onDeleteTrack = viewModel::deleteDownloadedTrack,
                            onDeleteAlbum = { deleteTarget = entry }
                        )
                    }
                }
                deleteTarget?.let { target ->
                    // 删除跑在 ViewModel 里（导航切走也不中断，破坏性操作不能删一半）：忙碌位落回
                    // false 才算删完，这时才关框。关完由 MainActivity 统一弹「已删除 N 首」（工单 13）。
                    val deleting = state.isDeletingDownload
                    var deleteStarted by remember { mutableStateOf(false) }
                    // 两个 key 都要：只看 deleting 的话，删除在一帧内跑完（或 ViewModel 直接
                    // 提前返回、忙碌位从未变过）时本 effect 不会重跑，对话框就永远关不上了。
                    LaunchedEffect(deleting, deleteStarted) {
                        if (deleteStarted && !deleting) deleteTarget = null
                    }
                    AlertDialog(
                        onDismissRequest = { if (!deleting) deleteTarget = null },
                        title = { Text("删掉这张专辑的下载？") },
                        text = {
                            Column {
                                Text(
                                    "将删掉《${target.album.name}》在手机上下载的 ${target.tracks.size} 首。\n" +
                                        "歌还在电脑上一首没动；服务器后来新增的歌不会跟着删，正在播的那首会跳过。"
                                )
                                // 删除进度（用户故事 62）：逐首删要时间，只把按钮变灰没人看得懂。
                                if (deleting) DeleteProgressNotice()
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !deleting,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                onClick = {
                                    deleteStarted = true
                                    viewModel.deleteAlbumCopies(target.album)
                                }
                            ) { Text("删除") }
                        },
                        dismissButton = {
                            TextButton(enabled = !deleting, onClick = { deleteTarget = null }) { Text("取消") }
                        }
                    )
                }
            }
        }
    }
}

/** 已下载列表的一行：封面 + 专辑名 + 进度，右侧展开/整张删。点行体展开逐首列表。
 * 与设置页 / QQ 音乐导入页同用 [SurfaceCard]（列表里的卡片语言）。 */
@Composable
private fun DownloadedAlbumRow(
    entry: CachedAlbum,
    cover: JellyfinRequest?,
    trackCoverFor: (Track) -> JellyfinRequest?,
    playingTrackId: String?,
    onDeleteTrack: (Track) -> Unit,
    onDeleteAlbum: () -> Unit
) {
    var expanded by rememberSaveable(entry.album.id) { mutableStateOf(false) }
    SurfaceCard {
        Row(
            Modifier.fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlbumCover(cover, entry.album.name, Modifier.size(Dimens.TouchTargetLarge))
            Spacer(Modifier.width(Dimens.SpaceMidMedium))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.album.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${entry.album.artist} · 已下载 ${entry.tracks.size}/${entry.totalCount} 首",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            IconButton(onClick = onDeleteAlbum) {
                Icon(Icons.Filled.Delete, contentDescription = "删除这张专辑的下载")
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开曲目"
            )
        }
        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            entry.tracks.forEach { track ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AlbumCover(trackCoverFor(track), track.title, Modifier.size(Dimens.TouchTargetSmall))
                    Spacer(Modifier.width(Dimens.SpaceMidMedium))
                    Column(Modifier.weight(1f)) {
                        Text(
                            track.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (track.id == playingTrackId) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Unspecified
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (track.id == playingTrackId) "正在播放 · 不能删" else track.artist,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // 正在播的那首删不了：从播放流底下抽走会让声音当场断掉。
                    IconButton(
                        onClick = { onDeleteTrack(track) },
                        enabled = track.id != playingTrackId
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除这首的下载")
                    }
                }
            }
        }
    }
}

/** 设置页的「下载」区块（工单 06）：占用 + 首数 + 「已下载」入口。
 * **没有清空按钮**——没有「一键清空全部」是有意的摩擦（ADR-0007 / spec 铁律 2）。 */
@Composable
private fun DownloadsSection(
    state: JellyfinUiState,
    viewModel: JellyfinViewModel,
    onOpenCachedCopies: () -> Unit
) {
    // 每次进设置页重拉：下载随时在跑，数字不能停在旧值。
    LaunchedEffect(Unit) { viewModel.loadCacheStats() }
    val application = LocalContext.current.applicationContext as PulsarApplication
    val scheduler = remember(application) { application.downloadScheduler }
    val downloadStates by scheduler.state.collectAsStateWithLifecycle()
    // 失败提示（用户故事 20）：调度器只管本次运行，失败数如实汇总在这里。
    val failedTracks = downloadStates.values.sumOf { (it as? DownloadState.PartiallyFailed)?.failed ?: 0 }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("下载", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.cacheTrackCount == 0) "点专辑或播放列表上的「下载」，就能离线听"
                    else "${state.cacheTrackCount} 首 · ${formatBytes(state.cacheBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onOpenCachedCopies,
                enabled = state.cacheTrackCount > 0
            ) { Text("已下载") }
        }
        if (failedTracks > 0) {
            Text(
                "有 $failedTracks 首下载失败 · 去专辑页点「重试」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 文件大小的短表示：回收站 / 下载提示共用。 */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

/**
 * 迷你条封面位（固定 40dp）。单独抽一个 composable，是为了躲开 MiniPlayerBar 里同时存在的
 * Row / Column 作用域——在那里直接调 AnimatedVisibility 会同时命中 RowScope 与 ColumnScope
 * 两个重载而无法解析（编译报 cannot be called in this context with an implicit receiver）。
 *
 * [visible] 由调用方传全屏页开关的取反：迷你封面必须自己也随全屏页做一次进出场，
 * sharedBounds 才认得出这算同一次匹配、把「原地绘制」让给 overlay 里的飞行副本；
 * 若 scope 不进过渡，就会出现「小封面留在原地 + 还有一个影子在飞」的两份封面（真机已复现）。
 * 用 fade 而非默认的 expand/shrink，避免交接那一帧的尺寸跳变；固定 40dp 占位保证整行不跳。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MiniPlayerCover(
    track: Track?,
    coverFor: (Track) -> JellyfinRequest?,
    sharedTransitionScope: SharedTransitionScope,
    visible: Boolean
) {
    Box(Modifier.size(Dimens.PlayerDockCover)) {
        if (track == null) {
            // 空态直接复用 AlbumCover 的空占位（8dp 圆角 + 阴影），不再是一个直角灰块。
            AlbumCover(request = null, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
                val coverScope = this
                with(sharedTransitionScope) {
                    Box(
                        Modifier
                            .sharedBounds(
                                rememberSharedContentState(key = NOW_PLAYING_COVER_KEY),
                                animatedVisibilityScope = coverScope
                            )
                            .fillMaxSize()
                    ) {
                        AlbumCover(coverFor(track), track.title, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/** 底部播放浮岛：折叠时只显示封面 + 歌名/歌手 + 播放/暂停 + 展开（切歌走整条横滑或全屏页）；点整条打开全屏播放页，右侧箭头切换小展开面板。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun MiniPlayerBar(
    backdrop: Backdrop,
    controllerState: ControllerState,
    playbackMode: PlaybackMode,
    onPlaybackModeChange: (PlaybackMode) -> Unit,
    currentTrack: Track?,
    /** 当前专辑主色（进度条填充 / 底部细线用）；无封面时为品牌靛蓝。 */
    accentColor: Color,
    coverFor: (Track) -> JellyfinRequest?,
    sharedTransitionScope: SharedTransitionScope,
    fullScreenOpen: Boolean,
    onOpenFullScreen: () -> Unit = {},
    /**
     * 上报迷你条（含小展开面板）的实测高度：底部浮层栈（Snackbar / 音量胶囊）靠它锚到条上方。
     * 展开/收起动画期间会逐帧回调，这是预期值——浮层跟着面板一起平滑上移。
     */
    onDockHeightChange: (Dp) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 小展开面板开关：右侧箭头按钮切换。
    var expanded by remember { mutableStateOf(false) }
    val controller = controllerState.controller
    // 播放/暂停：有媒体项（队列非空）才可用。队列为空时按钮置灰、点了没反应。
    // 不用 durationMs > 0 判断——刚起播、时长还没上报时按钮会先灰一下。
    // 队列快照在轮询与 onMediaItemTransition 两处刷新，点歌后按钮立刻可用。
    val hasMedia = controller != null && controllerState.queue.isNotEmpty()
    // 进度条额外要求已知时长，否则 valueRange 没有意义。
    val canSeek = hasMedia && controllerState.durationMs > 0
    // 进度状态：拖动中显示拖动值，松手才 seek，避免边拖边跳。
    val durationSec = (controllerState.durationMs / 1000f).coerceAtLeast(1f)
    var sliderPosition by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    // 松手 seek 后播放器上报的位置要等下一次轮询（500ms）才追上。追上之前继续显示拖动值，
    // 否则进度条会先弹回**拖动前**的旧位置、再跳到新位置 —— 真机可见的「闪一下」。
    // 非 null 即锁定中，值是松手时锁定的拖动位置。
    var seekHoldSec by remember { mutableStateOf<Float?>(null) }
    // 位置追上目标 → 放开锁定，显示值交还给播放器。
    LaunchedEffect(seekHoldSec, controllerState.positionMs) {
        val target = seekHoldSec ?: return@LaunchedEffect
        if (positionReachedSeekTarget(controllerState.positionMs / 1000f, target)) seekHoldSec = null
    }
    // 超时兜底：seek 被忽略（无媒体 / 播放器已销毁）时位置永远追不上，别把显示值锁死。
    LaunchedEffect(seekHoldSec) {
        if (seekHoldSec == null) return@LaunchedEffect
        delay(SEEK_SETTLE_TIMEOUT_MS)
        seekHoldSec = null
    }
    // 切歌放开：新歌位置从 0 起算，旧拖动值已无意义（拖到歌尾松手后正好会触发自动切歌）。
    val currentQueueId = controllerState.queue.firstOrNull { it.isCurrent }?.id
    LaunchedEffect(currentQueueId) { seekHoldSec = null }
    // 播放队列：本地顺序（拖拽时优先），松手才同步回播放器；默认折叠避免歌多时占满面板。
    val hapticFeedback = LocalHapticFeedback.current
    var queueExpanded by remember { mutableStateOf(false) }
    var isQueueDragging by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf(controllerState.queue) }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        queue = queue.toMutableList().apply { add(to.index, removeAt(from.index)) }
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    // 播放器快照变化时刷新本地队列；拖拽进行中不覆盖本地顺序，避免排序时列表跳动。
    LaunchedEffect(controllerState.queue) {
        if (!isQueueDragging) queue = controllerState.queue
    }
    // 输出信息（设备 + 采样率 + 位深），由 PlaybackService 写入诊断单例。
    val outputInfo by PlaybackDiagnostics.info.collectAsStateWithLifecycle()
    // 播放控制按钮（上一首/播放暂停/下一首）共用的轻触觉反馈。
    val controlView = LocalView.current
    // 可读性表面色：非组合的 onDrawSurface 里不能读 MaterialTheme，先取出来。
    val glassSurface = MaterialTheme.colorScheme.surface
    // 上报实测高度要把像素换算成 dp。
    val density = LocalDensity.current
    // 底部细进度线的比例：直接按轮询到的位置/时长算（拖动同步由展开面板的进度条负责）；
    // 时长未知（<=0）整条显示未播色。
    val miniProgressFraction = if (controllerState.durationMs > 0) {
        (controllerState.positionMs / controllerState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    // 液态玻璃容器。这里是**全 App 玻璃材质的基准**（模糊 / 透镜 / 圆角的默认值都取自此处），
    // 其余玻璃面统一走 GlassPanel，别再各写各的数字；前景色也由它统一提供，
    // 歌名/时间/面板文字在深色主题下不会再变成默认黑。
    GlassPanel(
        backdrop = backdrop,
        surface = glassSurface.copy(alpha = 0.55f),
        modifier = modifier
            .fillMaxWidth()
            // 实测高度（像素 → dp）：折叠态是整个条，展开态连小面板一起算，
            // 所以底部浮层栈不用知道面板里有什么、有没有展开，跟着这个数走即可。
            .onGloballyPositioned { coordinates ->
                onDockHeightChange(with(density) { coordinates.size.height.toDp() })
            }
    ) {
        Column {
            // 迷你条（始终可见）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 点击整条打开全屏播放页（连接上播放服务即可，方便未播放时也能进去操作）。
                    .clickable(enabled = controller != null) { onOpenFullScreen() }
                    // 左右滑动切歌：向左滑过阈值切下一首、向右滑切上一首，松手才触发，触发时带触觉反馈。
                    // 已知取舍：歌名超宽时跑马灯（basicMarquee 支持手动滚动）会抢走文字区域上的横向拖动手势，
                    // 此时封面和右侧按钮区域仍可滑动切歌；歌名不超宽时整条都能滑。
                    // 纯点按不受影响：横向拖动超过触摸溢出距离后框架自动取消点击，tap 仍走上面的 clickable 打开全屏页。
                    .pointerInput(controller) {
                        val swipeThreshold = 48.dp.toPx()
                        var accumulatedDrag = 0f
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { _, dragAmount -> accumulatedDrag += dragAmount },
                            onDragEnd = {
                                when {
                                    accumulatedDrag < -swipeThreshold -> {
                                        controlView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        controller?.seekToNext()
                                    }
                                    accumulatedDrag > swipeThreshold -> {
                                        controlView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        controller?.seekToPrevious()
                                    }
                                }
                                accumulatedDrag = 0f
                            },
                            onDragCancel = { accumulatedDrag = 0f }
                        )
                    }
                    .padding(
                        horizontal = Dimens.SpaceMidMedium,
                        vertical = Dimens.SpaceSmall
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 专辑封面：与全屏页大封面同 key 的 sharedBounds，打开/收起时跨布局飞行。
                // 细节见 MiniPlayerCover（含为何要单独抽一个 composable）。
                MiniPlayerCover(
                    track = currentTrack,
                    coverFor = coverFor,
                    sharedTransitionScope = sharedTransitionScope,
                    visible = !fullScreenOpen
                )
                Spacer(Modifier.width(Dimens.SpaceMidMedium))
                // 歌名 · 歌手
                Column(Modifier.weight(1f)) {
                    Text(
                        text = currentTrack?.title ?: "未选择歌曲",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // 长歌名跑马灯：停 2 秒后横向滚动，滚完再停 2 秒；文本不超宽时不滚动。
                        modifier = Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 2000,
                            repeatDelayMillis = 2000
                        )
                    )
                    Text(
                        text = controllerState.playbackError ?: currentTrack?.artist ?: controllerState.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (controllerState.playbackError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 折叠态只留播放/暂停与展开：上一首/下一首走全屏页或整条横滑手势（见上方 pointerInput）。
                // 播放/暂停：没有媒体项时置灰，点击无反应。
                // 禁用按钮自己**不消费**点击，会漏给整条（打开全屏页）；无媒体时盖一层透明热区
                // 把点击吃掉，保证「点了确实没反应」。
                Box {
                    IconButton(
                        onClick = {
                            controlView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            controller?.let { if (it.isPlaying) it.pause() else it.play() }
                        },
                        enabled = hasMedia,
                        modifier = Modifier.size(Dimens.TouchTargetSmall)
                    ) {
                        Icon(
                            if (controllerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (controllerState.isPlaying) "暂停播放" else "开始播放"
                        )
                    }
                    if (!hasMedia) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .pointerInput(Unit) {
                                    awaitEachGesture { awaitFirstDown().consume() }
                                }
                        )
                    }
                }
                // 展开/收起小面板（more = 展开更多内容，less = 收起更少内容）；进全屏页走点整条或面板里的全屏按钮
                IconButton(
                    onClick = { expanded = !expanded },
                    enabled = controller != null,
                    modifier = Modifier.size(Dimens.TouchTargetSmall)
                ) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                        contentDescription = if (expanded) "收起播放控制面板" else "展开播放控制面板"
                    )
                }
            }

            // 小展开面板：与 NowPlayingScreen 存在有意重复（进度/队列/输出信息两处各一份），各自独立演化。
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f)
                ) + fadeIn(),
                exit = shrinkVertically(
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f)
                ) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.SpaceMedium, vertical = Dimens.SpaceSmall)
                        // 面板内容（含播放队列）超出屏幕时整体可滚动，避免底部浮岛被裁掉。
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceTiny)
                ) {
                    // 进度条 + 时间：与全屏播放页同款液态玻璃胶囊（采样同一个 backdrop，
                    // 玻璃条后面就是曲库内容），配色走「浅色玻璃条」预设。
                    // 展示用位置：拖动中 / 松手后等播放器追上期间用拖动值，其余时间跟随播放器。
                    val displayedPositionSec =
                        if (isDragging || seekHoldSec != null) sliderPosition
                        else (controllerState.positionMs / 1000f).coerceIn(0f, durationSec)
                    LiquidGlassSlider(
                        value = displayedPositionSec,
                        onValueChange = { sliderPosition = it; isDragging = true },
                        onValueChangeFinished = {
                            controller?.seekTo((sliderPosition * 1000).toLong())
                            isDragging = false
                            // 锁定显示值，等播放器位置追上来再放开（见上面的 seekHoldSec）。
                            seekHoldSec = sliderPosition
                        },
                        // 拖动途中切歌导致 valueRange 变化时手势会被取消：只清拖动状态，不 seek。
                        onValueChangeCancelled = { isDragging = false },
                        valueRange = 0f..durationSec,
                        backdrop = backdrop,
                        enabled = canSeek,
                        colors = glassBarSliderColors(accentColor),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatPosition((displayedPositionSec * 1000).toLong()), style = MaterialTheme.typography.labelSmall)
                        Text(formatPosition(controllerState.durationMs), style = MaterialTheme.typography.labelSmall)
                    }
                    // 播放模式：图标切换（顺序循环 / 单曲循环 / 随机播放）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(
                            onClick = { onPlaybackModeChange(playbackMode.next()) },
                            enabled = controller != null,
                            modifier = Modifier.size(Dimens.TouchTargetSmall)
                        ) {
                            Icon(
                                playbackModeIcon(playbackMode),
                                contentDescription = "切换播放模式，当前：${playbackMode.label}",
                                tint = if (controller != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // 输出信息：走 USB 小尾巴还是系统默认 + 当前采样率 + 位深。
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = Dimens.SpaceTiny),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = outputLine(outputInfo),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    // 播放队列：默认折叠（歌多时占地方）；点开可长按拖动排序、移除歌曲，点歌跳转。
                    // 展开/收起箭头旋转动画：收起朝下、展开转 180° 朝上。
                    val queueChevronRotation by animateFloatAsState(
                        targetValue = if (queueExpanded) 180f else 0f,
                        label = "queueChevronRotation"
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Dimens.SpaceSmall)
                            // 整行可点，热区至少 48dp；内边距不再决定热区大小。
                            .heightIn(min = Dimens.QueueRowMinHeight)
                            .clickable { queueExpanded = !queueExpanded },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "播放队列（${queue.size} 首）",
                            // 分区标题用 titleMedium（SemiBold），与队列项的 titleSmall 拉开字号/字重层级。
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = if (queueExpanded) "收起播放队列" else "展开播放队列",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.rotate(queueChevronRotation)
                        )
                    }
                    // 展开/收起动画：高度从 0 生长到内容高度 + 淡入淡出；队列长也只占固定高度，其余靠列表自己滚。
                    AnimatedVisibility(
                        visible = queueExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        if (queue.isEmpty()) {
                            Text(
                                text = "暂无队列：从「歌曲 / 专辑 / 播放列表」里点一首歌开始",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            // 限高 + 内部滚动：队列再长也只占固定高度，其余靠列表自己滚。
                            LazyColumn(
                                state = lazyListState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = Dimens.QueueListMaxHeight)
                                    .wrapContentHeight()
                            ) {
                                items(queue, key = { it.id }) { item ->
                                    ReorderableItem(reorderableState, key = item.id) { dragging ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                // 队列行也是触摸目标：整行至少 48dp 可点。
                                                .heightIn(min = Dimens.QueueRowMinHeight)
                                                .background(
                                                    if (dragging) MaterialTheme.colorScheme.secondaryContainer
                                                    else Color.Transparent,
                                                    RoundedCornerShape(8.dp)
                                                )
                                                // 长按整行拖动排序；松手后把新顺序同步给播放器。
                                                .longPressDraggableHandle(
                                                    onDragStarted = { isQueueDragging = true },
                                                    onDragStopped = {
                                                        isQueueDragging = false
                                                        controller?.applyQueueOrder(queue)
                                                    }
                                                )
                                                .clickable(enabled = controller != null) {
                                                    val index = queue.indexOfFirst { it.id == item.id }
                                                    controller?.seekToDefaultPosition(index)
                                                    controller?.play()
                                                }
                                                .padding(vertical = Dimens.SpaceTiny),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(8.dp)
                                                    .background(
                                                        if (item.isCurrent) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.surfaceVariant,
                                                        CircleShape
                                                    )
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    text = item.title,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = if (item.isCurrent) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurface
                                                    },
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = item.artist,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    controller?.removeMediaItem(queue.indexOfFirst { it.id == item.id })
                                                },
                                                enabled = controller != null,
                                                modifier = Modifier.size(Dimens.TouchTargetSmall)
                                            ) {
                                                Icon(Icons.Filled.Close, contentDescription = "从队列中移除此歌曲", Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 底部细进度线：紧贴玻璃条底边、横贯全宽。2dp 做不了真正的玻璃胶囊（太薄，模糊 / 透镜
        // 都看不出来），于是走同一套视觉语言的「玻璃细线」：半透明轨道 + 专辑主色渐变填充 + 圆头。
        // 不加前端柔光：迷你条上两处进度显示的光都按真机反馈关掉了（玻璃条上再发光会糊）。
        val miniTrackColor = MaterialTheme.colorScheme.onSurfaceVariant
        val miniFillColor = accentColor
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(3.dp)
                // 玻璃条是 20dp 圆角，不裁剪的话进度线两端会悬出圆角外，这里用同款底圆角收边。
                .clip(RoundedCornerShape(bottomStart = Dimens.CornerExtraLarge, bottomEnd = Dimens.CornerExtraLarge))
                .background(miniTrackColor.copy(alpha = 0.22f))
        ) {
            if (miniProgressFraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(miniProgressFraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(percent = 50))
                        .background(
                            Brush.horizontalGradient(
                                0f to miniFillColor.copy(alpha = 0.75f),
                                1f to miniFillColor
                            )
                        )
                )
            }
        }
    }
}

/** 专辑封面：无请求（未登录或无封面）或加载失败时显示灰色占位块，不影响列表。
 * 封面底部叠加一个微渐变层，增加视觉深度和质感。（internal：全屏播放页 NowPlayingScreen 复用）
 * [cornerRadius] 可选圆角：默认 8dp（列表调用零影响），全屏播放页传 20dp 匹配玻璃语言。
 */
@Composable
internal fun AlbumCover(
    request: JellyfinRequest?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp
) {
    val shape = RoundedCornerShape(cornerRadius)
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    // 阴影用色板 scrim（恒黑）加透明度，不写死 0x1A000000 / 0x26000000。
    val shadowColor = MaterialTheme.colorScheme.scrim
    if (request == null) {
        Box(
            modifier
                .clip(shape)
                .shadow(
                    2.dp, shape,
                    ambientColor = shadowColor.copy(alpha = 0.10f),
                    spotColor = shadowColor.copy(alpha = 0.10f)
                )
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        return
    }
    val model = ImageRequest.Builder(LocalContext.current)
        .data(request.url)
        .addHeader("Authorization", request.headers["Authorization"].orEmpty())
        .crossfade(true)
        .build()
    Box(
        modifier.clip(shape).shadow(
            3.dp, shape,
            ambientColor = shadowColor.copy(alpha = 0.15f),
            spotColor = shadowColor.copy(alpha = 0.15f)
        )
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            placeholder = placeholder,
            error = placeholder,
            modifier = Modifier.fillMaxSize()
        )
        // 底部微渐变：营造封面嵌入感，提升视觉层次。用色板的 scrim（恒黑）而不是
        // onSurface——onSurface 在深色模式下是浅色，压在封面上会变成一道白雾。
        val scrimColor = MaterialTheme.colorScheme.scrim.copy(
            alpha = if (isSystemInDarkTheme()) 0.20f else 0.03f
        )
        // 高度跟封面走：25% 封顶 32dp（工单 14 / 报告 #8）。以前固定 32dp，40dp 的迷你封面
        // 有一大半压在暗部里。按比例算需要**实测高度**，所以走 drawWithCache 在绘制阶段算，
        // 不用 fillMaxHeight + heightIn——两个尺寸约束叠在一起时谁裁谁由 Modifier 顺序决定，容易写反。
        Box(
            Modifier
                .matchParentSize()
                .drawWithCache {
                    val scrimHeight = coverScrimHeight(size.height.toDp()).toPx()
                    val brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, scrimColor),
                        startY = size.height - scrimHeight,
                        endY = size.height
                    )
                    onDrawBehind { drawRect(brush) }
                }
        )
    }
}

/**
 * 播放条目元数据。响度增益挂 extras 而不是 MediaItem 的本地 tag——队列要经
 * MediaController 交给播放服务，本地 tag 只存在于控制端，到不了服务端；extras 会
 * 随 MediaMetadata 一起传过去，播放服务据此在换歌时算出回放增益。
 */
private fun Track.toPlaybackMetadata(): MediaMetadata = MediaMetadata.Builder()
    .setTitle(title)
    .setArtist(artist)
    .setAlbumTitle(album)
    .setExtras(
        Bundle().apply {
            putLoudnessGain(normalizationGainDb)
        }
    )
    .build()

/**
 * 把 [tracks] 以 [startTrackId] 为开头旋转成播放队列后交给 [controller]；任一歌曲无流地址则放弃播放。
 * 调用方（[onPlayTrack]）负责离线守门：传进来的列表里只有能放的歌。
 */
private fun playTracks(
    tracks: List<Track>,
    startTrackId: String,
    controller: MediaController,
    playbackMode: PlaybackMode,
    streamUrlFor: (Track) -> String?
) {
    val order = buildPlaybackOrder(tracks, startTrackId) ?: return
    val mediaItems = order.map { t ->
        val url = streamUrlFor(t) ?: return
        // 把封面 tag 挂到条目上：Activity 被系统回收重建后，仍可从播放器读回封面信息。
        MediaItem.Builder()
            .setUri(url)
            .setMediaId(t.id)
            .setTag(t.imageTag)
            .setMediaMetadata(t.toPlaybackMetadata())
            .build()
    }
    // 一次性监听本次加载失败，避免失败时应用崩溃；成功就绪后自动移除，防止重复累积。
    val errorListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            controller.removeListener(this)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                controller.removeListener(this)
            }
        }
    }
    controller.addListener(errorListener)
    controller.applyPlaybackMode(playbackMode)
    controller.setMediaItems(mediaItems)
    controller.prepare()
    controller.play()
}

/** 播放器当前队列中的一首歌（从播放器真实队列快照而来），含是否为当前播放项。（internal：全屏播放页复用） */
internal data class QueueTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val imageTag: String?,
    val isCurrent: Boolean
)

internal data class ControllerState(
    val controller: MediaController?,
    val status: String,
    val playbackError: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = C.TIME_UNSET,
    val isPlaying: Boolean = false,
    val queue: List<QueueTrack> = emptyList(),
    val usbExclusiveEnabled: Boolean = false,
    val usbExclusiveState: String = "disabled",
    val usbExclusiveError: String? = null,
    val usbExclusivePending: Boolean = false,
    val usbVolumePercent: Int = 100,
    val silenceSkipEnabled: Boolean = true,
    val onUsbExclusiveChange: (Boolean) -> Unit = {},
    val onUsbVolumeChange: (Int) -> Unit = {},
    val onSilenceSkipChange: (Boolean) -> Unit = {}
)

/** 读取播放器当前真实队列的快照（含当前播放项标记）。 */
private fun MediaController.queueSnapshot(): List<QueueTrack> {
    val count = mediaItemCount
    val currentIndex = currentMediaItemIndex
    if (count <= 0) return emptyList()
    return List(count) { index ->
        val item = getMediaItemAt(index)
        val metadata = item.mediaMetadata
        QueueTrack(
            id = item.mediaId.orEmpty(),
            title = metadata.title?.toString() ?: "未知歌曲",
            artist = metadata.artist?.toString() ?: "未知歌手",
            album = metadata.albumTitle?.toString().orEmpty(),
            imageTag = item.localConfiguration?.tag as? String,
            isCurrent = index == currentIndex
        )
    }
}

@Composable
private fun rememberMediaController(
    controllerFuture: ListenableFuture<MediaController>?,
    volumeKeys: ForegroundVolumeKeys
): ControllerState {
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var status by remember { mutableStateOf("正在连接播放服务…") }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(C.TIME_UNSET) }
    var isPlaying by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf<List<QueueTrack>>(emptyList()) }
    var usbExclusiveEnabled by remember { mutableStateOf(false) }
    var usbExclusiveState by remember { mutableStateOf("disabled") }
    var usbExclusiveError by remember { mutableStateOf<String?>(null) }
    var usbExclusivePending by remember { mutableStateOf(false) }
    var usbVolumePercent by remember { mutableStateOf(100) }
    var silenceSkipEnabled by remember { mutableStateOf(true) }
    DisposableEffect(controllerFuture) {
        if (controllerFuture == null) { status = "播放服务不可用"; onDispose { } } else {
            var isActive = true
            val listener = Runnable { if (isActive) runCatching { controller = controllerFuture.get(); status = controllerStatus(controller) }.onFailure { status = "播放服务连接失败" } }
            controllerFuture.addListener(listener) { it.run() }
            onDispose { isActive = false; controller = null }
        }
    }
    DisposableEffect(controller) {
        val current = controller
        if (current == null) onDispose { } else {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                    if (playing) playbackError = null
                    status = controllerStatus(current, playbackError)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    playbackError = null
                    status = controllerStatus(current, playbackError)
                    // 队列快照同步刷新：播放键的可用性（队列是否为空）与队列面板不能等下一轮
                    // 500ms 轮询才更新，否则点歌后的第一拍按钮还是灰的。
                    queue = current.queueSnapshot()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_BUFFERING) playbackError = null
                    status = controllerStatus(current, playbackError)
                }

                override fun onPlayerError(error: PlaybackException) {
                    playbackError = friendlyPlaybackError(error)
                    status = controllerStatus(current, playbackError)
                }
            }
            current.addListener(listener)
            // 连接后立即同步播放状态，避免图标与播放器不一致（如后台回来正在播放却显示播放箭头）。
            isPlaying = current.isPlaying
            status = controllerStatus(current, playbackError)
            onDispose { current.removeListener(listener) }
        }
    }
    // 前台音量键接管：把"USB 泵是否在输出 + 下发口"交给 Activity 的 dispatchKeyEvent（MIUI
    // 不向远程音量会话派发音量键，见 ForegroundVolumeKeys 注释）。控制器没连上时清空，
    // 音量键立刻还给系统。**泵没在输出（没插设备/未就绪）也走同一条清空路径**——那时声音
    // 走系统 AudioTrack，接管只会让音量键失灵。
    DisposableEffect(controller, usbExclusiveState) {
        val current = controller
        if (current == null) {
            volumeKeys.snapshot = null
            volumeKeys.sink = null
        } else {
            volumeKeys.snapshot = ForegroundVolumeKeys.Snapshot(
                usbAudioActive = UsbExclusiveMediaCommands.isUsbAudioActive(usbExclusiveState)
            )
            volumeKeys.sink = ForegroundVolumeKeys.Sink { step ->
                // 服务把算完的新音量随命令结果带回，拿它直接点亮浮窗——比等 500ms 轮询快一个来回。
                adjustDeviceVolume(current, step, VolumeFeedback::show)
            }
        }
        onDispose {
            volumeKeys.snapshot = null
            volumeKeys.sink = null
            // 交还音量键的同时把浮窗也收了：关掉独占开关后立刻走系统音量，浮窗再赖 1.5 秒
            // 会读成「还在调 App 的音量」。
            VolumeFeedback.dismiss()
        }
    }
    // 轮询刷新进度与播放状态：每 500ms 读取一次当前位置、总时长、播放中标记、播放队列快照。
    LaunchedEffect(controller) {
        val current = controller
        var isActive = true
        var usbQueryInFlight = false
        var silenceQueryInFlight = false
        try {
            while (isActive) {
                current?.let {
                    positionMs = it.currentPosition
                    durationMs = it.duration
                    isPlaying = it.isPlaying
                    queue = it.queueSnapshot()
                    if (!usbQueryInFlight) {
                        usbQueryInFlight = true
                        runCatching {
                            it.sendCustomCommand(
                                UsbExclusiveMediaCommands.getCommand(), Bundle.EMPTY
                            )
                        }.onSuccess { future ->
                            future.addListener({
                                if (isActive && controller === current) {
                                    runCatching {
                                        val result = future.get()
                                        if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                                            val extras = result.extras
                                            if (extras.containsKey(UsbExclusiveMediaCommands.KEY_STATE)) {
                                                usbExclusiveEnabled = extras.getBoolean(UsbExclusiveMediaCommands.KEY_ENABLED)
                                                usbExclusiveState = extras.getString(UsbExclusiveMediaCommands.KEY_STATE).orEmpty()
                                                usbExclusiveError = extras.getString(UsbExclusiveMediaCommands.KEY_ERROR)
                                                if (extras.containsKey(UsbExclusiveMediaCommands.KEY_VOLUME_PERCENT)) {
                                                    usbVolumePercent = extras.getInt(
                                                        UsbExclusiveMediaCommands.KEY_VOLUME_PERCENT, usbVolumePercent
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                usbQueryInFlight = false
                            }, { runnable -> runnable.run() })
                        }.onFailure { usbQueryInFlight = false }
                    }
                    if (!silenceQueryInFlight) {
                        silenceQueryInFlight = true
                        runCatching {
                            it.sendCustomCommand(
                                SilenceSkipMediaCommands.getCommand(), Bundle.EMPTY
                            )
                        }.onSuccess { future ->
                            future.addListener({
                                if (isActive && controller === current) {
                                    runCatching {
                                        val result = future.get()
                                        if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                                            val extras = result.extras
                                            if (extras.containsKey(SilenceSkipMediaCommands.KEY_ENABLED)) {
                                                silenceSkipEnabled = extras.getBoolean(SilenceSkipMediaCommands.KEY_ENABLED)
                                            }
                                        }
                                    }
                                }
                                silenceQueryInFlight = false
                            }, { runnable -> runnable.run() })
                        }.onFailure { silenceQueryInFlight = false }
                    }
                }
                delay(500)
            }
        } finally {
            isActive = false
        }
    }
    return ControllerState(
        controller = controller,
        status = status,
        playbackError = playbackError,
        positionMs = positionMs,
        durationMs = durationMs,
        isPlaying = isPlaying,
        queue = queue,
        usbExclusiveEnabled = usbExclusiveEnabled,
        usbExclusiveState = usbExclusiveState,
        usbExclusiveError = usbExclusiveError,
        usbExclusivePending = usbExclusivePending,
        usbVolumePercent = usbVolumePercent,
        silenceSkipEnabled = silenceSkipEnabled,
        onUsbExclusiveChange = { enabled ->
            val current = controller ?: return@ControllerState
            usbExclusivePending = true
            current.sendCustomCommand(
                SessionCommand(UsbExclusiveMediaCommands.SET_ENABLED, Bundle.EMPTY),
                UsbExclusiveMediaCommands.setArgs(enabled)
            ).addListener({
                runCatching {
                    val resultFuture = current.sendCustomCommand(
                        UsbExclusiveMediaCommands.getCommand(), Bundle.EMPTY
                    )
                    resultFuture.addListener({
                        runCatching {
                            val result = resultFuture.get()
                            if (result.resultCode != SessionResult.RESULT_SUCCESS) {
                                throw IllegalStateException("USB 独占设置命令失败")
                            }
                            val extras = result.extras
                            usbExclusiveEnabled = extras.getBoolean(UsbExclusiveMediaCommands.KEY_ENABLED)
                            usbExclusiveState = extras.getString(UsbExclusiveMediaCommands.KEY_STATE).orEmpty()
                            usbExclusiveError = extras.getString(UsbExclusiveMediaCommands.KEY_ERROR)
                        }.onFailure { usbExclusiveError = "USB 独占设置失败" }
                        usbExclusivePending = false
                    }, { runnable -> runnable.run() })
                }.onFailure {
                    usbExclusiveError = "USB 独占设置失败"
                    usbExclusivePending = false
                }
            }, { runnable -> runnable.run() })
        },
        onUsbVolumeChange = { newPercent ->
            usbVolumePercent = newPercent
            val current = controller ?: return@ControllerState
            val future = current.sendCustomCommand(
                SessionCommand(UsbExclusiveMediaCommands.SET_VOLUME, Bundle.EMPTY),
                UsbExclusiveMediaCommands.setVolumeArgs(newPercent)
            )
            future.addListener({
                if (future.get().resultCode != SessionResult.RESULT_SUCCESS) {
                    usbExclusiveError = "USB 音量设置失败"
                }
            }, { runnable -> runnable.run() })
        },
        onSilenceSkipChange = { enabled ->
            silenceSkipEnabled = enabled
            val current = controller ?: return@ControllerState
            // 用 addListener 而非阻塞的 .get()：PlaybackService 里 SET_ENABLED 命令要靠一个
            // 主线程协程写完 DataStore 才 set 这个 future，如果这里在主线程同步 .get() 等它，
            // 会跟那个协程互相卡死（协程排不上主线程执行，主线程又卡在 .get() 上），触发 ANR。
            current.sendCustomCommand(
                SessionCommand(SilenceSkipMediaCommands.SET_ENABLED, Bundle.EMPTY),
                SilenceSkipMediaCommands.setArgs(enabled)
            ).addListener({}, { runnable -> runnable.run() })
        }
    )
}

/**
 * 前台音量键的下发：把一档调整发到会话，由播放服务按自己手里的实时值算新音量。
 *
 * **不在这里自己算百分比**——百分比的真源在服务里（`usbVolumePercent`），UI 侧只有
 * 500ms 轮询来的快照；连按两下时本地快照还没刷新，自己算会把第二下算成同一个目标值而
 * 丢掉一档。传"加一档/减一档"走一个来回，算术就只有一处。
 *
 * 走自定义命令而不是 `MediaController.increaseDeviceVolume()`：后者依赖 MediaSession
 * 把设备音量命令传给控制器，实测靠不住（`isCommandAvailable` 时真时假），而自定义命令
 * 这条通道是 App 内滑块一直在用的、验证过好用的。
 *
 * @param step `+1` 加一档、`-1` 减一档。
 * @param onApplied 服务算完（并夹紧）的新音量。**只在命令成功时调用**——吃掉按键却什么都没
 *   发生时不该给音量反馈。
 * @return `false` = 会话没提供这条命令（服务未连上）。此时按键交还系统，免得吃掉按键
 *   却什么都不做。
 */
private fun adjustDeviceVolume(
    controller: MediaController,
    step: Int,
    onApplied: (Int) -> Unit
): Boolean {
    val command = UsbExclusiveMediaCommands.adjustVolumeCommand()
    if (!controller.isSessionCommandAvailable(command)) return false
    return runCatching {
        val future = controller.sendCustomCommand(
            command, UsbExclusiveMediaCommands.adjustVolumeArgs(step)
        )
        // 结果顺着这次命令的返回值回来，比等 500ms 轮询快得多——音量浮窗就是靠它跟手的。
        future.addListener({
            runCatching {
                val result = future.get()
                if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                    UsbExclusiveMediaCommands.volumePercentFrom(result.extras)?.let(onApplied)
                }
            }
        }, { runnable -> runnable.run() })
    }.isSuccess
}

private fun usbExclusiveStateLabel(state: ControllerState): String = when (state.usbExclusiveState) {
    "disabled" -> "已关闭"
    "no_device" -> "已开启，等待 USB 设备"
    "awaiting_permission" -> "等待 USB 权限"
    "ready" -> "已就绪"
    "streaming" -> "输出中"
    "error" -> "USB 独占失败：${state.usbExclusiveError.orEmpty()}"
    else -> "状态未知"
}

private fun controllerStatus(controller: MediaController?, playbackError: String? = null): String {
    if (playbackError != null) return playbackError
    if (controller == null) return "播放服务不可用"
    val title = controller.currentMediaItem?.mediaMetadata?.title ?: "未选择媒体"
    val state = when {
        controller.playbackState == Player.STATE_BUFFERING -> "缓冲中"
        controller.playbackState == Player.STATE_ENDED -> "已结束"
        controller.isPlaying -> "播放中"
        else -> "已暂停"
    }
    return "$title · $state"
}

private fun friendlyPlaybackError(error: PlaybackException): String = when {
    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "播放失败：网络连接中断"
    error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED -> "播放失败：音频格式不受支持"
    else -> "播放失败：请检查服务器或网络"
}

/** 把 [mode] 应用到播放器：设置 repeatMode 与 shuffle 开关。 */
private fun MediaController.applyPlaybackMode(mode: PlaybackMode) {
    val config = playbackModeConfig(mode)
    repeatMode = config.repeatMode
    shuffleModeEnabled = config.shuffleEnabled
}

/**
 * 把队列拖拽后的目标顺序同步到播放器：从前往后逐个把每首移到目标位置。
 * MediaController 的 moveMediaItem 会即时更新本地队列镜像，循环内每次重新查当前位置即可正确收敛。
 */
internal fun MediaController.applyQueueOrder(ordered: List<QueueTrack>) {
    val desired = ordered.map { it.id }
    for (targetIndex in desired.indices) {
        val id = desired[targetIndex]
        val currentIndex = (0 until mediaItemCount).firstOrNull { getMediaItemAt(it).mediaId == id } ?: continue
        if (currentIndex != targetIndex) moveMediaItem(currentIndex, targetIndex)
    }
}
