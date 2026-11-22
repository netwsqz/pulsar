package com.example.pulsar

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.example.pulsar.data.jellyfin.Lyrics
import com.example.pulsar.domain.model.Track
import com.example.pulsar.playback.PlaybackDiagnostics
import com.example.pulsar.playback.PlaybackIntent
import com.example.pulsar.playback.PlaybackMode
import com.example.pulsar.playback.PlaybackOutputInfo
import com.example.pulsar.playback.PlaybackUiState
import com.example.pulsar.playback.SEEK_SETTLE_TIMEOUT_MS
import com.example.pulsar.playback.formatPosition
import com.example.pulsar.playback.positionReachedSeekTarget
import com.example.pulsar.ui.components.LiquidGlassSlider
import com.example.pulsar.ui.components.PlaybackCover
import com.example.pulsar.ui.components.QueueDragState
import com.example.pulsar.ui.components.queueDragHandle
import com.example.pulsar.ui.components.queuePlacement
import com.example.pulsar.ui.components.rememberSegmentTick
import com.example.pulsar.ui.components.immersiveSliderColors
import com.example.pulsar.ui.components.liquidGlass
import com.example.pulsar.ui.theme.CoverGradient
import com.example.pulsar.ui.theme.Dimens
import com.example.pulsar.ui.theme.PoppinsFontFamily
import com.example.pulsar.ui.theme.THEME_TRANSITION_MS
import com.example.pulsar.ui.theme.systemMotionScale
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

// ===== 深色沉浸页白字体系（与系统主题无关，全屏页永远深底白字） =====
// **刻意保留白色、不换 onSurface**（工单 15 的例外）：本页背景恒为深色封面渐变 + 黑纱，
// 白字在任何封面下都有对比度；换成跟封面推导的 onSurface 反而会在浅色封面上糊掉。
private val NpTextPrimary = Color.White
private val NpTextSecondary = Color.White.copy(alpha = 0.7f)
private val NpTextFaint = Color.White.copy(alpha = 0.5f)

/**
 * 迷你条小封面 ↔ 全屏页大封面共享同一个 key：打开/收起全屏页时封面跨布局连续飞行。
 * 两侧（MiniPlayerBar / NowPlayingScreen）必须都注册同一个 key 才会匹配上。
 */
internal const val NOW_PLAYING_COVER_KEY = "now-playing-cover"

internal const val NOW_PLAYING_OPEN_MS = 270
internal const val NOW_PLAYING_CLOSE_MS = 190
private const val NOW_PLAYING_RETURN_MS = 230

