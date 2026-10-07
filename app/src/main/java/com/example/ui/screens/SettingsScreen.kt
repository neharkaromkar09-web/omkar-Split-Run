package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AnalysisSettings
import com.example.data.model.Sensitivity
import com.example.ui.components.AppHeader
import com.example.ui.theme.AutoSplitPrimary

@Composable
fun SettingsScreen(
    settings: AnalysisSettings,
    onUpdateSettings: (AnalysisSettings) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AppHeader(
            title = "Settings",
            subtitle = "Detection & Export Configuration",
            showBack = false
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // AI & DETECTION SECTION
            item {
                Text(
                    text = "AI & Detection",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SettingToggleRow(
                            title = "Auto Speech Detection",
                            subtitle = "Extract voice activity envelope and word cadences",
                            checked = settings.autoSpeechDetection,
                            testTag = "setting_auto_speech",
                            onCheckedChange = { onUpdateSettings(settings.copy(autoSpeechDetection = it)) }
                        )

                        SettingToggleRow(
                            title = "Sentence & Thought Detection",
                            subtitle = "Split at complete grammatical thought conclusions",
                            checked = settings.sentenceDetection,
                            testTag = "setting_sentence_detection",
                            onCheckedChange = { onUpdateSettings(settings.copy(sentenceDetection = it)) }
                        )

                        SettingToggleRow(
                            title = "Speaker Change Detection",
                            subtitle = "Identify voice pitch and timbre transitions",
                            checked = settings.speakerDetection,
                            testTag = "setting_speaker_detection",
                            onCheckedChange = { onUpdateSettings(settings.copy(speakerDetection = it)) }
                        )

                        SettingToggleRow(
                            title = "Natural Pause Detection",
                            subtitle = "Detect conversational pauses without breaking clauses",
                            checked = settings.naturalPauseDetection,
                            testTag = "setting_pause_detection",
                            onCheckedChange = { onUpdateSettings(settings.copy(naturalPauseDetection = it)) }
                        )

                        SettingToggleRow(
                            title = "Scene Assistance",
                            subtitle = "Use visual scene cuts as supporting boundary cues",
                            checked = settings.sceneAssistance,
                            testTag = "setting_scene_assistance",
                            onCheckedChange = { onUpdateSettings(settings.copy(sceneAssistance = it)) }
                        )

                        SettingToggleRow(
                            title = "Show Split Confidence",
                            subtitle = "Display percentage ratings on split markers",
                            checked = settings.showConfidence,
                            testTag = "setting_show_confidence",
                            onCheckedChange = { onUpdateSettings(settings.copy(showConfidence = it)) }
                        )
                    }
                }
            }

            // SPLIT SENSITIVITY
            item {
                Text(
                    text = "Split Sensitivity",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Determines minimum conversational pause duration before a thought is marked complete.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Sensitivity.values().forEach { s ->
                                FilterChip(
                                    selected = settings.sensitivity == s,
                                    onClick = { onUpdateSettings(settings.copy(sensitivity = s)) },
                                    label = { Text(s.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AutoSplitPrimary.copy(alpha = 0.15f),
                                        selectedLabelColor = AutoSplitPrimary
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // MINIMUM SPLIT DISTANCE
            item {
                Text(
                    text = "Minimum Split Distance",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Prevents overly fragmented clips by establishing minimum interval between split points.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1, 2, 3, 5).forEach { seconds ->
                                FilterChip(
                                    selected = settings.minSplitDistanceSeconds == seconds,
                                    onClick = { onUpdateSettings(settings.copy(minSplitDistanceSeconds = seconds)) },
                                    label = { Text("$seconds sec") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AutoSplitPrimary.copy(alpha = 0.15f),
                                        selectedLabelColor = AutoSplitPrimary
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AutoSplitPrimary
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}
