package com.example.pulsar.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.pulsar.domain.model.Track

/**
 * 播放条目元数据。响度增益挂 extras 而不是 MediaItem 的本地 tag——队列要经
 * MediaController 交给播放服务，本地 tag 只存在于控制端，到不了服务端；extras 会
 * 随 MediaMetadata 一起传过去，播放服务据此在换歌时算出回放增益。
 */
internal fun Track.toPlaybackMetadata(): MediaMetadata = MediaMetadata.Builder()
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
 * 把一首曲目构造成播放条目：流地址 + id + 封面 tag + 元数据。
 *
 * 封面 tag 也挂到条目上：Activity 被系统回收重建后，仍可从播放器读回封面信息。
 * `url` 由调用方保证非空（无流地址时应当不调本函数）。
 */
internal fun buildMediaItem(track: Track, url: String): MediaItem = MediaItem.Builder()
    .setUri(url)
    .setMediaId(track.id)
    .setTag(track.imageTag)
    .setMediaMetadata(track.toPlaybackMetadata())
    .build()
