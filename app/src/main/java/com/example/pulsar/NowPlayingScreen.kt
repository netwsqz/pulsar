package com.example.pulsar

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.example.pulsar.data.jellyfin.JellyfinRequest
import com.example.pulsar.data.jellyfin.Lyrics
import com.example.pulsar.domain.model.Track
import com.example.pulsar.playback.PlaybackDiagnostics
import com.example.pulsar.playback.PlaybackMode
import com.example.pulsar.playback.PlaybackOutputInfo
import com.example.pulsar.playback.formatPosition
import com.example.pulsar.ui.theme.CoverGradient
import com.example.pulsar.ui.theme.PoppinsFontFamily

// ===== 深色沉浸页白字体系（与系统主题无关，全屏页永远深底白字） =====
private val NpTextPrimary = Color.White
private val NpTextSecondary = Color.White.copy(alpha = 0.7f)
private val NpTextFaint = Color.White.copy(alpha = 0.5f)

// 播放/暂停白色圆按钮的立体感参数：轻微纵向渐变 + 近黑图标，模拟 hi-fi 实体按键。
private val NpPlayButtonTop = Color.White
private val NpPlayButtonBottom = Color(0xFFE9E9EE)
private val NpPlayButtonIcon = Color(0xFF15151A)

/**
 * 迷你条小封面 ↔ 全屏页大封面共享同一个 key：打开/收起全屏页时封面跨布局连续飞行。
 * 两侧（MiniPlayerBar / NowPlayingScreen）必须都注册同一个 key 才会匹配上。
 */
internal const val NOW_PLAYING_COVER_KEY = "now-playing-cover"

/**
 * 全屏播放页进出场时长。整页只做淡入淡出、不做位移：唯一的运动交给封面从迷你条飞到大封面。
 * 此前整页用 slideInVertically 从底部升起，与封面飞行是两套独立位移，到达时间对不齐，
 * 观感上「封面和页面各飞各的」。时长取 260ms，与封面飞行的弹簧（约 300ms）大致对齐。
 */
internal const val NOW_PLAYING_FADE_MS = 260

/**
 * 下滑收起全屏播放页的阈值：拖动位移超过屏高的 15% 即判定收起。
 */
