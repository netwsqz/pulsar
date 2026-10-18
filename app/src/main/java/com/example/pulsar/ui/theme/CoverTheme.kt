package com.example.pulsar.ui.theme

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import com.example.pulsar.data.jellyfin.JellyfinRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次封面取色的结果：全屏播放页的三色渐变 + 推导主题用的种子色。 */
data class CoverTheme(val gradient: CoverGradient, val seed: ThemeSeed)

/**
 * 跟随当前封面取色：封面请求变化时用 Coil 加载 96px 小图（与界面封面共用
 * ImageLoader 缓存，不重复下载），androidx.palette 提取色板后同时产出
 * 主题种子色（vibrant → dominant → 品牌靛蓝；用户预设色等工单 13 再接）
 * 和三色渐变。渐变与强调色都从**同一个种子**推导，保证一张封面只有一个主色。
 * 取色期间沿用上一次的结果（切歌直接旧色过渡到新色，不闪回默认色）；
 * 失败 / 无封面回退默认色板。darkTheme 只影响柔化方向，切换深浅模式不会重新取色。
 */
@Composable
fun rememberCoverGradient(
    coverRequest: JellyfinRequest?,
    darkTheme: Boolean
): CoverTheme {
    var swatches by remember { mutableStateOf<CoverSwatches?>(null) }
    val context = LocalContext.current
    // key 同时含 URL 与 Authorization：重新登录换 token 但曲目未变时（URL 不变）也能重新取色。
    LaunchedEffect(coverRequest?.url, coverRequest?.headers["Authorization"]) {
        val request = coverRequest
        if (request == null) {
            swatches = null // 无封面：立刻回默认
        } else {
            swatches = withContext(Dispatchers.IO) { extractCoverSwatches(context, request) }
        }
    }
    return CoverTheme(
        // 回退渐变用固定默认色，不读 MaterialTheme——本函数在主题之外调用，读不到动态色板。
        gradient = coverGradient(
            swatches = swatches?.all,
            darkTheme = darkTheme,
            fallback = defaultCoverGradient(darkTheme),
            // ADR-0005：种子、强调色、渐变必须取同一个主色，否则切页面会跳色。
            dominant = swatches?.vibrant
        ),
        seed = selectThemeSeed(swatches?.vibrant, swatches?.all, preset = null)
    )
}

/** 一次取色的原始样本：vibrant（androidx.palette 认定的鲜艳色）与完整色板。 */
private class CoverSwatches(val vibrant: CoverSwatch?, val all: List<CoverSwatch>)

/** 取小图提取色板；任何失败都返回 null（界面回退默认色板），不抛异常。 */
private suspend fun extractCoverSwatches(context: Context, request: JellyfinRequest): CoverSwatches? {
    return try {
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(request.url)
                .addHeader("Authorization", request.headers["Authorization"].orEmpty())
                .size(96) // 取色只需要小图：解码快、省内存
                .allowHardware(false) // palette 要读像素，必须是软件位图
                .build()
        )
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
        val palette = Palette.from(bitmap)
            .maximumColorCount(24)
            .generate()
        CoverSwatches(
            vibrant = palette.vibrantSwatch?.let { CoverSwatch(it.rgb, it.population) },
            all = palette.swatches.map { CoverSwatch(it.rgb, it.population) }
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