/** B 档：到大封面后只越过 2.2% 的路程，最后 65ms 轻轻贴住。其他方向不越界。 */
private val CoverArrivalEasing = Easing { fraction ->
    val peakTime = (NOW_PLAYING_OPEN_MS - 65f) / NOW_PLAYING_OPEN_MS
    if (fraction < peakTime) {
        1.022f * FastOutSlowInEasing.transform(fraction / peakTime)
    } else {
        1.022f - 0.022f * FastOutSlowInEasing.transform((fraction - peakTime) / (1f - peakTime))
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
internal val NowPlayingCloseBounds = BoundsTransform { _, _ -> tween(NOW_PLAYING_CLOSE_MS) }

@OptIn(ExperimentalSharedTransitionApi::class)
private val NowPlayingOpenBounds = BoundsTransform { from, to ->
    // 只在小封面展开到大封面时越过终点；接近落位的反向操作用同一条平滑曲线收敛。
    tween(NOW_PLAYING_OPEN_MS, easing = if (to.width > from.width * 1.1f) CoverArrivalEasing else FastOutSlowInEasing)
}

/**
 * 下滑收起全屏播放页的阈值：拖动位移超过屏高的 15% 即判定收起。
 */
internal const val NOW_PLAYING_DISMISS_RATIO = 0.15f

/**
 * 是否用横版骨架（左封面 + 右歌词）。
 *
 * 判定依据是**窗口宽高**而不是 `Configuration.orientation`：分屏、折叠屏展开、平板悬浮窗
 * 都可能出现「设备是竖的、窗口是宽的」，那时同样需要横版骨架才不会把封面压成一条。
 * 正方形（宽等于高）按竖版处理——竖版骨架在正方窗口里仍能看，横版会挤掉歌词。
 * 尺寸非正（首帧 / 异常）一律按竖版，避免用 0 去算比例。
 */
internal fun isLandscapeLayout(widthDp: Int, heightDp: Int): Boolean {
    if (widthDp <= 0 || heightDp <= 0) return false
    return widthDp > heightDp
}

/**
 * 记录「进页前的方向」，供收起全屏页时还原。
 *
 * **不能用 `-1` 之类的哨兵值表示「还没锁过」**：`SCREEN_ORIENTATION_UNSPECIFIED` 本身就是
 * `-1`，而 Manifest 没声明 `screenOrientation` 时 `requestedOrientation` 恰好就是它。
 * 哨兵与真实值撞车会让还原判断永远为假，App 永久卡在锁定的方向上。
 *
 * 返回新记录；[previous] 为 null 表示这是第一次锁，此时把 [currentOrientation] 存下来。
 * 已经记过（非 null）就原样返回，不覆盖——后续再按旋转按钮时「进页前方向」不该被改写。
 */
internal fun orientationToRestoreOnClose(
    previous: Int?,
    currentOrientation: Int
): Int = previous ?: currentOrientation

/**
 * `rememberSaveable` 用的「可空 Int」保存器：null 编码成 `Int.MIN_VALUE`（真实方向值都是
 * `ActivityInfo.SCREEN_ORIENTATION_*` 里的小整数，不会撞上）。
 *
 * 为什么要自定义：`mutableStateOf<Int?>` 的默认 saver 会把 null 当「没有值」，重建后
 * 也能恢复成 null，行为其实是对的；但把 null 的语义显式写出来，才不会被后来的人改成
 * `-1` 之类的哨兵（那正是踩过的坑：UNSPECIFIED 本身就等于 -1）。
 */
internal val NullableIntSaver: Saver<Int?, Int> = Saver(
    save = { value -> value ?: Int.MIN_VALUE },
    restore = { encoded -> if (encoded == Int.MIN_VALUE) null else encoded }
)

/**
 * 页面内旋转按钮的目标方向。
 *
 * 竖版 → 强制横屏，横版 → 强制竖屏；两个方向都用 `SENSOR_*` 变体：锁定后仍跟随传感器
 * 左右翻转（横屏时「刘海朝左 / 朝右」都能用），比 `LANDSCAPE` / `PORTRAIT` 更符合手机习惯。
 * 这只是**页面内**的方向锁，收起全屏页时由宿主把进页前的方向还回去——不在 App 里长期锁死。
 */
internal fun nowPlayingRotationTarget(currentlyLandscape: Boolean): Int =
    if (currentlyLandscape) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    } else {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

/**
 * 横版左右两栏的宽度分配：左封面 [LANDSCAPE_LEFT_RATIO]，右歌词补足 1。
 *
 * 42% 是「封面够大、歌词还能读到完整一行」的折中：更窄封面会跟着矮窗口一起缩成小块，
 * 更宽则歌词行平均要折成三行以上。
 *
 * **界面用的是 Compose 的 `weight`，本函数不是生产路径**——它只把「两栏怎么分」这条规则
 * 钉成可测的纯逻辑，防止两边权重加起来不等于 1（`weight` 是相对权重，写成
 * `0.42 : 1` 会得到 29.6 / 70.4 而不是 42 / 58，这个坑真踩过一次）。
 * 两栏之和恒等于可用宽度；宽度非正时两栏都给 0，不会算出负宽度。
 */
internal data class LandscapeSplit(val leftWidthDp: Float, val rightWidthDp: Float)

/** 横版左栏占比（封面列）。界面与 [landscapeSplit] 共用这一个常量。 */
internal const val LANDSCAPE_LEFT_RATIO = 0.42f

/** 按可用宽度算横版两栏宽度（规则校验用，界面实际走 `weight`）。 */
internal fun landscapeSplit(availableWidthDp: Int): LandscapeSplit {
    val width = availableWidthDp.coerceAtLeast(0).toFloat()
    val left = width * LANDSCAPE_LEFT_RATIO
    return LandscapeSplit(leftWidthDp = left, rightWidthDp = width - left)
}

/**
 * 下滑收起判定：拖动位移超过屏高 15% 即收起。
 * 防御：负位移（上滑）、非正屏高、NaN / 无穷一律返回 false，避免手势异常时误关页面。
 * 一期先钉死阈值规则，手势关闭批次直接调用。
 */
internal fun shouldDismissNowPlaying(draggedOffsetPx: Float, screenHeightPx: Float): Boolean {
    if (!draggedOffsetPx.isFinite() || !screenHeightPx.isFinite()) return false
    if (draggedOffsetPx <= 0f || screenHeightPx <= 0f) return false
    return draggedOffsetPx > screenHeightPx * NOW_PLAYING_DISMISS_RATIO
}

/**
 * 页指示器滑块的连续位置（单位：页）。
 *
 * 取 `currentPage + currentPageOffsetFraction`——横滑过程中这个和是**连续**变化的
 * （停在第 1 页与第 2 页正中间时是 1.5），指示器因此能跟着手指走，而不是翻页时才跳。
 * 位置钳制在 `[0, pageCount - 1]`：越界回弹期间不会滑出首尾页之外。
 * 偏移为非有限值时回落到当前页；页数非正返回 0（无指示器可画）。
 */
internal fun indicatorOffsetFraction(
    currentPage: Int,
    pageOffsetFraction: Float,
    pageCount: Int
): Float {
    if (pageCount <= 0) return 0f
    val offset = if (pageOffsetFraction.isFinite()) pageOffsetFraction else 0f
    return (currentPage + offset).coerceIn(0f, (pageCount - 1).toFloat())
}

/**
 * 指示器滑块在滑动中的拉伸系数：静止为 1，滑到两页正中间时最大。
 *
 * `currentPageOffsetFraction` 在页边界间大致取值 -0.5..0.5，取绝对值乘 2 归一到 0..1，
 * 于是「刚起步」拉得少、「正中间」拉得最多 —— 滑块被手指拽着走时像一滴液体在拉长。
 * 非有限值按静止处理（返回 1，绝不返回 NaN 去污染宽度计算）。
 */
internal fun indicatorStretch(pageOffsetFraction: Float, maxStretch: Float = 0.5f): Float {
    if (!pageOffsetFraction.isFinite()) return 1f
    val magnitude = (kotlin.math.abs(pageOffsetFraction) * 2f).coerceIn(0f, 1f)
    return 1f + maxStretch * magnitude
}

/**
 * 全屏播放页（hi-fi 深色沉浸改版，三页横滑）。主体为 [HorizontalPager]：
 * 第 1 页歌词（同步逐行高亮滚动 / 纯文本静态 / 无歌词空态）→
 * 第 2 页封面（居中大封面，圆角 20dp）→ 第 3 页播放队列（整页列表：拖拽排序、跳播、移除）。
 * 页间指示器：三根小横条。页面上下的固定骨架（标题 / 模式行 / 输出信息 / 进度条 / 控制行）
 * 不随横滑移动，只有中区三页切换。
 * 迷你播放条和全屏页共用一张运动中的封面；其余内容只做淡入淡出。
 * 页内下滑直接跟手，超过阈值从松手位置收起，未过阈值或取消则平稳回位。
 * 队列数据用 [PlaybackUiState.queue]（QueueTrack 快照，isCurrent 标记当前行）；拖拽期间的
 * 暂态顺序来自与迷你条共用的 [QueueDragState]，两者合起来就是队列页显示的内容（#28）。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun NowPlayingScreen(
    active: Boolean,
    playback: PlaybackUiState,
    /** 队列拖拽暂态：与迷你条共用同一个实例，队列真相仍是 [playback] 的 queue（#28）。 */
    queueDrag: QueueDragState,
    onPlaybackIntent: (PlaybackIntent) -> Boolean,
    currentTrack: Track?,
    artwork: Transition<Bitmap?>,
    backdropBitmap: Bitmap?,
    gradientColors: CoverGradient,
    lyrics: Lyrics,
    onLineSeek: (Long) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClose: () -> Unit,
    /** 请求锁定方向（`ActivityInfo.SCREEN_ORIENTATION_*`）。方向锁归父层持有，见下方注释。 */
    onToggleOrientation: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // 播放模式由会话持有，本地只读；点按钮发 CycleMode 意图。
    val playbackMode = playback.mode
    val controlsEnabled = active && playback.isConnected
    val hasMedia = controlsEnabled && playback.durationMs > 0
    // 横竖版式判定：按**窗口**宽高（不是设备方向），分屏 / 折叠屏展开后变宽同样走横版骨架。
    val windowSizeDp = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val windowWidthDp = with(density) { windowSizeDp.width.toDp().value.toInt() }
    val windowHeightDp = with(density) { windowSizeDp.height.toDp().value.toInt() }
    val landscape = isLandscapeLayout(windowWidthDp, windowHeightDp)
    // 本页永远是深底白字，系统栏图标因此恒用亮色（不跟主题深浅走）。
    // 沉浸式：进入全屏页时把状态栏 / 导航栏都藏起来；退出时把「跟随系统深浅」还回去——
    // 主界面由工单 14 的 edge-to-edge 统一设置。
    // 注意：边缘上滑唤出的**瞬态**系统栏是可见的，那 2-3 秒内 statusBarsPadding 会返回非 0，
    // 页内内容会跟着下移一点再弹回。这是隐藏系统栏的固有代价，换来的是正常态真正满屏。
    // 全屏页打开期间不调 enableEdgeToEdge：它会重设系统栏 style（含 scrim），
    // 跟本页的隐藏 / 恢复互相打架。
    val view = LocalView.current
    DisposableEffect(active, view) {
        if (!active) return@DisposableEffect onDispose {}
        val window = view.context.findActivity()?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
        if (insetsController != null) {
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
            // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE：隐藏后仍可从边缘滑动临时唤出，
            // 用户不会觉得系统栏「被吃掉」。
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            // 先恢复显示再调深浅，否则隐藏态下设的外观可能不生效。
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            val dark = (view.context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            insetsController?.isAppearanceLightStatusBars = !dark
            insetsController?.isAppearanceLightNavigationBars = !dark
        }
    }
    // 页面内的横竖切换按钮：**方向锁由 MainActivity 持有**——转屏会重建 Activity，
    // 若在这里用 DisposableEffect 记录 / 还原方向，重建瞬间就会把刚锁上的横屏又还原掉，
    // 屏幕会立刻弹回。父层把「进页前方向」存进 rememberSaveable，收起页面时再还回去。
    val toggleOrientation: (Int) -> Unit = onToggleOrientation
    // 全屏页的玻璃面取色板 surface（半透明），不再是写死的白膜；文字保持白色系。
    // 本页恒为深底（封面 + 黑纱），所以玻璃膜必须**取色板里较亮的那一端**：
    // 浅色色板的 surface 接近白，深色色板的 onSurface 接近白；反过来用会让深色模式的
    // 玻璃变成一层几乎看不见的黑膜，按钮直接糊进背景。
    val scheme = MaterialTheme.colorScheme
    val glassBase = if (scheme.surface.luminance() > 0.5f) scheme.surface else scheme.onSurface
    val glassSurface = glassBase.copy(alpha = 0.12f)
    val trackSurface = glassBase.copy(alpha = 0.16f)
    // 进度状态：拖动中显示拖动值，松手才 seek，避免边拖边跳。
    val durationSec = (playback.durationMs / 1000f).coerceAtLeast(1f)
    var sliderPosition by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    // 松手 seek 后播放器上报的位置要等下一次轮询（500ms）才追上。追上之前继续显示拖动值，
    // 否则进度条会先弹回**拖动前**的旧位置、再跳到新位置 —— 真机可见的「闪一下」。
    // 非 null 即锁定中，值是松手时锁定的拖动位置。
    var seekHoldSec by remember { mutableStateOf<Float?>(null) }
    // 位置追上目标 → 放开锁定，显示值交还给播放器。
    LaunchedEffect(seekHoldSec, playback.positionMs) {
        val target = seekHoldSec ?: return@LaunchedEffect
        if (positionReachedSeekTarget(playback.positionMs / 1000f, target)) seekHoldSec = null
    }
    // 超时兜底：seek 被忽略（无媒体 / 播放器已销毁）时位置永远追不上，别把显示值锁死。
    LaunchedEffect(seekHoldSec) {
        if (seekHoldSec == null) return@LaunchedEffect
        delay(SEEK_SETTLE_TIMEOUT_MS)
        seekHoldSec = null
    }
    // 切歌放开：新歌位置从 0 起算，旧拖动值已无意义（拖到歌尾松手后正好会触发自动切歌）。
    val currentQueueId = playback.currentMediaItem?.mediaId
    LaunchedEffect(currentQueueId) { seekHoldSec = null }
    // 播放队列：显示顺序 = 会话队列（唯一真相）+ 与迷你条共用的拖拽暂态。
    // 拖拽中只改暂态的 id 顺序，曲目数据与「正在播」标记永远取会话快照；松手才发 Reorder。
    val tick = rememberSegmentTick()
    val queue = queueDrag.displayed(playback.queue)
    val queueListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(queueListState) { from, to ->
        if (queueDrag.move(from.index, to.index, playback.queue)) tick()
    }
    // 三页横滑：歌词 / 封面 / 队列，默认落在封面页（第 2 页，index 1）。
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { NOW_PLAYING_PAGE_COUNT })
    // 退场没结束就重开，保留封面节点让共享动画反向；歌词或队列仍回到默认封面页。
    var wasClosing by remember { mutableStateOf(false) }
    LaunchedEffect(active, landscape) {
        if (active && wasClosing && !landscape && pagerState.currentPage != 1) {
            pagerState.requestScrollToPage(1)
        }
        wasClosing = !active
    }
    // 输出信息（设备 + 采样率 + 位深），由 PlaybackService 写入诊断单例。
    val outputInfo by PlaybackDiagnostics.info.collectAsStateWithLifecycle()
    // 下滑直接写位移；同一次手势不为每个触点启动协程。
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var returnJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val screenHeightPx = windowSizeDp.height.toFloat()
    val onCloseLatest by rememberUpdatedState(onClose)
    val dragEnabled by rememberUpdatedState(active)
    val motionScale by rememberUpdatedState(systemMotionScale())
    fun returnToRest() {
        returnJob?.cancel()
        if (motionScale == 0f) {
            dragOffset = 0f
        } else if (dragOffset > 0f) {
            returnJob = scope.launch {
                animate(dragOffset, 0f, animationSpec = tween(NOW_PLAYING_RETURN_MS)) { value, _ ->
                    dragOffset = value
                }
            }
        }
    }
    LaunchedEffect(active, screenHeightPx) {
        returnJob?.cancel()
        // 退场保留松手坐标；如果在淡出期间重开，在当前坐标上平滑接续。
        if (active) returnToRest()
    }
    // 接收关闭中的按键要留给下方迷你播放条；退场子树只绘制。
    val dismissDrag = if (active) Modifier.pointerInput(screenHeightPx) {
        detectVerticalDragGestures(
            onDragStart = { returnJob?.cancel() },
            onVerticalDrag = { change, delta ->
                change.consume()
                dragOffset = (dragOffset + delta).coerceAtLeast(0f)
            },
            onDragEnd = {
                if (dragEnabled) {
                    if (shouldDismissNowPlaying(dragOffset, screenHeightPx)) onCloseLatest()
                    else returnToRest()
                }
            },
            onDragCancel = { if (dragEnabled) returnToRest() }
        )
    } else Modifier

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = dragOffset
                val fraction = if (screenHeightPx > 0f) (dragOffset / screenHeightPx).coerceIn(0f, 1f) else 0f
                alpha = 1f - 0.5f * fraction
            }
            .then(if (active) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() })
    ) {
        // 本页局部的玻璃采样源。全屏页是 SharedTransitionLayout 里的独立浮层，拿不到主界面那套
        // backdrop；而它自己的背景（渐变 + 模糊封面 + scrim）正好最适合当玻璃底，于是就地自建一个。
        // 关键：**只录背景、不录控件**——进度条 / 播放键画在录制层之外，玻璃不会采样到自己
        // （自引用会让采样滞后一帧；主界面那套录全局是因为它的玻璃条就压在内容上，这里没必要）。
        val backdrop = rememberLayerBackdrop { drawContent() }
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(backdrop)
        ) {
            NowPlayingBackground(
                bitmap = backdropBitmap,
                gradientColors = gradientColors,
                modifier = Modifier.fillMaxSize()
            )
        }
        // 展示用位置（竖版横版共用）：拖动中 / 松手后等播放器追上期间用拖动值，其余时间跟随播放器。
        val displayedPositionSec =
            if (isDragging || seekHoldSec != null) sliderPosition
            else (playback.positionMs / 1000f).coerceIn(0f, durationSec)
        if (landscape) {
            LandscapeNowPlaying(
                active = active,
                dismissDrag = dismissDrag,
                playback = playback,
                queueDrag = queueDrag,
                onPlaybackIntent = onPlaybackIntent,
                currentTrack = currentTrack,
                artwork = artwork,
                gradientColors = gradientColors,
                lyrics = lyrics,
                onLineSeek = onLineSeek,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                backdrop = backdrop,
                glassSurface = glassSurface,
                trackSurface = trackSurface,
                playbackMode = playbackMode,
                hasMedia = hasMedia,
                outputInfo = outputInfo,
                onClose = onClose,
                onToggleOrientation = toggleOrientation,
                durationSec = durationSec,
                displayedPositionSec = displayedPositionSec,
                onSliderChange = { sliderPosition = it; isDragging = true },
                onSliderFinished = {
                    onPlaybackIntent(PlaybackIntent.SeekTo((sliderPosition * 1000).toLong()))
                    isDragging = false
                    // 锁定显示值，等播放器位置追上来再放开（见竖版的 seekHoldSec）。
                    seekHoldSec = sliderPosition
                },
                onSliderCancelled = { isDragging = false },
                // 队列与拖拽暂态由父层持有：竖版 / 横版共用同一份，切版式时不丢拖拽顺序。
                queue = queue,
                queueListState = queueListState,
                reorderableState = reorderableState
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 隐藏系统栏后 statusBarsPadding 变成 0，顶部歌名会钻到前摄挖孔下面
                    // （targetSdk 35 在 Android 15+ 上窗口默认画进挖孔区），所以显式避开。
                    .displayCutoutPadding()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            Box(Modifier.fillMaxWidth().then(dismissDrag)) {
                IconButton(
                    onClick = onClose,
                    enabled = active,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(48.dp)
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起全屏播放页", tint = NpTextPrimary)
                }
                // 标题区：左右各留 48dp 给收起按钮的对称空位，歌名仍以屏幕中线居中且不与按钮重叠。
                // 歌名指定 Poppins SemiBold；CJK 字符 Poppins 不含，自动回退系统字体，属正常表现。
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 歌名：超宽跑马灯（停 2 秒滚一次，滚完再停 2 秒）。
                    Text(
                        text = currentTrack?.title ?: "未选择歌曲",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = PoppinsFontFamily,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = NpTextPrimary,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 2000,
                            repeatDelayMillis = 2000
                        )
                    )
                    // 歌手：未选择歌曲时不显示空行。
                    if (currentTrack != null) {
                        Text(
                            text = currentTrack.artist,
                            style = MaterialTheme.typography.titleMedium,
                            color = NpTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            // ===== 2. 中区三页横滑：歌词 / 封面 / 队列（HorizontalPager，默认封面页）=====
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = active,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    // ---- 第 1 页：歌词 ----
                    0 -> LyricsPage(
                        lyrics = lyrics,
                        positionUs = playback.positionMs * 1000L,
                        onLineSeek = onLineSeek,
                        interactive = active,
                        modifier = Modifier.fillMaxSize()
                    )
                    // ---- 第 2 页：大封面 ----
                    1 -> Box(
                        modifier = Modifier.fillMaxSize().then(dismissDrag),
                        contentAlignment = Alignment.Center
                    ) {
                        // 留白放在封面外，保证共享坐标只包含图片，不在落位时跳一下。
                        Box(Modifier.padding(horizontal = Dimens.SpaceMidMedium)) {
                            NowPlayingCover(
                                artwork, currentTrack?.title,
                                sharedTransitionScope, animatedVisibilityScope,
                                inView = pagerState.currentPage == 1 || active,
                                modifier = Modifier.aspectRatio(1f),
                                cornerRadius = Dimens.CornerExtraLarge
                            )
                        }
                    }
                    // ---- 第 3 页：播放队列（整页列表）----
                    else -> QueuePage(
                        queue = queue,
                        lazyListState = queueListState,
                        reorderableState = reorderableState,
                        enabled = controlsEnabled,
                        onDragStarted = { queueDrag.start(playback.queue) },
                        onDragCancelled = queueDrag::cancel,
                        onDragStopped = {
                            queueDrag.drop(playback.queue) { ordered ->
                                onPlaybackIntent(PlaybackIntent.Reorder(ordered))
                            }
                        },
                        onSeekTo = { mediaId ->
                            onPlaybackIntent(PlaybackIntent.PlayQueueItem(mediaId))
                        },
                        onRemove = { mediaId ->
                            onPlaybackIntent(PlaybackIntent.RemoveFromQueue(setOf(mediaId)))
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            // 页间指示器：底槽三根暗条固定不动（保留「一共几页」的提示），上面一个亮色滑块跟着
            // 横滑**连续**移动——位置取 currentPage + currentPageOffsetFraction，滑到两页正中间时
            // 位置正好在 .5，同时按 indicatorStretch 被拉长一点，像一滴液体被手指拽着走。
            // 全部用 dp 算好再转 px（槽宽固定），不依赖测量，滑动过程不触发额外的布局。
            val indicatorOffset = indicatorOffsetFraction(
                pagerState.currentPage,
                pagerState.currentPageOffsetFraction,
                NOW_PLAYING_PAGE_COUNT
            )
            val indicatorPillWidth = IndicatorPillBaseWidth *
                indicatorStretch(pagerState.currentPageOffsetFraction)
            // 滑块左缘：先按「占满整格」定位，再减去（格宽 - 滑块宽）的一半让它居中于该格。
            val indicatorPillStart = IndicatorSlotWidth * indicatorOffset +
                (IndicatorSlotWidth - indicatorPillWidth) / 2f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(IndicatorSlotWidth * NOW_PLAYING_PAGE_COUNT)
                        .height(IndicatorBarHeight)
                ) {
                    // 底槽：每格一根暗条。
                    Row(Modifier.matchParentSize()) {
                        repeat(NOW_PLAYING_PAGE_COUNT) {
                            Box(Modifier.width(IndicatorSlotWidth).fillMaxHeight()) {
                                Box(
                                    Modifier
                                        .align(Alignment.Center)
                                        .size(width = IndicatorPillBaseWidth, height = IndicatorBarHeight)
                                        .background(NpTextFaint.copy(alpha = 0.45f), RoundedCornerShape(percent = 50))
                                )
                            }
                        }
                    }
                    // 滑块：亮色小胶囊 + 前端柔光；位置 / 宽度每帧变，drawBehind 之外只走 offset，代价很小。
                    Box(
                        Modifier
                            .offset { IntOffset(indicatorPillStart.roundToPx(), 0) }
                            .width(indicatorPillWidth)
                            .fillMaxHeight()
                            .drawBehind {
                                // 收得比较紧的一团柔光（不裁剪、可溢出 6dp 条高）：
                                // 大而淡会糊成一块，紧而亮才像「滑块自己在发光」。
                                val radius = size.height * 1.8f
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(NpTextPrimary.copy(alpha = 0.3f), Color.Transparent),
                                        center = center,
                                        radius = radius
                                    ),
                                    radius = radius,
                                    center = center,
                                    blendMode = BlendMode.Plus
                                )
                            }
                            .clip(RoundedCornerShape(percent = 50))
                            .background(
                                Brush.verticalGradient(
                                    0f to NpTextPrimary,
                                    1f to NpTextPrimary.copy(alpha = 0.82f)
                                )
                            )
                    )
                }
            }
            // ===== 3. 封面下方行：左 = 播放模式，右 = 页指示器对称空位 =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 播放模式：玻璃圆底 + 图标（顺序循环 / 单曲循环 / 随机播放）。
                // 玻璃底直径 44dp，留在 48dp 点击区内部，不改变既有触摸目标。
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .liquidGlass(
                                backdrop = backdrop,
                                surface = glassSurface,
                                shape = CircleShape,
                                // 44dp 小圆：折射按自身半径收窄，面板级的 14/28dp 会把它整个拧歪。
                                lensRefraction = 6.dp,
                                lensDepth = 12.dp
                            )
                    )
                    IconButton(
                        onClick = { onPlaybackIntent(PlaybackIntent.CycleMode) },
                        enabled = controlsEnabled,
                        modifier = Modifier.size(48.dp)
                    ) {
                        // 切换模式时图标交叉淡入淡出，跟进度条那套柔和动效保持一致（不再是硬切）。
                        Crossfade(targetState = playbackMode, label = "playbackModeIcon") { mode ->
                            Icon(
                                playbackModeIcon(mode),
                                contentDescription = "切换播放模式，当前：${mode.label}",
                                tint = if (playback.isConnected) NpTextPrimary else NpTextFaint
                            )
                        }
                    }
                }
                // 右侧原来是对称空位（保持播放模式钮居中于页宽）。现在放「切换到横屏」按钮：
                // 同尺寸 48dp，镜像后左侧的模式钮仍居原位，视觉上仍是两侧各一个圆钮。
                IconButton(
                    onClick = { toggleOrientation(nowPlayingRotationTarget(landscape)) },
                    enabled = active,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Filled.ScreenRotation,
                        contentDescription = "切换到横屏",
                        tint = NpTextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            // ===== 4. 输出信息：走 USB 小尾巴还是系统默认 + 当前采样率 + 位深，白字居中显眼 =====
            Text(
                text = outputLine(outputInfo),
                style = MaterialTheme.typography.titleSmall,
                color = NpTextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
            // ===== 5. 进度条 + 时间（durationSec/sliderPosition/isDragging 与主界面迷你进度线各自独立，
            // 全屏页打开期间迷你条不可见，不存在同步问题）。液态玻璃胶囊：采样本页背景（模糊封面）。 =====
            // 展示用位置在竖版/横版分叉之前算好（横版同一个值）。
            LiquidGlassSlider(
                value = displayedPositionSec,
                onValueChange = { sliderPosition = it; isDragging = true },
                onValueChangeFinished = {
                    onPlaybackIntent(PlaybackIntent.SeekTo((sliderPosition * 1000).toLong()))
                    isDragging = false
                    // 锁定显示值，等播放器位置追上来再放开（见上面的 seekHoldSec）。
                    seekHoldSec = sliderPosition
                },
                // 拖动途中切歌导致 valueRange 变化时手势会被取消：只清拖动状态，不 seek
                // （用户并没有确认这个位置，硬 seek 会让新歌从头跳到旧位置）。
                onValueChangeCancelled = { isDragging = false },
                valueRange = 0f..durationSec,
                gestureKey = currentQueueId,
                backdrop = backdrop,
                enabled = hasMedia,
                // 填充与前端柔光都用当前专辑主色（无封面时回退品牌靛蓝）。
                colors = immersiveSliderColors(gradientColors.accent, trackSurface = trackSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatPosition((displayedPositionSec * 1000).toLong()), style = MaterialTheme.typography.labelSmall, color = NpTextSecondary)
                Text(formatPosition(playback.durationMs), style = MaterialTheme.typography.labelSmall, color = NpTextSecondary)
            }
            // ===== 6. 底部控制行：只有三键——上一首（44dp）/ 播放暂停（72dp 白色实心圆）/ 下一首（44dp）。
            // 播放模式已挪到封面下方行。 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 上一首：44dp 实心图标，白 90%。
                IconButton(
                    onClick = { onPlaybackIntent(PlaybackIntent.Previous) },
                    enabled = controlsEnabled,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "播放上一首歌曲",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(32.dp)
                    )
                }
                MainPlayButton(
                    playing = playback.isPlaying,
                    enabled = controlsEnabled,
                    backdrop = backdrop,
                    glassSurface = glassSurface,
                    buttonSize = 72.dp,
                    iconSize = 36.dp,
                    lensRefraction = 10.dp,
                    lensDepth = 20.dp,
                    onClick = { onPlaybackIntent(PlaybackIntent.TogglePlayPause) }
                )
                // 下一首：44dp 实心图标，白 90%。
                IconButton(
                    onClick = { onPlaybackIntent(PlaybackIntent.Next) },
                    enabled = controlsEnabled,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "播放下一首歌曲",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
        }  // if (landscape) { ... } else {
    }
}

