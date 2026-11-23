package com.example.pulsar.playback

import com.example.pulsar.data.companion.isCompanionMediaId
import com.example.pulsar.download.isExistingDownloadMediaId

/** 有来源作用域的媒体 ID，不得交给 Jellyfin 的歌曲接口。 */
internal fun isScopedLibraryTrack(mediaId: String): Boolean =
    isCompanionMediaId(mediaId) || isExistingDownloadMediaId(mediaId)

internal fun playbackLibraryIdentity(mediaId: String): String = when {
    isScopedLibraryTrack(mediaId) -> mediaId.substringBeforeLast(':')
    ':' in mediaId -> "local"
    else -> "jellyfin"
}
