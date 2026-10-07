package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.AutoSplitBottomNav
import com.example.ui.screens.AnalysisScreen
import com.example.ui.screens.EditorScreen
import com.example.ui.screens.ExportScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ProjectsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.AutoSplitTheme
import com.example.viewmodel.AppScreen
import com.example.viewmodel.AutoSplitViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AutoSplitViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AutoSplitTheme {
                val editorState by viewModel.editorState.collectAsStateWithLifecycle()
                val analysisState by viewModel.analysisState.collectAsStateWithLifecycle()
                val exportState by viewModel.exportState.collectAsStateWithLifecycle()
                val recentProjects by viewModel.recentProjects.collectAsStateWithLifecycle()

                val showBottomNav = editorState.currentScreen in listOf(
                    AppScreen.HOME,
                    AppScreen.EDITOR,
                    AppScreen.PROJECTS,
                    AppScreen.SETTINGS
                )

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (showBottomNav) {
                            AutoSplitBottomNav(
                                currentScreen = editorState.currentScreen,
                                onNavigate = { screen ->
                                    viewModel.navigateTo(screen)
                                }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (editorState.currentScreen) {
                            AppScreen.HOME -> {
                                HomeScreen(
                                    referenceMetadata = editorState.referenceMetadata,
                                    newVideoMetadata = editorState.metadata,
                                    recentProjects = recentProjects,
                                    onReferenceVideoSelected = { uri ->
                                        viewModel.onReferenceVideoSelected(uri)
                                    },
                                    onNewVideoSelected = { uri ->
                                        viewModel.onNewVideoSelected(uri, startAnalysisImmediately = false)
                                    },
                                    onStartPipeline = {
                                        viewModel.triggerAnalysis()
                                    },
                                    onOpenProject = { project ->
                                        viewModel.loadProject(project)
                                    },
                                    onDeleteProject = { id ->
                                        viewModel.deleteProject(id)
                                    },
                                    onNavigateToProjects = {
                                        viewModel.navigateTo(AppScreen.PROJECTS)
                                    },
                                    onNavigateToSettings = {
                                        viewModel.navigateTo(AppScreen.SETTINGS)
                                    }
                                )
                            }

                            AppScreen.ANALYSIS -> {
                                AnalysisScreen(
                                    metadata = editorState.metadata,
                                    analysisState = analysisState,
                                    onRetry = { viewModel.triggerAnalysis() },
                                    onCancel = { viewModel.navigateTo(AppScreen.HOME) }
                                )
                            }

                            AppScreen.EDITOR -> {
                                EditorScreen(
                                    state = editorState,
                                    onBack = { viewModel.navigateTo(AppScreen.HOME) },
                                    onNavigateToExport = { viewModel.navigateTo(AppScreen.EXPORT) },
                                    onSeek = { ms -> viewModel.seekTo(ms) },
                                    onTogglePlay = { playing -> viewModel.setPlaying(playing) },
                                    onZoomIn = { viewModel.zoomIn() },
                                    onZoomOut = { viewModel.zoomOut() },
                                    onFitTimeline = { viewModel.fitTimeline() },
                                    onAddSplit = { viewModel.addManualSplit() },
                                    onSelectSplit = { id -> viewModel.selectSplit(id) },
                                    onUpdateSplitTime = { id, ms -> viewModel.updateSplitTime(id, ms) },
                                    onUpdateZoomIntensity = { id, intensity -> viewModel.updateZoomIntensity(id, intensity) },
                                    onDeleteSplit = { id -> viewModel.deleteSplit(id) },
                                    onUndo = { viewModel.undo() },
                                    onRedo = { viewModel.redo() },
                                    onPreviewClip = { clip -> viewModel.previewClip(clip) }
                                )
                            }

                            AppScreen.EXPORT -> {
                                ExportScreen(
                                    metadata = editorState.metadata,
                                    clips = editorState.clips,
                                    exportState = exportState,
                                    onBack = { viewModel.navigateTo(AppScreen.EDITOR) },
                                    onExportAll = { viewModel.exportClips() }
                                )
                            }

                            AppScreen.PROJECTS -> {
                                ProjectsScreen(
                                    projects = recentProjects,
                                    onOpenProject = { project ->
                                        viewModel.loadProject(project)
                                    },
                                    onDeleteProject = { id ->
                                        viewModel.deleteProject(id)
                                    }
                                )
                            }

                            AppScreen.SETTINGS -> {
                                SettingsScreen(
                                    settings = editorState.settings,
                                    onUpdateSettings = { newSettings ->
                                        viewModel.updateSettings(newSettings)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
