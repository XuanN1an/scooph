package dev.scooph.domain

/**
 * 用户对某个已装应用的策略，是全 app 唯一的策略定义（spec §9.2）。
 *
 * 落库时存字符串而不是枚举序号（见 `TrackedEntity.policy`）。
 */
enum class Policy {
    /** 跟随 bucket 更新。 */
    FOLLOW,

    /** 跳过指定版本，等下一个。被跳过的版本号存在 `skipVersionCode`。 */
    SKIP_VERSION,

    /** 用户显式停止跟踪。墓碑，永不被自动改回。 */
    UNTRACKED,
}

/**
 * 生效条目与本机已装版本的客观关系。
 *
 * 这个事实由 Go 侧 `Plan()` 算出（spec §9.2），Kotlin 只负责在用户策略下
 * 把它翻译成显示状态。
 */
enum class Relation {
    /** 条目版本新于本机（可更新）。 */
    NEWER,

    /** 条目版本与本机相同。 */
    SAME,

    /** 条目版本旧于本机（本机版本更新）。 */
    OLDER,
}

/**
 * 主屏上唯一的状态值集合，共七个（spec §9.2）。
 *
 * 状态由策略 + 生效条目 + 本机快照推导出来，不落库；`lastError` 是覆盖层，
 * 不是第八个状态。
 */
enum class DerivedState {
    /** ① 没有生效条目 → 不显示。 */
    NO_SOURCE,

    /** ② 本机未安装 → 不显示，只在条目页出现。 */
    NOT_INSTALLED,

    /** ③ policy = UNTRACKED → 已忽略组。 */
    IGNORED,

    /** ④ 版本相同 → 已是最新组。 */
    UP_TO_DATE,

    /** ⑤ 条目版本低于本机 → 已是最新组，标「本机版本更新」，无更新动作。 */
    AHEAD,

    /** ⑥ policy = SKIP_VERSION 且条目版本 == skipVersionCode → 已忽略组。 */
    SKIPPED,

    /** ⑦ 其余 → 可更新组。 */
    UPDATABLE,
}
