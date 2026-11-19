package dev.scooph.install

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 系统安装结果的四种投影（计划 Task 17）。
 *
 * 映射抽成纯函数，不碰 PackageInstaller 会话，单测不需要 Android 运行时：
 * 状态码都是编译期常量，直接用。
 */
class InstallResultTest {

    @Test
    fun `success maps to Success`() {
        assertEquals(
            InstallOutcome.Success,
            toOutcome(PackageInstaller.STATUS_SUCCESS, null),
        )
    }

    @Test
    fun `aborted maps to Cancelled`() {
        assertEquals(
            InstallOutcome.Cancelled,
            toOutcome(PackageInstaller.STATUS_FAILURE_ABORTED, "用户取消"),
        )
    }

    @Test
    fun `pending user action maps to NeedsUserAction`() {
        assertEquals(
            InstallOutcome.NeedsUserAction,
            toOutcome(PackageInstaller.STATUS_PENDING_USER_ACTION, null),
        )
    }

    @Test
    fun `other failures carry the system message`() {
        val outcome = toOutcome(PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_UPDATE_INCOMPATIBLE")

        assertEquals(
            InstallOutcome.Failed("INSTALL_FAILED_UPDATE_INCOMPATIBLE"),
            outcome,
        )
    }

    @Test
    fun `failure without a message still has a readable reason`() {
        val outcome = toOutcome(PackageInstaller.STATUS_FAILURE, null)

        assertEquals(InstallOutcome.Failed("安装失败（状态 1）"), outcome)
    }
}
