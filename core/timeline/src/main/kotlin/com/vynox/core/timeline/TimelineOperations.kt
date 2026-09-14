package com.vynox.core.timeline

import com.vynox.core.model.BlendMode
import com.vynox.core.model.Canvas
import com.vynox.core.model.EffectInstance
import com.vynox.core.model.Ids
import com.vynox.core.model.Layer
import com.vynox.core.model.LayerLabel
import com.vynox.core.model.Marker
import com.vynox.core.model.VynoxProject
import com.vynox.core.model.audioContent
import com.vynox.core.model.videoContent
import com.vynox.core.model.volumeAt
import kotlin.math.max
import kotlin.math.min

/**
 * Every structural edit the editor can perform.
 *
 * Operations are pure: they take a project and return a new one, never touching
 * files, decoders or UI state. Undo/redo, autosave and tests all go through
 * this single API, so a behaviour verified in a unit test is exactly the
 * behaviour the UI performs.
 */
object TimelineOperations {

    private const val MIN_DURATION = 1.0 / 30.0

    private fun touch(project: VynoxProject): VynoxProject =
        project.copy(meta = project.meta.copy(modifiedAt = System.currentTimeMillis()))

    private fun replace(project: VynoxProject, layer: Layer): VynoxProject =
        touch(project).withReplacedLayer(layer)

    private fun minDuration(project: VynoxProject): Double =
        min(MIN_DURATION, 1.0 / project.canvas.fps.toDouble().coerceAtLeast(1.0))

    // ------------------------------------------------------------------ layers

    fun addLayer(project: VynoxProject, layer: Layer, index: Int = 0): VynoxProject {
        val layers = project.layers.toMutableList()
        layers.add(index.coerceIn(0, layers.size), layer)
        return touch(project).withLayers(layers)
    }

    fun removeLayer(project: VynoxProject, layerId: String): VynoxProject {
        val layers = project.layers.filterNot { it.id == layerId }
        // Children of a removed group are re-parented to keep the project valid.
        val orphanFixed = layers.map { if (it.parentId == layerId) it.copy(parentId = null) else it }
        return touch(project).withLayers(orphanFixed)
    }

    fun duplicateLayer(project: VynoxProject, layerId: String): VynoxProject {
        val source = project.layer(layerId) ?: return project
        val index = project.indexOfLayer(layerId)
        val copy = source.copy(
            id = Ids.next("layer"),
            name = nextCopyName(project, source.name),
            effects = source.effects.map { it.copy(id = Ids.next("fx")) },
            masks = source.masks.map { it.copy(id = Ids.next("mask")) }
        )
        val layers = project.layers.toMutableList()
        layers.add(index.coerceIn(0, layers.size), copy)
        return touch(project).withLayers(layers)
    }

    private fun nextCopyName(project: VynoxProject, base: String): String {
        var index = 2
        while (project.layers.any { it.name == "$base $index" }) index++
        return "$base $index"
    }

    fun reorderLayer(project: VynoxProject, layerId: String, newIndex: Int): VynoxProject {
        val layers = project.layers.toMutableList()
        val current = layers.indexOfFirst { it.id == layerId }
        if (current < 0) return project
        val target = newIndex.coerceIn(0, layers.size - 1)
        if (current == target) return project
        val layer = layers.removeAt(current)
        layers.add(target, layer)
        return touch(project).withLayers(layers)
    }

    fun moveLayerForward(project: VynoxProject, layerId: String): VynoxProject {
        val index = project.indexOfLayer(layerId)
        return if (index <= 0) project else reorderLayer(project, layerId, index - 1)
    }

    fun moveLayerBackward(project: VynoxProject, layerId: String): VynoxProject {
        val index = project.indexOfLayer(layerId)
        return if (index < 0 || index >= project.layers.lastIndex) project
        else reorderLayer(project, layerId, index + 1)
    }

