package dev.scooph.install

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.scooph.ScoophApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 一键更新期间的前台服务（spec §10.4 / 计划 Task 19）。
 *
 * 服务只做两件事：把进程留在前台（`dataSync` 类型），以及队列结束时自己退出。
 * 队列状态放在 [ScoophApp.installQueue]，主屏和服务看到的是同一份。
 */
class InstallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "更新", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val packages = intent?.getStringArrayListExtra(EXTRA_PACKAGES).orEmpty()
        if (packages.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        val queue = (application as ScoophApp).installQueue
        queue.start(packages)

        scope.launch {
            queue.items.collect { items ->
                Log.i("ScoophQueue", items.joinToString { "${it.packageName}=${it.state}" })
                if (items.isNotEmpty() && items.all { isTerminal(it.state) }) {
                    postSummary(items)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在更新应用")
            .setOngoing(true)
            .setSilent(true)
            .build()

    // POST_NOTIFICATIONS 在 canPostNotifications() 里显式检查过；lint 的数据流
    // 跨不过这个辅助函数，所以这里局部豁免。
    @SuppressLint("MissingPermission")
    private fun postSummary(items: List<QueueItem>) {
        // POST_NOTIFICATIONS 未授权时静默跳过，不影响队列结果。
        if (!canPostNotifications()) return
        val notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Scooph")
                .setContentText(summaryText(items))
                .setAutoCancel(true)
                .build()
        runCatching { NotificationManagerCompat.from(this).notify(SUMMARY_ID, notification) }
    }

    private fun canPostNotifications(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        }

    private fun isTerminal(state: QueueState): Boolean =
        when (state) {
            QueueState.Waiting, QueueState.Downloading -> false
            QueueState.Done, QueueState.AwaitingConfirm, is QueueState.Skipped, is QueueState.Failed -> true
        }

    companion object {
        const val EXTRA_PACKAGES = "dev.scooph.extra.PACKAGES"
        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 1
        private const val SUMMARY_ID = 2

        /** 主屏点「全部更新」时调用：把包名列表交给前台服务。 */
        fun intent(context: Context, packages: List<String>): Intent =
            Intent(context, InstallService::class.java)
                .putStringArrayListExtra(EXTRA_PACKAGES, ArrayList(packages))
    }
}

/**
 * 队列结束时的汇总文案（计划 Task 21）。
 *
 * 全部失败时说「全部失败」；否则只列非零的计数，不加多余解释。
 * 签名变更算失败（人还没处理）。
 */
fun summaryText(items: List<QueueItem>): String {
    val done = items.count { it.state == QueueState.Done }
    val skipped = items.count { it.state is QueueState.Skipped }
    val failed =
        items.count { it.state is QueueState.Failed || it.state == QueueState.AwaitingConfirm }

    if (items.isNotEmpty() && done == 0 && skipped == 0) {
        return "全部失败 · 失败 $failed"
    }

    val parts =
        buildList {
            if (done > 0) add("成功 $done")
            if (skipped > 0) add("跳过 $skipped")
            if (failed > 0) add("失败 $failed")
        }
    if (parts.isEmpty()) return "更新完成"
    return "更新完成 · ${parts.joinToString("，")}"
}
