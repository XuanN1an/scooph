package dev.scooph.install

import android.content.pm.PackageInstaller

/**
 * 一次安装的结果（计划 Task 17）。
 *
 * 系统只给状态码和一个字符串，这里把它们投影成四种可穷举的结果：
 * [Success] / [Cancelled] / [NeedsUserAction] / [Failed]。
 * 失败原因在构造处就定稿，UI 只显示不再拼接（spec §9.2 的错误覆盖层）。
 */
sealed interface InstallOutcome {
    data object Success : InstallOutcome

    /** 用户在系统确认框里点了取消：这次不装，不落任何状态。 */
    data object Cancelled : InstallOutcome

    /** 系统要求用户先做动作（比如打开设置允许安装未知应用）。 */
    data object NeedsUserAction : InstallOutcome

    data class Failed(val reason: String) : InstallOutcome
}

/**
 * `PackageInstaller` 状态码到 [InstallOutcome] 的纯函数映射。
 *
 * [message] 是系统给的 `EXTRA_STATUS_MESSAGE`，为空时给一个可读的兜底文案。
 */
fun toOutcome(status: Int, message: String?): InstallOutcome =
    when (status) {
        PackageInstaller.STATUS_SUCCESS -> InstallOutcome.Success
        PackageInstaller.STATUS_FAILURE_ABORTED -> InstallOutcome.Cancelled
        PackageInstaller.STATUS_PENDING_USER_ACTION -> InstallOutcome.NeedsUserAction
        else ->
            InstallOutcome.Failed(
                message?.takeIf { it.isNotBlank() } ?: "安装失败（状态 $status）",
            )
    }
