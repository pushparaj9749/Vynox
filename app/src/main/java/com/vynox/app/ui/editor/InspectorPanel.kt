package com.vynox.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vynox.app.ui.common.Chip
import com.vynox.app.ui.common.KeyframeDiamondIcon
import com.vynox.app.ui.common.ColorChip
import com.vynox.app.ui.common.EmptyState
import com.vynox.app.ui.common.LabeledSlider
import com.vynox.app.ui.common.SectionTitle
import com.vynox.app.ui.common.ToggleRow
import com.vynox.app.ui.theme.VynoxColors
import com.vynox.core.animation.Animatable
import com.vynox.core.math.Color as EngineColor
import com.vynox.core.math.Vec2
import com.vynox.core.model.BlendMode
import com.vynox.core.model.ContentFit
import com.vynox.core.model.Layer
import com.vynox.core.model.LayerContent
import com.vynox.core.model.LayerType
import com.vynox.core.model.ShapeKind
import com.vynox.core.model.TextAlign
import kotlin.math.abs

val VYNOX_PALETTE = listOf(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF6C5CE7.toInt(), 0xFF22D3EE.toInt(),
    0xFFF472B6.toInt(), 0xFF34D399.toInt(), 0xFFFBBF24.toInt(), 0xFFF87171.toInt(),
    0xFF60A5FA.toInt(), 0xFFA78BFA.toInt(), 0xFF94A3B8.toInt(), 0xFF1F2937.toInt()
)

