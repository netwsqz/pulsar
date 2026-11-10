package com.example.pulsar.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 离线文案的唯一出处（#10）。
 *
 * 这些字面量曾经散在 `MainActivity` 多处与 `DiscoverSections` 里，各写一遍容易走样。
 * 这里把**改前文案逐字钉死**——收口只改出处，不改字。
 */
class OfflineMessagesTest {

    @Test
    fun `标题下的离线提示文案`() {
        assertEquals("离线 · 只显示已下载", OfflineNotice.VisibleOnly.message())
    }

    @Test
    fun `离线空态文案（专辑）`() {
        assertEquals("离线 · 手机里还没有下载的专辑", OfflineEmpty.Albums.message())
    }

    @Test
    fun `离线空态文案（歌曲）`() {
        assertEquals("离线 · 手机里还没有下载的歌曲", OfflineEmpty.Tracks.message())
    }

    @Test
    fun `在线搜索不打离线标识`() {
        assertNull(offlineSearchNotice(isOfflineSearch = false, isFiltering = false))
        assertNull(offlineSearchNotice(isOfflineSearch = false, isFiltering = true))
    }

    @Test
    fun `离线搜索且列表已被筛过时说明只搜已下载`() {
        val notice = offlineSearchNotice(isOfflineSearch = true, isFiltering = true)

        assertEquals(OfflineSearchNotice.DownloadedOnly, notice)
        assertEquals("曲库离线 · 只搜已下载；QQ 音乐不受影响", notice?.message())
    }

    @Test
    fun `离线搜索但已下载集合未知时说明只搜快照`() {
        // 集合未知 = 列表没被筛过：这时不能谎称「只搜已下载」。
        val notice = offlineSearchNotice(isOfflineSearch = true, isFiltering = false)

        assertEquals(OfflineSearchNotice.SnapshotOnly, notice)
        assertEquals("曲库离线 · 只搜手机上的快照；QQ 音乐不受影响", notice?.message())
    }
}
