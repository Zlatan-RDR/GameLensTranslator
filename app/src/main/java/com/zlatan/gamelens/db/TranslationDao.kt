package com.zlatan.gamelens.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TranslationDao {
    @Query("SELECT * FROM translations WHERE originalText = :originalText LIMIT 1")
    suspend fun getTranslation(originalText: String): TranslationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranslation(entity: TranslationEntity)

    @Query("SELECT COUNT(*) FROM translations")
    suspend fun getCount(): Int

    @Query("DELETE FROM translations WHERE id NOT IN (SELECT id FROM translations ORDER BY id DESC LIMIT 5000)")
    suspend fun pruneOldEntries(): Int

    @Query("DELETE FROM translations")
    suspend fun clearAll()
}
