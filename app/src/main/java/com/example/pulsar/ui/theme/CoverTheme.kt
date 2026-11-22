package com.example.pulsar.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.pulsar.data.jellyfin.JellyfinRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** 主题种子、全屏渐变和模糊底图来自同一次取样，不会各自追一张封面。 */
internal data class CoverTheme(
    val gradient: CoverGradient,
    val seed: ThemeSeed,
    val backdrop: Bitmap?,
    val artwork: Bitmap?
)

/**
 * 等待新图时沿用当前结果；取不到图就回到原有兜底。普通详情只取 96px 小图，
 * 播放页传入 [artworkSizePx] 时，从同一张较清晰的图缩小取色和做模糊背景。
 */
@Composable
internal fun rememberCoverGradient(
    coverRequest: JellyfinRequest?,
    darkTheme: Boolean,
    preset: Color? = null,
    sessionIdentity: Any? = null,
    artworkSizePx: Int? = null
): CoverTheme {
    // 只保留同次登录的上一次结果；登出 / 换账号同步丢弃，不能把旧账号的底图带过去。
    var sample by remember(sessionIdentity) { mutableStateOf<CoverSample?>(null) }
    val context = LocalContext.current
    LaunchedEffect(sessionIdentity, coverRequest, artworkSizePx) {
        val result = coverRequest?.let {
            withContext(Dispatchers.IO) { extractCoverSample(context, it, artworkSizePx) }
        }
        coroutineContext.ensureActive()
        sample = result
    }
    // 有新请求在等时沿用已显示的样本；明确没有封面则这一帧就切到既有兜底。
    val shown = sample.takeIf { coverRequest != null }
    val swatches = shown?.swatches
    // 渐变与种子必须取**同一个** swatch：palette 有饱和色但没有 vibrantSwatch 时，
    // 渐变若直接落到 fallback（预设/默认），种子却取了 dominant，控件与背景就会两个色。
    val dominant = swatches?.vibrant ?: swatches?.all?.let(::selectCoverSwatch)
    return CoverTheme(
        // 回退渐变用固定默认色（或用户预设色），不读 MaterialTheme——本函数在主题之外调用，读不到动态色板。
        gradient = coverGradient(
            swatches = swatches?.all,
            darkTheme = darkTheme,
            fallback = presetGradient(preset, darkTheme),
            // ADR-0005：种子、强调色、渐变必须取同一个主色，否则切页面会跳色。
            dominant = dominant
        ),
        seed = selectThemeSeed(swatches?.vibrant, swatches?.all, preset = preset),
        backdrop = shown?.small,
        artwork = shown?.artwork
    )
}

/** 无封面时的兜底渐变：用户选过预设色就按它推导（与主界面兜底一致），否则品牌靛蓝。
 * 预设色（含灰色）显式当 dominant 传，绕开 `selectCoverSwatch` 的饱和度筛选——
 * 用户自己选的颜色，灰也得认。 */
internal fun presetGradient(preset: Color?, darkTheme: Boolean): CoverGradient {
    val swatch = preset?.let { CoverSwatch(it.toArgb(), 1) }
    return coverGradient(
        swatches = swatch?.let { listOf(it) },
        darkTheme = darkTheme,
        dominant = swatch
    )
}

/** 一次取色的原始样本：vibrant（androidx.palette 认定的鲜艳色）与完整色板。 */
private class CoverSwatches(val vibrant: CoverSwatch?, val all: List<CoverSwatch>)
private class CoverSample(val small: Bitmap, val swatches: CoverSwatches, val artwork: Bitmap?)

/** 任一请求失败就显示同一套既有兜底；仍遵守协程取消。 */
private suspend fun extractCoverSample(context: Context, request: JellyfinRequest, artworkSizePx: Int?): CoverSample? {
    return try {
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(request.url)
                .addHeader("Authorization", request.headers["Authorization"].orEmpty())
                .size(artworkSizePx ?: 96)
                .allowHardware(false) // Palette 要读像素。
                .build()
        ) as? SuccessResult ?: return null
        val image = (result.drawable as? BitmapDrawable)?.bitmap ?: if (artworkSizePx != null) {
            result.drawable.toBitmap()
        } else return null
        val largest = maxOf(image.width, image.height)
        val small = if (largest > 96) {
            val scale = 96f / largest
            image.scale((image.width * scale).roundToInt().coerceAtLeast(1), (image.height * scale).roundToInt().coerceAtLeast(1))
        } else image
        val palette = Palette.from(small)
            .maximumColorCount(24)
            .generate()
        CoverSample(
            small = small,
            swatches = CoverSwatches(
                vibrant = palette.vibrantSwatch?.let { CoverSwatch(it.rgb, it.population) },
                all = palette.swatches.map { CoverSwatch(it.rgb, it.population) }
            ),
            artwork = image.takeIf { artworkSizePx != null }
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
