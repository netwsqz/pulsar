package com.example.pulsar.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.pulsar.domain.model.Track
import java.util.UUID

private const val REPORT_OWNER = "pulsar.playback.report_owner"
private const val REPORT_TRACK = "pulsar.playback.report_track"
private const val REPORT_REVISION = "pulsar.playback.report_revision"
private const val REPORT_QUEUE_ENTRY = "pulsar.playback.report_queue_entry"

/** 每次构建队列项都不同；同一项暂停、拖进度或切 USB 输出则不变。 */
internal val MediaItem.playbackReportQueueEntry: String?
    get() = mediaMetadata.extras?.getString(REPORT_QUEUE_ENTRY)

internal data class PlaybackReportIdentity(val owner: String, val trackId: String, val revision: String? = null)

internal val MediaItem.playbackReportIdentity: PlaybackReportIdentity?
    get() {
        val extras = mediaMetadata.extras ?: return null
        val owner = extras.getString(REPORT_OWNER) ?: return null
        val trackId = extras.getString(REPORT_TRACK) ?: return null
        return PlaybackReportIdentity(owner, trackId, extras.getString(REPORT_REVISION))
    }

/**
 * 播放条目元数据。响度增益挂 extras 而不是 MediaItem 的本地 tag——队列要经
 * MediaController 交给播放服务，本地 tag 只存在于控制端，到不了服务端；extras 会
 * 随 MediaMetadata 一起传过去，播放服务据此在换歌时算出回放增益。
 */
internal fun Track.toPlaybackMetadata(reportIdentity: PlaybackReportIdentity? = null): MediaMetadata = MediaMetadata.Builder()
    .setTitle(title)
    .setArtist(artist)
    .setAlbumTitle(album)
    .setExtras(
        Bundle().apply {
            putLoudnessGain(normalizationGainDb)
            if (reportIdentity != null) {
                putString(REPORT_OWNER, reportIdentity.owner)
                putString(REPORT_TRACK, reportIdentity.trackId)
                putString(REPORT_REVISION, reportIdentity.revision)
                putString(REPORT_QUEUE_ENTRY, UUID.randomUUID().toString())
            }
        }
    )
    .build()

/**
 * 把一首曲目构造成播放条目：流地址 + id + 封面 tag + 元数据。
 *
 * 封面 tag 也挂到条目上：Activity 被系统回收重建后，仍可从播放器读回封面信息。
 * `url` 由调用方保证非空（无流地址时应当不调本函数）。
 */
internal fun buildMediaItem(track: Track, url: String, reportIdentity: PlaybackReportIdentity? = null): MediaItem = MediaItem.Builder()
    .setUri(url)
    .setMediaId(track.id)
    .setTag(track.imageTag)
    .setMediaMetadata(track.toPlaybackMetadata(reportIdentity))
    .build()
