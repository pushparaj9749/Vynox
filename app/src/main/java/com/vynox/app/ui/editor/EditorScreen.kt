package com.vynox.app.ui.editor

import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.vynox.app.ui.common.Chip
import com.vynox.app.ui.common.LabeledSlider
import com.vynox.app.ui.common.PanelCard
import com.vynox.app.ui.common.rememberMediaPermissionLauncher
import com.vynox.app.ui.theme.VynoxColors
import com.vynox.core.composition.CompositionEvaluator
import com.vynox.core.math.Vec2
import com.vynox.core.model.AssetType
import com.vynox.core.model.Layer
import com.vynox.core.model.ShapeKind
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    onExport: () -> Unit
) {
    val context = LocalContext.current
    val project by viewModel.project.collectAsStateWithLifecycle()
    val playhead by viewModel.playhead.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val missing by viewModel.missingAssets.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by viewModel.canRedo.collectAsStateWithLifecycle()
    val zoom by viewModel.zoom.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var bottomTab by remember { mutableStateOf(BottomPanel.LAYERS) }
    var relinkAssetId by remember { mutableStateOf<String?>(null) }
    var pendingMediaPicker by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberMediaPermissionLauncher { pendingMediaPicker?.invoke() }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val asset = viewModel.importMedia(uri, AssetType.VIDEO)
            viewModel.addMediaLayer(asset)
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val asset = viewModel.importMedia(uri, AssetType.AUDIO)
            viewModel.addMediaLayer(asset)
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val asset = viewModel.importMedia(uri, AssetType.IMAGE)
            viewModel.addMediaLayer(asset)
        }
    }
    val scope = rememberCoroutineScope()
    val relinkPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val assetId = relinkAssetId
        if (uri != null && assetId != null) {
            runCatching { viewModel.relinkAsset(assetId, uri) }
                .onFailure { error ->
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "Could not relink: ${error.message ?: "unsupported file"}"
                        )
                    }
                }
        }
        relinkAssetId = null
    }

    fun pickVideo() {
        pendingMediaPicker = { videoPicker.launch("video/*") }
        permissionLauncher()
    }

    Column(modifier = Modifier.fillMaxSize().background(VynoxColors.Ink)) {
        // ------------------------------------------------------------- top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹",
                color = VynoxColors.TextPrimary,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 10.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    project.name,
                    color = VynoxColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${project.canvas.width}x${project.canvas.height} · ${project.canvas.fps}fps",
                    style = MaterialTheme.typography.labelSmall,
                    color = VynoxColors.TextMuted
                )
            }
            IconButton(onClick = { viewModel.undo() }, enabled = canUndo) {
                Icon(Icons.Rounded.Undo, "Undo", tint = if (canUndo) VynoxColors.TextPrimary else VynoxColors.Outline)
            }
            IconButton(onClick = { viewModel.redo() }, enabled = canRedo) {
                Icon(Icons.Rounded.Redo, "Redo", tint = if (canRedo) VynoxColors.TextPrimary else VynoxColors.Outline)
            }
            IconButton(onClick = { viewModel.save() }) {
                Icon(Icons.Rounded.Save, "Save", tint = VynoxColors.TextPrimary)
            }
            IconButton(onClick = onExport) {
                Icon(Icons.Rounded.Share, "Export", tint = VynoxColors.Cyan)
            }
        }

        if (missing.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(VynoxColors.Rose.copy(alpha = 0.18f))
                    .clickable {
                        val first = missing.firstOrNull()
                        if (first != null) {
                            relinkAssetId = first.id
                            relinkPicker.launch("*/*")
                        }
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Warning, null, tint = VynoxColors.Rose, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "${missing.size} missing media file(s) — tap to relink",
                    color = VynoxColors.TextPrimary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // -------------------------------------------------------------- preview
        PreviewStage(
            viewModel = viewModel,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        // ---------------------------------------------------------- transport
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.togglePlayback() }) {
                Text(
                    if (isPlaying) "❚❚" else "▶",
                    color = VynoxColors.Cyan,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Text(
                formatTimecode(playhead),
                color = VynoxColors.TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(64.dp)
            )
            LabeledSlider(
                label = "Scrub",
                value = playhead.toFloat(),
                range = 0f..project.duration.toFloat().coerceAtLeast(1f),
                valueText = formatTimecode(project.duration),
                onValueChange = { viewModel.setPlayhead(it.toDouble()) },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Chip(text = "⌄", selected = false, onClick = { viewModel.setZoom(zoom * 0.7f) })
            Spacer(Modifier.width(4.dp))
            Chip(text = "⌃", selected = false, onClick = { viewModel.setZoom(zoom * 1.4f) })
        }

        // ------------------------------------------------------------ add layer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AddButton("Text", Icons.Rounded.TextFields) { viewModel.addTextLayer(); bottomTab = BottomPanel.INSPECTOR }
            AddButton("Shape", Icons.Rounded.Layers) { viewModel.addShapeLayer(ShapeKind.ROUNDED_RECT); bottomTab = BottomPanel.INSPECTOR }
            AddButton("Video", Icons.Rounded.Movie) { pickVideo() }
            AddButton("Image", Icons.Rounded.Image) { imagePicker.launch("image/*") }
            AddButton("Audio", Icons.Rounded.Audiotrack) { audioPicker.launch("audio/*") }
        }

        // ----------------------------------------------------------- bottom UI
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            IconButton(
                onClick = { viewModel.splitAtPlayhead() },
                modifier = Modifier.background(VynoxColors.SurfaceHighest, RoundedCornerShape(10.dp))
            ) {
                Icon(Icons.Rounded.ContentCut, "Split", tint = VynoxColors.TextPrimary)
            }
            IconButton(
                onClick = { viewModel.deleteSelected() },
                enabled = selection.isNotEmpty(),
                modifier = Modifier.background(VynoxColors.SurfaceHighest, RoundedCornerShape(10.dp))
            ) {
                Icon(Icons.Rounded.Delete, "Delete", tint = if (selection.isEmpty()) VynoxColors.Outline else VynoxColors.Rose)
            }
            Chip(text = "Marker", selected = false, onClick = { viewModel.addMarkerAtPlayhead() })
            Chip(text = "Fit", selected = false, onClick = { viewModel.fitDurationToContent() })
            Spacer(Modifier.weight(1f))
            Chip(text = "Layers", selected = bottomTab == BottomPanel.LAYERS, onClick = { bottomTab = BottomPanel.LAYERS })
            Chip(text = "Inspector", selected = bottomTab == BottomPanel.INSPECTOR, onClick = { bottomTab = BottomPanel.INSPECTOR })
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.9f)
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(VynoxColors.Surface)
        ) {
            when (bottomTab) {
                BottomPanel.LAYERS -> LayerListPanel(viewModel, modifier = Modifier.fillMaxHeight())
                BottomPanel.INSPECTOR -> InspectorPanel(viewModel, modifier = Modifier.fillMaxHeight())
            }
        }

        // ------------------------------------------------------------ timeline
        TimelinePanel(viewModel, modifier = Modifier.height(150.dp))
        Spacer(Modifier.height(6.dp))
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(hostState = snackbarHostState)
    }
}

