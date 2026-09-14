package com.vynox.core.animation

import com.vynox.core.math.clamp
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Easing / interpolation model shared by every animated property in Vynox.
 *
 * A segment between two keyframes is described by a cubic Bezier curve in
 * normalized space (x = normalized time, y = normalized value). Linear and hold
 * are special cases; everything else (ease in, ease out, ease in-out, custom)
 * is expressed as control points, which is exactly what the graph editor edits.
 */
enum class InterpolationKind { LINEAR, HOLD, BEZIER }

data class BezierHandles(val cx1: Double, val cy1: Double, val cx2: Double, val cy2: Double) {
    fun isLinear(): Boolean =
        abs(cx1 - cy1) < 1e-6 && abs(cx2 - cy2) < 1e-6

    companion object {
        val LINEAR = BezierHandles(0.0, 0.0, 1.0, 1.0)
    }
}

data class Interpolation(
    val kind: InterpolationKind = InterpolationKind.LINEAR,
    val handles: BezierHandles = BezierHandles.LINEAR
) {
    val isHold: Boolean get() = kind == InterpolationKind.HOLD
    val isLinear: Boolean get() = kind == InterpolationKind.LINEAR
    val isBezier: Boolean get() = kind == InterpolationKind.BEZIER

    /** Eased progress (0..1) for a raw linear progress between two keyframes. */
    fun ease(progress: Double): Double = when (kind) {
        InterpolationKind.HOLD -> 0.0
        InterpolationKind.LINEAR -> clamp(progress, 0.0, 1.0)
        InterpolationKind.BEZIER -> cubicBezierY(handles, clamp(progress, 0.0, 1.0))
    }

    companion object {
        val LINEAR = Interpolation(InterpolationKind.LINEAR, BezierHandles.LINEAR)
        val HOLD = Interpolation(InterpolationKind.HOLD, BezierHandles.LINEAR)

        /** Named easing presets exposed in the editor UI and in .vnx files. */
        val PRESETS: Map<String, Interpolation> = linkedMapOf(
            "linear" to Interpolation(InterpolationKind.LINEAR, BezierHandles.LINEAR),
            "hold" to HOLD,
            "ease_in" to bezier(0.42, 0.0, 1.0, 1.0),
            "ease_out" to bezier(0.0, 0.0, 0.58, 1.0),
            "ease_in_out" to bezier(0.42, 0.0, 0.58, 1.0),
            "ease_in_quad" to bezier(0.55, 0.085, 0.68, 0.53),
            "ease_out_quad" to bezier(0.25, 0.46, 0.45, 0.94),
            "ease_in_out_quad" to bezier(0.455, 0.03, 0.515, 0.955),
            "ease_in_cubic" to bezier(0.55, 0.055, 0.675, 0.19),
            "ease_out_cubic" to bezier(0.215, 0.61, 0.355, 1.0),
            "ease_in_out_cubic" to bezier(0.645, 0.045, 0.355, 1.0),
            "ease_out_back" to bezier(0.175, 0.885, 0.32, 1.275),
            "ease_out_elastic" to bezier(0.16, 1.4, 0.3, 1.0),
            "ease_out_bounce" to bezier(0.28, 1.6, 0.42, 1.0)
        )

        fun bezier(cx1: Double, cy1: Double, cx2: Double, cy2: Double) =
            Interpolation(InterpolationKind.BEZIER, BezierHandles(cx1, cy1, cx2, cy2))

        fun fromPreset(name: String?): Interpolation =
            PRESETS[name] ?: LINEAR

        fun presetName(interpolation: Interpolation): String =
            PRESETS.entries.firstOrNull { (_, v) -> v.handles == interpolation.handles && v.kind == interpolation.kind }?.key
                ?: if (interpolation.kind == InterpolationKind.BEZIER) "custom" else interpolation.kind.name.lowercase()

        /**
         * Solves x(t) = progress for the cubic Bezier and returns y(t).
         * Uses Newton-Raphson with a bisection fallback for robustness on
         * curves with overshoot (control points outside 0..1).
         */
        fun cubicBezierY(handles: BezierHandles, progress: Double): Double {
            if (progress <= 0.0) return 0.0
            if (progress >= 1.0) return 1.0
            val x1 = handles.cx1
            val x2 = handles.cx2
            if (x1 == 0.0 && x2 == 1.0) {
                // Fast path: x(t) == t, so only y has to be evaluated.
                return bezierComponent(progress, handles.cy1, handles.cy2)
            }
            var t = progress
            for (i in 0 until 8) {
                val x = bezierComponent(t, x1, x2) - progress
                if (abs(x) < 1e-7) return bezierComponent(t, handles.cy1, handles.cy2)
                val d = bezierDerivative(t, x1, x2)
                if (abs(d) < 1e-9) break
                t -= x / d
            }
            // Bisection fallback guarantees convergence for pathological curves.
            var low = 0.0
            var high = 1.0
            t = progress
            for (i in 0 until 32) {
                val x = bezierComponent(t, x1, x2)
                if (abs(x - progress) < 1e-7) break
                if (x < progress) low = t else high = t
                t = (low + high) / 2.0
            }
            return bezierComponent(t, handles.cy1, handles.cy2)
        }

        /** Cubic Bezier component with implicit 0 and 1 endpoints. */
        fun bezierComponent(t: Double, p1: Double, p2: Double): Double {
            val u = 1.0 - t
            return 3.0 * u * u * t * p1 + 3.0 * u * t * t * p2 + t * t * t
        }

        fun bezierDerivative(t: Double, p1: Double, p2: Double): Double {
            val u = 1.0 - t
            return 3.0 * u * u * p1 + 6.0 * u * t * (p2 - p1) + 3.0 * t * t * (1.0 - p2)
        }

        fun distance(a: Interpolation, b: Interpolation): Double {
            if (a.kind != b.kind) return Double.POSITIVE_INFINITY
            val dx1 = a.handles.cx1 - b.handles.cx1
            val dy1 = a.handles.cy1 - b.handles.cy1
            val dx2 = a.handles.cx2 - b.handles.cx2
            val dy2 = a.handles.cy2 - b.handles.cy2
            return sqrt(dx1 * dx1 + dy1 * dy1 + dx2 * dx2 + dy2 * dy2)
        }
    }
}
