package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DnaPresetDao {
    @Query("SELECT * FROM dna_presets ORDER BY createdAt DESC")
    fun getAllPresets(): Flow<List<DnaPresetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: DnaPresetEntity)

    @Query("DELETE FROM dna_presets WHERE id = :id")
    suspend fun deletePreset(id: String)
}
