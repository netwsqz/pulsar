package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.UNKNOWN_ARTIST

/** 艺术家列表的一行：组名 + 它下面的专辑（已按专辑名排序）。 */
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

/** UI 用的入口：艺术家列表页和详情页共用，避免两处各写一遍。流派页已删除（ADR-0009）。 */
internal fun libraryGroups(albums: List<Album>): List<LibraryGroup> =
    groupAlbumsByArtist(albums)

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
