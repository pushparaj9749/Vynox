package com.vynox.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vynox.app.ui.theme.VynoxColors
import com.vynox.core.model.LayerType
import com.vynox.core.model.VynoxProject
import kotlin.math.abs

private val ROW_HEIGHT: Dp = 46.dp
private val RULER_HEIGHT: Dp = 28.dp
private const val EDGE_GRAB_DP = 14f

private sealed class DragMode {
    data class Move(val layerId: String, val grabTime: Double) : DragMode()
    data class TrimStart(val layerId: String) : DragMode()
    data class TrimEnd(val layerId: String) : DragMode()
    data object Scrub : DragMode()
    data object Pan : DragMode()
}

/**
 * Professional multi-layer timeline.
 *
 * Draws clips, keyframes, markers and the playhead on a Canvas and supports
 * scrubbing, clip dragging with snapping, in/out trimming, selection and
 * pinch zoom. Every gesture calls into [EditorViewModel], so the timeline is a
 * view of project state rather than a source of it.
 */
@Composable
fun TimelinePanel(
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier
) {
    val project by collectAsStateWithLifecycle(viewModel.project)
    val playhead by collectAsStateWithLifecycle(viewModel.playhead)
    val zoom by collectAsStateWithLifecycle(viewModel.zoom)
    val selection by collectAsStateWithLifecycle(viewModel.selection)

    var scrollX by remember { mutableFloatStateOf(0f) }
    var dragMode by remember { mutableStateOf<DragMode?>(null) }
    val textMeasurer = rememberTextMeasurer()

    val rowHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { ROW_HEIGHT.toPx() }
    val rulerHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { RULER_HEIGHT.toPx() }
    val edgeGrabPx = with(androidx.compose.ui.platform.LocalDensity.current) { EDGE_GRAB_DP.dp.toPx() }

    val contentHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
        (rulerHeightPx + rowHeightPx * project.layers.size.coerceAtLeast(1)).toDp()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(contentHeight)
            .background(VynoxColors.Surface)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(zoom, scrollX, project.layers.size) {
                    detectTapGestures { offset ->
                        val time = xToTime(offset.x, zoom, scrollX)
                        val rowIndex = ((offset.y - rulerHeightPx) / rowHeightPx).toInt()
                        val layer = project.layers.getOrNull(rowIndex)
                        if (offset.y < rulerHeightPx) {
                            viewModel.setPlayhead(time)
                        } else if (layer != null && time >= layer.startTime && time <= layer.endTime) {
                            viewModel.select(layer.id)
                        }
                    }
                }
                .pointerInput(zoom, scrollX, project.layers.size) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val time = xToTime(offset.x, zoom, scrollX)
                            if (offset.y < rulerHeightPx) {
                                dragMode = DragMode.Scrub
                                viewModel.setPlayhead(time)
                                return@detectDragGestures
                            }
                            val rowIndex = ((offset.y - rulerHeightPx) / rowHeightPx).toInt()
                            val layer = project.layers.getOrNull(rowIndex)
                            if (layer == null || layer.locked) {
                                dragMode = DragMode.Pan
                                return@detectDragGestures
                            }
                            val startX = timeToX(layer.startTime, zoom, scrollX)
                            val endX = timeToX(layer.endTime, zoom, scrollX)
                            dragMode = when {
                                abs(offset.x - startX) <= edgeGrabPx -> DragMode.TrimStart(layer.id)
                                abs(offset.x - endX) <= edgeGrabPx -> DragMode.TrimEnd(layer.id)
                                offset.x in startX..endX -> DragMode.Move(layer.id, time - layer.startTime)
                                else -> DragMode.Pan
                            }
                            if (dragMode !is DragMode.Pan) viewModel.select(layer.id)
                        },
                        onDragEnd = { dragMode = null },
                        onDragCancel = { dragMode = null },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val deltaTime = dragAmount.x / zoom
                            when (val mode = dragMode) {
                                DragMode.Scrub -> viewModel.setPlayhead(xToTime(change.position.x, zoom, scrollX))
                                DragMode.Pan -> scrollX = (scrollX - dragAmount.x).coerceAtLeast(0f)
                                is DragMode.Move -> {
                                    val layer = project.layer(mode.layerId) ?: return@detectDragGestures
                                    viewModel.moveLayer(layer.id, xToTime(change.position.x, zoom, scrollX) - mode.grabTime)
                                }
                                is DragMode.TrimStart -> {
                                    val layer = project.layer(mode.layerId) ?: return@detectDragGestures
                                    viewModel.trimLayerStart(layer.id, xToTime(change.position.x, zoom, scrollX))
                                }
                                is DragMode.TrimEnd -> {
                                    val layer = project.layer(mode.layerId) ?: return@detectDragGestures
                                    viewModel.trimLayerEnd(layer.id, xToTime(change.position.x, zoom, scrollX))
                                }
                                null -> Unit
                            }
                        }
                    )
                }
                .pointerInput(zoom) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        if (gestureZoom != 1f) {
                            viewModel.setZoom(zoom * gestureZoom)
                        } else if (pan.x != 0f) {
                            scrollX = (scrollX - pan.x).coerceAtLeast(0f)
                        }
                    }
                }
        ) {
            drawTimeline(
                project = project,
                playhead = playhead,
                zoom = zoom,
                scrollX = scrollX,
                selection = selection,
                rowHeightPx = rowHeightPx,
                rulerHeightPx = rulerHeightPx,
                textMeasurer = textMeasurer,
                density = this
            )
        }
    }
}