private enum class BottomPanel { LAYERS, INSPECTOR }

@Composable
private fun AddButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(VynoxColors.SurfaceHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = VynoxColors.Cyan, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = VynoxColors.TextPrimary, style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------------------------------------------------------- preview stage

@Composable
private fun PreviewStage(viewModel: EditorViewModel, modifier: Modifier = Modifier) {
    val project by viewModel.project.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val playhead by viewModel.playhead.collectAsStateWithLifecycle()
    val engine = remember { PreviewEngine(viewModel) }

    DisposableEffect(Unit) {
        viewModel.frameCapture = { engine.capture() }
        onDispose {
            viewModel.frameCapture = null
            engine.detach()
        }
    }

    BoxWithConstraints(modifier = modifier.padding(8.dp), contentAlignment = Alignment.Center) {
        val canvasAspect = project.canvas.width.toFloat() / project.canvas.height.toFloat().coerceAtLeast(1f)
        val boxWidth = maxWidth
        val boxHeight = maxHeight
        val width = if (boxWidth / boxHeight > canvasAspect) boxHeight * canvasAspect else boxWidth
        val height = width / canvasAspect

        Box(
            modifier = Modifier
                .size(width = width, height = height)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                .border(1.dp, VynoxColors.Outline, RoundedCornerShape(14.dp))
        ) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).also { view ->
                        engine.attach(view)
                        view.isClickable = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            val layer = project.layers.firstOrNull { it.id in selection }
            if (layer != null && !layer.locked) {
                TransformOverlay(
                    viewModel = viewModel,
                    layer = layer,
                    time = playhead
                )
            }
        }
    }
}

