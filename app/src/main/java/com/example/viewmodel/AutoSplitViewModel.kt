package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AutoSplitDatabase
import com.example.data.local.ProjectEntity
import com.example.data.model.AnalysisSettings
import com.example.data.model.Sensitivity
import com.example.data.model.SplitPoint
import com.example.data.model.SplitReason
import com.example.data.model.VideoClip
import com.example.data.model.VideoMetadata
import com.example.data.repository.ProjectRepository
import com.example.engine.SpeechAnalysisEngine
import com.example.engine.VideoProcessor
import com.example.engine.VideoSplitter
import com.example.engine.WaveformExtractor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class AppScreen {
    HOME,
    ANALYSIS,
    EDITOR,
    PROJECTS,
    SETTINGS,
    EXPORT
}

data class AnalysisUiState(
    val isAnalyzing: Boolean = false,
    val stage: String = "Preparing video...",
    val progress: Float = 0f,
    val errorMessage: String? = null
)

data class ExportUiState(
    val isExporting: Boolean = false,
    val currentClipIndex: Int = 0,
    val totalClips: Int = 0,
    val progress: Float = 0f,
    val exportedFiles: List<File> = emptyList(),
    val errorMessage: String? = null,
    val isCompleted: Boolean = false
)

data class EditorUiState(
    val currentScreen: AppScreen = AppScreen.HOME,
    val selectedVideoUri: Uri? = null,
    val metadata: VideoMetadata? = null,
    val waveform: FloatArray = FloatArray(120) { 0.1f },
    val splits: List<SplitPoint> = emptyList(),
    val selectedSplitId: String? = null,
    val clips: List<VideoClip> = emptyList(),
    val currentPlaybackMs: Long = 0L,
    val isPlaying: Boolean = false,
    val timelineZoom: Float = 1.0f,
    val currentProjectId: Long = 0L,
    val settings: AnalysisSettings = AnalysisSettings(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val previewClip: VideoClip? = null
)

class AutoSplitViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ProjectRepository by lazy {
        val db = AutoSplitDatabase.getInstance(application)
        ProjectRepository(db.projectDao())
    }

    val recentProjects: StateFlow<List<ProjectEntity>> = repository.allProjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _editorState = MutableStateFlow(EditorUiState())
    val editorState: StateFlow<EditorUiState> = _editorState.asStateFlow()

    private val _analysisState = MutableStateFlow(AnalysisUiState())
    val analysisState: StateFlow<AnalysisUiState> = _analysisState.asStateFlow()

    private val _exportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()

    private val undoStack = mutableListOf<List<SplitPoint>>()
    private val redoStack = mutableListOf<List<SplitPoint>>()

    private var analysisJob: Job? = null
    private var exportJob: Job? = null

    fun navigateTo(screen: AppScreen) {
        _editorState.update { it.copy(currentScreen = screen) }
    }

    fun onVideoSelected(uri: Uri, startAnalysisImmediately: Boolean = true) {
        viewModelScope.launch {
            _analysisState.value = AnalysisUiState(
                isAnalyzing = true,
                stage = "Preparing video...",
                progress = 0.05f,
                errorMessage = null
            )
            _editorState.update {
                it.copy(
                    selectedVideoUri = uri,
                    currentScreen = if (startAnalysisImmediately) AppScreen.ANALYSIS else AppScreen.EDITOR,
                    currentPlaybackMs = 0L,
                    isPlaying = false,
                    splits = emptyList(),
                    selectedSplitId = null,
                    clips = emptyList()
                )
            }
            undoStack.clear()
            redoStack.clear()
            updateUndoRedoStatus()

            val metadataResult = VideoProcessor.extractMetadata(getApplication(), uri)
            metadataResult.onSuccess { meta ->
                _editorState.update { it.copy(metadata = meta) }

                // Extract real audio waveform in parallel/sequence
                val waveform = WaveformExtractor.extractWaveform(
                    context = getApplication(),
                    uri = uri,
                    durationMs = meta.durationMs,
                    bucketCount = 120
                )
                _editorState.update { it.copy(waveform = waveform) }

                if (startAnalysisImmediately) {
                    runSpeechAnalysis(uri, meta)
                } else {
                    // Manual mode: compute clips from default empty splits
                    recomputeClips()
                }
            }.onFailure { err ->
                _analysisState.update {
                    it.copy(
                        isAnalyzing = false,
                        errorMessage = err.localizedMessage ?: "Unable to read this video."
                    )
                }
            }
        }
    }

    fun triggerAnalysis() {
        val uri = _editorState.value.selectedVideoUri ?: return
        val meta = _editorState.value.metadata ?: return
        _editorState.update { it.copy(currentScreen = AppScreen.ANALYSIS) }
        runSpeechAnalysis(uri, meta)
    }

    private fun runSpeechAnalysis(uri: Uri, metadata: VideoMetadata) {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            _analysisState.update {
                it.copy(isAnalyzing = true, stage = "Analyzing audio & speech...", progress = 0.15f, errorMessage = null)
            }

            try {
                val detectedSplits = SpeechAnalysisEngine.analyzeVideo(
                    context = getApplication(),
                    uri = uri,
                    durationMs = metadata.durationMs,
                    settings = _editorState.value.settings
                ) { stage, progress ->
                    _analysisState.update { it.copy(stage = stage, progress = progress) }
                }

                _editorState.update {
                    it.copy(
                        splits = detectedSplits,
                        currentScreen = AppScreen.EDITOR
                    )
                }
                recomputeClips()

                // Save project automatically
                saveCurrentProject()

                _analysisState.update { it.copy(isAnalyzing = false, stage = "Analysis complete.", progress = 1.0f) }
            } catch (e: Exception) {
                _analysisState.update {
                    it.copy(
                        isAnalyzing = false,
                        errorMessage = e.localizedMessage ?: "Analysis could not be completed."
                    )
                }
            }
        }
    }

    fun addManualSplit(timestampMs: Long? = null) {
        val meta = _editorState.value.metadata ?: return
        val targetTime = timestampMs ?: _editorState.value.currentPlaybackMs
        if (targetTime <= 200L || targetTime >= meta.durationMs - 200L) return

        recordUndoState()

        val newSplit = SplitPoint(
            timestampMs = targetTime,
            reason = SplitReason.MANUAL,
            confidence = 1.0f,
            isAi = false,
            note = "Custom split at ${SplitPoint.formatTimestamp(targetTime)}"
        )

        val updated = (_editorState.value.splits + newSplit).sortedBy { it.timestampMs }
        _editorState.update {
            it.copy(
                splits = updated,
                selectedSplitId = newSplit.id
            )
        }
        recomputeClips()
        saveCurrentProject()
    }

    fun updateSplitTimestamp(id: String, newTimestampMs: Long) {
        val meta = _editorState.value.metadata ?: return
        val clampedTime = newTimestampMs.coerceIn(200L, meta.durationMs - 200L)

        recordUndoState()

        val updated = _editorState.value.splits.map {
            if (it.id == id) it.copy(timestampMs = clampedTime) else it
        }.sortedBy { it.timestampMs }

        _editorState.update { it.copy(splits = updated) }
        recomputeClips()
        saveCurrentProject()
    }

    fun updateSplitTime(id: String, newTimestampMs: Long) {
        updateSplitTimestamp(id, newTimestampMs)
    }

    fun deleteSplit(id: String) {
        recordUndoState()

        val updated = _editorState.value.splits.filterNot { it.id == id }
        _editorState.update {
            it.copy(
                splits = updated,
                selectedSplitId = if (it.selectedSplitId == id) null else it.selectedSplitId
            )
        }
        recomputeClips()
        saveCurrentProject()
    }

    fun selectSplit(id: String?) {
        _editorState.update { it.copy(selectedSplitId = id) }
        id?.let { splitId ->
            val split = _editorState.value.splits.find { it.id == splitId }
            split?.let {
                seekTo(it.timestampMs)
            }
        }
    }

    fun seekTo(timeMs: Long) {
        val meta = _editorState.value.metadata ?: return
        val clamped = timeMs.coerceIn(0L, meta.durationMs)
        _editorState.update { it.copy(currentPlaybackMs = clamped) }
    }

    fun setPlaying(playing: Boolean) {
        _editorState.update { it.copy(isPlaying = playing) }
    }

    fun setZoom(zoom: Float) {
        val clamped = zoom.coerceIn(0.5f, 8.0f)
        _editorState.update { it.copy(timelineZoom = clamped) }
    }

    fun zoomIn() {
        setZoom(_editorState.value.timelineZoom * 1.5f)
    }

    fun zoomOut() {
        setZoom(_editorState.value.timelineZoom / 1.5f)
    }

    fun fitTimeline() {
        setZoom(1.0f)
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val previous = undoStack.removeAt(undoStack.lastIndex)
            redoStack.add(_editorState.value.splits)
            _editorState.update { it.copy(splits = previous, selectedSplitId = null) }
            recomputeClips()
            updateUndoRedoStatus()
            saveCurrentProject()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val next = redoStack.removeAt(redoStack.lastIndex)
            undoStack.add(_editorState.value.splits)
            _editorState.update { it.copy(splits = next, selectedSplitId = null) }
            recomputeClips()
            updateUndoRedoStatus()
            saveCurrentProject()
        }
    }

    private fun recordUndoState() {
        undoStack.add(_editorState.value.splits)
        redoStack.clear()
        if (undoStack.size > 30) undoStack.removeAt(0)
        updateUndoRedoStatus()
    }

    private fun updateUndoRedoStatus() {
        _editorState.update {
            it.copy(
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
    }

    private fun recomputeClips() {
        val meta = _editorState.value.metadata ?: return
        val splits = _editorState.value.splits.sortedBy { it.timestampMs }

        val clips = mutableListOf<VideoClip>()
        var start = 0L

        for (i in splits.indices) {
            val split = splits[i]
            clips.add(
                VideoClip(
                    clipIndex = i + 1,
                    startMs = start,
                    endMs = split.timestampMs,
                    splitReason = split.reason
                )
            )
            start = split.timestampMs
        }

        // Final clip to end of video
        clips.add(
            VideoClip(
                clipIndex = clips.size + 1,
                startMs = start,
                endMs = meta.durationMs,
                splitReason = SplitReason.SENTENCE_COMPLETED
            )
        )

        _editorState.update { it.copy(clips = clips) }
    }

    fun previewClip(clip: VideoClip?) {
        _editorState.update { it.copy(previewClip = clip) }
        clip?.let {
            seekTo(it.startMs)
            setPlaying(true)
        }
    }

    fun updateSettings(newSettings: AnalysisSettings) {
        _editorState.update { it.copy(settings = newSettings) }
    }

    fun updateSensitivity(sensitivity: Sensitivity) {
        _editorState.update {
            it.copy(settings = it.settings.copy(sensitivity = sensitivity))
        }
    }

    fun updateMinDistance(seconds: Int) {
        _editorState.update {
            it.copy(settings = it.settings.copy(minSplitDistanceSeconds = seconds))
        }
    }

    fun exportClips(selectedClipsOnly: List<VideoClip>? = null) {
        val uri = _editorState.value.selectedVideoUri ?: return
        val clipsToExport = selectedClipsOnly ?: _editorState.value.clips
        if (clipsToExport.isEmpty()) return

        // Prevent duplicate export runs
        if (_exportState.value.isExporting) return

        _exportState.value = ExportUiState(
            isExporting = true,
            currentClipIndex = 1,
            totalClips = clipsToExport.size,
            progress = 0f,
            exportedFiles = emptyList(),
            errorMessage = null,
            isCompleted = false
        )

        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            val result = VideoSplitter.exportClips(
                context = getApplication(),
                sourceUri = uri,
                clips = clipsToExport
            ) { progressEvent ->
                when (progressEvent) {
                    is VideoSplitter.SplitProgress.Progress -> {
                        _exportState.update {
                            it.copy(
                                currentClipIndex = progressEvent.clipIndex,
                                totalClips = progressEvent.totalClips,
                                progress = progressEvent.percent
                            )
                        }
                    }
                    is VideoSplitter.SplitProgress.Completed -> {
                        _exportState.update {
                            it.copy(
                                isExporting = false,
                                isCompleted = true,
                                exportedFiles = progressEvent.exportedFiles,
                                progress = 1f
                            )
                        }
                        saveCurrentProject(status = "Exported")
                    }
                    is VideoSplitter.SplitProgress.Failed -> {
                        _exportState.update {
                            it.copy(
                                isExporting = false,
                                errorMessage = progressEvent.error
                            )
                        }
                    }
                }
            }
        }
    }

    fun loadProject(project: ProjectEntity) {
        val uri = Uri.parse(project.videoUri)
        val splits = ProjectRepository.deserializeSplits(project.splitPointsJson)

        viewModelScope.launch {
            val metaResult = VideoProcessor.extractMetadata(getApplication(), uri)
            val meta = metaResult.getOrNull() ?: VideoMetadata(
                uriString = project.videoUri,
                fileName = project.fileName,
                durationMs = project.durationMs,
                width = project.width,
                height = project.height,
                fileSizeBytes = project.fileSizeBytes,
                fps = project.fps,
                hasAudio = project.hasAudio
            )

            val waveform = WaveformExtractor.extractWaveform(
                context = getApplication(),
                uri = uri,
                durationMs = meta.durationMs,
                bucketCount = 120
            )

            _editorState.value = EditorUiState(
                currentScreen = AppScreen.EDITOR,
                selectedVideoUri = uri,
                metadata = meta,
                waveform = waveform,
                splits = splits,
                currentProjectId = project.id
            )
            recomputeClips()
            undoStack.clear()
            redoStack.clear()
            updateUndoRedoStatus()
        }
    }

    fun deleteProject(id: Long) {
        viewModelScope.launch {
            repository.deleteProject(id)
            if (_editorState.value.currentProjectId == id) {
                _editorState.update { it.copy(currentProjectId = 0L) }
            }
        }
    }

    private fun saveCurrentProject(status: String = "Analyzed") {
        val meta = _editorState.value.metadata ?: return
        val uri = _editorState.value.selectedVideoUri ?: return
        val currentId = _editorState.value.currentProjectId
        val splits = _editorState.value.splits

        viewModelScope.launch {
            val savedId = repository.saveProject(
                id = currentId,
                fileName = meta.fileName,
                videoUri = uri.toString(),
                durationMs = meta.durationMs,
                width = meta.width,
                height = meta.height,
                fileSizeBytes = meta.fileSizeBytes,
                fps = meta.fps,
                hasAudio = meta.hasAudio,
                splitPoints = splits,
                status = status
            )
            _editorState.update { it.copy(currentProjectId = savedId) }
        }
    }
}
