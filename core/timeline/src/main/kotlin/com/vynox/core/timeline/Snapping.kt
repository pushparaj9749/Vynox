package com.vynox.core.timeline

import com.vynox.core.model.Layer
import com.vynox.core.model.VynoxProject
import kotlin.math.abs

data class SnapResult(
    val time: Double,
    val target: Double?,
    val kind: SnapKind
)

enum class SnapKind { NONE, LAYER_EDGE, PLAYHEAD, ZERO, MARKER, FRAME }

/**
 * Timeline snapping.
 *
 * Candidates are every layer edge, marker, the playhead, zero and (for export
 * safe edits) frame boundaries. Snapping is pure arithmetic so it is identical
 * whether the drag came from the touchscreen or from an automated test.
 */
object Snapping {

    fun candidates(
        project: VynoxProject,
        excludeLayerIds: Set<String> = emptySet(),
        playhead: Double? = null
    ): List<Pair<Double, SnapKind>> {
        val result = ArrayList<Pair<Double, SnapKind>>()
        result += 0.0 to SnapKind.ZERO
        project.layers.forEach { layer ->
            if (layer.id in excludeLayerIds) return@forEach
            result += layer.startTime to SnapKind.LAYER_EDGE
            result += layer.endTime to SnapKind.LAYER_EDGE
        }
        project.markers.forEach { result += it.time to SnapKind.MARKER }
        if (playhead != null) result += playhead to SnapKind.PLAYHEAD
        return result
    }

    fun snap(
        project: VynoxProject,
        time: Double,
        tolerance: Double = project.settings.snapToleranceSeconds,
        excludeLayerIds: Set<String> = emptySet(),
        playhead: Double? = null
    ): SnapResult {
        if (!project.settings.snapEnabled) return SnapResult(time, null, SnapKind.NONE)
        var best: Pair<Double, SnapKind>? = null
        var bestDistance = tolerance
        for (candidate in candidates(project, excludeLayerIds, playhead)) {
            val distance = abs(candidate.first - time)
            if (distance <= bestDistance) {
                bestDistance = distance
                best = candidate
            }
        }
        return if (best == null) SnapResult(time, null, SnapKind.NONE)
        else SnapResult(best.first, best.first, best.second)
    }

    /** Snaps a layer drag: both the in point and the out point can snap. */
    fun snapMove(
        project: VynoxProject,
        layer: Layer,
        newStart: Double,
        tolerance: Double = project.settings.snapToleranceSeconds,
        playhead: Double? = null
    ): SnapResult {
        val duration = layer.duration
        val startSnap = snap(project, newStart, tolerance, setOf(layer.id), playhead)
        val endSnap = snap(project, newStart + duration, tolerance, setOf(layer.id), playhead)
        return when {
            startSnap.target != null && endSnap.target != null ->
                if (abs(startSnap.target!! - newStart) <= abs(endSnap.target!! - (newStart + duration))) startSnap
                else SnapResult(endSnap.target!! - duration, endSnap.target, endSnap.kind)
            startSnap.target != null -> startSnap
            endSnap.target != null -> SnapResult(endSnap.target!! - duration, endSnap.target, endSnap.kind)
            else -> SnapResult(newStart, null, SnapKind.NONE)
        }
    }
}
