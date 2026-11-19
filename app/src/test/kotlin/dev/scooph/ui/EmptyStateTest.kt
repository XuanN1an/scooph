package dev.scooph.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 三种空状态各一句灰字（计划 Task 25）：没有插图、没有按钮。 */
class EmptyStateTest {

    @Test
    fun `no bucket yet`() {
        assertEquals("还没有来源", emptyStateText(EmptyKind.NO_SOURCE))
    }

    @Test
    fun `no matching app`() {
        assertEquals("没有可管理的应用", emptyStateText(EmptyKind.NO_MATCH))
    }

    @Test
    fun `everything up to date`() {
        assertEquals("全部是最新的", emptyStateText(EmptyKind.ALL_UP_TO_DATE))
    }
}
