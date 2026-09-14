package com.vynox.app.ui.export

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vynox.app.render.export.ExportSettings
import com.vynox.app.ui.common.Chip
import com.vynox.app.ui.common.EmptyState
import com.vynox.app.ui.common.LabeledSlider
import com.vynox.app.ui.common.PanelCard
import com.vynox.app.ui.common.SectionTitle
import com.vynox.app.ui.common.ToggleRow
import com.vynox.app.ui.common.VynoxPrimaryButton
import com.vynox.app.ui.common.VynoxProgress
import com.vynox.app.ui.common.VynoxSecondaryButton
import com.vynox.app.ui.common.VynoxTopBar
import com.vynox.app.ui.editor.EditorViewModel
import com.vynox.app.ui.theme.VynoxColors
import java.io.File

private data class ResolutionPreset(val label: String, val width: Int, val height: Int)

@Composable
fun ExportScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val project by viewModel.project.collectAsStateWithLifecycle()
    val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
    val progress by viewModel.exportProgress.collectAsStateWithLifecycle()

    val presets = remember(project) {
        val aspect = project.canvas.width.toDouble() / project.canvas.height.toDouble().coerceAtLeast(1.0)
        fun forHeight(height: Int) = ResolutionPreset(
            "${height}p",
            ((height * aspect).toInt() / 2) * 2,
            height
        )
        listOf(
            ResolutionPreset("Canvas", project.canvas.width, project.canvas.height),
            forHeight(720),
            forHeight(1080),
            forHeight(1440),
            forHeight(2160)
        )
    }
    var presetIndex by remember { mutableStateOf(0) }
    var fps by remember { mutableStateOf(project.canvas.fps) }
    var bitrate by remember { mutableStateOf(12f) }
    var includeAudio by remember { mutableStateOf(true) }
    var resultFile by remember { mutableStateOf<File?>(null) }

    val preset = presets[presetIndex]
    val settings = ExportSettings(
        width = preset.width,
        height = preset.height,
        fps = fps,
        videoBitrateMbps = bitrate,
        includeAudio = includeAudio,
        outputFile = File(
            File(context.getExternalFilesDir(null), "exports"),
            "${project.name.trim().ifBlank { "vynox" }.replace(Regex("[^A-Za-z0-9._-]"), "_")}-${preset.height}p-${System.currentTimeMillis()}.mp4"
        )
    )
    val estimatedBytes = remember(preset, bitrate, includeAudio, project) {
        viewModel.estimateExportSize(settings)
    }

    Column(modifier = Modifier.fillMaxSize().background(VynoxColors.Ink)) {
        VynoxTopBar(title = "Export", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            SectionTitle("Resolution")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEachIndexed { index, item ->
                    Chip(
                        text = item.label,
                        selected = index == presetIndex,
                        onClick = { presetIndex = index },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Text(
                "${preset.width} × ${preset.height}",
                style = MaterialTheme.typography.labelSmall,
                color = VynoxColors.TextMuted
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle("Frame rate")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(24, 25, 30, 60).forEach { value ->
                    Chip(text = "$value", selected = fps == value, onClick = { fps = value }, modifier = Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("Bitrate")
            LabeledSlider(
                label = "Video bitrate",
                value = bitrate,
                range = 2f..60f,
                valueText = "${bitrate.toInt()} Mbps",
                onValueChange = { bitrate = it }
            )
            ToggleRow("Include audio", includeAudio) { includeAudio = it }

            Spacer(Modifier.height(16.dp))
            PanelCard {
                Text(
                    "Output: ${settings.outputFile.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = VynoxColors.TextSecondary
                )
                Text(
                    "Estimated size: %.1f MB".format(estimatedBytes / (1024.0 * 1024.0)),
                    style = MaterialTheme.typography.bodySmall,
                    color = VynoxColors.TextMuted
                )
                Text(
                    "Frames: ${(project.duration * fps).toInt()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = VynoxColors.TextMuted
                )
            }

            Spacer(Modifier.height(20.dp))
            if (isExporting) {
                val value = progress
                VynoxProgress(progress = (value?.percent ?: 0f) / 100f)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Rendering ${value?.frame ?: 0} / ${value?.totalFrames ?: 0} frames — ${(value?.percent ?: 0f).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = VynoxColors.TextSecondary
                )
                Spacer(Modifier.height(12.dp))
                com.vynox.app.ui.common.VynoxSecondaryButton(
                    text = "Cancel",
                    onClick = { viewModel.cancelExport() },
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (resultFile == null) {
                VynoxPrimaryButton(
                    text = "Export MP4",
                    onClick = {
                        resultFile = null
                        viewModel.export(settings) { file -> resultFile = file }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                val file = resultFile
                if (file != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = VynoxColors.Emerald)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Export complete",
                            color = VynoxColors.TextPrimary,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        file.absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = VynoxColors.TextMuted
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VynoxPrimaryButton(
                            text = "Share",
                            onClick = {
                                val uri = FileProvider.getUriForFile(
                                    context, "${context.packageName}.fileprovider", file
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share video"))
                            },
                            modifier = Modifier.weight(1f)
                        )
                        VynoxSecondaryButton(
                            text = "Export again",
                            onClick = { resultFile = null },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    EmptyState(
                        title = "Export cancelled or failed",
                        message = "The renderer stopped before the file was written. Adjust the settings and try again.",
                        action = {
                            VynoxPrimaryButton(text = "Try again", onClick = { resultFile = null })
                        }
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
