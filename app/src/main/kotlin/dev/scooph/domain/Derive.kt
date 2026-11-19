package dev.scooph.domain

/**
 * 七值状态机（spec §9.2）——状态推导的唯一定义处。
 *
 * 纯函数，不读数据库、不做版本比较：版本关系 [Relation] 由 Go 侧 `Plan()` 算出，
 * 这里只按用户策略把它翻译成显示状态。按序号判定，命中即返回：
 *
 * ① [relation] 为 null（没有生效条目）→ [DerivedState.NO_SOURCE]
 * ② [policy] 为 null（本机未安装）→ [DerivedState.NOT_INSTALLED]
 * ③ [Policy.UNTRACKED] → [DerivedState.IGNORED]
 * ④ [Relation.SAME] → [DerivedState.UP_TO_DATE]
 * ⑤ [Relation.OLDER] → [DerivedState.AHEAD]
 * ⑥ [Policy.SKIP_VERSION] 且条目版本 == [skipVersionCode] → [DerivedState.SKIPPED]
 * ⑦ 其余 → [DerivedState.UPDATABLE]
 *
 * 顺序本身是规则的一部分：例如 ④ 在 ⑥ 之前，所以「本机已是被跳过的版本」算
 * 已是最新而非已忽略；⑥ 只针对那一版，上游出更高版本后自然落回 ⑦。
 */
fun derive(
    policy: Policy?,
    relation: Relation?,
    skipVersionCode: Long,
    entryVersionCode: Long,
): DerivedState = when {
    relation == null -> DerivedState.NO_SOURCE
    policy == null -> DerivedState.NOT_INSTALLED
    policy == Policy.UNTRACKED -> DerivedState.IGNORED
    relation == Relation.SAME -> DerivedState.UP_TO_DATE
    relation == Relation.OLDER -> DerivedState.AHEAD
    policy == Policy.SKIP_VERSION && entryVersionCode == skipVersionCode -> DerivedState.SKIPPED
    else -> DerivedState.UPDATABLE
}