// ------------------------------------------------------------ transform gizmo

private enum class HandleKind { MOVE, CORNER, ROTATE }

@Composable
private fun TransformOverlay(
    viewModel: EditorViewModel,
    layer: Layer,
    time: Double
) {
    val project by viewModel.project.collectAsStateWithLifecycle()
    val evaluator = remember(project) { CompositionEvaluator(project, com.vynox.app.render.source.AndroidTextMetrics) }
    var dragKind by remember { mutableStateOf<HandleKind?>(null) }
    var dragCornerIndex by remember { mutableStateOf(0) }
    var startPosition by remember { mutableStateOf(Vec2.ZERO) }
    var startScale by remember { mutableStateOf(Vec2(1.0, 1.0)) }
    var startRotation by remember { mutableStateOf(0.0) }
    var startDist by remember { mutableStateOf(1.0) }
    var startAngle by remember { mutableStateOf(0.0) }
    var lastMove by remember { mutableStateOf(Offset.Zero) }

    val localTime = (time - layer.startTime).coerceAtLeast(0.0)
    val size = evaluator.contentSize(layer)
    val matrix = evaluator.layerMatrix(layer, time)
    val corners = remember(layer.id, project) {
        listOf(
            Vec2(0.0, 0.0),
            Vec2(size.width, 0.0),
            Vec2(size.width, size.height),
            Vec2(0.0, size.height)
        ).map { matrix.transformPoint(it) }
    }
    val canvasWidth = project.canvas.width.toDouble()
    val canvasHeight = project.canvas.height.toDouble()

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(layer.id) {
                detectTapGestures { offset ->
                    val point = viewToCanvas(offset.x, offset.y, size0 = this.size, canvasWidth, canvasHeight)
                    val hit = evaluator.hitTest(point, time)
                    if (hit != null) viewModel.select(hit.id)
                }
            }
            .pointerInput(layer.id) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val scale = (this.size.width / canvasWidth).toFloat()
                        val cornerPoints = corners.map { Offset((it.x * scale).toFloat(), (it.y * scale).toFloat()) }
                        val centre = Offset(
                            cornerPoints.map { it.x }.average().toFloat(),
                            cornerPoints.map { it.y }.average().toFloat()
                        )
                        val rotatePoint = rotateHandlePoint(cornerPoints)
                        when {
                            (offset - rotatePoint).getDistance() < 32f -> {
                                dragKind = HandleKind.ROTATE
                                startRotation = layer.transform.rotation.valueAt(localTime)
                                startAngle = atan2(
                                    (offset.y - centre.y).toDouble(),
                                    (offset.x - centre.x).toDouble()
                                ) * 180.0 / Math.PI
                            }
                            cornerPoints.indices.any { (offset - cornerPoints[it]).getDistance() < 30f } -> {
                                dragKind = HandleKind.CORNER
                                dragCornerIndex = cornerPoints.indices.first { (offset - cornerPoints[it]).getDistance() < 30f }
                                startPosition = layer.transform.position.valueAt(localTime)
                                startScale = layer.transform.scale.valueAt(localTime)
                                startDist = hypot(
                                    (offset.x - centre.x).toDouble(),
                                    (offset.y - centre.y).toDouble()
                                ).coerceAtLeast(1.0)
                            }
                            else -> {
                                dragKind = HandleKind.MOVE
                                startPosition = layer.transform.position.valueAt(localTime)
                                lastMove = offset
                            }
                        }
                    },
                    onDragEnd = { dragKind = null },
                    onDragCancel = { dragKind = null },
                    onDrag = { change, _ ->
                        change.consume()
                        val scale = (this.size.width / canvasWidth).toFloat()
                        when (dragKind) {
                            HandleKind.MOVE -> {
                                val dx = (change.position.x - lastMove.x) / scale
                                val dy = (change.position.y - lastMove.y) / scale
                                lastMove = change.position
                                viewModel.setTransformVec2(
                                    layer.id,
                                    TransformProperty.POSITION,
                                    startPosition.x + dx,
                                    startPosition.y + dy,
                                    layer.transform.position.isAnimated
                                )
                                startPosition = layer.transform.position.valueAt(localTime)
                            }
                            HandleKind.CORNER -> {
                                val centre = Offset(
                                    corners.map { (it.x * scale).toFloat() }.average().toFloat(),
                                    corners.map { (it.y * scale).toFloat() }.average().toFloat()
                                )
                                val dist = hypot(
                                    (change.position.x - centre.x).toDouble(),
                                    (change.position.y - centre.y).toDouble()
                                ).coerceAtLeast(1.0)
                                val factor = (dist / startDist).coerceIn(0.05, 20.0)
                                viewModel.setTransformVec2(
                                    layer.id,
                                    TransformProperty.SCALE,
                                    startScale.x * factor,
                                    startScale.y * factor,
                                    layer.transform.scale.isAnimated
                                )
                            }
                            HandleKind.ROTATE -> {
                                val centre = Offset(
                                    corners.map { (it.x * scale).toFloat() }.average().toFloat(),
                                    corners.map { (it.y * scale).toFloat() }.average().toFloat()
                                )
                                val angle = atan2(
                                    (change.position.y - centre.y).toDouble(),
                                    (change.position.x - centre.x).toDouble()
                                ) * 180.0 / Math.PI
                                viewModel.setTransformScalar(
                                    layer.id,
                                    TransformProperty.ROTATION,
                                    startRotation + (angle - startAngle),
                                    layer.transform.rotation.isAnimated
                                )
                            }
                            null -> Unit
                        }
                    }
                )
            }
    ) {
        val scale = (size.width / canvasWidth).toFloat()
        val points = corners.map { Offset((it.x * scale).toFloat(), (it.y * scale).toFloat()) }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path, VynoxColors.Cyan, style = Stroke(width = 2f))

        val centre = Offset(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat())

        // Rotation handle
        val rotate = rotateHandlePoint(points)
        drawLine(VynoxColors.Cyan, points[0], rotate, strokeWidth = 2f)
        drawCircle(VynoxColors.Cyan, radius = 9f, center = rotate)

        // Corner handles
        points.forEach { point ->
            drawRect(
                color = VynoxColors.Ink,
                topLeft = Offset(point.x - 8f, point.y - 8f),
                size = androidx.compose.ui.geometry.Size(16f, 16f)
            )
            drawRect(
                color = VynoxColors.Cyan,
                topLeft = Offset(point.x - 6f, point.y - 6f),
                size = androidx.compose.ui.geometry.Size(12f, 12f)
            )
        }

        // Centre marker
        drawCircle(Color.White.copy(alpha = 0.7f), radius = 3f, center = centre)
    }
}

private fun viewToCanvas(
    x: Float,
    y: Float,
    size0: androidx.compose.ui.unit.IntSize,
    canvasWidth: Double,
    canvasHeight: Double
): Vec2 {
    val scale = (size0.width / canvasWidth)
    val scaleY = (size0.height / canvasHeight)
    return Vec2(x / scale, y / scaleY)
}

private fun rotateHandlePoint(points: List<Offset>): Offset {
    val topLeft = points[0]
    val topRight = points[1]
    val mid = Offset((topLeft.x + topRight.x) / 2f, (topLeft.y + topRight.y) / 2f)
    val dx = topRight.x - topLeft.x
    val dy = topRight.y - topLeft.y
    val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    // Outward normal of the top edge, 40px away from the edge.
    val nx = dy / length
    val ny = -dx / length
    return Offset(mid.x + nx * 40f, mid.y + ny * 40f)
}
