package com.example.pulsar

import com.example.pulsar.domain.model.Album
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 艺术家分组（工单 06 / ADR-0009）：纯函数、无 IO、稳定排序，边界覆盖空库/缺字段/同名归并。 */
class LibraryGroupsTest {

    private fun album(
        id: String,
        name: String,
        artist: String,
        genres: List<String> = emptyList()
    ) = Album(id = id, name = name, artist = artist, trackCount = null, imageTag = null, genres = genres)

    @Test
    fun `空列表返回空分组`() {
        assertTrue(groupAlbumsByArtist(emptyList()).isEmpty())
        assertTrue(libraryGroups(emptyList()).isEmpty())
    }

    @Test
    fun `按艺术家归并并按名称稳定排序`() {
        val groups = groupAlbumsByArtist(
            listOf(
                album("a1", "Zebra", "Beatles"),
                album("a2", "Apple", "abba"),
                album("a3", "Yellow", "Beatles")
            )
        )

        assertEquals(listOf("abba", "Beatles"), groups.map { it.name })
        // 组内也按专辑名排序（Apple/Zebra 与输入顺序无关）。
        assertEquals(listOf("a2"), groups[0].albums.map { it.id })
        assertEquals(listOf("a3", "a1"), groups[1].albums.map { it.id })
    }

    @Test
    fun `同名但大小写不同的艺术家合并为一组`() {
        val groups = groupAlbumsByArtist(
            listOf(
                album("a1", "One", "Beatles"),
                album("a2", "Two", "beatles"),
                album("a3", "Three", "BEATLES")
            )
        )

        assertEquals(1, groups.size)
        assertEquals("Beatles", groups[0].name)
        assertEquals(3, groups[0].albums.size)
    }

    @Test
    fun `缺失或空白的艺术家字段不产生分组`() {
        val groups = groupAlbumsByArtist(
            listOf(
                album("a1", "One", ""),
                album("a2", "Two", "   "),
                album("a3", "Three", "Real Artist")
            )
        )

        assertEquals(listOf("Real Artist"), groups.map { it.name })
    }

    @Test
    fun `没有艺术家的占位专辑不产生分组`() {
        // DTO 层会把缺字段的专辑填成 "Unknown artist"；艺术家页不该凭空多一个假艺术家。
        val groups = groupAlbumsByArtist(
            listOf(
                album("a1", "One", "Unknown artist"),
                album("a2", "Two", "Real Artist")
            )
        )

        assertEquals(listOf("Real Artist"), groups.map { it.name })
    }

    @Test
    fun `libraryGroups 只按艺术家分组，流派字段不参与`() {
        // 流派页已删除（ADR-0009）：专辑带流派也只按艺术家建组。
        val groups = libraryGroups(
            listOf(
                album("a1", "One", "Artist A", genres = listOf("Rock", "Pop")),
                album("a2", "Two", "Artist B", genres = listOf("Rock"))
            )
        )

        assertEquals(listOf("Artist A", "Artist B"), groups.map { it.name })
    }
}
