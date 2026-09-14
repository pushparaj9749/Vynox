package com.vynox.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vynox.app.ui.common.Chip
import com.vynox.app.ui.common.ColorRow
import com.vynox.app.ui.common.KeyframeDiamondIcon
import com.vynox.app.ui.common.EmptyState
import com.vynox.app.ui.common.LabeledSlider
import com.vynox.app.ui.common.SectionTitle
import com.vynox.app.ui.common.ToggleRow
import com.vynox.app.ui.theme.VynoxColors
import com.vynox.core.effects.EffectCategory
import com.vynox.core.effects.EffectRegistry
import com.vynox.core.effects.ParamKind
import com.vynox.core.effects.ParameterDescriptor
import com.vynox.core.model.EffectInstance
import com.vynox.core.model.Layer
import com.vynox.core.model.Mask
import com.vynox.core.model.MaskMode
import com.vynox.core.model.MaskShape
import com.vynox.core.math.Vec2

// -------------------------------------------------------------------- effects

@Composable
fun EffectsTab(viewModel: EditorViewModel, layer: Layer, playhead: Double) {
    var showCatalogue by remember(layer.id) { mutableStateOf(layer.effects.isEmpty()) }
    val localTime = (playhead - layer.startTime).coerceAtLeast(0.0)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SectionTitle("Effects")
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(VynoxColors.SurfaceHighest)
                .clickable { showCatalogue = !showCatalogue }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (showCatalogue) Icons.Rounded.Close else Icons.Rounded.Add,
                    contentDescription = null,
                    tint = VynoxColors.Cyan,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (showCatalogue) "Close" else "Add",
                    color = VynoxColors.TextPrimary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }

    if (showCatalogue) {
        val byCategory = EffectRegistry.byCategory()
        byCategory.forEach { (category, definitions) ->
            Text(
                category.label,
                style = MaterialTheme.typography.labelSmall,
                color = VynoxColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                definitions.forEach { definition ->
                    Chip(text = definition.name, selected = false, onClick = {
                        viewModel.addEffect(layer.id, definition.typeId)
                        showCatalogue = false
                    })
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (layer.effects.isEmpty()) {
        EmptyState(
            title = "No effects",
            message = "Add a blur, colour grade, sharpen, glow or vignette. Effects render on the GPU and are saved with the project."
        )
        return
    }

    layer.effects.forEachIndexed { index, effect ->
        EffectCard(
            viewModel = viewModel,
            layer = layer,
            effect = effect,
            localTime = localTime,
            canMoveUp = index > 0,
            canMoveDown = index < layer.effects.lastIndex
        )
    }
}

@Composable
private fun EffectCard(
    viewModel: EditorViewModel,
    layer: Layer,
    effect: EffectInstance,
    localTime: Double,
    canMoveUp: Boolean,
    canMoveDown: Boolean
) {
    val definition = EffectRegistry.get(effect.typeId)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(VynoxColors.SurfaceHighest)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    definition?.name ?: effect.typeId,
                    color = VynoxColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium
                )
                definition?.description?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = VynoxColors.TextMuted)
                }
            }
            Switch(
                checked = effect.enabled,
                onCheckedChange = { viewModel.toggleEffect(layer.id, effect.id) }
            )
        }
        Row {
            IconButton(onClick = { viewModel.moveEffect(layer.id, effect.id, -1) }, enabled = canMoveUp) {
                Icon(Icons.Rounded.KeyboardArrowUp, "Up", tint = if (canMoveUp) VynoxColors.TextSecondary else VynoxColors.Outline)
            }
            IconButton(onClick = { viewModel.moveEffect(layer.id, effect.id, 1) }, enabled = canMoveDown) {
                Icon(Icons.Rounded.KeyboardArrowDown, "Down", tint = if (canMoveDown) VynoxColors.TextSecondary else VynoxColors.Outline)
            }
            IconButton(onClick = { viewModel.removeEffect(layer.id, effect.id) }) {
                Icon(Icons.Rounded.Delete, "Remove", tint = VynoxColors.Rose)
            }
        }

        definition?.parameters?.forEach { descriptor ->
            EffectParameterRow(viewModel, layer, effect, descriptor, localTime)
        }
    }
}

