package com.example.pulsar.state

import com.example.pulsar.QQDownloadUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * QQ 下载状态的清理语义钉子（#31）。
 *
 * 与曲库那条线（`StateClearedTest`，#13）同一套口径：
 * - 每块 slice 的 `cleared()` 直接返回默认实例，**约定「字段默认值 = 已清」**，
 *   给 slice 新增字段时自动跟上，不会漏清；
 * - 组合根的 `cleared(serviceUrl)` 只用于**换了另一台电脑上的导入服务**：
 *   服务状态、搜索结果、下载任务与弹窗整块作废（它们都属于上一条服务），
 *   用户偏好（音质、文件操作密钥）保留——那是「怎么用」，不是「看到什么」。
 *
 * 夹具见 [QQDownloadStateFixtures]：新增字段没填进夹具就会红。
 */
class QQDownloadStateClearedTest {

    /** 每个字段都**不是**默认值的 state：只有它能证明清理真的发生了。 */
    private val populated = QQDownloadStateFixtures.populated

    // ── 换服务：清掉「上一条服务看到的东西」，保留用户偏好 ──────────

    @Test
    fun `换服务清掉服务状态、搜索与下载，只保留用户偏好与新地址`() {
        val cleared = populated.cleared(serviceUrl = "http://10.0.0.9:8900")

        assertEquals(QQServiceState(serviceUrl = "http://10.0.0.9:8900"), cleared.service)
        assertEquals(QQSearchState(), cleared.search)
        assertEquals(QQDownloadState(), cleared.download)
        // 音质与文件操作密钥是持久化偏好：换服务不该把它们在界面上抹掉。
        assertEquals(populated.settings, cleared.settings)
    }

    @Test
    fun `换服务不会留下旧服务的下载标记与选曲弹窗`() {
        val cleared = populated.cleared(serviceUrl = "http://10.0.0.9:8900")

        assertTrue(cleared.download.inLibraryMids.isEmpty())
        assertTrue(cleared.download.activeDownloadingMids.isEmpty())
        assertNull(cleared.download.albumPicker)
        assertNull(cleared.download.redownloadPrompt)
        assertNull(cleared.download.actionMessage)
        assertTrue(cleared.search.songs.isEmpty())
        assertNull(cleared.search.error)
    }

    // ── 新增字段的保险丝 ─────────────────────────────────────────

    @Test
    fun `夹具覆盖了每个 slice 的每个字段`() {
        assertEveryFieldPopulated(QQServiceState(), populated.service, "service")
        assertEveryFieldPopulated(QQSettingsState(), populated.settings, "settings")
        assertEveryFieldPopulated(QQSearchState(), populated.search, "search")
        assertEveryFieldPopulated(QQDownloadState(), populated.download, "download")
    }

    @Test
    fun `有清理路径的三块 slice，cleared 都是默认实例`() {
        // 「默认值 = 已清」：新增字段只要不把默认值当有意义的值，就自动跟上。
        // QQSettingsState 没有清理路径（用户偏好不随换服务清），所以它没有 cleared()。
        assertEquals(QQServiceState(), populated.service.cleared())
        assertEquals(QQSearchState(), populated.search.cleared())
        assertEquals(QQDownloadState(), populated.download.cleared())
    }

    /**
     * 逐字段确认 [populated] 与 [default] 不同（嵌套 slice 递归下去）。
     *
     * 给 slice 新增字段却没更新夹具时，这条会红——提示把新字段也纳入清理测试。
     * 不更新的话，「清成默认值」的断言对新字段永远成立，等于没测。
     */
    private fun assertEveryFieldPopulated(default: Any, populated: Any, path: String) {
        val fields = populated.javaClass.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
        assertTrue("$path 上没找到字段（反射失效？）", fields.isNotEmpty())
        fields.forEach { field ->
            field.isAccessible = true
            val name = "$path.${field.name}"
            val actual = field.get(populated)
            val fallback = field.get(default)
            if (isStateSlice(field.type)) {
                assertEveryFieldPopulated(fallback as Any, actual as Any, name)
            } else {
                assertNotEquals("新增字段 $name 没进清理夹具（默认值即「已清」）", fallback, actual)
            }
        }
    }

    /** `com.example.pulsar.state` 包里的 data class（嵌套 slice）；枚举不算。 */
    private fun isStateSlice(type: Class<*>): Boolean =
        type.name.startsWith("com.example.pulsar.state.") && !type.isEnum
}
