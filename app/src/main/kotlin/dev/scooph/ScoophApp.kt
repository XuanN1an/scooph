package dev.scooph

import android.app.Application
import androidx.room.Room
import dev.scooph.data.Repository
import dev.scooph.data.db.AppDatabase
import dev.scooph.engine.EngineFacade
import dev.scooph.scan.AndroidPackageScanner
import java.io.File

/**
 * 进程级单例（spec §7）：数据库、Go 引擎与编排层只在这里各建一次。
 *
 * Task 11 只需要 [Repository]；Task 12 会在这里接默认 bucket。
 */
class ScoophApp : Application() {

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
        )
    }
}
