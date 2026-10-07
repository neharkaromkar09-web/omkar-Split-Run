package com.example.data.repository

import com.example.data.local.DnaPresetDao
import com.example.data.local.DnaPresetEntity
import com.example.data.local.ProjectDao
import com.example.data.local.ProjectEntity
import com.example.data.model.EasingType
import com.example.data.model.EditingDNA
import com.example.data.model.NormalizedCurvePoint
import com.example.data.model.ReferenceSegmentPattern
import com.example.data.model.SplitPoint
import com.example.data.model.SplitReason
import com.example.data.model.TransformKeyframe
import com.example.data.model.ZoomDirection
import com.example.data.model.ZoomTiming
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

class ProjectRepository(
    private val projectDao: ProjectDao,
    private val dnaPresetDao: DnaPresetDao? = null
) {

    val allProjects: Flow<List<ProjectEntity>> = projectDao.getAllProjects()

    fun getProject(id: Long): Flow<ProjectEntity?> = projectDao.getProjectById(id)

    val allDnaPresets: Flow<List<EditingDNA>>? = dnaPresetDao?.getAllPresets()?.map { entities ->
        val userPresets = entities.mapNotNull { deserializeDna(it.jsonPayload) }
        EditingDNA.getBuiltInPresets() + userPresets
    }

    suspend fun saveDnaPreset(dna: EditingDNA) {
        if (dnaPresetDao == null) return
        val entity = DnaPresetEntity(
            id = dna.id,
            name = dna.name,
            description = dna.description,
            pacingStyle = dna.pacingStyle,
            referenceVideoName = dna.referenceVideoName,
            referenceDurationMs = dna.referenceDurationMs,
            scaleMin = dna.scaleMin,
            scaleMax = dna.scaleMax,
            zoomDurationMs = dna.zoomDurationMs,
            zoomDirection = dna.zoomDirection.name,
            jsonPayload = serializeDna(dna)
        )
        dnaPresetDao.insertPreset(entity)
    }

    suspend fun deleteDnaPreset(id: String) {
        dnaPresetDao?.deletePreset(id)
    }

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

                    val keyframes = mutableListOf<TransformKeyframe>()
                    val kfArray = obj.optJSONArray("keyframes")
                    if (kfArray != null) {
                        for (k in 0 until kfArray.length()) {
                            val kfObj = kfArray.getJSONObject(k)
                            keyframes.add(
                                TransformKeyframe(
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

        fun serializeDna(dna: EditingDNA): String {
            val obj = JSONObject()
            obj.put("id", dna.id)
            obj.put("name", dna.name)
            obj.put("description", dna.description)
            obj.put("referenceVideoName", dna.referenceVideoName)
            obj.put("referenceDurationMs", dna.referenceDurationMs)
            obj.put("pacingStyle", dna.pacingStyle)
            obj.put("totalCutsDetected", dna.totalCutsDetected)
            obj.put("averageSentenceDurationMs", dna.averageSentenceDurationMs)
            obj.put("speechToSplitOffsetMs", dna.speechToSplitOffsetMs)
            obj.put("splitToZoomOffsetMs", dna.splitToZoomOffsetMs)
            obj.put("zoomDurationMs", dna.zoomDurationMs)
            obj.put("holdDurationMs", dna.holdDurationMs)
            obj.put("zoomDirection", dna.zoomDirection.name)
            obj.put("zoomTiming", dna.zoomTiming.name)
            obj.put("scaleMin", dna.scaleMin.toDouble())
            obj.put("scaleMax", dna.scaleMax.toDouble())
            obj.put("positionXBias", dna.positionXBias.toDouble())
            obj.put("positionYBias", dna.positionYBias.toDouble())
            obj.put("easingType", dna.easingType.name)
            obj.put("isBuiltIn", dna.isBuiltIn)

            val curveArr = JSONArray()
            for (pt in dna.normalizedCurve) {
                val ptObj = JSONObject()
                ptObj.put("progress", pt.progress.toDouble())
                ptObj.put("scale", pt.scale.toDouble())
                curveArr.put(ptObj)
            }
            obj.put("normalizedCurve", curveArr)

            val segArr = JSONArray()
            for (seg in dna.segmentPatterns) {
                val segObj = JSONObject()
                segObj.put("segmentIndex", seg.segmentIndex)
                segObj.put("cutTimestampMs", seg.cutTimestampMs)
                segObj.put("splitToZoomOffsetMs", seg.splitToZoomOffsetMs)
                segObj.put("zoomDurationMs", seg.zoomDurationMs)
                segObj.put("zoomDirection", seg.zoomDirection.name)
                segObj.put("scaleMin", seg.scaleMin.toDouble())
                segObj.put("scaleMax", seg.scaleMax.toDouble())
                segObj.put("easingType", seg.easingType.name)
                segArr.put(segObj)
            }
            obj.put("segmentPatterns", segArr)

            return obj.toString()
        }

        fun deserializeDna(jsonString: String?): EditingDNA? {
            if (jsonString.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(jsonString)
                val id = obj.optString("id", java.util.UUID.randomUUID().toString())
                val name = obj.optString("name", "Custom Style")
                val description = obj.optString("description", "")
                val refName = obj.optString("referenceVideoName", "Reference Style")
                val refDur = obj.optLong("referenceDurationMs", 15000L)
                val pacing = obj.optString("pacingStyle", "Dynamic")
                val totalCuts = obj.optInt("totalCutsDetected", 4)
                val avgSentence = obj.optLong("averageSentenceDurationMs", 3000L)
                val speechOffset = obj.optLong("speechToSplitOffsetMs", 0L)
                val splitOffset = obj.optLong("splitToZoomOffsetMs", -120L)
                val zoomDur = obj.optLong("zoomDurationMs", 650L)
                val holdDur = obj.optLong("holdDurationMs", 300L)
                val dirName = obj.optString("zoomDirection", ZoomDirection.ZOOM_OUT_THEN_IN.name)
                val dir = runCatching { ZoomDirection.valueOf(dirName) }.getOrDefault(ZoomDirection.ZOOM_OUT_THEN_IN)
                val timingName = obj.optString("zoomTiming", ZoomTiming.SPANNING_SPLIT.name)
                val timing = runCatching { ZoomTiming.valueOf(timingName) }.getOrDefault(ZoomTiming.SPANNING_SPLIT)
                val minS = obj.optDouble("scaleMin", 0.90).toFloat()
                val maxS = obj.optDouble("scaleMax", 1.18).toFloat()
                val posX = obj.optDouble("positionXBias", 0.0).toFloat()
                val posY = obj.optDouble("positionYBias", -0.08).toFloat()
                val easingName = obj.optString("easingType", EasingType.EASE_IN_OUT.name)
                val easing = runCatching { EasingType.valueOf(easingName) }.getOrDefault(EasingType.EASE_IN_OUT)
                val isBuiltIn = obj.optBoolean("isBuiltIn", false)

                val curveList = mutableListOf<NormalizedCurvePoint>()
                val curveArr = obj.optJSONArray("normalizedCurve")
                if (curveArr != null) {
                    for (i in 0 until curveArr.length()) {
                        val cObj = curveArr.getJSONObject(i)
                        curveList.add(
                            NormalizedCurvePoint(
                                progress = cObj.optDouble("progress", 0.0).toFloat(),
                                scale = cObj.optDouble("scale", 1.0).toFloat()
                            )
                        )
                    }
                }

                val segList = mutableListOf<ReferenceSegmentPattern>()
                val segArr = obj.optJSONArray("segmentPatterns")
                if (segArr != null) {
                    for (i in 0 until segArr.length()) {
                        val sObj = segArr.getJSONObject(i)
                        val sDirName = sObj.optString("zoomDirection", ZoomDirection.ZOOM_OUT_THEN_IN.name)
                        val sDir = runCatching { ZoomDirection.valueOf(sDirName) }.getOrDefault(ZoomDirection.ZOOM_OUT_THEN_IN)
                        val sEasingName = sObj.optString("easingType", EasingType.EASE_IN_OUT.name)
                        val sEasing = runCatching { EasingType.valueOf(sEasingName) }.getOrDefault(EasingType.EASE_IN_OUT)

                        segList.add(
                            ReferenceSegmentPattern(
                                segmentIndex = sObj.optInt("segmentIndex", i + 1),
                                cutTimestampMs = sObj.optLong("cutTimestampMs", 0L),
                                splitToZoomOffsetMs = sObj.optLong("splitToZoomOffsetMs", -120L),
                                zoomDurationMs = sObj.optLong("zoomDurationMs", 650L),
                                zoomDirection = sDir,
                                scaleMin = sObj.optDouble("scaleMin", 0.90).toFloat(),
                                scaleMax = sObj.optDouble("scaleMax", 1.18).toFloat(),
                                easingType = sEasing
                            )
                        )
                    }
                }

                EditingDNA(
                    id = id,
                    name = name,
                    description = description,
                    referenceVideoName = refName,
                    referenceDurationMs = refDur,
                    pacingStyle = pacing,
                    totalCutsDetected = totalCuts,
                    averageSentenceDurationMs = avgSentence,
                    speechToSplitOffsetMs = speechOffset,
                    splitToZoomOffsetMs = splitOffset,
                    zoomDurationMs = zoomDur,
                    holdDurationMs = holdDur,
                    zoomDirection = dir,
                    zoomTiming = timing,
                    scaleMin = minS,
                    scaleMax = maxS,
                    positionXBias = posX,
                    positionYBias = posY,
                    easingType = easing,
                    normalizedCurve = if (curveList.isNotEmpty()) curveList else EditingDNA.defaultCurve(dir, minS, maxS),
                    segmentPatterns = segList,
                    isBuiltIn = isBuiltIn
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
