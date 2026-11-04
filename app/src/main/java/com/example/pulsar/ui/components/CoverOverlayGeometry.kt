package com.example.pulsar.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp

/**
 * 封面上的两层叠加物的几何规则（工单 14 / spec §9）：播放指示条与封面底部渐变。
 *
 * 都是纯数值函数、不碰 Compose 状态，拆出来就是为了能直接单测——这些数字以前散在布局与绘制代码里，
 * 只有真机肉眼能验，改一个坏一个也看不出来。
 */

/** 均衡器条的根数。画布宽度、绘制循环、暂停进度表都以它为准，加一根不用改散落的数字。 */
internal const val PlayingBarCount = 3

/** 单根均衡器条的宽度。 */
internal val PlayingBarWidth = 3.dp

/** 两根均衡器条之间的间距。 */
internal val PlayingBarGap = 2.dp

/** 单根条的最小高度（进度 0f）。 */
internal val PlayingBarMinHeight = 4.dp

/** 单根条的最大高度（进度 1f），也是指示器画布的固定高度——最高的那根条正好顶满画布。 */
internal val PlayingBarMaxHeight = 14.dp

/** 指示器画布宽度 = 三根条 + 两个间隙 = 13dp。画布尺寸恒定，条高变化只发生在绘制阶段。 */
internal val PlayingBarsWidth =
    PlayingBarWidth * PlayingBarCount + PlayingBarGap * (PlayingBarCount - 1)

/** 暂停时三根条各自的进度（按根号取，长度必须等于 [PlayingBarCount]）：
 * 落在 4 / 9 / 6 dp，静止不跳（与播放共用 [playingBarHeight] 这一条映射）。 */
internal val pausedPlayingBarProgress = listOf(0f, 0.5f, 0.2f)

/** 进度 0f..1f 线性映射到 4~14dp。越界夹回，保证画布内永远画得下。 */
internal fun playingBarHeight(progress: Float): Dp =
    PlayingBarMinHeight + (PlayingBarMaxHeight - PlayingBarMinHeight) * progress.coerceIn(0f, 1f)

/** 封面底部渐变占封面高度的比例。 */
private const val CoverScrimFraction = 0.25f

/** 封面底部渐变的高度上限：再大的封面也只用 32dp 做嵌入感，不吞掉画面。 */
private val CoverScrimMaxHeight = 32.dp

/** 封面底部渐变的高度：封面高度的 [CoverScrimFraction]，封顶 [CoverScrimMaxHeight]——
 * 40dp 的小封面不再被压暗一大半。 */
internal fun coverScrimHeight(coverHeight: Dp): Dp =
    (coverHeight * CoverScrimFraction).coerceAtMost(CoverScrimMaxHeight)
