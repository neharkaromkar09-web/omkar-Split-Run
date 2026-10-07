package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val videoUri: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fileSizeBytes: Long,
    val fps: Float,
    val hasAudio: Boolean,
    val splitPointsJson: String, // serialized split points
    val clipCount: Int,
    val status: String, // "Draft", "Analyzed", "Exported"
    val thumbnailUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
