package com.example.pulsar.library

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Track
import com.example.pulsar.offline.albumIdentityOf
import com.example.pulsar.offline.normalizedNameKey

/** 专辑、艺术家详情与手动下载共享同一份曲目身份，不另存一份曲库。 */
internal class CompanionTrackCatalog(val tracks: List<Track>, knownTotals: Map<String, Int>) {
    private val byId = tracks.associateBy(Track::id)
    private val byAlbum = tracks.groupBy(::albumId)
    private val albumsById = byAlbum.mapValues { (id, members) ->
        val first = members.first()
        Album(
            id, albumIdentityOf(first).displayName.ifEmpty { "未标注专辑" },
            first.artist, maxOf(members.size, knownTotals[id] ?: 0),
            members.firstNotNullOfOrNull(Track::imageTag),
        )
    }
    val albums = albumsById.values.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

    fun track(id: String): Track? = byId[id]
    fun albumFor(track: Track): Album? = albumsById[albumId(track)]
    fun members(album: Album): List<Track> = byAlbum[album.id].orEmpty()
    fun artworkTrack(album: Album): Track? = members(album).firstOrNull { it.imageTag != null }

    // 保持 #49 已用的下载分组 ID；同名专辑至少按歌手区分。
    private fun albumId(track: Track): String {
        val artist = normalizedNameKey(track.artist).orEmpty()
        return "${artist.length}:$artist:${albumIdentityOf(track).key}"
    }
}