private fun timeToX(time: Double, zoom: Float, scrollX: Float): Float =
    (time * zoom).toFloat() - scrollX

private fun xToTime(x: Float, zoom: Float, scrollX: Float): Double =
    ((x + scrollX) / zoom).toDouble().coerceAtLeast(0.0)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTimeline(
    project: VynoxProject,
    playhead: Double,
    zoom: Float,
    scrollX: Float,
    selection: Set<String>,
    rowHeightPx: Float,
    rulerHeightPx: Float,
    textMeasurer: TextMeasurer,
    density: androidx.compose.ui.unit.Density
) {
    val width = size.width
    val height = size.height

    // Rows
    project.layers.forEachIndexed { index, layer ->
        val top = rulerHeightPx + index * rowHeightPx
        drawRect(
            color = if (index % 2 == 0) VynoxColors.Surface else VynoxColors.SurfaceRaised,
            topLeft = Offset(0f, top),
            size = Size(width, rowHeightPx)
        )
        drawLine(
            color = VynoxColors.Outline.copy(alpha = 0.4f),
            start = Offset(0f, top + rowHeightPx),
            end = Offset(width, top + rowHeightPx),
            strokeWidth = 1f
        )
    }

    // Grid / ruler ticks
    val interval = niceInterval(zoom)
    val firstTick = ((scrollX / zoom).toInt() / interval) * interval
    var tick = firstTick
    while (timeToX(tick.toDouble(), zoom, scrollX) < width) {
        val x = timeToX(tick.toDouble(), zoom, scrollX)
        if (x >= 0f) {
            drawLine(
                color = VynoxColors.Outline.copy(alpha = 0.35f),
                start = Offset(x, rulerHeightPx),
                end = Offset(x, height),
                strokeWidth = 1f
            )
            drawLine(
                color = VynoxColors.TextMuted,
                start = Offset(x, rulerHeightPx - 10f),
                end = Offset(x, rulerHeightPx),
                strokeWidth = 1.5f
            )
            val label = formatTimecode(tick.toDouble())
            val layout = textMeasurer.measure(
                text = label,
                style = TextStyle(fontSize = 10.sp, color = VynoxColors.TextMuted)
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(x + 4f, 4f)
            )
        }
        tick += interval
    }
    drawLine(
        color = VynoxColors.Outline,
        start = Offset(0f, rulerHeightPx),
        end = Offset(width, rulerHeightPx),
        strokeWidth = 1f
    )

    // Markers
    project.markers.forEach { marker ->
        val x = timeToX(marker.time, zoom, scrollX)
        if (x in -20f..width + 20f) {
            val path = Path().apply {
                moveTo(x, rulerHeightPx - 4f)
                lineTo(x - 5f, rulerHeightPx - 12f)
                lineTo(x + 5f, rulerHeightPx - 12f)
                close()
            }
            drawPath(path, Color(marker.colorArgb))
        }
    }

    // Clips
    project.layers.forEachIndexed { index, layer ->
        val top = rulerHeightPx + index * rowHeightPx + 5f
        val clipHeight = rowHeightPx - 10f
        val startX = timeToX(layer.startTime, zoom, scrollX)
        val endX = timeToX(layer.endTime, zoom, scrollX)
        if (endX < 0f || startX > width) return@forEachIndexed

        val baseColor = when (layer.type) {
            LayerType.VIDEO -> VynoxColors.TimelineClipVideo
            LayerType.IMAGE -> VynoxColors.TimelineClip
            LayerType.AUDIO -> VynoxColors.TimelineClipAudio
            LayerType.TEXT -> VynoxColors.TimelineClipText
            LayerType.SHAPE -> VynoxColors.TimelineClipShape
            LayerType.GROUP -> VynoxColors.TimelineClip
        }
        val color = if (layer.enabled) baseColor else baseColor.copy(alpha = 0.45f)
        val selected = layer.id in selection

        drawRoundRect(
            color = color,
            topLeft = Offset(startX.coerceAtLeast(-40f), top),
            size = Size((endX - startX).coerceAtLeast(4f), clipHeight),
            cornerRadius = CornerRadius(8f, 8f)
        )
        if (selected) {
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(startX.coerceAtLeast(-40f), top),
                size = Size((endX - startX).coerceAtLeast(4f), clipHeight),
                cornerRadius = CornerRadius(8f, 8f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f, color = VynoxColors.Cyan)
            )
        }

        // Label
        val label = layer.name + if (layer.muted) "  🔇" else ""
        val textLayout = textMeasurer.measure(
            text = label,
            style = TextStyle(fontSize = 11.sp, color = Color.White),
            maxLines = 1
        )
        if (endX - startX > 40f) {
            drawText(textLayoutResult = textLayout, topLeft = Offset(startX + 8f, top + 6f))
        }

        // Keyframe diamonds
        val keyframeTimes = com.vynox.core.timeline.LayerProperties.keyframeTimes(layer)
        keyframeTimes.forEach { time ->
            val x = timeToX(layer.startTime + time, zoom, scrollX)
            if (x < startX - 6f || x > endX + 6f) return@forEach
            val y = top + clipHeight - 8f
            val path = Path().apply {
                moveTo(x, y - 4f)
                lineTo(x + 4f, y)
                lineTo(x, y + 4f)
                lineTo(x - 4f, y)
                close()
            }
            drawPath(path, VynoxColors.Amber)
        }

        // Trim handles
        if (selected) {
            drawRect(color = VynoxColors.Cyan, topLeft = Offset(startX, top), size = Size(3f, clipHeight))
            drawRect(color = VynoxColors.Cyan, topLeft = Offset(endX - 3f, top), size = Size(3f, clipHeight))
        }
    }

    // Playhead
    val playheadX = timeToX(playhead, zoom, scrollX)
    drawLine(
        color = VynoxColors.Playhead,
        start = Offset(playheadX, 0f),
        end = Offset(playheadX, height),
        strokeWidth = 2f
    )
    val handle = Path().apply {
        moveTo(playheadX - 7f, 0f)
        lineTo(playheadX + 7f, 0f)
        lineTo(playheadX, 12f)
        close()
    }
    drawPath(handle, VynoxColors.Playhead)
}

private fun niceInterval(zoom: Float): Int {
    val targetPx = 80f
    val raw = targetPx / zoom
    return when {
        raw <= 1 -> 1
        raw <= 2 -> 2
        raw <= 5 -> 5
        raw <= 10 -> 10
        raw <= 15 -> 15
        raw <= 30 -> 30
        raw <= 60 -> 60
        else -> (raw / 60).toInt().coerceAtLeast(1) * 60
    }
}

fun formatTimecode(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0)
    val minutes = (total / 60).toInt()
    val secs = (total % 60).toInt()
    return "%d:%02d".format(minutes, secs)
}
