package com.jarvis.assistant.agentic.soul

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        UserSoulEntity::class,
        SynthesizedSkillEntity::class,
        AgentSoulEntity::class,
        AgentStepLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SoulDatabase : RoomDatabase() {

    abstract fun soulDao(): SoulDao

    companion object {
        private const val DATABASE_NAME = "jarvis_soul_matrix.db"

        @Volatile
        private var instance: SoulDatabase? = null

        fun getInstance(context: Context): SoulDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SoulDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
