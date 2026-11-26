package com.example.pulsar.ui.components

import android.content.Context
import coil.ImageLoader
import coil.imageLoader
import coil.request.ImageRequest
import com.example.pulsar.PulsarApplication
import com.example.pulsar.data.CoverRequest
import com.example.pulsar.data.companion.CompanionArtworkRequest
import com.example.pulsar.data.jellyfin.JellyfinRequest

internal fun CoverRequest.imageLoader(context: Context): ImageLoader = when (this) {
    is CompanionArtworkRequest -> (context.applicationContext as PulsarApplication).companionImageLoader
    else -> context.imageLoader
}

internal fun CoverRequest.imageRequest(context: Context): ImageRequest.Builder =
    ImageRequest.Builder(context).data(url).apply {
        when (val request = this@imageRequest) {
            is JellyfinRequest -> request.headers.forEach { (name, value) -> addHeader(name, value) }
            is CompanionArtworkRequest -> {
                // 同一地址重新配对或文件更新后，不命中上一份内存封面。
                memoryCacheKey("${request.scope}:${request.revision}:${request.url}")
                tag(CompanionArtworkRequest::class.java, request)
            }
        }
    }
