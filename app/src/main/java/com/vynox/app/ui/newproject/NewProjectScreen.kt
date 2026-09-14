package com.vynox.app.ui.newproject

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vynox.app.ui.common.Chip
import com.vynox.app.ui.common.LabeledSlider
import com.vynox.app.ui.common.SectionTitle
import com.vynox.app.ui.common.VynoxPrimaryButton
import com.vynox.app.ui.common.VynoxTopBar
import com.vynox.app.ui.theme.VynoxColors
import com.vynox.core.model.Canvas
import com.vynox.core.model.ProjectFactory
import com.vynox.core.model.VynoxProject
import kotlinx.coroutines.flow.first

data class CanvasPreset(val label: String, val width: Int, val height: Int)

val CANVAS_PRESETS = listOf(
    CanvasPreset("1080p", 1920, 1080),
    CanvasPreset("720p", 1280, 720),
    CanvasPreset("Vertical", 1080, 1920),
    CanvasPreset("Square", 1080, 1080),
    CanvasPreset("4K", 3840, 2160)
)

@Composable
fun NewProjectScreen(
    onBack: () -> Unit,
    onCreate: (VynoxProject) -> Unit,
    versionName: String
) {
    var name by remember { mutableStateOf("Untitled Project") }
    var presetIndex by remember { mutableIntStateOf(0) }
    var fps by remember { mutableIntStateOf(30) }
    var duration by remember { mutableFloatStateOf(15f) }
    var background by remember { mutableStateOf(BackgroundChoice.BLACK) }

    LaunchedEffect(Unit) {
        val settings = com.vynox.app.VynoxServices.settingsStore.settings.first()
        val index = CANVAS_PRESETS.indexOfFirst { it.width == settings.defaultWidth && it.height == settings.defaultHeight }
        if (index >= 0) presetIndex = index
        fps = settings.defaultFps
        duration = settings.defaultDuration.toFloat()
    }

    val preset = CANVAS_PRESETS[presetIndex]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VynoxColors.Ink)
    ) {
        VynoxTopBar(title = "New project", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Project name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VynoxColors.Violet,
                    unfocusedBorderColor = VynoxColors.Outline,
                    focusedTextColor = VynoxColors.TextPrimary,
                    unfocusedTextColor = VynoxColors.TextPrimary
                )
            )

            Spacer(Modifier.height(20.dp))
            SectionTitle("Canvas")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CANVAS_PRESETS.forEachIndexed { index, item ->
                    Chip(
                        text = item.label,
                        selected = index == presetIndex,
                        onClick = { presetIndex = index },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${preset.width} × ${preset.height}",
                style = MaterialTheme.typography.bodySmall,
                color = VynoxColors.TextMuted
            )

            Spacer(Modifier.height(20.dp))
            SectionTitle("Frame rate")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(24, 25, 30, 60).forEach { value ->
                    Chip(
                        text = "$value",
                        selected = fps == value,
                        onClick = { fps = value },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("Duration")
            LabeledSlider(
                label = "Timeline length",
                value = duration,
                range = 5f..120f,
                onValueChange = { duration = it },
                valueText = "${duration.toInt()}s"
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle("Background")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BackgroundChoice.entries.forEach { choice ->
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when (choice) {
                                    BackgroundChoice.BLACK -> Color.Black
                                    BackgroundChoice.WHITE -> Color.White
                                    BackgroundChoice.TRANSPARENT -> VynoxColors.SurfaceHighest
                                }
                            )
                            .then(
                                if (background == choice) {
                                    Modifier.background(Color.Transparent).padding(0.dp)
                                } else Modifier
                            )
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (background == choice) VynoxColors.Cyan.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(10.dp))
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            VynoxPrimaryButton(
                text = "Create project",
                onClick = {
                    val canvas = Canvas(
                        width = preset.width,
                        height = preset.height,
                        fps = fps,
                        background = when (background) {
                            BackgroundChoice.BLACK -> com.vynox.core.math.Color.BLACK
                            BackgroundChoice.WHITE -> com.vynox.core.math.Color.WHITE
                            BackgroundChoice.TRANSPARENT -> com.vynox.core.math.Color.TRANSPARENT
                        },
                        duration = duration.toDouble()
                    )
                    onCreate(
                        ProjectFactory.create(name.ifBlank { "Untitled Project" }, canvas, versionName)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

enum class BackgroundChoice { BLACK, WHITE, TRANSPARENT }
