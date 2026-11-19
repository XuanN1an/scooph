package dev.scooph.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 七值状态机（spec §9.2）。
 *
 * 判定顺序严格按 spec，命中即返回：
 * ① relation == null → NO_SOURCE
 * ② policy == null → NOT_INSTALLED
 * ③ UNTRACKED → IGNORED
 * ④ SAME → UP_TO_DATE
 * ⑤ OLDER → AHEAD
 * ⑥ SKIP_VERSION 且 entryVersionCode == skipVersionCode → SKIPPED
 * ⑦ 其余 → UPDATABLE
 */
class DeriveTest {

    @Test
    fun `exhaustive policy and relation matrix`() {
        // 3 个 policy × 3 个 relation，期望值逐条写死。
        // skipVersionCode = 0 与 entryVersionCode = 1 不同，因此 ⑥ 不参与。
        val cases = listOf(
            Triple(Policy.FOLLOW, Relation.NEWER, DerivedState.UPDATABLE),
            Triple(Policy.FOLLOW, Relation.SAME, DerivedState.UP_TO_DATE),
            Triple(Policy.FOLLOW, Relation.OLDER, DerivedState.AHEAD),
            Triple(Policy.SKIP_VERSION, Relation.NEWER, DerivedState.UPDATABLE),
            Triple(Policy.SKIP_VERSION, Relation.SAME, DerivedState.UP_TO_DATE),
            Triple(Policy.SKIP_VERSION, Relation.OLDER, DerivedState.AHEAD),
            Triple(Policy.UNTRACKED, Relation.NEWER, DerivedState.IGNORED),
            Triple(Policy.UNTRACKED, Relation.SAME, DerivedState.IGNORED),
            Triple(Policy.UNTRACKED, Relation.OLDER, DerivedState.IGNORED),
        )
        assertEquals("矩阵应为 9 条", 9, cases.size)
        for ((policy, relation, expected) in cases) {
            assertEquals(
                "$policy × $relation",
                expected,
                derive(policy, relation, skipVersionCode = 0, entryVersionCode = 1),
            )
        }
    }

    @Test
    fun `null relation means no source regardless of policy`() {
        assertEquals(
            DerivedState.NO_SOURCE,
            derive(Policy.FOLLOW, null, skipVersionCode = 0, entryVersionCode = 1),
        )
        assertEquals(
            DerivedState.NO_SOURCE,
            derive(Policy.UNTRACKED, null, skipVersionCode = 0, entryVersionCode = 1),
        )
    }

    @Test
    fun `null policy means not installed`() {
        assertEquals(
            DerivedState.NOT_INSTALLED,
            derive(null, Relation.NEWER, skipVersionCode = 0, entryVersionCode = 1),
        )
        assertEquals(
            DerivedState.NOT_INSTALLED,
            derive(null, Relation.OLDER, skipVersionCode = 0, entryVersionCode = 1),
        )
    }

    @Test
    fun `no source outranks not installed when both are null`() {
        // ① 的优先级高于 ②：没有条目时谈不上「未安装」。
        assertEquals(
            DerivedState.NO_SOURCE,
            derive(null, null, skipVersionCode = 0, entryVersionCode = 1),
        )
    }

    @Test
    fun `skipped version is skipped`() {
        assertEquals(
            DerivedState.SKIPPED,
            derive(Policy.SKIP_VERSION, Relation.NEWER, skipVersionCode = 7000, entryVersionCode = 7000),
        )
    }

    @Test
    fun `skip then newer version comes back as updatable`() {
        // 跳过只针对那一版；上游出了更高的版本就重新可更新。
        assertEquals(
            DerivedState.UPDATABLE,
            derive(Policy.SKIP_VERSION, Relation.NEWER, skipVersionCode = 7000, entryVersionCode = 7001),
        )
    }

    @Test
    fun `same version outranks skip classification`() {
        // ④ 在 ⑥ 之前：本机已经是这一版，属「已是最新」而不是「已忽略」。
        assertEquals(
            DerivedState.UP_TO_DATE,
            derive(Policy.SKIP_VERSION, Relation.SAME, skipVersionCode = 7000, entryVersionCode = 7000),
        )
    }

    @Test
    fun `older entry is ahead even when it matches skip version`() {
        // ⑤ 在 ⑥ 之前：本机版本更高时只标「本机版本更新」。
        assertEquals(
            DerivedState.AHEAD,
            derive(Policy.SKIP_VERSION, Relation.OLDER, skipVersionCode = 7000, entryVersionCode = 7000),
        )
    }

    @Test
    fun `zero installed version is updatable`() {
        // Review Focus 4：已装 versionCode 为 0 时，关系由 Relation.NEWER 表达，
        // 不得因 0 产生负数比较或「本机版本更新」的误判。
        assertEquals(
            DerivedState.UPDATABLE,
            derive(Policy.FOLLOW, Relation.NEWER, skipVersionCode = 0, entryVersionCode = 1),
        )
    }

    @Test
    fun `zero skip version does not swallow newer entry`() {
        assertEquals(
            DerivedState.UPDATABLE,
            derive(Policy.SKIP_VERSION, Relation.NEWER, skipVersionCode = 0, entryVersionCode = 1),
        )
    }
}
