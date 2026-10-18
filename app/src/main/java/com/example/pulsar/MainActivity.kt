package com.example.pulsar

import android.app.Application
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import com.example.pulsar.ui.components.LiquidGlassSlider
import com.example.pulsar.ui.components.SurfaceCard
import com.example.pulsar.ui.components.VolumeFeedback
import com.example.pulsar.ui.components.VolumeFeedbackOverlay
import com.example.pulsar.ui.components.glassBarSliderColors
import com.example.pulsar.ui.components.liquidGlass
import com.example.pulsar.ui.theme.BrandPrimary
import com.example.pulsar.ui.theme.CoverGradient
import com.example.pulsar.ui.theme.CoverTheme
import com.example.pulsar.ui.theme.Dimens
import com.example.pulsar.ui.theme.PulsarTheme
import com.example.pulsar.ui.theme.ThemeSeedSource
import com.example.pulsar.ui.theme.rememberCoverGradient
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExitToApp
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtMost
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
    val coverTheme = rememberCoverGradient(
        coverRequest = currentTrack?.let(viewModel::trackCover),
        darkTheme = darkTheme
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
                coverTheme = coverTheme
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
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
/**
 * 艺术家 / 流派列表页（工单 06）：由已加载的专辑在客户端分组而来（零新 API）。
 * 行语言沿用 Auxio 列表项：封面 + 组名 + 「N 张专辑」副信息。
 * 封面取组内第一张专辑的封面——服务端没给艺术家/流派单独的图。
 */
@Composable
private fun LibraryGroupsContent(
    state: JellyfinUiState,
    title: String,
    byGenre: Boolean,
    onOpenGroup: (String) -> Unit,
    coverFor: (Album) -> JellyfinRequest?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val albums = state.albums
    val groups = remember(albums, byGenre) { libraryGroups(albums.orEmpty(), byGenre) }
    Column(
        modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Dimens.SpaceMedium),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(top = GlassIslandTopSpacing - Dimens.SpaceSmall)
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                albums == null && state.isLoading -> TrackListSkeleton(count = 8)
                albums == null && state.errorMessage != null -> ErrorState(state.errorMessage, onRetry = onRetry)
                groups.isEmpty() -> EmptyState(if (byGenre) "暂无流派" else "暂无艺术家")
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
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

/** 艺术家 / 流派详情（工单 06）：标题 + 该组下的专辑网格，点专辑进专辑详情。 */
@Composable
private fun LibraryGroupDetailContent(
    state: JellyfinUiState,
    groupName: String,
    isGenre: Boolean,
    onOpenAlbum: (Album) -> Unit,
    coverFor: (Album) -> JellyfinRequest?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val albums = state.albums
    // 组名来自导航状态，专辑列表可能还在加载/刷新：按组名重新分组，找不到就显示空态。
    val group = remember(albums, groupName, isGenre) {
        // 组名按大小写不敏感匹配：显示名取「首次出现的写法」，输入顺序一变精确匹配就会失手。
        libraryGroups(albums.orEmpty(), isGenre)
            .firstOrNull { it.name.equals(groupName, ignoreCase = true) }
    }
    Column(
        modifier.fillMaxSize().statusBarsPadding()
            .padding(horizontal = Dimens.SpaceMedium).padding(top = Dimens.SpaceSmall),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceTiny)) {
            Text(groupName, style = MaterialTheme.typography.titleLarge)
            group?.let {
                Text(
                    "${it.albums.size} 张专辑",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                albums == null && state.isLoading -> {
                    val shimmer = rememberSkeletonShimmer()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
                    ) {
                        items(6) {
                            SkeletonBox(
                                Modifier.fillMaxWidth().aspectRatio(1f),
                                progress = shimmer,
                                shape = RoundedCornerShape(Dimens.CornerLarge)
                            )
                        }
                    }
                }
                albums == null && state.errorMessage != null -> ErrorState(state.errorMessage, onRetry = onRetry)
                group == null || group.albums.isEmpty() -> EmptyState("这里还没有专辑")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
                ) {
                    items(group.albums, key = { it.id }) { album ->
                        AlbumGridCard(album, coverFor(album), onClick = { onOpenAlbum(album) })
                    }
                }
            }
        }
    }
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
    val darkTheme = isSystemInDarkTheme()
    val shimmerColor = if (darkTheme) Color.White else Color(0xFF0F172A)
    val shimmerPeakAlpha = if (darkTheme) 0.15f else 0.09f
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

/** 专辑/播放列表卡片的 1dp 描边色：浅色主题用深色低透明细线（白描边在浅底上不可见），
 * 深色主题用白色低透明细线——两个主题下都是「比表面深/浅一点」的层次。 */
@Composable
private fun cardBorderColor(): Color = if (isSystemInDarkTheme()) Color(0x1AFFFFFF) else Color(0x14888888)

/** 曲目列表的灰色波浪骨架：与 [TrackRow] 同款排布（48dp 圆角封面 + 标题/作者两条灰条
 * + 从封面右缘起的分隔线），共用一份扫光进度。会话恢复期间用它填满列表，视觉上是
 * "这个页面正在加载"，而不是一片空白或"暂无内容"的空态文案。
 * [trailingSpace] 为 true 时给行尾「更多」按钮留出同样的 48dp 位——歌曲页 / 发现页的
 * 真实行有该按钮，不留位加载完文字列会突然变窄；播放列表等无按钮的列表传 false。 */