/**
 * 全屏播放页的横版骨架（窗口宽 > 高时启用，见 [isLandscapeLayout]）。
 *
 * 版式（与竖版共用同一套视觉 Token / 同一批子组件，只重排）：
 * ```
 * ┌─────────────────────────────┐
 * │              │              │
 * │   [ 封面 ]    │   歌词逐行    │
 * │              │   高亮滚动    │
 * │  歌名 / 歌手   │              │
 * ├──────────────┴──────────────┤
 * │  输出信息   ─────●────── 时间 │
 * │      ⏮      ▶      ⏭        │
 * └─────────────────────────────┘
 * ```
 * - 左列固定占宽度的 [LANDSCAPE_LEFT_RATIO]（42%）：封面、歌名 / 歌手。
 * - 右列是歌词页，吃掉剩余宽度与全部高度；**队列**用顶部胶囊切过去——横屏高度本来就矮，
 *   再塞一列队列会把歌词压到只剩两行。队列复用竖版第 3 页同一个 [QueuePage]，拖拽暂态也共用。
 * - 底部固定骨架与竖版一致：输出信息 → 进度条 + 时间 → 三键控制行（含播放模式）。
 *   横屏高度紧张，这几行用紧凑间距，不再叠 `vertical = 8.dp` 之类的大留白。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun LandscapeNowPlaying(
    active: Boolean,
    dismissDrag: Modifier,
    playback: PlaybackUiState,
    queueDrag: QueueDragState,
    onPlaybackIntent: (PlaybackIntent) -> Boolean,
    currentTrack: Track?,
    artwork: Transition<Bitmap?>,
    gradientColors: CoverGradient,
    lyrics: Lyrics,
    onLineSeek: (Long) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    backdrop: com.kyant.backdrop.Backdrop,
    glassSurface: Color,
    trackSurface: Color,
    playbackMode: PlaybackMode,
    hasMedia: Boolean,
    outputInfo: PlaybackOutputInfo,
    onClose: () -> Unit,
    /** 请求锁定方向（`ActivityInfo.SCREEN_ORIENTATION_*`）。方向锁归父层持有，见下方注释。 */
    onToggleOrientation: (Int) -> Unit,
    durationSec: Float,
    displayedPositionSec: Float,
    onSliderChange: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    onSliderCancelled: () -> Unit,
    queue: List<QueueTrack>,
    queueListState: LazyListState,
    reorderableState: ReorderableLazyListState,
    modifier: Modifier = Modifier
) {
    // 右栏在歌词 / 队列之间切换：横屏不另开一列，队列复用竖版第 3 页同一个 QueuePage。
    // **rememberSaveable**：转屏会重建 Activity（Manifest 没声明 configChanges），
    // 用 remember 会让用户刚切到队列的右栏在旋转后弹回歌词。
    var showQueue by rememberSaveable { mutableStateOf(false) }
    val controlsEnabled = active && playback.isConnected

    Column(
        modifier = modifier
            .fillMaxSize()
            // 系统栏已隐藏（inset 为 0），但横屏时刘海会切在左右侧，仍要避开。
            .displayCutoutPadding()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Dimens.SpaceMedium),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ===== 顶部：收起 / 横竖切换 / 右栏切换（歌词 ↔ 队列） =====
        Row(
            modifier = Modifier.fillMaxWidth().then(dismissDrag),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose, enabled = active, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "收起全屏播放页",
                    tint = NpTextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(
                onClick = { onToggleOrientation(nowPlayingRotationTarget(currentlyLandscape = true)) },
                enabled = active,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Filled.ScreenRotation,
                    contentDescription = "切换到竖屏",
                    tint = NpTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            // 右栏切换：胶囊里两个小标签，当前项高亮。
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .liquidGlass(
                        backdrop = backdrop,
                        surface = glassSurface,
                        shape = RoundedCornerShape(percent = 50),
                        lensRefraction = 6.dp,
                        lensDepth = 12.dp
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                PaneChip("歌词", selected = !showQueue, enabled = active) { showQueue = false }
                PaneChip("队列", selected = showQueue, enabled = active) { showQueue = true }
            }
        }
        // ===== 主体：左封面 + 右歌词 / 队列 =====
        // 两栏比例：左封面 [LANDSCAPE_LEFT_RATIO]，右栏吃掉余下的 (1 - 该值)。
        // **两边必须同分母**：weight 是相对权重，`weight(0.42f)` + `weight(1f)` 算出来是
        // 29.6 / 70.4，不是 42 / 58。所以右栏写 `1f - LANDSCAPE_LEFT_RATIO`（= 0.58）。
        // 用 weight 而不是「按窗口宽度算 dp」：weight 直接吃本 Row 的实测宽度，
        // 不受系统栏 / 刘海 / 分屏影响。
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左列：封面 + 歌名歌手。封面再按可用高度收一次。
            Column(
                modifier = Modifier
                    .weight(LANDSCAPE_LEFT_RATIO)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .then(dismissDrag),
                    contentAlignment = Alignment.Center
                ) {
                    val side = minOf(maxWidth, maxHeight)
                    NowPlayingCover(
                        artwork, currentTrack?.title,
                        sharedTransitionScope, animatedVisibilityScope,
                        inView = true,
                        modifier = Modifier.size(side),
                        cornerRadius = Dimens.CornerLarge
                    )
                }
                Spacer(Modifier.height(Dimens.SpaceSmall))
                Text(
                    text = currentTrack?.title ?: "未选择歌曲",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = NpTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                if (currentTrack != null) {
                    Text(
                        text = currentTrack.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = NpTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.width(Dimens.SpaceLarge))
            // 右栏：歌词（默认）或队列。权重与左栏同分母，合起来正好 1。
            Box(
                modifier = Modifier
                    .weight(1f - LANDSCAPE_LEFT_RATIO)
                    .fillMaxHeight()
            ) {
                if (showQueue) {
                    QueuePage(
                        queue = queue,
                        lazyListState = queueListState,
                        reorderableState = reorderableState,
                        enabled = controlsEnabled,
                        onDragStarted = { queueDrag.start(playback.queue) },
                        onDragCancelled = queueDrag::cancel,
                        onDragStopped = {
                            queueDrag.drop(playback.queue) { ordered ->
                                onPlaybackIntent(PlaybackIntent.Reorder(ordered))
                            }
                        },
                        onSeekTo = { mediaId ->
                            onPlaybackIntent(PlaybackIntent.PlayQueueItem(mediaId))
                        },
                        onRemove = { mediaId ->
                            onPlaybackIntent(PlaybackIntent.RemoveFromQueue(setOf(mediaId)))
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LyricsPage(
                        lyrics = lyrics,
                        positionUs = playback.positionMs * 1000L,
                        onLineSeek = onLineSeek,
                        interactive = active,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        // ===== 底部固定骨架：输出信息 → 进度条 + 时间 → 三键控制行 =====
        Text(
            text = outputLine(outputInfo),
            style = MaterialTheme.typography.labelMedium,
            color = NpTextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.SpaceTiny)
        )
        LiquidGlassSlider(
            value = displayedPositionSec,
            onValueChange = onSliderChange,
            onValueChangeFinished = onSliderFinished,
            onValueChangeCancelled = onSliderCancelled,
            valueRange = 0f..durationSec,
            gestureKey = playback.currentMediaItem?.mediaId,
            backdrop = backdrop,
            enabled = hasMedia,
            colors = immersiveSliderColors(gradientColors.accent, trackSurface = trackSurface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.SpaceSmall)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatPosition((displayedPositionSec * 1000).toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = NpTextSecondary
            )
            Text(
                formatPosition(playback.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = NpTextSecondary
            )
        }
        // 三键控制行：横屏下改用紧凑间距，播放键 64dp（竖版 72dp，在矮窗口里偏大）。
        // 间距用 Arrangement.spacedBy 而不是手插固定 Spacer：总宽度不够时（分屏约 320dp 宽）
        // 整行可横向滚动，两端的按钮不会被裁到屏幕外、点击区也不会落在屏外。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 手势导航的 home 手势区在底边中间，正好落在播放键下方。系统栏隐藏后
                // navigationBarsPadding() 为 0，所以单独给 bottom 留一点，避免按播放键时
                // 纵向抖动被系统当成上滑 home。
                .padding(top = Dimens.SpaceTiny, bottom = Dimens.SpaceSmall)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(
                space = Dimens.SpaceLarge,
                alignment = Alignment.CenterHorizontally
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 播放模式（左）
            IconButton(
                onClick = { onPlaybackIntent(PlaybackIntent.CycleMode) },
                enabled = controlsEnabled,
                modifier = Modifier.size(48.dp)
            ) {
                Crossfade(targetState = playbackMode, label = "playbackModeIcon") { mode ->
                    Icon(
                        playbackModeIcon(mode),
                        contentDescription = "切换播放模式，当前：${mode.label}",
                        tint = if (playback.isConnected) NpTextPrimary else NpTextFaint,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            IconButton(
                onClick = { onPlaybackIntent(PlaybackIntent.Previous) },
                enabled = controlsEnabled,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = "播放上一首歌曲",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(30.dp)
                )
            }
            MainPlayButton(
                playing = playback.isPlaying,
                enabled = controlsEnabled,
                backdrop = backdrop,
                glassSurface = glassSurface,
                buttonSize = Dimens.TouchTargetLarge,
                iconSize = 32.dp,
                lensRefraction = 9.dp,
                lensDepth = 18.dp,
                onClick = { onPlaybackIntent(PlaybackIntent.TogglePlayPause) }
            )
            IconButton(
                onClick = { onPlaybackIntent(PlaybackIntent.Next) },
                enabled = controlsEnabled,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "播放下一首歌曲",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(30.dp)
                )
            }
            // 播放模式 / 上一首 / 播放暂停 / 下一首：spacedBy 均匀铺开。
        }
    }
}

@Composable
private fun MainPlayButton(
    playing: Boolean,
    enabled: Boolean,
    backdrop: Backdrop,
    glassSurface: Color,
    buttonSize: Dp,
    iconSize: Dp,
    lensRefraction: Dp,
    lensDepth: Dp,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motionEnabled = systemMotionScale() > 0f
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled && motionEnabled) 0.96f else 1f,
        animationSpec = tween(if (pressed) 80 else 130),
        label = "playButtonPress"
    )
    Box(
        Modifier.size(buttonSize).clip(CircleShape).clickable(
            interactionSource = interaction,
            indication = ripple(color = Color.White),
            enabled = enabled,
            role = Role.Button,
            onClick = onClick
        )
    ) {
        // 点击和读屏都在外层，按压只缩里头的玻璃和图标，不缩小热区。
        Box(
            Modifier.matchParentSize()
                .graphicsLayer {
                    val scale = if (motionEnabled) pressScale else 1f
                    scaleX = scale
                    scaleY = scale
                }
                .liquidGlass(
                    backdrop = backdrop,
                    surface = glassSurface.copy(alpha = if (pressed && enabled) 0.22f else glassSurface.alpha),
                    shape = CircleShape,
                    lensRefraction = lensRefraction,
                    lensDepth = lensDepth
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "暂停播放" else "开始播放",
                tint = if (enabled) NpTextPrimary else NpTextFaint,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NowPlayingCover(
    artwork: Transition<Bitmap?>,
    title: String?,
    sharedTransitionScope: SharedTransitionScope,
    visibility: AnimatedVisibilityScope,
    inView: Boolean,
    modifier: Modifier,
    cornerRadius: Dp
) {
    // 歌词/队列上看不到大封面时，不拿 Pager 预组合的屏外节点做退场起点。
    val coverModifier = if (inView) with(sharedTransitionScope) {
        Modifier.sharedElement(
            rememberSharedContentState(NOW_PLAYING_COVER_KEY),
            animatedVisibilityScope = visibility,
            boundsTransform = NowPlayingOpenBounds
        ).then(modifier)
    } else modifier
    PlaybackCover(artwork, title, coverModifier, cornerRadius)
}

/** 横屏右栏的切换标签（歌词 / 队列）。选中项纯白，未选中半透明。 */
@Composable
private fun PaneChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    // 用 Box 撑出最小触摸高度：直接给 Text 加 padding 的话可点区域只有文字那 ~27dp。
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = Dimens.SpaceMidMedium),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) NpTextPrimary else NpTextFaint
        )
    }
}

/** 从 Compose 的 Context 里取宿主 Activity：系统栏图标只能通过它的 window 设置。 */
internal tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 全屏播放页的背景三层：封面三色渐变 → 96px 小图拉伸 + 高斯模糊 → 深色 scrim。
 *
 * 单独抽成一个 composable 是为了让它**整体落在录制层内**（调用处外面套了 `layerBackdrop`），
 * 页内的玻璃进度条 / 播放键才有画面可采样。三层的顺序与视觉和抽出来之前完全一致。
 */
@Composable
private fun NowPlayingBackground(
    bitmap: Bitmap?,
    gradientColors: CoverGradient,
    modifier: Modifier = Modifier
) {
    val saturation = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.25f) }) }
    Box(modifier) {
        // 渐变、模糊底图和主题色板取自同一张小图，等待新封面时保留当前底色。
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to gradientColors.top,
                        0.3f to gradientColors.middle,
                        1f to gradientColors.bottom
                    )
                )
        )
        // 背景层 2：封面模糊图铺满。加载 96px 小位图（带鉴权头）拉伸到全屏，再叠高斯模糊：
        // Android 12+ 用 Modifier.blur(40dp) 把像素块完全化开；旧系统无 RenderEffect，
        // 回退纯拉伸（96px 双线性放大已比 48px 平滑一倍，无明显像素感）。
        // alpha 0.7 + 饱和度 1.25 让封面颜色透出来不发灰；加载失败 / 无封面时此层不绘制，渐变兜底。
        Crossfade(
            targetState = bitmap,
            modifier = Modifier.matchParentSize()
                .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(40.dp) else Modifier)
                .alpha(0.7f),
            animationSpec = tween(THEME_TRANSITION_MS),
            label = "nowPlayingBackdrop"
        ) { image ->
            if (image != null) {
                Image(
                    bitmap = remember(image) { image.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = saturation,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        // 背景层 3：垂直渐变深色 scrim（上浅下深），保证白字与白色播放键的对比度稳定。
        // 压得不能太重：黑纱太厚会把封面颜色洗灰（用户反馈「太灰」后从 0.45/0.6 降到 0.25/0.45）。
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.25f),
                        1f to Color.Black.copy(alpha = 0.45f)
                    )
                )
        )
    }
}