@Composable
private fun EffectParameterRow(
    viewModel: EditorViewModel,
    layer: Layer,
    effect: EffectInstance,
    descriptor: ParameterDescriptor,
    localTime: Double
) {
    val property = effect.parameters[descriptor.key]
    val animated = property?.isAnimated == true
    val hasKey = property?.keyframes?.any { kotlin.math.abs(it.time - localTime) < 1e-3 } == true

    Spacer(Modifier.height(4.dp))
    when (descriptor.kind) {
        ParamKind.COLOR -> {
            Text(descriptor.label, style = MaterialTheme.typography.bodySmall, color = VynoxColors.TextSecondary)
            val current = property?.valueAt(localTime) as? com.vynox.core.math.Color
            ColorRow(selected = current?.argb ?: 0) { argb ->
                viewModel.setEffectParamColor(layer.id, effect.id, descriptor.key, com.vynox.core.math.Color.fromArgb(argb))
            }
        }
        ParamKind.BOOL -> {
            val value = (property?.valueAt(localTime) as? Double ?: 0.0) >= 0.5
            ToggleRow(descriptor.label, value) {
                viewModel.setEffectParamValue(layer.id, effect.id, descriptor.key, if (it) 1.0 else 0.0, animated)
            }
        }
        ParamKind.ENUM -> {
            Text(descriptor.label, style = MaterialTheme.typography.bodySmall, color = VynoxColors.TextSecondary)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                descriptor.options.forEachIndexed { index, option ->
                    val current = (property?.valueAt(localTime) as? Double ?: 0.0).toInt()
                    Chip(text = option, selected = current == index) {
                        viewModel.setEffectParamValue(layer.id, effect.id, descriptor.key, index.toDouble(), animated)
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
        else -> {
            val value = (property?.valueAt(localTime) as? Double) ?: descriptor.defaultValue as? Double ?: 0.0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    LabeledSlider(
                        label = descriptor.label,
                        value = value.toFloat(),
                        range = descriptor.min.toFloat()..descriptor.max.toFloat(),
                        valueText = formatParam(value, descriptor),
                        onValueChange = {
                            viewModel.setEffectParamValue(layer.id, effect.id, descriptor.key, it.toDouble(), animated)
                        }
                    )
                }
                if (descriptor.animatable) {
                    androidx.compose.material3.IconButton(
                        onClick = { viewModel.toggleEffectKeyframe(layer.id, effect.id, descriptor.key) }
                    ) {
                        KeyframeDiamondIcon(
                            tint = if (hasKey) VynoxColors.Amber else VynoxColors.TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatParam(value: Double, descriptor: ParameterDescriptor): String =
    if (descriptor.kind == ParamKind.INT) value.toInt().toString() + descriptor.unit
    else "%.2f".format(value) + descriptor.unit

// ---------------------------------------------------------------------- masks

@Composable
fun MasksTab(viewModel: EditorViewModel, layer: Layer) {
    SectionTitle("Masks")
    Row {
        listOf(MaskShape.RECT, MaskShape.ELLIPSE, MaskShape.PATH).forEach { shape ->
            Chip(text = shape.name.lowercase().replaceFirstChar { it.uppercase() }, selected = false) {
                viewModel.addMask(layer.id, shape)
            }
            Spacer(Modifier.width(6.dp))
        }
    }

    if (layer.masks.isEmpty()) {
        EmptyState(
            title = "No masks",
            message = "Masks clip a layer before effects are applied. Add a rectangle, ellipse or custom path."
        )
        return
    }

    layer.masks.forEach { mask ->
        MaskCard(viewModel = viewModel, layer = layer, mask = mask)
    }
}

@Composable
private fun MaskCard(viewModel: EditorViewModel, layer: Layer, mask: Mask) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(VynoxColors.SurfaceHighest)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                mask.name,
                color = VynoxColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = mask.enabled, onCheckedChange = { viewModel.updateMask(layer.id, mask.copy(enabled = it)) })
            IconButton(onClick = { viewModel.removeMask(layer.id, mask.id) }) {
                Icon(Icons.Rounded.Delete, "Remove mask", tint = VynoxColors.Rose)
            }
        }
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            MaskMode.entries.forEach { mode ->
                Chip(text = mode.name.lowercase().replaceFirstChar { it.uppercase() }, selected = mask.mode == mode) {
                    viewModel.updateMask(layer.id, mask.copy(mode = mode))
                }
                Spacer(Modifier.width(6.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        LabeledSlider("X", mask.position.x.toFloat(), -2000f..4000f, {
            viewModel.updateMask(layer.id, mask.copy(position = Vec2(it.toDouble(), mask.position.y)))
        }, valueText = "${mask.position.x.toInt()}")
        LabeledSlider("Y", mask.position.y.toFloat(), -2000f..4000f, {
            viewModel.updateMask(layer.id, mask.copy(position = Vec2(mask.position.x, it.toDouble())))
        }, valueText = "${mask.position.y.toInt()}")
        LabeledSlider("Width", mask.size.x.toFloat(), 4f..4000f, {
            viewModel.updateMask(layer.id, mask.copy(size = Vec2(it.toDouble(), mask.size.y)))
        }, valueText = "${mask.size.x.toInt()}")
        LabeledSlider("Height", mask.size.y.toFloat(), 4f..4000f, {
            viewModel.updateMask(layer.id, mask.copy(size = Vec2(mask.size.x, it.toDouble())))
        }, valueText = "${mask.size.y.toInt()}")
        LabeledSlider("Rotation", mask.rotation.toFloat(), -360f..360f, {
            viewModel.updateMask(layer.id, mask.copy(rotation = it.toDouble()))
        }, valueText = "${mask.rotation.toInt()}°")
        LabeledSlider("Feather", mask.feather.toFloat(), 0f..400f, {
            viewModel.updateMask(layer.id, mask.copy(feather = it.toDouble()))
        }, valueText = "${mask.feather.toInt()}px")
        LabeledSlider("Opacity", mask.opacity.toFloat(), 0f..1f, {
            viewModel.updateMask(layer.id, mask.copy(opacity = it.toDouble()))
        }, valueText = "${(mask.opacity * 100).toInt()}%")
        ToggleRow("Invert", mask.invert) { viewModel.updateMask(layer.id, mask.copy(invert = it)) }
        if (mask.shape == MaskShape.PATH) {
            Text(
                "${mask.path.size} points · path masks use the first ${mask.path.size} vertices",
                style = MaterialTheme.typography.labelSmall,
                color = VynoxColors.TextMuted
            )
        }
    }
}
