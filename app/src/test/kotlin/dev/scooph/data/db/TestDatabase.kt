package dev.scooph.data.db

import android.content.Context
import androidx.room.Room
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 单测用的 in-memory Room + 专属执行器。
 *
 * Robolectric 的 native SQLite 在 Windows 上会和 Room 的
 * `InvalidationTracker` 抢跑：后台线程正查表时数据库被 close，
 * 会直接崩在 `robolectric-nativeruntime.dll` 里（EXCEPTION_ACCESS_VIOLATION）。
 * 给查询和事务都换成自己的单线程执行器，[release] 先等执行器收尾再 close，
 * 就把这个竞态消掉了。
 */
object TestDatabase {

    fun create(context: Context): TestDatabaseHandle {
        val executor = Executors.newSingleThreadExecutor()
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .setQueryExecutor(executor)
                .setTransactionExecutor(executor)
                .build()
        return TestDatabaseHandle(db, executor)
    }
}

/** [TestDatabase.create] 的返回值；测试结束时调用 [release]。 */
class TestDatabaseHandle(
    val db: AppDatabase,
    private val executor: ExecutorService,
) {
    fun release() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        db.close()
    }
}
