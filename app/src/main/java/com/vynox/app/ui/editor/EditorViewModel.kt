package com.vynox.app.ui.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vynox.app.VynoxServices
import com.vynox.app.render.audio.AudioMixer
import com.vynox.app.render.audio.AudioStream
import com.vynox.app.render.export.ExportProgress
import com.vynox.app.render.export.ExportSettings
import com.vynox.app.render.export.VideoExporter
import com.vynox.app.render.gl.LayerTexture
import com.vynox.app.render.gl.RenderSourceProvider
import com.vynox.app.render.source.AndroidTextMetrics
import com.vynox.app.render.source.RenderSources
import com.vynox.core.animation.Animatable
import com.vynox.core.animation.AnimatableOps
import com.vynox.core.animation.AngleInterpolator
import com.vynox.core.animation.ColorInterpolator
import com.vynox.core.animation.Interpolation
import com.vynox.core.animation.ScalarInterpolator
import com.vynox.core.animation.StaticValue
import com.vynox.core.animation.Vec2Interpolator
import com.vynox.core.animation.ValueInterpolator
import com.vynox.core.composition.CompositionEvaluator
import com.vynox.core.composition.RenderMask
import com.vynox.core.composition.RenderNode
import com.vynox.core.effects.EffectRegistry
import com.vynox.core.math.Color
import com.vynox.core.math.Vec2
import com.vynox.core.model.Asset
import com.vynox.core.model.AssetType
import com.vynox.core.model.BlendMode
import com.vynox.core.model.ContentFit
import com.vynox.core.model.Canvas
import com.vynox.core.model.EffectInstance
import com.vynox.core.model.Ids
import com.vynox.core.model.Layer
import com.vynox.core.model.LayerContent
import com.vynox.core.model.LayerFactory
import com.vynox.core.model.LayerType
import com.vynox.core.model.Mask
import com.vynox.core.model.MaskShape
import com.vynox.core.model.PreviewQuality
import com.vynox.core.model.ProjectFactory
import com.vynox.core.model.ShapeKind
import com.vynox.core.model.Transform
import com.vynox.core.model.VynoxProject
import com.vynox.core.timeline.History
import com.vynox.core.timeline.Snapping
import com.vynox.core.timeline.TimelineOperations
import com.vynox.core.vnx.AssetResolution
import com.vynox.core.vnx.VnxWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class InspectorTab(val label: String) {
    TRANSFORM("Transform"),
    CONTENT("Content"),
    EFFECTS("Effects"),
    MASKS("Masks"),
    ANIMATION("Animation")
}

/** Transform properties that can be animated. */
enum class TransformProperty(val label: String) {
    POSITION("Position"),
    SCALE("Scale"),
    ROTATION("Rotation"),
    OPACITY("Opacity");

    val isVec2: Boolean get() = this == POSITION || this == SCALE
}

data class EditorUiState(
    val project: VynoxProject,
    val selection: Set<String>,
    val playhead: Double,
    val isPlaying: Boolean
)

