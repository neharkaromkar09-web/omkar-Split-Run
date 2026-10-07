package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ProjectEntity::class, DnaPresetEntity::class], version = 2, exportSchema = false)
abstract class AutoSplitDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun dnaPresetDao(): DnaPresetDao

    companion object {
        @Volatile
        private var INSTANCE: AutoSplitDatabase? = null

        fun getInstance(context: Context): AutoSplitDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AutoSplitDatabase::class.java,
                    "autosplit_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
