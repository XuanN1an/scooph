package dev.scooph.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SourceEntity::class, EntryEntity::class, TrackedEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao

    abstract fun entryDao(): EntryDao

    abstract fun trackedDao(): TrackedDao
}