@Composable
fun InspectorPanel(
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier
) {
    val project by viewModel.project.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val playhead by viewModel.playhead.collectAsStateWithLifecycle()

    val layer = project.layers.firstOrNull { it.id in selection }

    Column(modifier = modifier.fillMaxSize().background(VynoxColors.Surface)) {
        // Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            InspectorTab.entries.forEach { item ->
                Chip(
                    text = item.label,
                    selected = tab == item,
                    onClick = { viewModel.setTab(item) }
                )
                Spacer(Modifier.width(8.dp))
            }
        }

        if (layer == null) {
            EmptyState(
                title = "Nothing selected",
                message = "Select a layer to edit its transform, content, effects and animation.",
                modifier = Modifier.fillMaxSize()
            )
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
        ) {
            Text(
                layer.name,
                style = MaterialTheme.typography.titleSmall,
                color = VynoxColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))

            when (tab) {
                InspectorTab.TRANSFORM -> TransformTab(viewModel, layer, playhead)
                InspectorTab.CONTENT -> ContentTab(viewModel, layer, playhead)
                InspectorTab.EFFECTS -> EffectsTab(viewModel, layer, playhead)
                InspectorTab.MASKS -> MasksTab(viewModel, layer)
                InspectorTab.ANIMATION -> AnimationTab(viewModel, layer, playhead)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

// ------------------------------------------------------------------ transform

@Composable
private fun TransformTab(viewModel: EditorViewModel, layer: Layer, playhead: Double) {
    val localTime = (playhead - layer.startTime).coerceAtLeast(0.0)
    val position = layer.transform.position.valueAt(localTime)
    val scale = layer.transform.scale.valueAt(localTime)
    val rotation = layer.transform.rotation.valueAt(localTime)
    val opacity = layer.transform.opacity.valueAt(localTime)
    val canvas = viewModel.project.collectAsStateWithLifecycle().value.canvas

    SectionTitle("Position")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            LabeledSlider(
                label = "X",
                value = position.x.toFloat(),
                range = (-canvas.width.toFloat())..(canvas.width * 2f),
                valueText = position.x.toInt().toString(),
                onValueChange = {
                    viewModel.setTransformVec2(layer.id, TransformProperty.POSITION, it.toDouble(), position.y, layer.transform.position.isAnimated)
                }
            )
            LabeledSlider(
                label = "Y",
                value = position.y.toFloat(),
                range = (-canvas.height.toFloat())..(canvas.height * 2f),
                valueText = position.y.toInt().toString(),
                onValueChange = {
                    viewModel.setTransformVec2(layer.id, TransformProperty.POSITION, position.x, it.toDouble(), layer.transform.position.isAnimated)
                }
            )
        }
        KeyframeToggle(
            active = hasKeyframe(layer.transform.position, localTime),
            animated = layer.transform.position.isAnimated,
            onClick = { viewModel.toggleKeyframe(layer.id, TransformProperty.POSITION) }
        )
    }

    SectionTitle("Scale")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            LabeledSlider("X", scale.x.toFloat(), 0f..5f, {
                viewModel.setTransformVec2(layer.id, TransformProperty.SCALE, it.toDouble(), scale.y, layer.transform.scale.isAnimated)
            }, "%.2f".format(scale.x))
            LabeledSlider("Y", scale.y.toFloat(), 0f..5f, {
                viewModel.setTransformVec2(layer.id, TransformProperty.SCALE, scale.x, it.toDouble(), layer.transform.scale.isAnimated)
            }, "%.2f".format(scale.y))
        }
        KeyframeToggle(
            active = hasKeyframe(layer.transform.scale, localTime),
            animated = layer.transform.scale.isAnimated,
            onClick = { viewModel.toggleKeyframe(layer.id, TransformProperty.SCALE) }
        )
    }

    SectionTitle("Rotation & opacity")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            LabeledSlider("Rotation", rotation.toFloat(), -360f..360f, {
                viewModel.setTransformScalar(layer.id, TransformProperty.ROTATION, it.toDouble(), layer.transform.rotation.isAnimated)
            }, "${rotation.toInt()}°")
            LabeledSlider("Opacity", opacity.toFloat(), 0f..1f, {
                viewModel.setTransformScalar(layer.id, TransformProperty.OPACITY, it.toDouble(), layer.transform.opacity.isAnimated)
            }, "${(opacity * 100).toInt()}%")
        }
        Column {
            KeyframeToggle(
                active = hasKeyframe(layer.transform.rotation, localTime),
                animated = layer.transform.rotation.isAnimated,
                onClick = { viewModel.toggleKeyframe(layer.id, TransformProperty.ROTATION) }
            )
            KeyframeToggle(
                active = hasKeyframe(layer.transform.opacity, localTime),
                animated = layer.transform.opacity.isAnimated,
                onClick = { viewModel.toggleKeyframe(layer.id, TransformProperty.OPACITY) }
            )
        }
    }

    SectionTitle("Anchor")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0f to 0f, 0.5f to 0f, 1f to 0f, 0f to 0.5f, 0.5f to 0.5f, 1f to 0.5f, 0f to 1f, 0.5f to 1f, 1f to 1f).forEach { (ax, ay) ->
            val selected = abs(layer.transform.anchor.x - ax) < 0.01 && abs(layer.transform.anchor.y - ay) < 0.01
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) VynoxColors.Violet else VynoxColors.SurfaceHighest)
                    .border(1.dp, VynoxColors.Outline, RoundedCornerShape(8.dp))
                    .clickable { viewModel.setAnchor(layer.id, Vec2(ax.toDouble(), ay.toDouble())) }
            )
        }
    }

    SectionTitle("Blend mode")
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        BlendMode.entries.forEach { mode ->
            Chip(
                text = mode.label,
                selected = layer.blendMode == mode,
                onClick = { viewModel.setBlendMode(layer.id, mode) }
            )
            Spacer(Modifier.width(6.dp))
        }
    }

    SectionTitle("Timing")
    LabeledSlider(
        label = "Duration",
        value = layer.duration.toFloat(),
        range = 0.2f..(layer.duration.toFloat() * 2).coerceAtLeast(10f),
        valueText = "%.1fs".format(layer.duration),
        onValueChange = { viewModel.setDuration(layer.id, it.toDouble()) }
    )
}

@Composable
private fun KeyframeToggle(active: Boolean, animated: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) VynoxColors.Amber else VynoxColors.SurfaceHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        KeyframeDiamondIcon(
            tint = when {
                active -> VynoxColors.Amber
                animated -> VynoxColors.Amber.copy(alpha = 0.45f)
                else -> VynoxColors.TextMuted
            },
            modifier = Modifier.size(18.dp)
        )
    }
}

private fun hasKeyframe(property: Animatable<*>, time: Double): Boolean =
    property.keyframes.any { abs(it.time - time) < 1e-3 }

// -------------------------------------------------------------------- content

@Composable
private fun ContentTab(viewModel: EditorViewModel, layer: Layer, playhead: Double) {
    when (layer.type) {
        LayerType.TEXT -> TextContentEditor(viewModel, layer)
        LayerType.SHAPE -> ShapeContentEditor(viewModel, layer)
        LayerType.VIDEO, LayerType.AUDIO -> MediaContentEditor(viewModel, layer)
        LayerType.IMAGE -> ImageContentEditor(viewModel, layer)
        LayerType.GROUP -> {
            SectionTitle("Group")
            Text(
                "Groups transform their children. Parent a layer to this group from the layer list.",
                style = MaterialTheme.typography.bodySmall,
                color = VynoxColors.TextMuted
            )
        }
    }
}

