package com.example.pulsar

import com.example.pulsar.state.QQDownloadState
import com.example.pulsar.state.QQSearchState
import com.example.pulsar.state.QQServiceState
import com.example.pulsar.state.QQSettingsState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * QQ 下载状态组合根的形状钉子（#31 拆分，#34 删兼容层）。
 *
 * 拆法与曲库那条线一致：`QQDownloadUiState` 只按所有权装四块 slice，
 * 屏幕与测试直接读带归属的路径（`state.service.xxx` / `state.search.xxx` / …）。
 * 迁移期兼容层已删除——谁想加回平铺字段，形状钉子（`组合根恰好只有四块 slice 字段`）会红。
 */
class QQDownloadUiStateTest {

    @Test
    fun `组合根恰好只有四块 slice 字段`() {
        // 按**字段名集合**钉死，不按类型筛：兼容层删掉后，类型筛会退化成恒真，
        // 「不许往组合根加字段」这条约束就静默失效了（#34 的注意事项）。
        val fields = QQDownloadUiState::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .sorted()

        assertEquals(listOf("download", "search", "service", "settings"), fields)
    }

    @Test
    fun `默认状态的四块 slice 都是默认实例`() {
        val state = QQDownloadUiState()

        assertEquals(QQServiceState(), state.service)
        assertEquals(QQSettingsState(), state.settings)
        assertEquals(QQSearchState(), state.search)
        assertEquals(QQDownloadState(), state.download)
    }
}
