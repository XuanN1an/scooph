package dev.scooph.install

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
                if (items.isNotEmpty() && items.all { isTerminal(it.state) }) {
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

    private fun isTerminal(state: QueueState): Boolean =
        when (state) {
            QueueState.Waiting, QueueState.Downloading -> false
            QueueState.Done, QueueState.AwaitingConfirm, is QueueState.Skipped, is QueueState.Failed -> true
        }

    companion object {
        const val EXTRA_PACKAGES = "dev.scooph.extra.PACKAGES"
        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 1

        /** 主屏点「全部更新」时调用：把包名列表交给前台服务。 */
        fun intent(context: Context, packages: List<String>): Intent =
            Intent(context, InstallService::class.java)
                .putStringArrayListExtra(EXTRA_PACKAGES, ArrayList(packages))
    }
}