@Composable
private fun TextContentEditor(viewModel: EditorViewModel, layer: Layer) {
    val content = layer.content as? LayerContent.TextContent ?: return
    OutlinedTextField(
        value = content.text,
        onValueChange = { newText -> viewModel.updateText(layer.id, "text") { it.copy(text = newText) } },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Text") },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = VynoxColors.Violet,
            unfocusedBorderColor = VynoxColors.Outline,
            focusedTextColor = VynoxColors.TextPrimary,
            unfocusedTextColor = VynoxColors.TextPrimary
        )
    )
    Spacer(Modifier.height(12.dp))
    LabeledSlider("Font size", content.fontSize.toFloat(), 8f..400f, {
        viewModel.updateText(layer.id, "textsize") { it.copy(fontSize = it.toDouble()) }
    }, "${content.fontSize.toInt()}px")
    SectionTitle("Weight & style")
    Row {
        listOf(300, 400, 600, 700, 900).forEach { weight ->
            Chip(text = "$weight", selected = content.fontWeight == weight, onClick = {
                viewModel.updateText(layer.id) { it.copy(fontWeight = weight) }
            })
            Spacer(Modifier.width(6.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
    Row {
        TextAlign.entries.forEach { align ->
            Chip(text = align.label, selected = content.alignment == align, onClick = {
                viewModel.updateText(layer.id) { it.copy(alignment = align) }
            })
            Spacer(Modifier.width(6.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
    ToggleRow("Italic", content.italic) { viewModel.updateText(layer.id) { it.copy(italic = !content.italic) } }
    Spacer(Modifier.height(8.dp))
    LabeledSlider("Letter spacing", content.letterSpacing.toFloat(), -10f..40f, {
        viewModel.updateText(layer.id, "letter") { it.copy(letterSpacing = it.toDouble()) }
    }, "%.1f".format(content.letterSpacing))
    LabeledSlider("Line spacing", content.lineSpacing.toFloat(), 0.7f..3f, {
        viewModel.updateText(layer.id, "linespace") { it.copy(lineSpacing = it.toDouble()) }
    }, "%.2f".format(content.lineSpacing))
    SectionTitle("Colour")
    ColorRow(selected = content.color.valueAt(0.0).argb) { argb ->
        viewModel.updateText(layer.id) { it.copy(color = com.vynox.core.animation.StaticValue(EngineColor.fromArgb(argb))) }
    }
    Spacer(Modifier.height(10.dp))
    ToggleRow("Stroke", content.strokeEnabled) { viewModel.updateText(layer.id) { it.copy(strokeEnabled = !content.strokeEnabled) } }
    if (content.strokeEnabled) {
        LabeledSlider("Stroke width", content.strokeWidth.toFloat(), 0f..40f, {
            viewModel.updateText(layer.id, "stroke") { it.copy(strokeWidth = it.toDouble()) }
        }, "%.1f".format(content.strokeWidth))
        ColorRow(selected = content.strokeColor.valueAt(0.0).argb) { argb ->
            viewModel.updateText(layer.id) { it.copy(strokeColor = com.vynox.core.animation.StaticValue(EngineColor.fromArgb(argb))) }
        }
    }
    ToggleRow("Shadow", content.shadowEnabled) { viewModel.updateText(layer.id) { it.copy(shadowEnabled = !content.shadowEnabled) } }
    if (content.shadowEnabled) {
        LabeledSlider("Shadow radius", content.shadowRadius.toFloat(), 0f..80f, {
            viewModel.updateText(layer.id, "shadow") { it.copy(shadowRadius = it.toDouble()) }
        }, "%.0f".format(content.shadowRadius))
    }
    ToggleRow("Background", content.backgroundEnabled) { viewModel.updateText(layer.id) { it.copy(backgroundEnabled = !content.backgroundEnabled) } }
    if (content.backgroundEnabled) {
        LabeledSlider("Padding", content.backgroundPadding.toFloat(), 0f..80f, {
            viewModel.updateText(layer.id, "bgpad") { it.copy(backgroundPadding = it.toDouble()) }
        }, "%.0f".format(content.backgroundPadding))
        ColorRow(selected = content.backgroundColor.valueAt(0.0).argb) { argb ->
            viewModel.updateText(layer.id) { it.copy(backgroundColor = com.vynox.core.animation.StaticValue(EngineColor.fromArgb(argb))) }
        }
    }
}

@Composable
private fun ShapeContentEditor(viewModel: EditorViewModel, layer: Layer) {
    val content = layer.content as? LayerContent.ShapeContent ?: return
    SectionTitle("Shape")
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        ShapeKind.entries.forEach { kind ->
            Chip(text = kind.label, selected = content.shape == kind, onClick = {
                viewModel.updateShape(layer.id) { it.copy(shape = kind) }
            })
            Spacer(Modifier.width(6.dp))
        }
    }
    Spacer(Modifier.height(10.dp))
    LabeledSlider("Width", content.size.x.toFloat(), 8f..2048f, {
        viewModel.updateShape(layer.id, "shapew") { it.copy(size = Vec2(it.toDouble(), content.size.y)) }
    }, "${content.size.x.toInt()}")
    LabeledSlider("Height", content.size.y.toFloat(), 8f..2048f, {
        viewModel.updateShape(layer.id, "shapeh") { it.copy(size = Vec2(content.size.x, it.toDouble())) }
    }, "${content.size.y.toInt()}")
    if (content.shape == ShapeKind.ROUNDED_RECT) {
        LabeledSlider("Corner radius", content.cornerRadius.toFloat(), 0f..400f, {
            viewModel.updateShape(layer.id, "radius") { it.copy(cornerRadius = it.toDouble()) }
        }, "${content.cornerRadius.toInt()}")
    }
    if (content.shape == ShapeKind.POLYGON) {
        LabeledSlider("Sides", content.sides.toFloat(), 3f..12f, {
            viewModel.updateShape(layer.id, "sides") { it.copy(sides = it.toInt()) }
        }, "${content.sides}", steps = 8)
    }
    Spacer(Modifier.height(10.dp))
    ToggleRow("Fill", content.fillEnabled) { viewModel.updateShape(layer.id) { it.copy(fillEnabled = !content.fillEnabled) } }
    if (content.fillEnabled) {
        ColorRow(selected = content.fillColor.valueAt(0.0).argb) { argb ->
            viewModel.updateShape(layer.id) { it.copy(fillColor = com.vynox.core.animation.StaticValue(EngineColor.fromArgb(argb))) }
        }
    }
    ToggleRow("Stroke", content.strokeEnabled) { viewModel.updateShape(layer.id) { it.copy(strokeEnabled = !content.strokeEnabled) } }
    if (content.strokeEnabled) {
        LabeledSlider("Stroke width", content.strokeWidth.toFloat(), 0f..80f, {
            viewModel.updateShape(layer.id, "stwidth") { it.copy(strokeWidth = it.toDouble()) }
        }, "%.0f".format(content.strokeWidth))
        ColorRow(selected = content.strokeColor.valueAt(0.0).argb) { argb ->
            viewModel.updateShape(layer.id) { it.copy(strokeColor = com.vynox.core.animation.StaticValue(EngineColor.fromArgb(argb))) }
        }
    }
}

@Composable
private fun MediaContentEditor(viewModel: EditorViewModel, layer: Layer) {
    val volume = when (val content = layer.content) {
        is LayerContent.VideoContent -> content.volume.valueAt(0.0)
        is LayerContent.AudioContent -> content.volume.valueAt(0.0)
        else -> 0.0
    }
    SectionTitle("Audio")
    LabeledSlider("Volume", volume.toFloat(), 0f..2f, {
        viewModel.setMediaVolume(layer.id, it.toDouble())
    }, "${(volume * 100).toInt()}%")
    ToggleRow("Mute", layer.muted) { viewModel.toggleMute(layer.id) }
    if (layer.type == LayerType.VIDEO) {
        SectionTitle("Playback")
        Row {
            listOf(0.25, 0.5, 1.0, 2.0).forEach { speed ->
                Chip(text = "${speed}x", selected = kotlin.math.abs(layer.speed - speed) < 1e-6, onClick = {
                    viewModel.updateLayerSpeed(layer.id, speed)
                })
                Spacer(Modifier.width(6.dp))
            }
        }
    }
}

@Composable
private fun ImageContentEditor(viewModel: EditorViewModel, layer: Layer) {
    val content = layer.content as? LayerContent.ImageContent ?: return
    SectionTitle("Fit")
    Row {
        ContentFit.entries.forEach { fit ->
            Chip(text = fit.name.lowercase().replaceFirstChar { it.uppercase() }, selected = content.fit == fit, onClick = {
                viewModel.updateImageFit(layer.id, fit)
            })
            Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
fun ColorRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        VYNOX_PALETTE.forEach { argb ->
            ColorChip(
                color = Color(argb),
                selected = selected == argb,
                onClick = { onSelect(argb) }
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}
