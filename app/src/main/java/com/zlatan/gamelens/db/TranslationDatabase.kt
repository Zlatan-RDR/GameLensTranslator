package com.zlatan.gamelens.db

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TranslationEntity::class], version = 1, exportSchema = false)
abstract class TranslationDatabase : RoomDatabase() {
    abstract fun translationDao(): TranslationDao

    companion object {
        private const val TAG = "TranslationDatabase"

        @Volatile
        private var INSTANCE: TranslationDatabase? = null

        fun getInstance(context: Context): TranslationDatabase {
            return INSTANCE ?: synchronized(this) {
                try {
                    val instance = Room.databaseBuilder(
                        context.applicationContext,
                        TranslationDatabase::class.java,
                        "gamelens_translations.db"
                    ).fallbackToDestructiveMigration().build()
                    INSTANCE = instance
                    instance
                } catch (t: Throwable) {
                    Log.e(TAG, "Error building Room database: ${t.message}", t)
                    throw t
                }
            }
        }
    }
}
