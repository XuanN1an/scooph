package dev.scooph.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** 把本地 APK 交给系统安装。实现负责会话、确认与结果映射。 */
interface Installer {
    suspend fun install(apk: File, packageName: String): InstallOutcome
}

/**
 * `PackageInstaller` 会话实现（spec §10.3 / 计划 Task 17）。
 *
 * 流程：整包会话 → 写入 APK → `commit` 带一个广播 PendingIntent →
 * 挂起等系统回执 → [toOutcome] 投影。系统确认框由 Android 自己弹，
 * 这里没有自己的模态框。
 */
class AndroidInstaller(private val context: Context) : Installer {

    private companion object {
        /** 系统回执的 action；Android 没有公开常量，自己定一个并写成显式包名。 */
        const val ACTION_INSTALL_COMMIT = "dev.scooph.action.INSTALL_COMMIT"
    }

    override suspend fun install(apk: File, packageName: String): InstallOutcome {
        if (!context.packageManager.canRequestPackageInstalls()) {
            return InstallOutcome.Failed("需要允许安装未知应用")
        }

        val installer = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(packageName)
                // setInstallerPackageName 是 API 34 才有的；更早的版本由系统推断。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setInstallerPackageName(context.packageName)
                }
            }
        val sessionId = installer.createSession(params)
        return try {
            installer.openSession(sessionId).use { session ->
                writeApk(session, apk)
                awaitCommit(session, sessionId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            InstallOutcome.Failed(e.message ?: "安装失败")
        } finally {
            runCatching { installer.abandonSession(sessionId) }
        }
    }

    private fun writeApk(session: PackageInstaller.Session, apk: File) {
        apk.inputStream().use { input ->
            session.openWrite("base.apk", 0, apk.length()).use { output ->
                input.copyTo(output)
                session.fsync(output)
            }
        }
    }

    private suspend fun awaitCommit(
        session: PackageInstaller.Session,
        sessionId: Int,
    ): InstallOutcome = suspendCancellableCoroutine { continuation ->
        val finished = AtomicBoolean(false)

        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (!finished.compareAndSet(false, true)) return
                    runCatching { receiverContext.unregisterReceiver(this) }
                    val status =
                        intent.getIntExtra(
                            PackageInstaller.EXTRA_STATUS,
                            PackageInstaller.STATUS_FAILURE,
                        )
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    continuation.resume(toOutcome(status, message))
                }
            }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_INSTALL_COMMIT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        continuation.invokeOnCancellation {
            runCatching { context.unregisterReceiver(receiver) }
        }

        // 回执只发给本应用：PendingIntent 以本应用身份发送，收件人写死包名。
        val commitIntent =
            Intent(ACTION_INSTALL_COMMIT).setPackage(context.packageName)
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                sessionId,
                commitIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        session.commit(pendingIntent.intentSender)
    }
}
