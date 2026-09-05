package com.example.pulsar.ui.theme

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.material3.MaterialTheme
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

/**
 * 跟随当前封面取色的背景渐变：封面请求变化时用 Coil 加载 96px 小图（与界面封面共用
 * ImageLoader 缓存，不重复下载），androidx.palette 提取色板后转成柔和渐变。
 * 取色期间沿用上一个渐变（切歌直接旧色过渡到新色，不闪回默认色）；失败 / 无封面回退默认主题渐变。
 * darkTheme 只影响柔化方向，切换深浅模式不会重新取色。
 */
@Composable
fun rememberCoverGradient(coverRequest: JellyfinRequest?, darkTheme: Boolean): CoverGradient {
    var swatches by remember { mutableStateOf<List<CoverSwatch>?>(null) }
    val context = LocalContext.current
    // 回退渐变取自当前 Material 主题，保证无封面时与取色功能引入前视觉完全一致。
    val fallback = CoverGradient(
        top = MaterialTheme.colorScheme.primaryContainer,
        middle = MaterialTheme.colorScheme.surfaceVariant,
        bottom = MaterialTheme.colorScheme.background
    )
    // key 同时含 URL 与 Authorization：重新登录换 token 但曲目未变时（URL 不变）也能重新取色。
    LaunchedEffect(coverRequest?.url, coverRequest?.headers["Authorization"]) {
        val request = coverRequest
        if (request == null) {
            swatches = null // 无封面：立刻回默认
        } else {
            swatches = withContext(Dispatchers.IO) { extractCoverSwatches(context, request) }
        }
    }
    return coverGradient(swatches, darkTheme, fallback)
}

/** 取小图提取色板；任何失败都返回 null（界面回退默认渐变），不抛异常。 */
private suspend fun extractCoverSwatches(context: Context, request: JellyfinRequest): List<CoverSwatch>? {
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
        Palette.from(bitmap)
            .maximumColorCount(24)
            .generate()
            .swatches
            .map { CoverSwatch(it.rgb, it.population) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
