package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dna_presets")
data class DnaPresetEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String,
    val pacingStyle: String,
    val referenceVideoName: String,
    val referenceDurationMs: Long,
    val scaleMin: Float,
    val scaleMax: Float,
    val zoomDurationMs: Long,
    val zoomDirection: String,
    val jsonPayload: String, // serialized full EditingDNA
    val createdAt: Long = System.currentTimeMillis()
)
