package com.example.data.repository

import com.example.data.local.ProjectDao
import com.example.data.local.ProjectEntity
import com.example.data.model.SplitPoint
import com.example.data.model.SplitReason
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

class ProjectRepository(private val projectDao: ProjectDao) {

    val allProjects: Flow<List<ProjectEntity>> = projectDao.getAllProjects()

    fun getProject(id: Long): Flow<ProjectEntity?> = projectDao.getProjectById(id)

    suspend fun saveProject(
        id: Long = 0,
        fileName: String,
        videoUri: String,
        durationMs: Long,
        width: Int,
        height: Int,
        fileSizeBytes: Long,
        fps: Float,
        hasAudio: Boolean,
        splitPoints: List<SplitPoint>,
        status: String = "Analyzed",
        thumbnailUri: String? = null
    ): Long {
        val splitsJson = serializeSplits(splitPoints)
        val entity = ProjectEntity(
            id = id,
            fileName = fileName,
            videoUri = videoUri,
            durationMs = durationMs,
            width = width,
            height = height,
            fileSizeBytes = fileSizeBytes,
            fps = fps,
            hasAudio = hasAudio,
            splitPointsJson = splitsJson,
            clipCount = (splitPoints.size + 1),
            status = status,
            thumbnailUri = thumbnailUri,
            updatedAt = System.currentTimeMillis()
        )
        return if (id == 0L) {
            projectDao.insertProject(entity)
        } else {
            projectDao.updateProject(entity)
            id
        }
    }

    suspend fun deleteProject(id: Long) {
        projectDao.deleteProjectById(id)
    }

    companion object {
        fun serializeSplits(splits: List<SplitPoint>): String {
            val jsonArray = JSONArray()
            for (split in splits) {
                val obj = JSONObject()
                obj.put("id", split.id)
                obj.put("timestampMs", split.timestampMs)
                obj.put("reason", split.reason.name)
                obj.put("confidence", split.confidence.toDouble())
                obj.put("isAi", split.isAi)
                obj.put("note", split.note)
                obj.put("zoomIntensityMultiplier", split.zoomIntensityMultiplier.toDouble())

                val kfArray = JSONArray()
                for (kf in split.keyframes) {
                    val kfObj = JSONObject()
                    kfObj.put("timestampMs", kf.timestampMs)
                    kfObj.put("scale", kf.scale.toDouble())
                    kfObj.put("positionX", kf.positionX.toDouble())
                    kfObj.put("positionY", kf.positionY.toDouble())
                    kfObj.put("rotation", kf.rotation.toDouble())
                    kfArray.put(kfObj)
                }
                obj.put("keyframes", kfArray)

                jsonArray.put(obj)
            }
            return jsonArray.toString()
        }

        fun deserializeSplits(jsonString: String?): List<SplitPoint> {
            if (jsonString.isNullOrBlank()) return emptyList()
            val list = mutableListOf<SplitPoint>()
            try {
                val array = JSONArray(jsonString)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.optString("id", "")
                    val timestampMs = obj.optLong("timestampMs", 0L)
                    val reasonName = obj.optString("reason", SplitReason.SENTENCE_COMPLETED.name)
                    val reason = runCatching { SplitReason.valueOf(reasonName) }.getOrDefault(SplitReason.SENTENCE_COMPLETED)
                    val confidence = obj.optDouble("confidence", 0.9).toFloat()
                    val isAi = obj.optBoolean("isAi", true)
                    val note = obj.optString("note", "")
                    val zoomIntensity = obj.optDouble("zoomIntensityMultiplier", 1.0).toFloat()

                    val keyframes = mutableListOf<com.example.data.model.TransformKeyframe>()
                    val kfArray = obj.optJSONArray("keyframes")
                    if (kfArray != null) {
                        for (k in 0 until kfArray.length()) {
                            val kfObj = kfArray.getJSONObject(k)
                            keyframes.add(
                                com.example.data.model.TransformKeyframe(
                                    timestampMs = kfObj.optLong("timestampMs", 0L),
                                    scale = kfObj.optDouble("scale", 1.0).toFloat(),
                                    positionX = kfObj.optDouble("positionX", 0.0).toFloat(),
                                    positionY = kfObj.optDouble("positionY", 0.0).toFloat(),
                                    rotation = kfObj.optDouble("rotation", 0.0).toFloat()
                                )
                            )
                        }
                    }

                    list.add(
                        SplitPoint(
                            id = if (id.isNotEmpty()) id else java.util.UUID.randomUUID().toString(),
                            timestampMs = timestampMs,
                            reason = reason,
                            confidence = confidence,
                            isAi = isAi,
                            note = note,
                            keyframes = keyframes,
                            zoomIntensityMultiplier = zoomIntensity
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return list.sortedBy { it.timestampMs }
        }
    }
}