internal const val NOW_PLAYING_DISMISS_RATIO = 0.15f

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
 * 全屏播放页（hi-fi 深色沉浸改版，三页横滑）。主体为 [HorizontalPager]：
 * 第 1 页歌词（同步逐行高亮滚动 / 纯文本静态 / 无歌词空态）→
 * 第 2 页封面（居中大封面，圆角 20dp）→ 第 3 页播放队列（整页列表：拖拽排序、跳播、移除）。
 * 页间指示器：三根小横条。页面上下的固定骨架（标题 / 模式行 / 输出信息 / 进度条 / 控制行）
 * 不随横滑移动，只有中区三页切换。
 * 封面注册 sharedElement（key 与迷你条小封面一致）：打开/收起全屏页时小封面 morph 成
 * 大封面（位置、尺寸、圆角连续插值），其余内容仍走底部滑入滑出。
 * 整页永远深色沉浸白字（与系统主题无关）；背景三层：封面三色渐变（兜底）→
 * 48px 超小封面位图拉伸铺满（位图放大到全屏自然就是模糊效果，兼容所有 API，不用 Modifier.blur）→
 * 深色垂直 scrim 保证白字对比度。封面加载失败 / 无封面时只有渐变 + scrim。
 * 页面打开 / 关闭动画（底部滑入滑出）与返回键拦截在 MainActivity 侧实现；
 * 页内下滑手势收起：dragOffset（px）驱动根 Box 的 translationY 跟手位移，
 * 松手用 [shouldDismissNowPlaying] 判定——超阈值直接调 onClose，让 MainActivity 侧
 * AnimatedVisibility 的 exit 滑出从松手位置无缝接管（dragOffset 停在松手处不归零，
 * 避免「弹回 0 再滑出」的双重动画）；未超阈值弹回原位。
 * 横滑与下滑互不干扰：Pager 在中区消费横向拖动，根 Box 的下滑手势只从未被消费的区域起效。
 * 队列数据用 [ControllerState.queue]（QueueTrack 快照，isCurrent 标记当前行）。
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
internal fun NowPlayingScreen(
    controllerState: ControllerState,
    currentTrack: Track?,
    coverRequest: JellyfinRequest?,
    gradientColors: CoverGradient,
    playbackMode: PlaybackMode,
    onPlaybackModeChange: (PlaybackMode) -> Unit,
    lyrics: Lyrics,
    onLineSeek: (Long) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val controller = controllerState.controller
    val hasMedia = controller != null && controllerState.durationMs > 0
    // 三个控制按钮（上一首 / 播放暂停 / 下一首）共用的轻触觉反馈：整个页面取一次。
    val controlView = LocalView.current
    // 进度状态：拖动中显示拖动值，松手才 seek，避免边拖边跳。
    val durationSec = (controllerState.durationMs / 1000f).coerceAtLeast(1f)
    var sliderPosition by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    // 播放队列：本地顺序（拖拽时优先），松手才同步回播放器。队列页在第 3 页整页展示。
    val hapticFeedback = LocalHapticFeedback.current
    var isQueueDragging by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf(controllerState.queue) }
    val queueListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(queueListState) { from, to ->
        queue = queue.toMutableList().apply { add(to.index, removeAt(from.index)) }
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    // 播放器快照变化时刷新本地队列；拖拽进行中不覆盖本地顺序，避免排序时列表跳动。
    LaunchedEffect(controllerState.queue) {
        if (!isQueueDragging) queue = controllerState.queue
    }
    // 三页横滑：歌词 / 封面 / 队列，默认落在封面页（第 2 页，index 1）。
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { NOW_PLAYING_PAGE_COUNT })
    // 输出信息（设备 + 采样率 + 位深），由 PlaybackService 写入诊断单例。
    val outputInfo by PlaybackDiagnostics.info.collectAsStateWithLifecycle()
    // 下滑收起手势：Animatable 存 px 位移（手势期间 snapTo 跟手，松手 animateTo 收尾）。
    // 只允许向下拖（graphicsLayer 里 coerceAtLeast(0f)），收起按钮 / 返回键路径不经过它，保持 0。
    val dragOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    // 与 MainActivity 侧进出场一致的弹簧参数，弹回 / 滑出手感统一。
    val dismissSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 400f)
    // 模糊背景的图片加载上下文（非组合回调里不能用 LocalContext）。
    val context = LocalContext.current

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                val offset = dragOffset.value.coerceAtLeast(0f)
                translationY = offset
                // 屏高为 0 时避免 0/0 = NaN 污染 alpha（防御，与 shouldDismissNowPlaying 同思路）。
                val fraction = if (screenHeightPx > 0f) (offset / screenHeightPx).coerceIn(0f, 1f) else 0f
                alpha = 1f - 0.5f * fraction
            }
            // 背景层 1：与主界面同源的封面三色垂直渐变（MainActivity 传入的是动画后的颜色，
            // 切歌跟随 800ms 过渡）。无封面 / 加载失败时它是唯一可见的背景。
            .background(
                Brush.verticalGradient(
                    0f to gradientColors.top,
                    0.3f to gradientColors.middle,
                    1f to gradientColors.bottom
                )
            )
            // 下滑收起手势加在根 Box 上：页内没有横向手势；进度 Slider 拖动、队列 LazyColumn 滚动 /
            // 长按排序都是子组件的 pointerInput，Android 指针分发子组件先消费（消费后父层收不到位移），
            // 因此只有从空白 / 不可交互区域起拖才会收起页面，天然防误触；点在纯 clickable 按钮上
            // 起拖则由本手势接管（按钮点击被取消），与底部弹层惯例一致。
            // pointerInput 以屏高为 key：旋转/分屏后重选手势块，拿到新的屏高与弹簧参数。
            .pointerInput(screenHeightPx) {
                // 手势期间累计的绝对位移（px）：在事件回调里直接读写，避免松手判定时
                // 最后一个事件 launch 的 snapTo 还没执行到而差一帧。
                var draggedPx = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        // 基线取当前实际位移：弹回动画进行中续拖可无缝接管（snapTo 会取消
                        // 在飞的 animateTo）；exit 收尾中反向起拖则从当前叠加位置平滑拉回。
                        draggedPx = dragOffset.value
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        draggedPx = (draggedPx + dragAmount).coerceAtLeast(0f)
                        val target = draggedPx
                        // snapTo 在拖拽事件协程里执行，跟手无掉帧延迟；连续事件由
                        // Animatable 内置的 MutatorMutex 串行化，后者取消前者，不丢最新值。
                        scope.launch { dragOffset.snapTo(target) }
                    },
                    onDragEnd = {
                        if (shouldDismissNowPlaying(draggedPx, screenHeightPx)) {
                            // 收起：直接 onClose()，MainActivity 侧 exit（slideOutVertically 0→页高）
                            // 叠加在 dragOffset（停在松手位置）之上，从松手处无缝继续滑出，
                            // 拖得再浅也能完整滑出屏外。特意不在这里归零或先滑到屏底：
                            // 归零会闪回屏幕中央，先滑到屏底则会在 exit 窗口内残留屏外位移，
                            // 若用户立刻重开页面会复用同一组合实例，页面停在屏外不可见。
                            // exit 结束组合销毁，remember 释放，下次打开 dragOffset 从 0 开始。
                            onClose()
                        } else {
                            scope.launch { dragOffset.animateTo(0f, dismissSpring) }
                        }
                    },
                    onDragCancel = {
                        // 手势被上层截断（如系统手势）按未达阈值处理，弹回原位。
                        scope.launch { dragOffset.animateTo(0f, dismissSpring) }
                    }
                )
            }
    ) {
        // 背景层 2：封面模糊图铺满。加载 96px 小位图（带鉴权头）拉伸到全屏，再叠高斯模糊：
        // Android 12+ 用 Modifier.blur(40dp) 把像素块完全化开；旧系统无 RenderEffect，
        // 回退纯拉伸（96px 双线性放大已比 48px 平滑一倍，无明显像素感）。
        // alpha 0.7 + 饱和度 1.25 让封面颜色透出来不发灰；加载失败 / 无封面时此层不绘制，渐变兜底。
        coverRequest?.let { request ->
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(request.url)
                    .size(96)
                    .addHeader("Authorization", request.headers["Authorization"].orEmpty())
                    .allowHardware(false)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.25f) }),
                modifier = Modifier
                    .matchParentSize()
                    .then(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Modifier.blur(40.dp)
                        } else {
                            Modifier
                        }
                    )
                    .alpha(0.7f)
            )
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ===== 1. 顶部区：居中歌名 + 歌手，左上角浮动收起按钮（页内下滑手势收起见根 Box） =====
            Box(Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = onClose,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    // ---- 第 1 页：歌词 ----
                    0 -> LyricsPage(
                        lyrics = lyrics,
                        positionUs = controllerState.positionMs * 1000L,
                        onLineSeek = onLineSeek,
                        modifier = Modifier.fillMaxSize()
                    )
                    // ---- 第 2 页：大封面 ----
                    1 -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        // 两侧 12dp 边距 + 正方形（aspectRatio）：封面边长 = min(屏宽-56, 剩余高度)，
                        // 约 86% 屏宽（82-88% 区间内）；无封面 / 未登录时 AlbumCover 内部显示占位块。
                        // 边距放在外层 Box：sharedBounds 必须紧贴封面本体量尺寸，否则会把 12dp
                        // 内边距算进 bounds，比真实封面宽 24dp，飞行落位时尺寸对不上会「跳」一下。
                        // sharedBounds 与迷你条小封面同 key：打开/收起时封面连续 morph
                        // （sharedBounds 支持两侧 enter/exit 交接，sharedElement 不行）。
                        Box(Modifier.padding(horizontal = 12.dp)) {
                            with(sharedTransitionScope) {
                                AlbumCover(
                                    coverRequest,
                                    currentTrack?.title ?: "未选择歌曲",
                                    Modifier
                                        .aspectRatio(1f)
                                        .sharedBounds(
                                            rememberSharedContentState(key = NOW_PLAYING_COVER_KEY),
                                            animatedVisibilityScope = animatedVisibilityScope
                                        ),
                                    cornerRadius = 20.dp
                                )
                            }
                        }
                    }
                    // ---- 第 3 页：播放队列（整页列表）----
                    else -> QueuePage(
                        queue = queue,
                        lazyListState = queueListState,
                        reorderableState = reorderableState,
                        enabled = controller != null,
                        onDragStarted = { isQueueDragging = true },
                        onDragStopped = {
                            isQueueDragging = false
                            controller?.applyQueueOrder(queue)
                        },
                        onSeekTo = { index ->
                            controller?.seekToDefaultPosition(index)
                            controller?.play()
                        },
                        onRemove = { index -> controller?.removeMediaItem(index) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            // 页间指示器：三根小横条，当前页亮白。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(NOW_PLAYING_PAGE_COUNT) { page ->
                    val selected = pagerState.currentPage == page
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .size(width = if (selected) 20.dp else 8.dp, height = 4.dp)
                            .background(
                                if (selected) NpTextPrimary else NpTextFaint,
                                RoundedCornerShape(2.dp)
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
                // 播放模式：图标切换（顺序循环 / 单曲循环 / 随机播放），激活纯白、未激活（无播放器）White50。
                IconButton(
                    onClick = { onPlaybackModeChange(playbackMode.next()) },
                    enabled = controller != null,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        playbackModeIcon(playbackMode),
                        contentDescription = "切换播放模式，当前：${playbackMode.label}",
                        tint = if (controller != null) NpTextPrimary else NpTextFaint
                    )
                }
                Spacer(Modifier.size(48.dp))
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
            // 全屏页打开期间迷你条不可见，不存在同步问题）；白系配色适配深色页。 =====
            Slider(
                value = if (isDragging) sliderPosition else (controllerState.positionMs / 1000f).coerceIn(0f, durationSec),
                onValueChange = { sliderPosition = it; isDragging = true },
                onValueChangeFinished = {
                    controller?.seekTo((sliderPosition * 1000).toLong())
                    isDragging = false
                },
                valueRange = 0f..durationSec,
                enabled = hasMedia,
                colors = SliderDefaults.colors(
                    thumbColor = NpTextPrimary,
                    activeTrackColor = NpTextPrimary,
                    inactiveTrackColor = NpTextFaint
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatPosition(controllerState.positionMs), style = MaterialTheme.typography.labelSmall, color = NpTextSecondary)
                Text(formatPosition(controllerState.durationMs), style = MaterialTheme.typography.labelSmall, color = NpTextSecondary)
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
                    onClick = {
                        controlView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        controller?.seekToPrevious()
                    },
                    enabled = controller != null,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "播放上一首歌曲",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(32.dp)
                    )
                }
                // 播放/暂停主按钮：72dp 白色实心圆（轻微纵向渐变提升质感）+ 近黑图标 +
                // 8dp 阴影立体感。shadow 在 clip 之前保证阴影不被裁掉。
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .shadow(
                            elevation = 8.dp,
                            shape = CircleShape,
                            ambientColor = Color(0x40000000),
                            spotColor = Color(0x66000000)
                        )
                        .clip(CircleShape)
                        .background(
                            Brush.verticalGradient(
                                0f to NpPlayButtonTop,
                                1f to NpPlayButtonBottom
                            )
                        )
                        .clickable(enabled = controller != null) {
                            controlView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            controller?.let { if (it.isPlaying) it.pause() else it.play() }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (controllerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (controllerState.isPlaying) "暂停播放" else "开始播放",
                        tint = NpPlayButtonIcon,
                        modifier = Modifier.size(36.dp)
                    )
                }
                // 下一首：44dp 实心图标，白 90%。
                IconButton(
                    onClick = {
                        controlView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        controller?.seekToNext()
                    },
                    enabled = controller != null,
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
    }
}