/**
 * Owns the document being edited.
 *
 * Every mutation goes through [commit], which pushes the previous state onto the
 * undo stack. Nothing here touches Android views, and every operation is a pure
 * transformation of [VynoxProject] provided by the engine modules - the UI is a
 * thin, reactive shell on top.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>().applicationContext

    private val emptyProject: VynoxProject =
        ProjectFactory.create("Untitled", Canvas(), "0.1.0")

    private val _project = MutableStateFlow(emptyProject)
    val project: StateFlow<VynoxProject> = _project.asStateFlow()

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private val _playhead = MutableStateFlow(0.0)
    val playhead: StateFlow<Double> = _playhead.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _zoom = MutableStateFlow(60f) // pixels per second
    val zoom: StateFlow<Float> = _zoom.asStateFlow()

    private val _tab = MutableStateFlow(InspectorTab.TRANSFORM)
    val tab: StateFlow<InspectorTab> = _tab.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val _missingAssets = MutableStateFlow<List<Asset>>(emptyList())
    val missingAssets: StateFlow<List<Asset>> = _missingAssets.asStateFlow()

    private val _warnings = MutableStateFlow<List<VnxWarning>>(emptyList())
    val warnings: StateFlow<List<VnxWarning>> = _warnings.asStateFlow()

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message: SharedFlow<String> = _message.asSharedFlow()

    private val _exportProgress = MutableStateFlow<ExportProgress?>(null)
    val exportProgress: StateFlow<ExportProgress?> = _exportProgress.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private lateinit var history: History
    private var currentFile: File? = null
    private var playbackJob: Job? = null
    private var autosaveJob: Job? = null

    /** Renderer hooks supplied by the preview component. */
    var sources: RenderSources? = null
    var frameCapture: (() -> android.graphics.Bitmap?)? = null

    val evaluator: CompositionEvaluator
        get() = CompositionEvaluator(_project.value, AndroidTextMetrics)

    init {
        history = History(emptyProject)
        viewModelScope.launch {
            val settings = VynoxServices.settingsStore.settings.first()
            if (settings.autosaveEnabled) startAutosave(settings.autosaveIntervalSeconds)
        }
    }

    // ------------------------------------------------------------------ loading

    fun load(project: VynoxProject, file: File? = null, warnings: List<VnxWarning> = emptyList()) {
        history = History(project)
        _project.value = project
        currentFile = file
        _warnings.value = warnings
        _selection.value = emptySet()
        _playhead.value = 0.0
        refreshUndoState()
        refreshMissingAssets()
    }

    fun newProject(name: String, canvas: Canvas) {
        load(ProjectFactory.create(name, canvas, com.vynox.app.BuildConfig.VERSION_NAME))
    }

    fun currentFile(): File? = currentFile

    // -------------------------------------------------------------- undo & save

    private fun commit(label: String, next: VynoxProject, coalesceKey: String? = null) {
        history.commit(label, next, coalesceKey)
        _project.value = history.current
        refreshUndoState()
    }

    /** Commits a change without creating an undo step (playhead, transient UI). */
    private fun replace(next: VynoxProject) {
        history.replace(next)
        _project.value = next
    }

    private fun refreshUndoState() {
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
    }

    fun undo() {
        if (history.undo()) {
            _project.value = history.current
            refreshUndoState()
            viewModelScope.launch { _message.emit("Undo") }
        }
    }

    fun redo() {
        if (history.redo()) {
            _project.value = history.current
            refreshUndoState()
            viewModelScope.launch { _message.emit("Redo") }
        }
    }

    private fun updateLayer(
        layerId: String,
        label: String,
        coalesceKey: String? = null,
        block: (Layer) -> Layer
    ) {
        val project = _project.value
        val layer = project.layer(layerId) ?: return
        val updated = block(layer)
        commit(label, project.withReplacedLayer(updated), coalesceKey)
    }

    // ---------------------------------------------------------------- playback

    fun setPlayhead(time: Double) {
        _playhead.value = time.coerceAtLeast(0.0).coerceAtMost(_project.value.duration)
    }

    fun setZoom(pixelsPerSecond: Float) {
        _zoom.value = pixelsPerSecond.coerceIn(8f, 600f)
    }

    fun setTab(tab: InspectorTab) {
        _tab.value = tab
    }

    fun play() {
        if (_isPlaying.value) return
        _isPlaying.value = true
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch(Dispatchers.Default) {
            val fps = _project.value.canvas.fps.toDouble().coerceAtLeast(1.0)
            val frameDurationMs = (1000.0 / fps).toLong()
            while (_isPlaying.value) {
                delay(frameDurationMs)
                val next = _playhead.value + 1.0 / fps
                if (next >= _project.value.duration) {
                    _playhead.value = 0.0
                } else {
                    _playhead.value = next
                }
            }
        }
    }

    fun pause() {
        _isPlaying.value = false
        playbackJob?.cancel()
        playbackJob = null
    }

    fun togglePlayback() {
        if (_isPlaying.value) pause() else play()
    }

    fun stepFrames(frames: Int) {
        pause()
        val fps = _project.value.canvas.fps.toDouble().coerceAtLeast(1.0)
        setPlayhead(_playhead.value + frames / fps)
    }

    // --------------------------------------------------------------- selection

    fun select(layerId: String?) {
        _selection.value = if (layerId == null) emptySet() else setOf(layerId)
    }

    fun toggleSelection(layerId: String) {
        val current = _selection.value.toMutableSet()
        if (!current.add(layerId)) current.remove(layerId)
        _selection.value = current
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun selectedLayers(): List<Layer> =
        _project.value.layers.filter { it.id in _selection.value }

    fun primarySelection(): Layer? =
        _project.value.layers.firstOrNull { it.id in _selection.value }

    // ------------------------------------------------------------------ layers

    fun addTextLayer() {
        val project = _project.value
        val layer = LayerFactory.textLayer(project.canvas, "Vynox").copy(startTime = 0.0, duration = project.duration)
        commit("Add text", TimelineOperations.addLayer(project, layer, 0))
        select(layer.id)
        _tab.value = InspectorTab.CONTENT
    }

    fun addShapeLayer(shape: ShapeKind) {
        val project = _project.value
        val layer = LayerFactory.shapeLayer(project.canvas, shape).copy(startTime = 0.0, duration = project.duration)
        commit("Add shape", TimelineOperations.addLayer(project, layer, 0))
        select(layer.id)
        _tab.value = InspectorTab.CONTENT
    }

    fun addGroupLayer() {
        val project = _project.value
        val layer = LayerFactory.groupLayer(project.canvas).copy(startTime = 0.0, duration = project.duration)
        commit("Add group", TimelineOperations.addLayer(project, layer, 0))
        select(layer.id)
    }

    fun addMediaLayer(asset: Asset) {
        val project = _project.value
        val withAsset = project.withAsset(asset)
        val layer = LayerFactory.mediaLayer(asset, project.canvas, _playhead.value)
        commit("Add media", TimelineOperations.addLayer(withAsset, layer, 0))
        select(layer.id)
        _tab.value = InspectorTab.TRANSFORM
    }

    fun duplicateSelected() {
        val layerId = primarySelection()?.id ?: return
        val project = _project.value
        commit("Duplicate layer", TimelineOperations.duplicateLayer(project, layerId))
    }

    fun deleteSelected() {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        var project = _project.value
        ids.forEach { project = TimelineOperations.removeLayer(project, it) }
        commit("Delete layer", project)
        clearSelection()
    }

    fun reorderLayer(layerId: String, newIndex: Int) {
        commit("Reorder layer", TimelineOperations.reorderLayer(_project.value, layerId, newIndex))
    }

    fun moveLayerInStack(layerId: String, delta: Int) {
        val project = _project.value
        val next = if (delta < 0) TimelineOperations.moveLayerForward(project, layerId)
        else TimelineOperations.moveLayerBackward(project, layerId)
        commit("Move layer", next)
    }

    fun setLayerName(layerId: String, name: String) =
        updateLayer(layerId, "Rename layer") { it.copy(name = name) }

    fun toggleVisibility(layerId: String) =
        updateLayer(layerId, "Toggle visibility") { it.copy(enabled = !it.enabled) }

    fun toggleLock(layerId: String) =
        updateLayer(layerId, "Toggle lock") { it.copy(locked = !it.locked) }

    fun toggleMute(layerId: String) =
        updateLayer(layerId, "Toggle mute") { it.copy(muted = !it.muted) }

    fun toggleSolo(layerId: String) =
        updateLayer(layerId, "Toggle solo") { it.copy(solo = !it.solo) }

    fun setBlendMode(layerId: String, mode: BlendMode) =
        updateLayer(layerId, "Blend mode") { it.copy(blendMode = mode) }

    fun setDuration(layerId: String, duration: Double) =
        updateLayer(layerId, "Layer duration") { it.copy(duration = duration.coerceAtLeast(1.0 / 30.0)) }

    // ------------------------------------------------------------- timeline ops

    fun splitAtPlayhead() {
        val layerId = primarySelection()?.id ?: run {
            viewModelScope.launch { _message.emit("Select a layer to split") }
            return
        }
        val project = _project.value
        val next = TimelineOperations.splitLayer(project, layerId, _playhead.value)
        if (next === project) {
            viewModelScope.launch { _message.emit("Playhead is not inside the selected clip") }
            return
        }
        commit("Split clip", next)
    }

    fun moveLayer(layerId: String, newStart: Double, snap: Boolean = true) {
        val project = _project.value
        val layer = project.layer(layerId) ?: return
        val target = if (snap) {
            Snapping.snapMove(project, layer, newStart, playhead = _playhead.value).time
        } else newStart
        commit("Move clip", TimelineOperations.moveLayerInTime(project, layerId, target), "move:$layerId")
    }

    fun trimLayerStart(layerId: String, newStart: Double) =
        commit("Trim in", TimelineOperations.trimLayerStart(_project.value, layerId, newStart), "trimStart:$layerId")

    fun trimLayerEnd(layerId: String, newEnd: Double) =
        commit("Trim out", TimelineOperations.trimLayerEnd(_project.value, layerId, newEnd), "trimEnd:$layerId")

    fun addMarkerAtPlayhead() {
        commit("Add marker", TimelineOperations.addMarker(_project.value, _playhead.value))
    }

    fun fitDurationToContent() {
        commit("Fit duration", TimelineOperations.fitToContent(_project.value))
    }

    // ------------------------------------------------------------- transforms

    private fun propertyOf(layer: Layer, property: TransformProperty): Animatable<*> = when (property) {
        TransformProperty.POSITION -> layer.transform.position
        TransformProperty.SCALE -> layer.transform.scale
        TransformProperty.ROTATION -> layer.transform.rotation
        TransformProperty.OPACITY -> layer.transform.opacity
    }

    private fun withProperty(layer: Layer, property: TransformProperty, value: Animatable<*>): Layer {
        val transform = when (property) {
            TransformProperty.POSITION -> layer.transform.copy(position = value as Animatable<Vec2>)
            TransformProperty.SCALE -> layer.transform.copy(scale = value as Animatable<Vec2>)
            TransformProperty.ROTATION -> layer.transform.copy(rotation = value as Animatable<Double>)
            TransformProperty.OPACITY -> layer.transform.copy(opacity = value as Animatable<Double>)
        }
        return layer.copy(transform = transform)
    }

    private fun <T> interpolatorFor(property: TransformProperty): ValueInterpolator<T> =
        when (property) {
            TransformProperty.ROTATION -> AngleInterpolator
            TransformProperty.OPACITY -> ScalarInterpolator
            else -> Vec2Interpolator
        } as ValueInterpolator<T>

    fun setTransformVec2(layerId: String, property: TransformProperty, x: Double, y: Double, animated: Boolean) {
        if (!property.isVec2) return
        updateLayer(layerId, "Change ${property.label}", "transform:$layerId:$property") { layer ->
            val localTime = (_playhead.value - layer.startTime).coerceAtLeast(0.0)
            val propertyValue = propertyOf(layer, property)
            val next: Animatable<Vec2> = if (animated || propertyValue.isAnimated) {
                AnimatableOps.setKeyframe(
                    propertyValue as Animatable<Vec2>,
                    Vec2Interpolator,
                    localTime,
                    Vec2(x, y)
                )
            } else {
                StaticValue(Vec2(x, y))
            }
            withProperty(layer, property, next)
        }
    }

    fun setTransformScalar(layerId: String, property: TransformProperty, value: Double, animated: Boolean) {
        if (property.isVec2) return
        updateLayer(layerId, "Change ${property.label}", "transform:$layerId:$property") { layer ->
            val localTime = (_playhead.value - layer.startTime).coerceAtLeast(0.0)
            val propertyValue = propertyOf(layer, property) as Animatable<Double>
            val next: Animatable<Double> = if (animated || propertyValue.isAnimated) {
                AnimatableOps.setKeyframe(
                    propertyValue,
                    interpolatorFor<Double>(property),
                    localTime,
                    value
                )
            } else {
                StaticValue(value)
            }
            withProperty(layer, property, next)
        }
    }

    fun setAnchor(layerId: String, anchor: Vec2) =
        updateLayer(layerId, "Anchor", "anchor:$layerId") { it.copy(transform = it.transform.copy(anchor = anchor)) }

    /** Adds or removes a keyframe at the playhead for a transform property. */
    fun toggleKeyframe(layerId: String, property: TransformProperty) {
        updateLayer(layerId, "Toggle keyframe") { layer ->
            val localTime = (_playhead.value - layer.startTime).coerceAtLeast(0.0)
            val propertyValue = propertyOf(layer, property)
            val updated: Animatable<*> = if (propertyValue.isAnimated && propertyValue.keyframes.any { kotlin.math.abs(it.time - localTime) < 1e-3 }) {
                AnimatableOps.removeKeyframe(propertyValue, localTime)
            } else if (property.isVec2) {
                val typed = propertyValue as Animatable<Vec2>
                AnimatableOps.setKeyframe(typed, Vec2Interpolator, localTime, typed.valueAt(localTime))
            } else if (property == TransformProperty.ROTATION) {
                val typed = propertyValue as Animatable<Double>
                AnimatableOps.setKeyframe(typed, AngleInterpolator, localTime, typed.valueAt(localTime))
            } else {
                val typed = propertyValue as Animatable<Double>
                AnimatableOps.setKeyframe(typed, ScalarInterpolator, localTime, typed.valueAt(localTime))
            }
            withProperty(layer, property, updated)
        }
    }

    fun setKeyframeInterpolation(layerId: String, property: TransformProperty, time: Double, interpolation: Interpolation) {
        updateLayer(layerId, "Easing") { layer ->
            val value = propertyOf(layer, property)
            withProperty(layer, property, AnimatableOps.setInterpolation(value, time, interpolation))
        }
    }

    fun moveKeyframe(layerId: String, property: TransformProperty, fromTime: Double, toTime: Double) {
        updateLayer(layerId, "Move keyframe", "kfmove:$layerId:$property") { layer ->
            val value = propertyOf(layer, property)
            withProperty(layer, property, AnimatableOps.moveKeyframe(value, fromTime, toTime))
        }
    }

    fun deleteKeyframe(layerId: String, property: TransformProperty, time: Double) {
        updateLayer(layerId, "Delete keyframe") { layer ->
            val value = propertyOf(layer, property)
            withProperty(layer, property, AnimatableOps.removeKeyframe(value, time))
        }
    }

    fun keyframeTimes(layerId: String): List<Double> {
        val layer = _project.value.layer(layerId) ?: return emptyList()
        return com.vynox.core.timeline.LayerProperties.keyframeTimes(layer)
    }

    fun hasKeyframes(layerId: String): Boolean = keyframeTimes(layerId).isNotEmpty()

    // ----------------------------------------------------------------- content

    fun updateText(layerId: String, coalesceKey: String? = null, block: (LayerContent.TextContent) -> LayerContent.TextContent) {
        updateLayer(layerId, "Edit text", coalesceKey) { layer ->
            val content = layer.content as? LayerContent.TextContent ?: return@updateLayer layer
            layer.copy(content = block(content))
        }
        sources?.invalidateText()
    }

    fun updateShape(layerId: String, coalesceKey: String? = null, block: (LayerContent.ShapeContent) -> LayerContent.ShapeContent) {
        updateLayer(layerId, "Edit shape", coalesceKey) { layer ->
            val content = layer.content as? LayerContent.ShapeContent ?: return@updateLayer layer
            layer.copy(content = block(content))
        }
    }

    fun updateLayerSpeed(layerId: String, speed: Double) =
        updateLayer(layerId, "Speed") { it.copy(speed = speed.coerceIn(0.1, 8.0)) }

    fun updateImageFit(layerId: String, fit: ContentFit) {
        updateLayer(layerId, "Fit") { layer ->
            val content = layer.content as? LayerContent.ImageContent ?: return@updateLayer layer
            layer.copy(content = content.copy(fit = fit))
        }
    }

    fun setMediaVolume(layerId: String, volume: Double) {
        updateLayer(layerId, "Volume", "volume:$layerId") { layer ->
            when (val content = layer.content) {
                is LayerContent.VideoContent -> layer.copy(content = content.copy(volume = StaticValue(volume)))
                is LayerContent.AudioContent -> layer.copy(content = content.copy(volume = StaticValue(volume)))
                else -> layer
            }
        }
    }

    // ----------------------------------------------------------------- effects

    fun addEffect(layerId: String, typeId: String) {
        val effect = EffectRegistry.create(typeId) ?: return
        updateLayer(layerId, "Add effect") { it.withEffect(effect) }
        _tab.value = InspectorTab.EFFECTS
    }

    fun removeEffect(layerId: String, effectId: String) =
        updateLayer(layerId, "Remove effect") { it.withoutEffect(effectId) }

    fun toggleEffect(layerId: String, effectId: String) =
        updateLayer(layerId, "Toggle effect") { layer ->
            val effect = layer.effect(effectId) ?: return@updateLayer layer
            layer.withReplacedEffect(effect.copy(enabled = !effect.enabled))
        }

    fun moveEffect(layerId: String, effectId: String, newIndex: Int) =
        commit("Reorder effect", TimelineOperations.moveEffect(_project.value, layerId, effectId, newIndex))

    fun setEffectParamValue(layerId: String, effectId: String, param: String, value: Double, animated: Boolean) {
        updateLayer(layerId, "Effect parameter", "fx:$layerId:$effectId:$param") { layer ->
            val effect = layer.effect(effectId) ?: return@updateLayer layer
            val localTime = (_playhead.value - layer.startTime).coerceAtLeast(0.0)
            val current = effect.parameters[param]
            val next: Animatable<*> = if (current != null && (animated || current.isAnimated)) {
                AnimatableOps.setKeyframe(current as Animatable<Double>, ScalarInterpolator, localTime, value)
            } else {
                StaticValue(value)
            }
            layer.withReplacedEffect(effect.copy(parameters = effect.parameters + (param to next)))
        }
    }

    fun setEffectParamColor(layerId: String, effectId: String, param: String, color: Color) {
        updateLayer(layerId, "Effect colour", "fxc:$layerId:$effectId:$param") { layer ->
            val effect = layer.effect(effectId) ?: return@updateLayer layer
            layer.withReplacedEffect(
                effect.copy(parameters = effect.parameters + (param to StaticValue(color)))
            )
        }
    }

    fun toggleEffectKeyframe(layerId: String, effectId: String, param: String) {
        updateLayer(layerId, "Toggle effect keyframe") { layer ->
            val effect = layer.effect(effectId) ?: return@updateLayer layer
            val localTime = (_playhead.value - layer.startTime).coerceAtLeast(0.0)
            val current = effect.parameters[param] ?: return@updateLayer layer
            val hasKey = current.keyframes.any { kotlin.math.abs(it.time - localTime) < 1e-3 }
            val next = if (hasKey) {
                AnimatableOps.removeKeyframe(current, localTime)
            } else {
                AnimatableOps.setKeyframe(current, interpolatorForParam(current), localTime, current.valueAt(localTime))
            }
            layer.withReplacedEffect(effect.copy(parameters = effect.parameters + (param to next)))
        }
    }

    private fun <T> interpolatorForParam(value: Animatable<T>): ValueInterpolator<T> =
        when (value.valueAt(0.0)) {
            is Color -> ColorInterpolator
            is Vec2 -> Vec2Interpolator
            else -> ScalarInterpolator
        } as ValueInterpolator<T>

    // ------------------------------------------------------------------- masks

    fun addMask(layerId: String, shape: MaskShape) {
        val project = _project.value
        val layer = project.layer(layerId) ?: return
        val size = if (shape == MaskShape.ELLIPSE) Vec2(600.0, 600.0) else Vec2(project.canvas.width * 0.6, project.canvas.height * 0.6)
        val mask = Mask(
            id = Ids.next("mask"),
            name = shape.name.lowercase().replaceFirstChar { it.uppercase() },
            shape = shape,
            position = Vec2(project.canvas.width / 2.0, project.canvas.height / 2.0),
            size = size
        )
        commit("Add mask", TimelineOperations.addMask(project, layerId, mask))
        _tab.value = InspectorTab.MASKS
    }

    fun updateMask(layerId: String, mask: Mask) =
        updateLayer(layerId, "Edit mask", "mask:$layerId:${mask.id}") { it.withReplacedMask(mask) }

    fun removeMask(layerId: String, maskId: String) =
        updateLayer(layerId, "Remove mask") { it.withoutMask(maskId) }

    // ------------------------------------------------------------------ assets

    fun refreshMissingAssets() {
        _missingAssets.value = AssetResolution.missing(_project.value) { asset ->
            VynoxServices.assetStore.exists(asset)
        }
    }

    fun assetUri(assetId: String): String? = _project.value.asset(assetId)?.uri

    fun importMedia(uri: Uri, typeHint: AssetType? = null): Asset {
        val name = VynoxServices.mediaProbe.displayName(uri)
        val type = typeHint ?: VynoxServices.mediaProbe.guessType(uri, name)
        val probe = VynoxServices.mediaProbe.probe(uri)
        val asset = VynoxServices.assetStore.import(uri, name, type, probe)
        val project = _project.value
        replace(project.copy(assets = project.assets.filterNot { it.id == asset.id } + asset))
        refreshMissingAssets()
        return asset
    }

    fun relinkAsset(assetId: String, uri: Uri) {
        val project = _project.value
        val updated = VynoxServices.projectStore.relinkAsset(project, assetId, uri)
        commit("Relink asset", updated)
        refreshMissingAssets()
        sources?.invalidate(assetId)
    }

    // ------------------------------------------------------------ persistence

    fun save(packAssets: Boolean = false, onSaved: (File) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val preview = frameCapture?.invoke()?.let { VynoxServices.projectStore.encodePreview(it) }
            val settings = VynoxServices.settingsStore.settings.first()
            val file = VynoxServices.projectStore.save(
                project = _project.value,
                packAssets = packAssets || settings.packAssetsOnSave,
                previewPng = preview
            )
            currentFile = file
            withContext(Dispatchers.Main) {
                _message.emit("Saved ${file.name}")
                onSaved(file)
            }
        }
    }

    private fun startAutosave(intervalSeconds: Int) {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(intervalSeconds * 1000L)
                if (_project.value.layers.isEmpty() && currentFile == null) continue
                val preview = withContext(Dispatchers.Main) { frameCapture?.invoke() }
                    ?.let { VynoxServices.projectStore.encodePreview(it) }
                VynoxServices.projectStore.autosave(_project.value, preview)
            }
        }
    }

    // ------------------------------------------------------------------ export

    private var exporter: VideoExporter? = null

    fun export(settings: ExportSettings, onComplete: (File?) -> Unit) {
        if (_isExporting.value) return
        _isExporting.value = true
        _exportProgress.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val engine = VideoExporter(context)
            exporter = engine
            val file = engine.export(
                project = _project.value,
                settings = settings,
                assets = { assetId -> assetUri(assetId) },
                onProgress = { progress -> _exportProgress.value = progress }
            )
            withContext(Dispatchers.Main) {
                _isExporting.value = false
                exporter = null
                onComplete(file)
            }
        }
    }

    fun cancelExport() {
        exporter?.cancelRequested = true
    }

    fun estimateExportSize(settings: ExportSettings): Long =
        VideoExporter(context).estimateSizeBytes(_project.value, settings)

    override fun onCleared() {
        super.onCleared()
        pause()
        autosaveJob?.cancel()
        sources?.release()
    }
}
