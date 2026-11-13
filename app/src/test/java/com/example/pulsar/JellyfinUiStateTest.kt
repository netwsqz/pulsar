package com.example.pulsar

import com.example.pulsar.state.DownloadsState
import com.example.pulsar.state.LibraryState
import com.example.pulsar.state.SessionState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * 组合根形状钉子（`docs/library-state-refactor.md` Step 5 / #16）。
 *
 * #11 的迁移期兼容层（36 个平铺字段的只读副本 + 同名 `copy`）已在 #16 删除：
 * [JellyfinUiState] **只按所有权分三块 slice**，屏幕直接读写 slice，不再有第二种写法。
 * 谁想加回平铺字段，这条会红——先想清楚为什么它不属于某个 slice。
 */
class JellyfinUiStateTest {

    @Test
    fun `组合根只有三块 slice 字段`() {
        val fields = JellyfinUiState::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .sorted()

        assertEquals(listOf("downloads", "library", "session"), fields)
    }

    @Test
    fun `默认状态的三块 slice 都是默认实例`() {
        val state = JellyfinUiState()

        assertEquals(SessionState(), state.session)
        assertEquals(LibraryState(), state.library)
        assertEquals(DownloadsState(), state.downloads)
    }
}
