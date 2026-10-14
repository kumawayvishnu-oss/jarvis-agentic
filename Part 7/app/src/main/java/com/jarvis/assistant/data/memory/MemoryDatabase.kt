package com.jarvis.assistant.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MemoryEntity::class], version = 1, exportSchema = false)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao

    companion object {
        private const val DATABASE_NAME = "jarvis_memory.db"

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Preserves all existing memories and schema intact without deletion
            }
        }

        @Volatile
        private var instance: MemoryDatabase? = null

        fun getInstance(context: Context): MemoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