/** 全屏页页数：歌词 / 封面 / 队列。 */
internal const val NOW_PLAYING_PAGE_COUNT = 3

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
                            .clickable(enabled = lyrics.isSynced) { onLineSeek(lines[index].startUs) }
                    )
                }
            }
        }
    }
}

/**
 * 队列页（横滑第 3 页）：原折叠区列表整页化——拖拽排序、点行跳播、逐行移除。
 * 排序交互沿用 longPressDraggableHandle；列表内容超屏时列表自己滚动。
 * 拖拽状态由调用方持有（排序期间暂停外部队列覆盖），经 [onDragStarted]/[onDragStopped] 回报。
 */
@Composable
private fun QueuePage(
    queue: List<QueueTrack>,
    lazyListState: LazyListState,
    reorderableState: ReorderableLazyListState,
    enabled: Boolean,
    onDragStarted: () -> Unit,
    onDragStopped: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onRemove: (Int) -> Unit,
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
                text = "从「全部音乐 / 专辑 / 播放列表」里点一首歌开始",
                style = MaterialTheme.typography.bodyMedium,
                color = NpTextFaint,
                textAlign = TextAlign.Center
            )
        }
        return
    }
    LazyColumn(
        state = lazyListState,
        modifier = modifier.fillMaxWidth()
    ) {
        items(queue, key = { it.id }) { item ->
            ReorderableItem(reorderableState, key = item.id) { dragging ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            // 拖拽中：白色半透明高亮（深色页适配）。
                            if (dragging) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        // 长按整行拖动排序；松手后把新顺序同步给播放器。
                        .longPressDraggableHandle(
                            onDragStarted = { _ -> onDragStarted() },
                            onDragStopped = onDragStopped
                        )
                        .clickable(enabled = enabled) {
                            onSeekTo(queue.indexOfFirst { it.id == item.id })
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 当前轮播行指示点：白色。
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                if (item.isCurrent) NpTextPrimary else Color.White.copy(alpha = 0.3f),
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
                        onClick = { onRemove(queue.indexOfFirst { it.id == item.id }) },
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