/** 全屏页页数：歌词 / 封面 / 队列。 */
internal const val NOW_PLAYING_PAGE_COUNT = 3

/** 页指示器：每格宽度。底槽按格居中，滑块按格定位，两者共用同一套几何。 */
private val IndicatorSlotWidth = 28.dp

/** 页指示器：底条与滑块的高度。 */
private val IndicatorBarHeight = 6.dp

/** 页指示器：滑块静止宽度（横滑过程中按 [indicatorStretch] 拉长）。 */
private val IndicatorPillBaseWidth = 16.dp

/**
 * 歌词页（横滑第 1 页）。三态：
 * - 同步歌词（isSynced）：LazyColumn 逐行展示，按播放位置高亮当前行并自动滚动到可见区，
 *   点任意行 seek 到该行时间（onLineSeek 传入微秒）。
 * - 纯文本歌词（!isSynced 且 lines 非空）：静态展示，不滚动不高亮。
 * - 无歌词（lines 为空/null）：居中引导空态，提示如何给曲库添加 .lrc。
 */
@Composable
private fun LyricsPage(
    lyrics: Lyrics,
    positionUs: Long,
    onLineSeek: (Long) -> Unit,
    interactive: Boolean,
    modifier: Modifier = Modifier
) {
    val lines = lyrics.lines
    when {
        lines == null || lines.isEmpty() -> {
            // 无歌词空态：居中引导，说明往 Jellyfin 音乐目录放 .lrc 即可生效。
            Column(
                modifier = modifier.padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "这首歌还没有歌词",
                    style = MaterialTheme.typography.titleMedium,
                    color = NpTextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "给歌曲添加歌词即可显示：Jellyfin 10.10+ 直接读取音频文件内嵌歌词标签；旧版可放同名的 .lrc 文件到音乐目录，刷新媒体库生效",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NpTextFaint,
                    textAlign = TextAlign.Center
                )
            }
        }
        !lyrics.isSynced -> {
            // 纯文本歌词：静态可滚动展示。
            LazyColumn(
                modifier = modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                items(lines.size) { index ->
                    Text(
                        text = lines[index].text,
                        style = MaterialTheme.typography.titleMedium,
                        color = NpTextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 32.dp)
                    )
                }
            }
        }
        else -> {
            // 同步歌词：逐行高亮 + 自动滚动。高亮行字重更高、纯白，其余半透明。
            val listState = rememberLazyListState()
            val currentIndex = lyrics.currentIndexFor(positionUs)
            val density = LocalDensity.current
            // 当前行变化时平滑滚到可见区（偏移把行放在列表偏上位置，符合歌词页阅读习惯）。
            LaunchedEffect(currentIndex) {
                if (currentIndex >= 0) {
                    listState.animateScrollToItem(
                        index = currentIndex,
                        scrollOffset = -with(density) { 120.dp.toPx().toInt() }
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                items(lines.size) { index ->
                    val isCurrent = index == currentIndex
                    Text(
                        text = lines[index].text,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = if (isCurrent) NpTextPrimary else NpTextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(vertical = 8.dp, horizontal = 32.dp)
                            // 点行跳转到该行时间；无时间戳的行（startUs=0 仅首行常见）也可跳。
                            .clickable(enabled = interactive && lyrics.isSynced) { onLineSeek(lines[index].startUs) }
                    )
                }
            }
        }
    }
}

