package com.vynox.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vynox.app.VynoxServices
import com.vynox.app.ui.common.Chip
import com.vynox.app.ui.common.PanelCard
import com.vynox.app.ui.common.SectionTitle
import com.vynox.app.ui.common.ToggleRow
import com.vynox.app.ui.common.VynoxTopBar
import com.vynox.app.ui.theme.VynoxColors
import com.vynox.core.model.PreviewQuality
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    versionName: String
) {
    val settings by VynoxServices.settingsStore.settings.collectAsState(initial = com.vynox.app.data.VynoxSettings())
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().background(VynoxColors.Ink)) {
        VynoxTopBar(title = "Settings", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            SectionTitle("Project")
            PanelCard {
                ToggleRow(
                    label = "Autosave",
                    checked = settings.autosaveEnabled,
                    onCheckedChange = { scope.launch { VynoxServices.settingsStore.setAutosave(it) } }
                )
                Text(
                    "Snapshots are written locally every ${settings.autosaveIntervalSeconds}s so work can be recovered after an unexpected close.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VynoxColors.TextMuted
                )
                Spacer(Modifier.height(10.dp))
                Text("Interval", style = MaterialTheme.typography.bodySmall, color = VynoxColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                androidx.compose.foundation.layout.Row {
                    listOf(30, 60, 180).forEach { seconds ->
                        Chip(
                            text = if (seconds < 60) "${seconds}s" else "${seconds / 60}m",
                            selected = settings.autosaveIntervalSeconds == seconds,
                            onClick = { scope.launch { VynoxServices.settingsStore.setAutosaveInterval(seconds) } },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    label = "Package media when saving",
                    checked = settings.packAssetsOnSave,
                    onCheckedChange = { scope.launch { VynoxServices.settingsStore.setPackAssets(it) } }
                )
                Text(
                    "Bundled .vnx files carry their media, so a single file can be shared and opened elsewhere.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VynoxColors.TextMuted
                )
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("Editing")
            PanelCard {
                ToggleRow(
                    label = "Timeline snapping",
                    checked = settings.snapEnabled,
                    onCheckedChange = { scope.launch { VynoxServices.settingsStore.setSnap(it) } }
                )
                ToggleRow(
                    label = "Show safe area guides",
                    checked = settings.showSafeArea,
                    onCheckedChange = { scope.launch { VynoxServices.settingsStore.setSafeArea(it) } }
                )
                Spacer(Modifier.height(8.dp))
                Text("Preview quality", style = MaterialTheme.typography.bodySmall, color = VynoxColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                androidx.compose.foundation.layout.Row {
                    PreviewQuality.entries.forEach { quality ->
                        Chip(
                            text = quality.label,
                            selected = settings.previewQuality == quality,
                            onClick = { scope.launch { VynoxServices.settingsStore.setPreviewQuality(quality) } },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("New project defaults")
            PanelCard {
                val presets = com.vynox.app.ui.newproject.CANVAS_PRESETS
                Text("Canvas", style = MaterialTheme.typography.bodySmall, color = VynoxColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                presets.chunked(3).forEach { row ->
                    androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
                        row.forEach { preset ->
                            Chip(
                                text = preset.label,
                                selected = settings.defaultWidth == preset.width && settings.defaultHeight == preset.height,
                                onClick = {
                                    scope.launch {
                                        VynoxServices.settingsStore.setDefaults(
                                            preset.width, preset.height, settings.defaultFps, settings.defaultDuration
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                )
                }
                Text("Frame rate", style = MaterialTheme.typography.bodySmall, color = VynoxColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                androidx.compose.foundation.layout.Row {
                    listOf(24, 25, 30, 60).forEach { fps ->
                        Chip(
                            text = "$fps",
                            selected = settings.defaultFps == fps,
                            onClick = {
                                scope.launch {
                                    VynoxServices.settingsStore.setDefaults(
                                        settings.defaultWidth, settings.defaultHeight, fps, settings.defaultDuration
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("Updates")
            PanelCard {
                ToggleRow(
                    label = "Check GitHub for updates",
                    checked = settings.checkForUpdates,
                    onCheckedChange = { scope.launch { VynoxServices.settingsStore.setCheckUpdates(it) } }
                )
                Text(
                    "Optional. Vynox never needs the internet to edit, render or export.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VynoxColors.TextMuted
                )
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("About this build")
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
                color = VynoxColors.Surface
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Vynox $versionName", color = VynoxColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Project format: .vnx schema v${com.vynox.core.model.VynoxSchema.CURRENT}",
                        style = MaterialTheme.typography.labelSmall,
                        color = VynoxColors.TextMuted
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
