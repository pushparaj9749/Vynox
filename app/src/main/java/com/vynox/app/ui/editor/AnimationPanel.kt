package com.vynox.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vynox.app.ui.common.Chip
import com.vynox.app.ui.common.EmptyState
import com.vynox.app.ui.common.SectionTitle
import com.vynox.app.ui.theme.VynoxColors
import com.vynox.core.animation.Animatable
import com.vynox.core.animation.Interpolation
import com.vynox.core.animation.ScalarInterpolator
import com.vynox.core.math.Vec2
import com.vynox.core.model.Layer
import kotlin.math.abs
import kotlin.math.max

/**
 * Animation workspace: a dope sheet of keyframes plus a graph editor that edits
 * the actual [Interpolation] of a segment.
 */
@Composable
fun AnimationTab(viewModel: EditorViewModel, layer: Layer, playhead: Double) {
    var property by remember(layer.id) { mutableStateOf(TransformProperty.POSITION) }
    val localTime = (playhead - layer.startTime).coerceAtLeast(0.0)
    val animatable: Animatable<*> = when (property) {
        TransformProperty.POSITION -> layer.transform.position
        TransformProperty.SCALE -> layer.transform.scale
        TransformProperty.ROTATION -> layer.transform.rotation
        TransformProperty.OPACITY -> layer.transform.opacity
    }

    SectionTitle("Property")
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        TransformProperty.entries.forEach { item ->
            Chip(text = item.label, selected = property == item, onClick = { property = item })
            Spacer(Modifier.width(6.dp))
        }
    }

    Spacer(Modifier.height(10.dp))
    SectionTitle("Keyframes")
    if (animatable.keyframes.isEmpty()) {
        EmptyState(
            title = "Not animated",
            message = "Tap the diamond next to ${property.label} to add the first keyframe at the playhead."
        )
        return
    }

    // Dope sheet
    animatable.keyframes.forEach { keyframe ->
        val value = keyframe.value
        val valueText = when (value) {
            is Vec2 -> "(${value.x.toInt()}, ${value.y.toInt()})"
            is Double -> "%.2f".format(value)
            else -> value.toString()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .background(VynoxColors.SurfaceHighest, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Diamond,
                contentDescription = null,
                tint = VynoxColors.Amber,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "%.2fs".format(keyframe.time),
                style = MaterialTheme.typography.bodySmall,
                color = VynoxColors.TextPrimary,
                modifier = Modifier.width(48.dp)
            )
            Text(
                valueText,
                style = MaterialTheme.typography.bodySmall,
                color = VynoxColors.TextSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                Interpolation.presetName(keyframe.interpolation),
                style = MaterialTheme.typography.labelSmall,
                color = VynoxColors.Cyan
            )
            IconButton(onClick = { viewModel.deleteKeyframe(layer.id, property, keyframe.time) }) {
                Icon(Icons.Rounded.Delete, "Delete keyframe", tint = VynoxColors.Rose, modifier = Modifier.size(18.dp))
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    SectionTitle("Easing presets")
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Interpolation.PRESETS.keys.forEach { name ->
            val selected = animatable.keyframes.firstOrNull { abs(it.time - localTime) < 1e-3 }
                ?.interpolation?.let { Interpolation.presetName(it) == name } == true
            Chip(text = name.replace('_', ' '), selected = selected) {
                viewModel.setKeyframeInterpolation(
                    layer.id, property, localTime, Interpolation.fromPreset(name)
                )
            }
            Spacer(Modifier.width(6.dp))
        }
    }

    Spacer(Modifier.height(14.dp))
    SectionTitle("Graph")
    GraphEditor(
        keyframes = animatable.keyframes.map { kf ->
            KeyframePoint(
                time = kf.time,
                value = (kf.value as? Double) ?: ((kf.value as? Vec2)?.x ?: 0.0),
                interpolation = kf.interpolation
            )
        },
        playheadTime = localTime,
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        onKeyframeMove = { from, to -> viewModel.moveKeyframe(layer.id, property, from, to) },
        onInterpolationChange = { interpolation ->
            val target = animatable.keyframes.minByOrNull { abs(it.time - localTime) }
            if (target != null) {
                viewModel.setKeyframeInterpolation(layer.id, property, target.time, interpolation)
            }
        }
    )

    Spacer(Modifier.height(8.dp))
    Text(
        "Drag the keyframe dots left/right to retime; drag the curve inside the graph to shape the " +
            "Bezier easing of the selected segment.",
        style = MaterialTheme.typography.labelSmall,
        color = VynoxColors.TextMuted
    )
}

data class KeyframePoint(
    val time: Double,
    val value: Double,
    val interpolation: Interpolation
)

/** Interactive bezier (value vs time) graph editor. */
@Composable
fun GraphEditor(
    keyframes: List<KeyframePoint>,
    playheadTime: Double,
    modifier: Modifier = Modifier,
    onKeyframeMove: (from: Double, to: Double) -> Unit,
    onInterpolationChange: (Interpolation) -> Unit
) {
    if (keyframes.size < 2) {
        Box(
            modifier = modifier
                .background(VynoxColors.SurfaceHighest, RoundedCornerShape(14.dp))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Add a second keyframe to edit the curve",
                style = MaterialTheme.typography.bodySmall,
                color = VynoxColors.TextMuted
            )
        }
        return
    }

    var draggingTime by remember { mutableStateOf<Double?>(null) }
    val minTime = keyframes.first().time
    val maxTime = keyframes.last().time
    val values = keyframes.map { it.value }
    val minValue = values.min()
    val maxValue = values.max()
    val valueSpan = max(1e-6, maxValue - minValue)
    val valuePad = valueSpan * 0.25
    val vMin = minValue - valuePad
    val vSpan = valueSpan + valuePad * 2

    // The segment being shaped: the one containing the playhead.
    var segmentIndex by remember(keyframes.size) { mutableStateOf(0) }
    val segment = run {
        var index = 0
        for (i in 0 until keyframes.size - 1) {
            if (playheadTime >= keyframes[i].time) index = i
        }
        index
    }
    segmentIndex = segment

    Box(
        modifier = modifier
            .background(VynoxColors.SurfaceHighest, RoundedCornerShape(14.dp))
    ) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(keyframes.size) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val w = size.width
                            val hit = keyframes.firstOrNull { kf ->
                                val x = ((kf.time - minTime) / max(1e-6, maxTime - minTime)) * w
                                val y = (1.0 - ((kf.value - vMin) / vSpan)) * size.height
                                val dx = offset.x - x.toFloat()
                                val dy = offset.y - y.toFloat()
                                dx * dx + dy * dy < 26f * 26f
                            }
                            draggingTime = hit?.time
                        },
                        onDragEnd = { draggingTime = null },
                        onDragCancel = { draggingTime = null },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val current = draggingTime
                            if (current != null) {
                                val deltaTime = (dragAmount.x / size.width) * max(1e-6, maxTime - minTime)
                                val target = (current + deltaTime).coerceAtLeast(0.0)
                                onKeyframeMove(current, target)
                                draggingTime = target
                            } else {
                                // Dragging the curve itself shapes the bezier handles.
                                val nx = (change.position.x / size.width).coerceIn(0.0, 1.0)
                                val ny = (1.0 - (change.position.y / size.height)).coerceIn(-0.4, 1.4)
                                val currentInterp = keyframes.getOrNull(segmentIndex + 1)?.interpolation
                                val basis = if (currentInterp?.isBezier == true) currentInterp else Interpolation.PRESETS.getValue("ease_in_out")
                                val left = nx < 0.5
                                val handles = if (left) {
                                    com.vynox.core.animation.BezierHandles(
                                        nx.coerceIn(0.0, 1.0), ny, basis.handles.cx2, basis.handles.cy2
                                    )
                                } else {
                                    com.vynox.core.animation.BezierHandles(
                                        basis.handles.cx1, basis.handles.cy1, nx.coerceIn(0.0, 1.0), ny
                                    )
                                }
                                onInterpolationChange(Interpolation(com.vynox.core.animation.InterpolationKind.BEZIER, handles))
                            }
                        }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            val timeSpan = max(1e-6, maxTime - minTime)
            fun xOf(time: Double): Float = (((time - minTime) / timeSpan) * w).toFloat()
            fun yOf(value: Double): Float = ((1.0 - ((value - vMin) / vSpan)) * h).toFloat()

            // Grid
            for (i in 0..4) {
                val y = h * i / 4f
                drawLine(
                    color = VynoxColors.Outline.copy(alpha = 0.5f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f
                )
            }
            for (i in 0..4) {
                val x = w * i / 4f
                drawLine(
                    color = VynoxColors.Outline.copy(alpha = 0.35f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f
                )
            }

            // Curve
            val path = Path()
            val samples = 96
            var started = false
            for (i in 0..samples) {
                val progress = i.toDouble() / samples
                val time = minTime + progress * timeSpan
                val value = sampleValue(keyframes, time)
                val point = Offset(xOf(time), yOf(value))
                if (!started) {
                    path.moveTo(point.x, point.y)
                    started = true
                } else {
                    path.lineTo(point.x, point.y)
                }
            }
            drawPath(path, VynoxColors.Cyan, style = Stroke(width = 3f))

            // Highlights the segment currently being shaped.
            if (segmentIndex < keyframes.size - 1) {
                val from = keyframes[segmentIndex]
                val to = keyframes[segmentIndex + 1]
                drawRect(
                    color = VynoxColors.Violet.copy(alpha = 0.12f),
                    topLeft = Offset(xOf(from.time), 0f),
                    size = Size((xOf(to.time) - xOf(from.time)).coerceAtLeast(2f), h)
                )
            }

            // Playhead
            val px = xOf(playheadTime.coerceIn(minTime, maxTime))
            drawLine(
                color = VynoxColors.Playhead,
                start = Offset(px, 0f),
                end = Offset(px, h),
                strokeWidth = 2f
            )

            // Keyframe dots
            keyframes.forEach { kf ->
                drawCircle(
                    color = if (draggingTime == kf.time) VynoxColors.Amber else Color.White,
                    radius = 9f,
                    center = Offset(xOf(kf.time), yOf(kf.value))
                )
            }
        }
    }
}

/** Samples the keyframe list with per-segment easing. */
private fun sampleValue(keyframes: List<KeyframePoint>, time: Double): Double {
    if (keyframes.size == 1) return keyframes.first().value
    val first = keyframes.first()
    if (time <= first.time) return first.value
    val last = keyframes.last()
    if (time >= last.time) return last.value
    var index = 0
    while (index < keyframes.size - 1 && keyframes[index + 1].time <= time) index++
    val from = keyframes[index]
    val to = keyframes[index + 1]
    val span = to.time - from.time
    val raw = if (span <= 1e-9) 1.0 else (time - from.time) / span
    val eased = to.interpolation.ease(raw)
    return ScalarInterpolator.interpolate(from.value, to.value, eased)
}
