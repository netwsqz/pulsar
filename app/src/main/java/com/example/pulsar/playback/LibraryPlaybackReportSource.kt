package com.example.pulsar.playback

import com.example.pulsar.data.companion.CompanionLibraryConnection
import com.example.pulsar.data.companion.isCompanionMediaId

/** 以媒体项的归属路由，不随用户当前浏览的页面切换。 */
internal class LibraryPlaybackReportSource(
    private val companion: CompanionLibraryConnection,
    private val jellyfin: PlaybackReportSource,
) : PlaybackReportSource {
    override fun channelFor(identity: PlaybackReportIdentity): PlaybackReportChannel? = when {
        isCompanionMediaId(identity.trackId) -> companion.playbackReportChannel(identity)
        identity.trackId.isNotBlank() && ':' !in identity.trackId -> jellyfin.channelFor(identity)
        else -> null // 本地音频和未关联的旧副本不计入任何服务器历史。
    }
}
