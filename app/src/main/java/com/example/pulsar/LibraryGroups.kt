package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.UNKNOWN_ARTIST

/** 艺术家/流派列表的一行：组名 + 它下面的专辑（已按专辑名排序）。 */
internal data class LibraryGroup(val name: String, val albums: List<Album>)

/**
 * 按艺术家把专辑分组（工单 06）。纯函数、无 IO。
 *
 * 归并规则：
 * - 组名按**大小写不敏感**归并（"Beatles" / "beatles" 是同一人），显示时取首次出现的写法；
 * - 空白艺术家不产生分组（服务端缺字段时别造一个空名字的艺术家）；
 * - 组按名称（大小写不敏感）排序，组内专辑按专辑名排序——顺序稳定，不随请求顺序跳。
 */
internal fun groupAlbumsByArtist(albums: List<Album>): List<LibraryGroup> =
    groupAlbums(albums) { listOf(it.artist) }

/**
 * 按流派把专辑分组。一张专辑可以属于多个流派，每个流派各出现一次；
 * 同一张专辑重复标注同一流派（大小写不同）只算一次。规则其余同 [groupAlbumsByArtist]。
 */
internal fun groupAlbumsByGenre(albums: List<Album>): List<LibraryGroup> =
    groupAlbums(albums) { it.genres }

/** UI 用的统一入口：按页面类型选分组维度，列表页和详情页共用，避免两处各写一遍 if。 */
internal fun libraryGroups(albums: List<Album>, byGenre: Boolean): List<LibraryGroup> =
    if (byGenre) groupAlbumsByGenre(albums) else groupAlbumsByArtist(albums)

private fun groupAlbums(
    albums: List<Album>,
    keysOf: (Album) -> List<String>
): List<LibraryGroup> {
    // 键 = 小写组名；值 = (首次出现的显示写法, 专辑集合)
    val groups = LinkedHashMap<String, Pair<String, MutableList<Album>>>()
    albums.forEach { album ->
        val seen = HashSet<String>()
        keysOf(album)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != UNKNOWN_ARTIST && seen.add(it.lowercase()) }
            .forEach { name ->
                groups.getOrPut(name.lowercase()) { name to mutableListOf() }.second += album
            }
    }
    return groups.values
        .map { (name, members) ->
            LibraryGroup(
                name = name,
                albums = members.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
            )
        }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.name }))
}