@Composable
private fun TrackListSkeleton(
    count: Int = 6,
    trailingSpace: Boolean = false,
    modifier: Modifier = Modifier
) {
    val shimmer = rememberSkeletonShimmer()
    // 8dp / 4dp 是组件自身尺寸（与 AlbumCover 默认圆角、灰条圆角一致），按工单 01 约定不进 Dimens。
    val coverShape = RoundedCornerShape(8.dp)
    Column(modifier) {
        repeat(count) { index ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceSmall),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonBox(Modifier.size(Dimens.TouchTargetSmall).clip(coverShape), progress = shimmer)
                Spacer(Modifier.width(Dimens.SpaceMidMedium))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceTiny)) {
                    SkeletonBox(
                        Modifier.fillMaxWidth(0.55f).height(14.dp),
                        progress = shimmer,
                        shape = RoundedCornerShape(4.dp)
                    )
                    SkeletonBox(
                        Modifier.fillMaxWidth(0.35f).height(11.dp),
                        progress = shimmer,
                        shape = RoundedCornerShape(4.dp)
                    )
                }
                if (trailingSpace) Spacer(Modifier.width(Dimens.TouchTargetSmall))
            }
            if (index != count - 1) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = Dimens.TouchTargetSmall + Dimens.SpaceMidMedium),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginScreen(state: JellyfinUiState, viewModel: JellyfinViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
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

/** 顶部玻璃岛的固定占位高度：岛本身 56dp + 上下外边距各 8dp；状态栏高度由各列表的 statusBars inset 另算。 */
internal val GlassIslandTopSpacing = 72.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun MusicLibraryScreen(
    state: JellyfinUiState,
    viewModel: JellyfinViewModel,
    controllerFuture: ListenableFuture<MediaController>?,
    volumeKeys: ForegroundVolumeKeys,
    qqState: QQDownloadUiState = QQDownloadUiState(),
    qqViewModel: QQDownloadViewModel? = null,
    currentTrack: Track?,
    coverTheme: CoverTheme
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
    // `recentlyPlayed` / `mostPlayed` 作 key：切歌后 ViewModel 把这两份历史清空
    // （工单 09 上报改了服务端数据），停在同一页也要重新拉一次。
    val sessionReady = state.tracks != null
    LaunchedEffect(section, sessionReady, state.isOffline, state.recentlyPlayed, state.mostPlayed) {
        when (val s = section) {
            LibrarySection.Discover -> if (sessionReady) {
                viewModel.loadDiscoverAlbums()
                viewModel.loadRecentlyPlayed()
                viewModel.loadMostPlayed()
                viewModel.loadRecentlyAdded()
            }
            LibrarySection.Tracks -> Unit
            LibrarySection.Albums -> if (sessionReady) viewModel.loadAlbums()
            // 艺术家 / 流派页由已加载的专辑客户端分组而来（工单 06），零新 API：
            // 只确保专辑列表到位，其余全在 UI 侧现算。
            LibrarySection.Artists, LibrarySection.Genres -> if (sessionReady) viewModel.loadAlbums()
            is LibrarySection.GroupDetail -> if (sessionReady) viewModel.loadAlbums()
            LibrarySection.Playlists -> if (sessionReady) viewModel.loadPlaylists()
            LibrarySection.Search -> Unit
            LibrarySection.QQDownload -> qqViewModel?.refreshJobs()
            LibrarySection.Settings -> Unit
            LibrarySection.CachedCopies -> viewModel.loadCachedAlbums()
            is LibrarySection.AlbumDetail -> if (sessionReady) viewModel.loadAlbumTracks(s.album)
            is LibrarySection.PlaylistDetail -> if (sessionReady) viewModel.loadPlaylistTracks(s.playlist)
        }
    }
    val tracks = state.tracks.orEmpty()
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
    // 离线时 playTracks 内部会跳过没缓存的歌（队列里混着能放/不能放时按工单 03 口径处理）。
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    // 离线没缓存的歌的统一话术（文案只此一处，经 ViewModel 的 unplayableReason 出口）。
    val onPlayTrack: (List<Track>, String) -> Unit = { trackList, trackId ->
        val controller = controllerState.controller
        if (controller != null) {
            val start = trackList.firstOrNull { it.id == trackId }
            val reason = start?.let(viewModel::unplayableReason)
            if (reason != null) {
                // 先守门再进队列：点了没缓存的歌只弹提示，不把它写进「正在播」镜像——
                // 不然迷你条/高亮/歌词都会指向一首根本没在放的歌。
                snackbarScope.launch { snackbarHostState.showSnackbar(reason) }
            } else {
                // 队列里只留能放的：离线播到没缓存的歌只会中途断掉。
                val playable = trackList.filter(viewModel::isPlayable)
                viewModel.setPlaybackQueue(playable, trackId)
                playTracks(playable, trackId, controller, playbackMode, viewModel::streamUrl)
            }
        }
    }
    // 离线态（工单 03/04）：列表行据此标灰，点击没缓存的歌给明确提示。
    val offlineState = remember(state.isOffline, state.cachedTrackIds) {
        OfflineState(
            active = state.isOffline,
            cachedTrackIds = state.cachedTrackIds,
            onUnplayable = { track ->
                snackbarScope.launch {
                    snackbarHostState.showSnackbar(viewModel.unplayableReason(track) ?: "这首歌没有缓存")
                }
            }
        )
    }
    val onAddToQueue: (Track) -> Unit = { track ->
        val ctrl = controllerState.controller
        if (ctrl != null) {
            // 离线没缓存的歌进不了队列——插进去等到它播也是断，不如当场说清。
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
                // 来源已消费：二级链（设置 → 已缓存 → 专辑）返回后不留陈旧来源，
                // 否则再往回退会卡在 设置↔已缓存 的循环里。
                detailOrigin = LibrarySection.Discover
            }
            is LibrarySection.PlaylistDetail -> {
                viewModel.clearPlaylistDetail()
                section = detailOrigin
                detailOrigin = LibrarySection.Discover
            }
            is LibrarySection.GroupDetail -> {
                // 组详情没有可清的服务端状态（专辑列表还在用）。来源固定是艺术家/流派页，
                // 由 isGenre 直接推出——不借 detailOrigin：它可能被「组详情 → 专辑详情」覆盖过。
                section = if (s.isGenre) LibrarySection.Genres else LibrarySection.Artists
                detailOrigin = LibrarySection.Discover
            }
            LibrarySection.Settings -> {
                section = detailOrigin
                detailOrigin = LibrarySection.Discover
            }
            LibrarySection.CachedCopies -> section = LibrarySection.Settings
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
    // 迷你播放条小展开面板开关：展开时 Snackbar 上移让位。
    var playerExpanded by remember { mutableStateOf(false) }
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
    val scheme = MaterialTheme.colorScheme
    val backgroundBrush = Brush.verticalGradient(
        0f to scheme.surface,
        0.3f to scheme.surfaceVariant,
        1f to scheme.background
    )
    // 全屏播放页背景保持原有的封面三色渐变（工单 12 再处理），三色 + 强调色一起 800ms 过渡。
    val gradientSpec = tween<Color>(800)
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
        LocalOfflineState provides offlineState
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
                    LibrarySection.Artists, LibrarySection.Genres -> LibraryGroupsContent(
                        state = state,
                        title = s.navLabel().orEmpty(),
                        byGenre = s == LibrarySection.Genres,
                        onOpenGroup = { name ->
                            detailOrigin = section
                            section = LibrarySection.GroupDetail(name, isGenre = s == LibrarySection.Genres)
                        },
                        coverFor = viewModel::albumCover,
                        onRetry = viewModel::loadAlbums,
                        modifier = Modifier.fillMaxSize()
                    )
                    LibrarySection.Search -> SearchContent(
                        state = state,
                        trackCoverFor = viewModel::trackCover,
                        albumCoverFor = viewModel::albumCover,
                        playlistCoverFor = viewModel::playlistCover,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onRetry = { viewModel.updateSearchQuery(state.searchQuery) },
                        onPlayTrack = onPlayTrack,
                        onAddToQueue = onAddToQueue,
                        onOpenAlbum = openAlbum,
                        onOpenPlaylist = openPlaylist,
                        currentTrackId = currentTrack?.id,
                        isPlaying = controllerState.isPlaying,
                        modifier = Modifier.fillMaxSize()
                    )
                    LibrarySection.QQDownload -> {
                        val qq = qqViewModel
                        if (qq != null) {
                            QQDownloadScreen(
                                state = qqState,
                                onQualityChange = qq::updateQuality,
                                onSearchQueryChange = qq::updateSearchQuery,
                                onDownloadSong = { qq.downloadSong(it) },
                                onRequestRedownload = qq::requestRedownload,
                                onConfirmRedownload = qq::confirmRedownload,
                                onDismissRedownload = qq::dismissRedownload,
                                onDownloadAlbum = qq::downloadAlbum,
                                onSelectAlbumTracks = qq::openAlbumPicker,
                                onToggleAlbumTrack = qq::toggleAlbumTrack,
                                onSelectAllAlbumTracks = qq::selectAllAlbumTracks,
                                onClearAlbumSelection = qq::clearAlbumSelection,
                                onConfirmAlbumDownload = qq::confirmAlbumDownload,
                                onRetryAlbumTracks = qq::retryAlbumTracks,
                                onDismissAlbumPicker = qq::dismissAlbumPicker,
                                onRetryCredential = qq::retryCredential,
                                onRefreshCredential = qq::refreshCredential,
                                onRefreshJobs = qq::refreshJobs,
                                onFileOpsKeyInput = qq::updateFileOpsKeyInput,
                                onSaveFileOpsKey = qq::saveFileOpsKey,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    is LibrarySection.AlbumDetail -> AlbumDetailContent(
                        state = state,
                        album = s.album,
                        coverFor = viewModel::trackCover,
                        onPlayTrack = onPlayTrack,
                        onAddToQueue = onAddToQueue,
                        albumCoverFor = viewModel::albumCover,
                        onRetry = { viewModel.loadAlbumTracks(s.album) },
                        currentTrackId = currentTrack?.id,
                        isPlaying = controllerState.isPlaying,
                        modifier = Modifier.fillMaxSize()
                    )
                    is LibrarySection.PlaylistDetail -> PlaylistDetailContent(
                        state = state,
                        playlist = s.playlist,
                        coverFor = viewModel::trackCover,
                        onPlayTrack = onPlayTrack,
                        onAddToQueue = onAddToQueue,
                        playlistCoverFor = viewModel::playlistCover,
                        onRetry = { viewModel.loadPlaylistTracks(s.playlist) },
                        currentTrackId = currentTrack?.id,
                        isPlaying = controllerState.isPlaying,
                        modifier = Modifier.fillMaxSize()
                    )
                    is LibrarySection.GroupDetail -> LibraryGroupDetailContent(
                        state = state,
                        groupName = s.name,
                        isGenre = s.isGenre,
                        onOpenAlbum = openAlbum,
                        coverFor = viewModel::albumCover,
                        onRetry = viewModel::loadAlbums,
                        modifier = Modifier.fillMaxSize()
                    )
                    LibrarySection.CachedCopies -> CachedCopiesContent(
                        state = state,
                        viewModel = viewModel,
                        albumCoverFor = viewModel::albumCover,
                        onOpenAlbum = openAlbum,
                        modifier = Modifier.fillMaxSize()
                    )
                    LibrarySection.Settings -> SettingsContent(
                        state = state,
                        viewModel = viewModel,
                        controllerState = controllerState,
                        onOpenCachedCopies = { section = LibrarySection.CachedCopies },
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
                    .padding(horizontal = 12.dp, vertical = 8.dp)
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
            onExpandedChange = { playerExpanded = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
        // Snackbar 提示（悬浮在播放条上方，显示「已添加到播放队列」反馈）；
        // 小展开面板展开时上移让位（面板含进度/队列约 320dp 高）。
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp + if (playerExpanded) 320.dp else 0.dp)
                .padding(horizontal = 12.dp)
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
        // 音量浮窗：按音量键时从顶部玻璃岛正下方冒出来的液态玻璃胶囊。
        // 声明在全屏播放页**之后** = 压在全屏页之上（全屏页背景不透明，先声明就被它盖住了）；
        // 用 backdrop 采样它下面的内容，所以又必须在记进 backdrop 的那层之外——本层正是。
        // 独占关闭时不会弹：那时音量键根本不进 App（ForegroundVolumeKeys 一律放行给系统）。
        VolumeFeedbackOverlay(
            state = volumeFeedback,
            backdrop = backdrop,
            accent = coverAccentAnimated,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = GlassIslandTopSpacing + 8.dp)
        )
        // 多选操作条（工单 05）：玻璃岛正下方悬浮，「全选」只选当前页面的列表。
        if (state.multiSelectIds.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = GlassIslandTopSpacing + 8.dp, start = 16.dp, end = 16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 2.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已选 ${state.multiSelectIds.size} 首",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { viewModel.selectAllTracks(currentListTracks.map { it.id }) },
                        enabled = currentListTracks.isNotEmpty()
                    ) { Text("全选") }
                    TextButton(onClick = viewModel::clearMultiSelect) { Text("取消") }
                    TextButton(
                        onClick = {
                            // 同一个确认框：目标换成勾选的全部曲目
                            deleteTarget = currentListTracks.filter { it.id in state.multiSelectIds }
                        },
                        enabled = state.multiSelectIds.isNotEmpty() && !state.isFileOpsBusy
                    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
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
 * 顶部玻璃岛：悬浮的液态玻璃面板（与播放条同款：四角圆角 + 模糊 + 透镜 + 高光），
 * 装「发现 / 歌曲 / 专辑 / 艺术家 / 流派 / 播放列表 / QQ 下载」与搜索 / 设置 / 退出按钮。
 * chip 分组可横向滑动（分区多了会挤不下），滑动区域两侧有渐变提示，选中项切换时自动滚入可见区。
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
    Row(
        modifier = modifier
            .liquidGlass(
                backdrop = backdrop,
                // 表面色比播放条更实：岛下压着滚动的曲库，太透会看不清 chip 文字。
                surface = glassSurface.copy(alpha = 0.8f)
            )
            .height(Dimens.TouchTargetMedium)
            .padding(horizontal = Dimens.SpaceSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall)
    ) {
        // chip 分组可横向滑动的区域：两侧叠加渐变遮罩提示"还能划"，避免用户以为只有这几个按钮。
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
        // 搜索：放大镜图标，进入搜索页时高亮为品牌色。
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
        IconButton(
            onClick = onShowSettings,
            modifier = Modifier.size(Dimens.TouchTargetSmall)
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "设置",
                tint = if (section == LibrarySection.Settings) {
                    MaterialTheme.colorScheme.primary
                } else {
                    LocalContentColor.current
                }
            )
        }
        IconButton(
            onClick = onSignOut,
            modifier = Modifier.size(Dimens.TouchTargetSmall)
        ) {
            Icon(Icons.Filled.ExitToApp, contentDescription = "退出登录")
        }
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
        contentPadding = PaddingValues(start = Dimens.SpaceMedium, end = Dimens.SpaceMedium, top = GlassIslandTopSpacing, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceTiny)
    ) {
        item { Text("你的音乐", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = Dimens.SpaceSmall)) }
        item {
            Button(
                onClick = onLocalAudio,
                Modifier.fillMaxWidth().padding(top = Dimens.SpaceTiny, bottom = Dimens.SpaceSmall),
                enabled = controllerState.controller != null
            ) { Text("选择手机里的音频") }
        }
        // 会话还没恢复完用灰色波浪；已连上但曲库为空才是"暂无音乐"。
        if (state.tracks == null) item { TrackListSkeleton(count = 7, trailingSpace = true) } else if (tracks.isEmpty()) item { EmptyState("暂无音乐") }
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

/** 离线态与「哪些歌有完整副本」：由 [MusicLibraryScreen] 提供，所有 TrackRow 消费。
 * cachedTrackIds 为 null = 在线（或还没算出来），不标灰。 */
private class OfflineState(
    val active: Boolean,
    val cachedTrackIds: Set<String>?,
    /** 点击没缓存的歌：弹「这首歌没有缓存」提示。 */
    val onUnplayable: (Track) -> Unit
)

private val LocalOfflineState = compositionLocalOf<OfflineState?> { null }

/** 单曲行：小封面 + 标题（大）+ 作者（灰色小字）+ 底部灰色分隔线。取消卡片形式，列表更紧凑。
 * 当前播放曲目：标题高亮为品牌色，封面上叠一层半透明遮罩 + 均衡器跳动条（暂停时静止）。
 * 长按弹出上下文菜单（弹性缩放 + 淡入 + 锚定在长按位置，逐项错峰弹出），支持"添加到播放队列"等操作。
 * 手势用 combinedClickable（Material 水波纹按压反馈），长按触觉由框架内置；触点坐标由旁挂的
 * 轻量 pointerInput 只观察不消费地记录，菜单仍锚定真实触点。
 * 离线时没缓存的歌整行变灰：能看到、但现在放不了——跟「没有这首歌」是两回事。 */
@Composable
private fun TrackRow(
    track: Track,
    cover: JellyfinRequest?,
    onClick: () -> Unit,
    showDivider: Boolean,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    onAddToQueue: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    var rowWindowOffset by remember { mutableStateOf(Offset.Zero) }
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    var lastDownOffset by remember { mutableStateOf(Offset.Zero) }
    var menuOffset by remember { mutableStateOf(IntOffset.Zero) }
    val multiSelect = LocalMultiSelect.current
    // 离线且这首歌没有完整副本 → 整行标灰；点击改为弹「没缓存」提示，不发起播放。
    val offline = LocalOfflineState.current
    val dimmed = offline?.active == true && offline.cachedTrackIds?.contains(track.id) != true
    // 长按与「更多」按钮共用同一个菜单，锚点不同而已。
    fun openMenuAt(point: IntOffset) {
        menuOffset = point
        showMenu = true
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // combinedClickable 提供 Material 水波纹 + 长按；长按触觉（LongPress）由其内部默认开启。
                .combinedClickable(
                    // 多选模式下点击整行 = 勾选，不再触发播放（工单 05）；
                    // 离线没缓存的歌点击 = 弹「没缓存」提示，同样不发起播放（工单 03）。
                    onClick = {
                        when {
                            multiSelect?.active == true -> multiSelect.onToggle(track.id)
                            dimmed -> offline?.onUnplayable?.invoke(track)
                            else -> onClick()
                        }
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
                }
                .padding(vertical = Dimens.SpaceSmall),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 多选模式：行首勾选框只做指示，勾选动作在整行点击上。
            if (multiSelect?.active == true) {
                Checkbox(
                    checked = track.id in multiSelect.selected,
                    onCheckedChange = null
                )
                Spacer(Modifier.width(Dimens.SpaceTiny))
            }
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
            Spacer(Modifier.width(Dimens.SpaceMidMedium))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    // 当前曲目标题用品牌色，与播放队列面板的高亮风格一致；
                    // 离线没缓存的歌整行降透明度（标灰）。
                    color = when {
                        isCurrent -> MaterialTheme.colorScheme.primary
                        dimmed -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        else -> Color.Unspecified
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (dimmed) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Auxio 列表项的「更多」按钮：与长按弹同一个菜单（工单 05）。
            // 锚点取行右端中部，菜单照样贴着按钮弹；多选模式下整行是勾选语义，不显示。
            if (onAddToQueue != null && multiSelect?.active != true) {
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
        }
        // 分隔线从封面右缘开始：封面下方不画线，行之间更干净。
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = Dimens.TouchTargetSmall + Dimens.SpaceMidMedium),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
    // 长按弹出菜单：Popup 作为独立窗口渲染，动画在菜单内容上的 graphicsLayer 进行。
    if (showMenu) {
        val fileOps = LocalTrackFileOps.current
        AddToQueueMenu(
            touchPoint = menuOffset,
            onAddToQueue = { onAddToQueue?.invoke() },
            onRename = fileOps?.let { ops -> { ops.onRename(track) } },
            onDelete = fileOps?.let { ops -> { ops.onDelete(track) } },
            // 正在播的那首删除置灰：文件没了播放器缓冲还在，是诡异 bug 高发区（spec 铁律 3）
            deleteEnabled = !isCurrent,
            onMultiSelect = multiSelect?.let { ms -> { ms.onEnter(track.id) } },
            onDismiss = { showMenu = false }
        )
    }
}

/** 正在播放的封面指示层：淡深色遮罩 + 居中三根均衡器条。
 * 播放中：条高在 4~14dp 间循环跳动（三根相位错开，单周期 800ms）；
 * 暂停中：三根静止为固定高度（4/9/6dp），颜色转为 onSurfaceVariant。 */
@Composable
private fun PlayingIndicatorOverlay(
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    // 播放中取封面主题色（与迷你条进度、全屏进度条同源）；暂停时保持中性灰，一眼看得出没在放。
    val barColor = if (isPlaying) LocalCoverAccent.current else MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "playingBars")
    // 单周期 800ms：偏慢一点，避免在列表里显得躁。
    // InfiniteTransition 没有 animateDp 扩展，用 animateFloat 驱动 0f..1f 再映射到 4~14dp 高度。
    val barSpec = infiniteRepeatable<Float>(
        animation = tween<Float>(800, easing = LinearEasing),
        repeatMode = RepeatMode.Reverse
    )
    val bar1 by transition.animateFloat(0f, 1f, barSpec, label = "bar1")
    val bar2 by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween<Float>(800, easing = LinearEasing), RepeatMode.Reverse, StartOffset(400)),
        label = "bar2"
    )
    val bar3 by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween<Float>(800, easing = LinearEasing), RepeatMode.Reverse, StartOffset(200)),
        label = "bar3"
    )
    // 进度 0f..1f 线性映射到 4~14dp 高度。
    fun barHeight(progress: Float): Dp = (4 + progress * 10).dp
    Box(
        modifier
            .background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            PlayingBar(if (isPlaying) barHeight(bar1) else 4.dp, barColor)
            PlayingBar(if (isPlaying) barHeight(bar2) else 9.dp, barColor)
            PlayingBar(if (isPlaying) barHeight(bar3) else 6.dp, barColor)
        }
    }
}

/** 单根均衡器条：固定 3dp 宽，高度由外部指定。 */
@Composable
private fun PlayingBar(height: Dp, color: Color) {
    Box(
        Modifier
            .width(3.dp)
            .height(height)
            .background(color, RoundedCornerShape(1.5.dp))
    )
}

/** 长按弹出的上下文菜单：「添加到播放队列」+ 文件操作（改名 / 删除，可选项）。
 * 菜单以长按行作为锚点，尽量贴近触摸位置；外围保留阴影安全区，避免动画缩放时裁切阴影。 */
@Composable
private fun AddToQueueMenu(
    touchPoint: IntOffset,
    onAddToQueue: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    deleteEnabled: Boolean = true,
    onMultiSelect: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    // 整体进入动画只有弹性缩放，**没有整体淡入**：菜单带阴影，在同一个 layer 上跑 alpha 会让
    // Android 走离屏合成，阴影在动画里闪（真机反馈）。菜单项自己本来就是逐条淡入的，整体再淡一遍看不出来。
    val menuScale = remember { Animatable(0.85f) }
    // 选中菜单项后：动作立刻执行 + 立刻关菜单，没有退场动画。
    // 之前等两段 120ms 动画播完才动作，手感发黏（真机反馈）。
    fun closeAnd(action: () -> Unit) {
        action()
        onDismiss()
    }
    val shadowPaddingPx = with(LocalDensity.current) { 16.dp.toPx().toInt() }
    LaunchedEffect(Unit) {
        menuScale.snapTo(0.85f)
        menuScale.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 650f))
    }
    Popup(
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val x = (touchPoint.x - shadowPaddingPx).coerceIn(
                    0,
                    (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                )
                val y = (touchPoint.y - popupContentSize.height / 2).coerceIn(
                    0,
                    (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                )
                return IntOffset(x, y)
            }
        },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Box(
            modifier = Modifier.padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = menuScale.value
                        scaleY = menuScale.value
                    }
            ) {
                Surface(
                    modifier = Modifier.width(240.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shadowElevation = 16.dp,
                    tonalElevation = 2.dp,
                    border = BorderStroke(
                        0.5.dp,
                        // 高光描边：浅色主题 0.6 营造玻璃质感；深色主题降到 0.25，避免过亮刺眼。
                        Color.White.copy(alpha = if (isSystemInDarkTheme()) 0.25f else 0.6f)
                    )
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        var menuItemVisible by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { menuItemVisible = true }
                        // 加队列成功的轻触觉反馈。
                        val menuView = LocalView.current
                        fun tapFeedback() = menuView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        StaggeredMenuItem(
                            visible = menuItemVisible,
                            delayMs = 0,
                            icon = { Icon(Icons.Filled.PlaylistAdd, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                            text = "添加到播放队列",
                            onClick = {
                                tapFeedback()
                                closeAnd(onAddToQueue)
                            }
                        )
                        if (onRename != null) {
                            StaggeredMenuItem(
                                visible = menuItemVisible,
                                delayMs = 40,
                                icon = { Icon(Icons.Filled.Edit, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                                text = "改名",
                                onClick = {
                                    tapFeedback()
                                    closeAnd(onRename)
                                }
                            )
                        }
                        if (onDelete != null) {
                            StaggeredMenuItem(
                                visible = menuItemVisible,
                                delayMs = 80,
                                icon = { Icon(Icons.Filled.Delete, null, Modifier.size(20.dp), tint = if (deleteEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                                text = "删除",
                                enabled = deleteEnabled,
                                onClick = {
                                    if (deleteEnabled) {
                                        tapFeedback()
                                        closeAnd(onDelete)
                                    }
                                }
                            )
                        }
                        if (onMultiSelect != null) {
                            StaggeredMenuItem(
                                visible = menuItemVisible,
                                delayMs = 120,
                                icon = { Icon(Icons.Filled.Checklist, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                                text = "多选",
                                onClick = {
                                    tapFeedback()
                                    closeAnd(onMultiSelect)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

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
            title = { Text("修改歌曲信息") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Text(if (state.isFileOpsBusy) "删除中…" else "删除")
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
    enabled: Boolean = true
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
            .graphicsLayer { this.alpha = if (enabled) alpha else alpha * 0.38f }
            .offset(y = offsetY)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
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
    // 换一批按钮转圈动画：请求进行中持续旋转，结束后停在当前角度。
    val infiniteTransition = rememberInfiniteTransition(label = "discoverRefreshRotation")
    val refreshRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label = "discoverRefreshRotationValue"
    )
    // 每日推荐：按当天日期稳定随机挑 4 首（同一天内跨重启不变）。
    // 下拉刷新递增变体编号，立刻换一批（挑选是纯本地计算，不等网络）。
    var picksVariant by rememberSaveable { mutableStateOf(0) }
    val dailyPicks = remember(tracks, picksVariant) {
        discoverDailyPicks(tracks, dayIndex = LocalDate.now().toEpochDay(), count = 4, variant = picksVariant)
    }
    // 下拉刷新：随机专辑 + 三节播放历史一起重拉（ViewModel 里统一清字段放行幂等守卫），
    // 每日推荐是本地计算，递增变体编号立刻换一批。
    val pullRefreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = state.isDiscoverRefreshing,
        onRefresh = {
            picksVariant++
            onRefresh()
        },
        state = pullRefreshState,
        modifier = modifier.fillMaxSize()
    ) {
    LazyColumn(
        modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(start = Dimens.SpaceMedium, end = Dimens.SpaceMedium, top = GlassIslandTopSpacing, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceTiny)
    ) {
        // 三节历史来自服务端，各自独立加载/失败（工单 02）：拉到了就显示，为空就整节隐藏；
        // 还没拉到且这一节没失败时显示横滑骨架——失败过的不再转圈（否则永远等下去）。
        fun historyLoading(section: String) =
            !state.isOffline && section !in state.discoverFailedSections
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
        item(key = "recentlyAdded") {
            DiscoverAlbumStrip(
                title = "最近添加",
                albums = state.recentlyAdded,
                showSkeleton = historyLoading("recentlyAdded"),
                onOpenAlbum = onOpenAlbum,
                coverFor = coverFor
            )
        }
        // 三节历史全部失败：单节失败是静默缺席（不打扰），全挂了才给一次错误 + 重试。
        if (state.discoverFailedSections.size == 3) {
            item(key = "historyError") {
                ErrorState("推荐内容暂时加载失败", onRetry = onRefresh)
            }
        }
        // ── 每日推荐 ─────────────────────────────────────────────
        item(key = "dailyHeader") {
            DiscoverSectionHeader("每日推荐", Modifier.padding(top = Dimens.SpaceMidLarge))
        }
        if (state.tracks == null) {
            // 会话还没恢复完：灰色波浪占位。不能走下面的"登录后这里会推荐…"空态文案——
            // 那是"确实没有内容"，而这里是"还在连"，两者语义完全不同。
            item(key = "dailySkeleton") {
                TrackListSkeleton(count = 3, trailingSpace = true, modifier = Modifier.padding(top = Dimens.SpaceTiny))
            }
        } else if (dailyPicks.isEmpty() && tracks.isEmpty()) {
            item(key = "dailyEmpty") {
                Text(
                    "登录后这里会推荐当天的歌曲",
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
                            modifier = if (state.isLoading) Modifier.rotate(refreshRotation) else Modifier
                        )
                    }
                }
            )
        }
        val albums = state.discoverAlbums
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
                item(key = "albumEmpty") { EmptyState("暂无专辑") }
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
        // 整页都没有可看的内容（登录了、服务端也确实没歌）：给一句明确空态，而不是一片空白。
        val hasAnySection = dailyPicks.isNotEmpty() ||
            state.recentlyPlayed.orEmpty().isNotEmpty() ||
            state.mostPlayed.orEmpty().isNotEmpty() ||
            state.recentlyAdded.orEmpty().isNotEmpty() ||
            state.discoverAlbums.orEmpty().isNotEmpty()
        if (state.tracks != null && !hasAnySection && !state.isLoading) {
            item(key = "discoverEmpty") {
                EmptyState("曲库还是空的——去 QQ 下载页搜歌，或在设置里刷新曲库")
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

/** 横滑 section 的骨架：三张与真卡同宽的占位（封面方块 + 两根文字条）。 */
@Composable
private fun DiscoverStripSkeleton() {
    val shimmer = rememberSkeletonShimmer()
    Row(
        modifier = Modifier.padding(top = Dimens.SpaceSmall),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
    ) {
        repeat(3) {
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
 * 专辑页与艺术家/流派详情页共用，避免两处各写一份卡片样式。 */
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
                Text(
                    album.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    album.trackCount?.let { "${album.artist} · $it 首" } ?: album.artist,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun AlbumsContent(
    state: JellyfinUiState,
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
            modifier = Modifier.fillMaxWidth().padding(top = GlassIslandTopSpacing - Dimens.SpaceSmall)
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val albums = state.albums
            when {
                albums == null && state.isLoading -> {
                    // 骨架屏：2列网格，每个卡片显示占位方块（圆角/间距与 AlbumGridCard 一致，切换不跳变）
                    val shimmer = rememberSkeletonShimmer()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
                    ) {
                        items(6) { // 显示6个骨架占位
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
                                        Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f),
                                        progress = shimmer,
                                        shape = RoundedCornerShape(Dimens.CornerLarge)
                                    )
                                    Column(Modifier.padding(Dimens.SpaceMidMedium), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceTiny)) {
                                        SkeletonBox(
                                            Modifier
                                                .fillMaxWidth(0.7f)
                                                .height(16.dp),
                                            progress = shimmer,
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        SkeletonBox(
                                            Modifier
                                                .fillMaxWidth(0.5f)
                                                .height(14.dp),
                                            progress = shimmer,
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                albums == null && state.errorMessage != null -> ErrorState(state.errorMessage, onRetry = onRetry)
                albums.orEmpty().isEmpty() -> EmptyState("暂无专辑")
                else -> {
                    val albumList = albums.orEmpty()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp),
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

/** 专辑/播放列表详情页加载骨架：复刻真实头部结构（120dp 封面方块 + 右侧两根文字条），
 * 下方 6 个与 TrackRow 同构的占位行（48dp 方块 + 两根文字条）。专辑与播放列表详情共用。 */
@Composable
private fun AlbumDetailSkeleton(modifier: Modifier = Modifier) {
    val shimmer = rememberSkeletonShimmer()
    Column(modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SkeletonBox(Modifier.size(120.dp), progress = shimmer, shape = RoundedCornerShape(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBox(Modifier.fillMaxWidth(0.7f).height(16.dp), progress = shimmer, shape = RoundedCornerShape(4.dp))
                SkeletonBox(Modifier.fillMaxWidth(0.5f).height(14.dp), progress = shimmer, shape = RoundedCornerShape(4.dp))
            }
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(6) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SkeletonBox(Modifier.size(48.dp), progress = shimmer, shape = RoundedCornerShape(8.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SkeletonBox(Modifier.fillMaxWidth(0.9f).height(16.dp), progress = shimmer, shape = RoundedCornerShape(4.dp))
                        SkeletonBox(Modifier.fillMaxWidth(0.5f).height(12.dp), progress = shimmer, shape = RoundedCornerShape(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumDetailContent(
    state: JellyfinUiState,
    album: Album,
    coverFor: (Track) -> JellyfinRequest?,
    onPlayTrack: (List<Track>, String) -> Unit,
    onAddToQueue: (Track) -> Unit,
    albumCoverFor: (Album) -> JellyfinRequest?,
    onRetry: () -> Unit,
    currentTrackId: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    // 详情页顶部玻璃岛已隐藏：只让出状态栏高度，不用再给浮岛留位。
    Column(
        modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp).padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AlbumCover(albumCoverFor(album), album.name, Modifier.size(120.dp))
            Column {
                Text(album.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    album.trackCount?.let { "${album.artist} · $it 首" } ?: album.artist,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val albumTracks = state.albumTracks
            when {
                albumTracks == null && state.isLoading -> AlbumDetailSkeleton(Modifier.fillMaxSize())
                albumTracks == null && state.errorMessage != null -> ErrorState(state.errorMessage, onRetry = onRetry)
                albumTracks.orEmpty().isEmpty() -> EmptyState("该专辑暂无歌曲")
                else -> {
                    val trackList = albumTracks.orEmpty()
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(trackList, key = { _, t -> t.id }) { index, track ->
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
            modifier = Modifier.fillMaxWidth().padding(top = GlassIslandTopSpacing - Dimens.SpaceSmall)
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
                        contentPadding = PaddingValues(bottom = 96.dp),
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

/** 搜索结果页：顶部固定搜索框，下方按「歌曲 / 专辑 / 播放列表」分组展示结果；歌曲点播、专辑 / 播放列表进详情。 */
@Composable
private fun SearchContent(
    state: JellyfinUiState,
    trackCoverFor: (Track) -> JellyfinRequest?,
    albumCoverFor: (Album) -> JellyfinRequest?,
    playlistCoverFor: (Playlist) -> JellyfinRequest?,
    onSearchQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onPlayTrack: (List<Track>, String) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    currentTrackId: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Dimens.SpaceMedium)
            // 让出顶部玻璃岛高度（与列表页同款占位），搜索框固定在岛正下方。
            .padding(top = GlassIslandTopSpacing),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
    ) {
        // 搜索框固定顶部：输入时 ViewModel 防抖后请求；非空时显示清除按钮。
        // 圆角与 QQ 下载页搜索框同档（CornerLarge），两页的搜索语言一致。
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.SpaceSmall),
            shape = RoundedCornerShape(Dimens.CornerLarge),
            placeholder = { Text("搜索歌曲、专辑、播放列表") },
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
        // 离线搜索标识（工单 04）：让用户知道这轮结果来自手机上的快照，
        // 范围比在线搜索小——不是歌丢了。
        if (state.isOfflineSearch) {
            Text(
                "离线搜索 · 只搜手机上的曲库快照",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val results = state.searchResults
            val isEmptyResult = results != null &&
                results.tracks.isEmpty() && results.albums.isEmpty() && results.playlists.isEmpty()
            when {
                state.searchQuery.isBlank() -> Text(
                    "输入关键词搜索音乐库",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.isSearching -> CircularProgressIndicator()
                state.isOfflineSearch && isEmptyResult -> EmptyState("快照里没找到「${state.searchQuery}」\n联网后能搜全曲库")
                state.errorMessage != null -> ErrorState(state.errorMessage, onRetry = onRetry)
                isEmptyResult -> EmptyState("未找到相关内容")
                results == null -> EmptyState("未找到相关内容")
                else -> {
                    val searchResults = results
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceTiny)
                    ) {
                        if (searchResults.tracks.isNotEmpty()) {
                            item { SearchSectionHeader("歌曲") }
                            itemsIndexed(searchResults.tracks, key = { _, t -> t.id }) { index, track ->
                                TrackRow(
                                    track = track,
                                    cover = trackCoverFor(track),
                                    onClick = { onPlayTrack(searchResults.tracks, track.id) },
                                    onAddToQueue = { onAddToQueue(track) },
                                    showDivider = index != searchResults.tracks.lastIndex,
                                    isCurrent = currentTrackId == track.id,
                                    isPlaying = isPlaying,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        if (searchResults.albums.isNotEmpty()) {
                            item { SearchSectionHeader("专辑") }
                            itemsIndexed(searchResults.albums, key = { _, a -> a.id }) { index, album ->
                                SearchResultRow(
                                    cover = albumCoverFor(album),
                                    title = album.name,
                                    subtitle = album.trackCount?.let { "${album.artist} · $it 首" } ?: album.artist,
                                    onClick = { onOpenAlbum(album) },
                                    showDivider = index != searchResults.albums.lastIndex,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        if (searchResults.playlists.isNotEmpty()) {
                            item { SearchSectionHeader("播放列表") }
                            itemsIndexed(searchResults.playlists, key = { _, p -> p.id }) { index, playlist ->
                                SearchResultRow(
                                    cover = playlistCoverFor(playlist),
                                    title = playlist.name,
                                    subtitle = playlist.trackCount?.let { "$it 首" } ?: "播放列表",
                                    onClick = { onOpenPlaylist(playlist) },
                                    showDivider = index != searchResults.playlists.lastIndex,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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

/** 搜索结果里的专辑 / 播放列表行：小封面 + 名称 + 副标题，点击进详情；视觉与 TrackRow 保持一致。 */
@Composable
private fun SearchResultRow(
    cover: JellyfinRequest?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showDivider: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = Dimens.SpaceSmall),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlbumCover(cover, title, Modifier.size(Dimens.TouchTargetSmall))
            Spacer(Modifier.width(Dimens.SpaceMidMedium))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = Dimens.TouchTargetSmall + Dimens.SpaceMidMedium),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun PlaylistDetailContent(
    state: JellyfinUiState,
    playlist: Playlist,
    coverFor: (Track) -> JellyfinRequest?,
    onPlayTrack: (List<Track>, String) -> Unit,
    onAddToQueue: (Track) -> Unit,
    playlistCoverFor: (Playlist) -> JellyfinRequest?,
    onRetry: () -> Unit,
    currentTrackId: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    // 详情页顶部玻璃岛已隐藏：只让出状态栏高度，不用再给浮岛留位。
    Column(
        modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp).padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AlbumCover(playlistCoverFor(playlist), playlist.name, Modifier.size(120.dp))
            Column {
                Text(playlist.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    playlist.trackCount?.let { "$it 首" } ?: "播放列表",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val playlistTracks = state.playlistTracks
            when {
                playlistTracks == null && state.isLoading -> AlbumDetailSkeleton(Modifier.fillMaxSize())
                playlistTracks == null && state.errorMessage != null -> ErrorState(state.errorMessage, onRetry = onRetry)
                playlistTracks.orEmpty().isEmpty() -> EmptyState("该播放列表暂无歌曲")
                else -> {
                    val trackList = playlistTracks.orEmpty()
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(trackList, key = { _, t -> t.id }) { index, track ->
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
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = Dimens.SpaceSmall, bottom = Dimens.SpaceSmall)
        )
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
        // 回收站：删除的歌都先挪到这里（E:\file\Te_Music_deleted），能恢复、能真清空。
        SettingsSectionHeader("存储")
        SurfaceCard { RecycleBinSection(state = state, viewModel = viewModel) }
        // 手机缓存：听过的歌在手机上留的副本，离线能听。占了空间就得看得见（ADR-0003）。
        SurfaceCard {
            MediaCacheSection(state = state, viewModel = viewModel, onOpenCachedCopies = onOpenCachedCopies)
        }
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
    // 每次进设置页重拉一次：删除/恢复随时会改变回收站内容，不缓存。
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

/** 已缓存列表页（CONTEXT.md「已缓存列表」）：按专辑分组的完整副本。点专辑进详情页，
 * 每张专辑可单独删副本——删的是手机副本，歌还在电脑上（确认框说清，同 ADR-0003）。 */
@Composable
private fun CachedCopiesContent(
    state: JellyfinUiState,
    viewModel: JellyfinViewModel,
    albumCoverFor: (Album) -> JellyfinRequest?,
    onOpenAlbum: (Album) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = Dimens.SpaceMedium)
            .padding(top = Dimens.SpaceSmall)
    ) {
        Text(
            "已缓存",
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
            ) { EmptyState("还没有缓存的音乐\n先在线听几首，副本会自动留下") }
            else -> {
                var deleteTarget by remember { mutableStateOf<CachedAlbum?>(null) }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    items(cachedAlbums, key = { it.album.id }) { entry ->
                        CachedAlbumRow(
                            entry = entry,
                            cover = albumCoverFor(entry.album),
                            busy = state.isCacheBusy,
                            onOpen = { onOpenAlbum(entry.album) },
                            onDelete = { deleteTarget = entry }
                        )
                    }
                }
                deleteTarget?.let { target ->
                    AlertDialog(
                        onDismissRequest = { deleteTarget = null },
                        title = { Text("删掉这张专辑的副本？") },
                        text = {
                            Text(
                                "将删掉《${target.album.name}》在手机上的 ${target.cachedCount} 首副本。\n" +
                                    "歌还在电脑上一首没动，下次联网听过会自动再攒；正在播的那首会保留。"
                            )
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !state.isCacheBusy,
                                onClick = {
                                    val album = target.album
                                    deleteTarget = null
                                    viewModel.deleteAlbumCopies(album)
                                }
                            ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { deleteTarget = null }) { Text("取消") }
                        }
                    )
                }
            }
        }
    }
}

/** 已缓存列表的一行：封面 + 专辑名 + 进度，右侧删除按钮。整行点击进专辑详情页。
 * 与设置页 / QQ 下载页同用 [SurfaceCard]（列表里的卡片语言）。 */
@Composable
private fun CachedAlbumRow(
    entry: CachedAlbum,
    cover: JellyfinRequest?,
    busy: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    SurfaceCard(onClick = onOpen) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                    "${entry.album.artist} · 已缓存 ${entry.cachedCount}/${entry.totalCount} 首",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Filled.Delete, contentDescription = "删除这张专辑的副本")
            }
        }
    }
}

/** 设置页的手机缓存区块（工单 05）：占用 + 首数 + 一键清空。
 * 清空的只是手机上的副本，歌还在电脑上——确认框里必须说清这一点（ADR-0003）。 */
@Composable
private fun MediaCacheSection(
    state: JellyfinUiState,
    viewModel: JellyfinViewModel,
    onOpenCachedCopies: () -> Unit
) {
    // 每次进设置页重拉：播放随时在攒副本，数字不能停在旧值。
    LaunchedEffect(Unit) { viewModel.loadCacheStats() }
    var showClearConfirm by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("手机缓存", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.cacheTrackCount == 0) "听过的歌自动存在手机上，没网也能听"
                    else "${state.cacheTrackCount} 首 · ${formatBytes(state.cacheBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onOpenCachedCopies,
                enabled = state.cacheTrackCount > 0
            ) { Text("已缓存") }
            TextButton(
                onClick = { showClearConfirm = true },
                enabled = state.cacheTrackCount > 0 && !state.isCacheBusy
            ) { Text("清空") }
        }
        if (state.isCacheBusy) {
            Text(
                "正在清空…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.cacheMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { if (!state.isCacheBusy) showClearConfirm = false },
            title = { Text("清空手机缓存？") },
            text = {
                Text(
                    "将删掉手机上的 ${state.cacheTrackCount} 首缓存副本（${formatBytes(state.cacheBytes)}）。\n" +
                        "歌还在电脑上一首没动，清掉的只是手机里的副本；下次联网听过会自动再攒。"
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.isCacheBusy,
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearCache()
                    }
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(enabled = !state.isCacheBusy, onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 文件大小的短表示：回收站列表用。 */
private fun formatBytes(bytes: Long): String = when {
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
    Box(Modifier.size(40.dp)) {
        if (track == null) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
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

/** 底部播放浮岛：折叠时显示封面 + 歌名 + 播放/暂停 + 下一首；点整条打开全屏播放页，右侧箭头切换小展开面板。 */
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
    onExpandedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 小展开面板开关：右侧箭头按钮切换；展开/收起变化上报给外部（Snackbar 让位等）。
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { onExpandedChange(expanded) }
    val controller = controllerState.controller
    val hasMedia = controller != null && controllerState.durationMs > 0
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
    // 底部细进度线的比例：直接按轮询到的位置/时长算（拖动同步由展开面板的进度条负责）；
    // 时长未知（<=0）整条显示未播色。
    val miniProgressFraction = if (controllerState.durationMs > 0) {
        (controllerState.positionMs / controllerState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    // 液态玻璃容器。这里是**全 App 玻璃材质的基准**（模糊 / 透镜 / 圆角的默认值都取自此处），
    // 其余玻璃面统一走 liquidGlass，别再各写各的数字。
    Box(
        modifier
            .fillMaxWidth()
            .liquidGlass(backdrop = backdrop, surface = glassSurface.copy(alpha = 0.55f))
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
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
                Spacer(Modifier.width(12.dp))
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
                // 上一首
                IconButton(
                    onClick = {
                        controlView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        controller?.seekToPrevious()
                    },
                    enabled = controller != null,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "播放上一首歌曲")
                }
                // 播放/暂停
                IconButton(
                    onClick = {
                        controlView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        controller?.let { if (it.isPlaying) it.pause() else it.play() }
                    },
                    enabled = controller != null,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        if (controllerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (controllerState.isPlaying) "暂停播放" else "开始播放"
                    )
                }
                // 下一首
                IconButton(
                    onClick = {
                        controlView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        controller?.seekToNext()
                    },
                    enabled = controller != null,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "播放下一首歌曲")
                }
                // 展开/收起小面板（more = 展开更多内容，less = 收起更少内容）；进全屏页走点整条或面板里的全屏按钮
                IconButton(
                    onClick = { expanded = !expanded },
                    enabled = controller != null,
                    modifier = Modifier.size(48.dp)
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
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        // 面板内容（含播放队列）超出屏幕时整体可滚动，避免底部浮岛被裁掉。
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
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
                        enabled = hasMedia,
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
                            modifier = Modifier.size(48.dp)
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
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
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
                            .padding(top = 8.dp)
                            .clickable { queueExpanded = !queueExpanded }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "播放队列（${queue.size} 首）",
                            style = MaterialTheme.typography.titleSmall,
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
                                    .heightIn(max = 240.dp)
                                    .wrapContentHeight()
                            ) {
                                items(queue, key = { it.id }) { item ->
                                    ReorderableItem(reorderableState, key = item.id) { dragging ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
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
                                                .padding(vertical = 4.dp),
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
                                                modifier = Modifier.size(40.dp)
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
                .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
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
    if (request == null) {
        Box(
            modifier
                .clip(shape)
                .shadow(2.dp, shape, ambientColor = Color(0x1A000000), spotColor = Color(0x1A000000))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        return
    }
    val model = ImageRequest.Builder(LocalContext.current)
        .data(request.url)
        .addHeader("Authorization", request.headers["Authorization"].orEmpty())
        .crossfade(true)
        .build()
    Box(modifier.clip(shape).shadow(3.dp, shape, ambientColor = Color(0x26000000), spotColor = Color(0x26000000))) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            placeholder = placeholder,
            error = placeholder,
            modifier = Modifier.fillMaxSize()
        )
        // 底部微渐变：营造封面嵌入感，提升视觉层次。
        // 深色主题下背景更暗，同强度黑色几乎不可见，加强到约 20% 才有层次。
        val scrimColor = if (isSystemInDarkTheme()) Color(0x33000000) else Color(0x08000000)
        Box(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to scrimColor
                    )
                )
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
    // 前台音量键接管：把"独占开关 + 下发口"交给 Activity 的 dispatchKeyEvent（MIUI 不向
    // 远程音量会话派发音量键，见 ForegroundVolumeKeys 注释）。控制器没连上时清空，音量键
    // 立刻还给系统。独占关闭也走同一条清空路径——这就是"关掉开关马上交还系统"。
    DisposableEffect(controller, usbExclusiveEnabled) {
        val current = controller
        if (current == null) {
            volumeKeys.snapshot = null
            volumeKeys.sink = null
        } else {
            volumeKeys.snapshot =
                ForegroundVolumeKeys.Snapshot(exclusiveEnabled = usbExclusiveEnabled)
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
