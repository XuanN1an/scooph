package dev.scooph

import android.app.Application
import android.util.Log
import androidx.room.Room
import dev.scooph.data.Repository
import dev.scooph.data.db.AppDatabase
import dev.scooph.engine.EngineFacade
import dev.scooph.install.AndroidInstaller
import dev.scooph.install.ApkInspector
import dev.scooph.install.InstallQueue
import dev.scooph.scan.AndroidPackageScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 进程级单例（spec §7）：数据库、Go 引擎与编排层只在这里各建一次。
 *
 * 首次启动在 IO 作用域里接上官方 bucket（Task 12）；失败只记日志，
 * 不弹任何东西，主屏照常打开。
 */
class ScoophApp : Application() {

    /** 进程级 IO 作用域：跑不需要 UI 返回值的后台任务。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy {
        Room.databaseBuilder(applicationContext, AppDatabase::class.java, "scooph.db").build()
    }

    val repository: Repository by lazy {
        Repository(
            sourceDao = database.sourceDao(),
            entryDao = database.entryDao(),
            trackedDao = database.trackedDao(),
            engine = EngineFacade(File(filesDir, "engine")),
            scanner = AndroidPackageScanner(packageManager),
            installer = AndroidInstaller(this),
            // 下载缓存放 cacheDir：系统「清除缓存」就能回收，不占 filesDir。
            downloadsDir = File(cacheDir, "engine/downloads"),
        )
    }

    /**
     * 进程级更新队列：主屏与前台服务共享同一份状态。
     *
     * 队列不落库，进程结束即消失（spec §10.4）。
     */
    val installQueue: InstallQueue by lazy {
        InstallQueue(appScope) { packageName, onProgress ->
            val outcome = repository.installOrUpdate(packageName, onProgress)
            Log.i(TAG, "installOrUpdate $packageName -> $outcome")
            outcome
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 签名校验读 APK 文件时要用 PackageManager（v2/v3 签名没有 META-INF 证书）。
        ApkInspector.init(packageManager)
        appScope.launch {
            try {
                // 旧版本的下载目录在 filesDir 下，迁移到 cacheDir 后顺手删掉。
                File(filesDir, "engine/downloads").deleteRecursively()
                repository.cleanupDownloads()
                repository.ensureDefaultSource()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 首次同步失败不是崩溃点：来源状态会标 ERROR，主屏照常打开。
                Log.w(TAG, "默认 bucket 初始化失败", e)
            }
        }
    }

    private companion object {
        const val TAG = "ScoophApp"
    }
}
