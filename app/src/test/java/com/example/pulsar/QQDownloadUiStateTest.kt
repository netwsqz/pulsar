package com.example.pulsar

import com.example.pulsar.state.QQDownloadState
import com.example.pulsar.state.QQDownloadStateFixtures
import com.example.pulsar.state.QQSearchState
import com.example.pulsar.state.QQServiceState
import com.example.pulsar.state.QQSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * QQ 下载状态组合根的形状钉子（#31，`docs/qq-download-state-refactor.md`）。
 *
 * 拆法与曲库那条线一致：`QQDownloadUiState` 只按所有权装四块 slice，
 * 旧的 25 个平铺字段保留为**只读兼容层**（构造时求值的 `val`，屏幕与现有测试不用改）。
 * 兼容层只是读法，不承载状态——构造参数只有 slice，写入一律走 slice。
 *
 * 谁想加回可写的平铺字段，或者把某个旧读法接到别的 slice 字段上，这些会红。
 */
class QQDownloadUiStateTest {

    private val populated = QQDownloadStateFixtures.populated

    @Test
    fun `组合根只有四块 slice 字段`() {
        // 兼容层也带 backing field，所以按类型筛：属于 state 包的字段只能是这四块 slice。
        val sliceFields = QQDownloadUiState::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .filter { it.type.name.startsWith("com.example.pulsar.state.") }
            .map { it.name }
            .sorted()

        assertEquals(listOf("download", "search", "service", "settings"), sliceFields)
    }

    @Test
    fun `默认状态的四块 slice 都是默认实例`() {
        val state = QQDownloadUiState()

        assertEquals(QQServiceState(), state.service)
        assertEquals(QQSettingsState(), state.settings)
        assertEquals(QQSearchState(), state.search)
        assertEquals(QQDownloadState(), state.download)
    }

    @Test
    fun `兼容层是只读的`() {
        // 兼容层只许是 val（final backing field）：状态写入一律走 slice，
        // 否则会回到「两份写法、总有一处忘写」的老毛病（曲库那条线 #16 的教训）。
        val writable = QQDownloadUiState::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .filterNot { it.type.name.startsWith("com.example.pulsar.state.") }
            .filterNot { Modifier.isFinal(it.modifiers) }
            .map { it.name }

        assertTrue("兼容层出现了可写字段：$writable", writable.isEmpty())
    }

    @Test
    fun `兼容层读法与 slice 字段的映射固定`() {
        // 兼容层由构造参数派生，值本身不会漂；这条钉的是**映射**——哪个旧读法接哪块 slice 的
        // 哪个字段。有人把 `songs` 接到 `albums`、或换 slice 却不改读法时，这里会红。
        assertEquals(populated.service.serviceUrl, populated.serviceUrl)
        assertEquals(populated.service.serviceReachable, populated.serviceReachable)
        assertEquals(populated.service.credentialExpired, populated.credentialExpired)
        assertEquals(populated.service.isRetryingCredential, populated.isRetryingCredential)
        assertEquals(populated.service.credentialMessage, populated.credentialMessage)
        assertEquals(populated.service.libraryReady, populated.libraryReady)
        assertEquals(populated.service.libraryCount, populated.libraryCount)
        assertEquals(populated.service.libraryRoot, populated.libraryRoot)

        assertEquals(populated.settings.quality, populated.quality)
        assertEquals(populated.settings.fileOpsKey, populated.fileOpsKey)
        assertEquals(populated.settings.fileOpsKeyInput, populated.fileOpsKeyInput)

        assertEquals(populated.search.query, populated.searchQuery)
        assertEquals(populated.search.isSearching, populated.isSearching)
        assertEquals(populated.search.songs, populated.songs)
        assertEquals(populated.search.albums, populated.albums)
        assertEquals(populated.search.searchHadResults, populated.searchHadResults)
        assertEquals(populated.search.error, populated.searchError)

        assertEquals(populated.download.jobs, populated.jobs)
        assertEquals(populated.download.downloadingMids, populated.downloadingMids)
        assertEquals(populated.download.inLibraryMids, populated.inLibraryMids)
        assertEquals(populated.download.isEnqueuingAlbum, populated.isEnqueuingAlbum)
        assertEquals(populated.download.enqueuingAlbumMid, populated.enqueuingAlbumMid)
        assertEquals(populated.download.albumPicker, populated.albumPicker)
        assertEquals(populated.download.redownloadPrompt, populated.redownloadPrompt)
        assertEquals(populated.download.actionMessage, populated.actionMessage)
        assertEquals(populated.download.activeDownloadingMids, populated.activeDownloadingMids)

        // 「下载中」= 服务端标记 ∪ 本地 queued/running，派生只允许算一次（在 slice 里）。
        assertTrue(populated.activeDownloadingMids.containsAll(setOf("down-1", "0039MnYb0qxYhV")))
        assertFalse(populated.activeDownloadingMids.contains("lib-1"))
    }
}