/**
 * 队列页（横滑第 3 页）：原折叠区列表整页化——拖拽排序、点行跳播、逐行移除。
 * 排序交互沿用 longPressDraggableHandle；列表内容超屏时列表自己滚动。
 * 拖拽状态由调用方持有（两个队列面板共用同一份暂态），经 [onDragStarted]/[onDragStopped] 回报。
 * 行点击 / 移除都按**媒体 id** 回报：拖拽暂态期间列表顺序可能领先播放器一轮，索引不可靠（#28）。
 */
@Composable
private fun QueuePage(
    queue: List<QueueTrack>,
    lazyListState: LazyListState,
    reorderableState: ReorderableLazyListState,
    enabled: Boolean,
    onDragStarted: () -> Unit,
    onDragStopped: () -> Unit,
    onDragCancelled: () -> Unit,
    onSeekTo: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (queue.isEmpty()) {
        Column(
            modifier = modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "暂无队列",
                style = MaterialTheme.typography.titleMedium,
                color = NpTextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "从「歌曲 / 专辑 / 播放列表」里点一首歌开始",
                style = MaterialTheme.typography.bodyMedium,
                color = NpTextFaint,
                textAlign = TextAlign.Center
            )
        }
        return
    }
    // 队列行是深底页上的「面」：与玻璃面同源取色板亮端，跟切歌换色走。
    val scheme = MaterialTheme.colorScheme
    val rowSurface = if (scheme.surface.luminance() > 0.5f) scheme.surface else scheme.onSurface
    LazyColumn(
        state = lazyListState,
        modifier = modifier.fillMaxWidth()
    ) {
        items(queue, key = { it.id }) { item ->
            ReorderableItem(reorderableState, key = item.id, animateItemModifier = queuePlacement()) { dragging ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            // 拖拽中：亮色半透明高亮（深色页适配）。
                            if (dragging) rowSurface.copy(alpha = 0.12f) else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        // 长按整行拖动排序；松手后把新顺序同步给播放器。
                        .then(queueDragHandle(enabled, onDragStarted, onDragStopped, onDragCancelled))
                        .clickable(enabled = enabled) {
                            onSeekTo(item.id)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 当前轮播行指示点：白色（深底页恒亮）。
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                if (item.isCurrent) NpTextPrimary else NpTextPrimary.copy(alpha = 0.3f),
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (item.isCurrent) NpTextPrimary else Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = item.artist,
                            style = MaterialTheme.typography.labelSmall,
                            color = NpTextFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = { onRemove(item.id) },
                        enabled = enabled,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "从队列中移除此歌曲",
                            tint = NpTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}


/** 播放模式对应的图标：顺序循环 / 单曲循环 / 随机播放。（自 MiniPlayerBar 展开面板迁入） */
internal fun playbackModeIcon(mode: PlaybackMode): ImageVector = when (mode) {
    PlaybackMode.SEQUENCE -> Icons.Filled.Repeat
    PlaybackMode.REPEAT_ONE -> Icons.Filled.RepeatOne
    PlaybackMode.SHUFFLE -> Icons.Filled.Shuffle
}

/** 输出信息一行文案：走 USB 小尾巴还是系统默认 + 采样率 + 位深；未播放时显示"未播放"。（自面板迁入） */
internal fun outputLine(info: PlaybackOutputInfo): String {
    val sample = info.sampleRateHz ?: return "输出：未播放"
    val device = if (info.viaUsb) "USB 小尾巴" else "系统默认"
    val rate = if (sample % 1000 == 0) "${sample / 1000} kHz" else String.format("%.1f kHz", sample / 1000.0)
    val bit = info.encoding?.let { " · ${encodingLabel(it)}" }.orEmpty()
    return "输出：$device · $rate$bit"
}

/** 音频管线实际编码的友好名（C.ENCODING_PCM_*）。（自面板迁入） */
private fun encodingLabel(encoding: Int): String = when (encoding) {
    C.ENCODING_PCM_16BIT -> "16-bit"
    C.ENCODING_PCM_24BIT -> "24-bit"
    C.ENCODING_PCM_32BIT -> "32-bit"
    C.ENCODING_PCM_FLOAT -> "float32"
    else -> "编码$encoding"
}