    fun moveLayerInTime(project: VynoxProject, layerId: String, newStart: Double): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        return replace(project, layer.copy(startTime = max(0.0, newStart)))
    }

    fun nudgeLayer(project: VynoxProject, layerId: String, delta: Double): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        return moveLayerInTime(project, layerId, max(0.0, layer.startTime + delta))
    }

    /** Trims the in point, keeping the visible content anchored to the same frames. */
    fun trimLayerStart(project: VynoxProject, layerId: String, newStart: Double): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        val minDuration = minDuration(project)
        val lowerBound = layer.startTime - (layer.sourceIn / layer.speed.coerceAtLeast(0.01))
        val upperBound = layer.endTime - minDuration
        val clamped = newStart.coerceIn(max(0.0, lowerBound), max(lowerBound + minDuration, upperBound))
        val delta = clamped - layer.startTime
        val newDuration = (layer.duration - delta).coerceAtLeast(minDuration)
        val newSourceIn = (layer.sourceIn + delta * layer.speed).coerceAtLeast(0.0)
        val shifted = LayerProperties.shiftKeyframes(layer, -delta)
        return replace(project, shifted.copy(
            startTime = clamped,
            duration = newDuration,
            sourceIn = newSourceIn,
            transform = shifted.transform
        ).let { LayerProperties.trimKeyframes(it, 0.0, newDuration) })
    }

    /** Trims the out point. */
    fun trimLayerEnd(project: VynoxProject, layerId: String, newEnd: Double): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        val minDuration = minDuration(project)
        val clamped = newEnd.coerceIn(layer.startTime + minDuration, layer.endTime)
        val newDuration = clamped - layer.startTime
        return replace(project, LayerProperties.trimKeyframes(layer.copy(duration = newDuration), 0.0, newDuration))
    }

    fun setLayerDuration(project: VynoxProject, layerId: String, duration: Double): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        val minDuration = minDuration(project)
        val newDuration = duration.coerceAtLeast(minDuration)
        return replace(project, LayerProperties.trimKeyframes(layer.copy(duration = newDuration), 0.0, newDuration))
    }

    /** Splits a layer at [time] (composition time) into two independent clips. */
    fun splitLayer(project: VynoxProject, layerId: String, time: Double): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        val minDuration = minDuration(project)
        if (time <= layer.startTime + minDuration || time >= layer.endTime - minDuration) return project

        val localSplit = time - layer.startTime
        val leftDuration = localSplit
        val rightDuration = layer.duration - localSplit

        val left = LayerProperties.trimKeyframes(layer.copy(duration = leftDuration), 0.0, leftDuration)
        val rightSource = LayerProperties.shiftKeyframes(layer, -localSplit)
        val right = LayerProperties.trimKeyframes(
            rightSource.copy(
                id = Ids.next("layer"),
                name = nextCopyName(project, layer.name),
                startTime = time,
                duration = rightDuration,
                sourceIn = layer.sourceIn + localSplit * layer.speed,
                effects = layer.effects.map { it.copy(id = Ids.next("fx")) },
                masks = layer.masks.map { it.copy(id = Ids.next("mask")) }
            ),
            0.0,
            rightDuration
        )

        val layers = project.layers.toMutableList()
        val index = layers.indexOfFirst { it.id == layerId }
        layers[index] = left
        layers.add(index + 1, right)
        return touch(project).withLayers(layers)
    }

    // ------------------------------------------------------------- attributes

    fun setLayerName(project: VynoxProject, layerId: String, name: String): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        return replace(project, layer.copy(name = name))
    }

    fun setLayerEnabled(project: VynoxProject, layerId: String, enabled: Boolean): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        return replace(project, layer.copy(enabled = enabled))
    }

    fun toggleLayerEnabled(project: VynoxProject, layerId: String): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        return setLayerEnabled(project, layerId, !layer.enabled)
    }

    fun setLayerLocked(project: VynoxProject, layerId: String, locked: Boolean): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        return replace(project, layer.copy(locked = locked))
    }

    fun toggleLayerLocked(project: VynoxProject, layerId: String): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        return setLayerLocked(project, layerId, !layer.locked)
    }

    fun setLayerMuted(project: VynoxProject, layerId: String, muted: Boolean): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        return replace(project, layer.copy(muted = muted))
    }

    fun toggleLayerMuted(project: VynoxProject, layerId: String): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        return setLayerMuted(project, layerId, !layer.muted)
    }

    fun setLayerSolo(project: VynoxProject, layerId: String, solo: Boolean): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        return replace(project, layer.copy(solo = solo))
    }

    fun setBlendMode(project: VynoxProject, layerId: String, blendMode: BlendMode): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        return replace(project, layer.copy(blendMode = blendMode))
    }

    fun setLayerLabel(project: VynoxProject, layerId: String, label: LayerLabel): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        return replace(project, layer.copy(label = label))
    }

    fun setLayerSpeed(project: VynoxProject, layerId: String, speed: Double): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        val safeSpeed = speed.coerceIn(0.1, 8.0)
        val newDuration = (layer.duration * (layer.speed / safeSpeed)).coerceAtLeast(minDuration(project))
        return replace(project, layer.copy(speed = safeSpeed, duration = newDuration))
    }

    fun setLayerParent(project: VynoxProject, layerId: String, parentId: String?): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        if (parentId == layer.id) return project
        if (parentId != null) {
            // Reject cycles by walking up the prospective parent chain.
            var cursor: String? = parentId
            var guard = 0
            while (cursor != null && guard++ < 64) {
                if (cursor == layer.id) return project
                cursor = project.layer(cursor)?.parentId
            }
        }
        return replace(project, layer.copy(parentId = parentId))
    }

    // ---------------------------------------------------------------- effects

    fun addEffect(project: VynoxProject, layerId: String, effect: EffectInstance): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        return replace(project, layer.withEffect(effect))
    }

    fun removeEffect(project: VynoxProject, layerId: String, effectId: String): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        return replace(project, layer.withoutEffect(effectId))
    }

    fun replaceEffect(project: VynoxProject, layerId: String, effect: EffectInstance): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        return replace(project, layer.withReplacedEffect(effect))
    }

    fun moveEffect(project: VynoxProject, layerId: String, effectId: String, newIndex: Int): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        val effects = layer.effects.toMutableList()
        val current = effects.indexOfFirst { it.id == effectId }
        if (current < 0) return project
        val effect = effects.removeAt(current)
        effects.add(newIndex.coerceIn(0, effects.size), effect)
        return replace(project, layer.copy(effects = effects))
    }

    // ------------------------------------------------------------------ masks

    fun addMask(project: VynoxProject, layerId: String, mask: com.vynox.core.model.Mask): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        return replace(project, layer.withMask(mask))
    }

    fun removeMask(project: VynoxProject, layerId: String, maskId: String): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        return replace(project, layer.withoutMask(maskId))
    }

    fun replaceMask(project: VynoxProject, layerId: String, mask: com.vynox.core.model.Mask): VynoxProject {
        val layer = project.layer(layerId) ?: return project
        return replace(project, layer.withReplacedMask(mask))
    }

    // ---------------------------------------------------------------- markers

    fun addMarker(project: VynoxProject, time: Double, label: String = "Marker"): VynoxProject =
        touch(project).copy(markers = (project.markers + Marker(Ids.next("marker"), time.coerceAtLeast(0.0), label)).sortedBy { it.time })

    fun removeMarker(project: VynoxProject, markerId: String): VynoxProject =
        touch(project).copy(markers = project.markers.filterNot { it.id == markerId })

    // ------------------------------------------------------------ composition

    fun setCanvas(project: VynoxProject, canvas: Canvas): VynoxProject =
        touch(project).copy(canvas = canvas)

    fun setProjectDuration(project: VynoxProject, duration: Double): VynoxProject =
        touch(project).copy(canvas = project.canvas.copy(duration = duration.coerceAtLeast(MIN_DURATION)))

    /** Trims the project to the outermost layer content. */
    fun fitToContent(project: VynoxProject): VynoxProject {
        val end = project.layers.maxOfOrNull { it.endTime } ?: project.canvas.duration
        return setProjectDuration(project, end)
    }

    /** Audio gain of a layer at composition time (used by meters and export). */
    fun gainAt(project: VynoxProject, layerId: String, time: Double): Double {
        val layer = project.layer(layerId) ?: return 0.0
        if (!layer.isActiveAt(time)) return 0.0
        return when {
            layer.muted -> 0.0
            layer.audioContent()?.muted == true -> 0.0
            layer.videoContent()?.muted == true -> 0.0
            else -> layer.volumeAt(time)
        }
    }
}
