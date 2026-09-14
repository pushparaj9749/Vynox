package com.vynox.core.animation

import com.vynox.core.math.Color
import com.vynox.core.math.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class InterpolationTest {

    @Test
    fun linearIsIdentity() {
        assertEquals(0.0, Interpolation.LINEAR.ease(0.0), 1e-9)
        assertEquals(0.25, Interpolation.LINEAR.ease(0.25), 1e-9)
        assertEquals(1.0, Interpolation.LINEAR.ease(1.0), 1e-9)
        assertEquals(1.0, Interpolation.LINEAR.ease(2.0), 1e-9)
    }

    @Test
    fun holdNeverAdvances() {
        assertEquals(0.0, Interpolation.HOLD.ease(0.99), 1e-9)
        assertEquals(0.0, Interpolation.HOLD.ease(1.0), 1e-9)
    }

    @Test
    fun easeInIsSlowerAtTheStart() {
        val easeIn = Interpolation.fromPreset("ease_in")
        assertTrue(easeIn.ease(0.5) < 0.5)
        assertEquals(0.0, easeIn.ease(0.0), 1e-9)
        assertEquals(1.0, easeIn.ease(1.0), 1e-6)
    }

    @Test
    fun easeOutIsFasterAtTheStart() {
        val easeOut = Interpolation.fromPreset("ease_out")
        assertTrue(easeOut.ease(0.25) > 0.25)
        assertEquals(1.0, easeOut.ease(1.0), 1e-6)
    }

    @Test
    fun easeInOutIsSymmetric() {
        val ease = Interpolation.fromPreset("ease_in_out")
        assertTrue(abs(ease.ease(0.5) - 0.5) < 0.05)
        assertTrue(ease.ease(0.25) < 0.25)
        assertTrue(ease.ease(0.75) > 0.75)
    }

    @Test
    fun bezierCurveIsSolvedAccurately() {
        // A curve whose x is not the identity forces the Newton/bisection solver.
        val curve = Interpolation.bezier(0.9, 0.0, 0.1, 1.0)
        val sampled = (0..20).map { i -> curve.ease(i / 20.0) }
        sampled.forEachIndexed { index, value ->
            assertTrue("value out of range: $value", value >= -1e-6 && value <= 1.0001)
            if (index > 0) assertTrue("not monotonic at $index", value >= sampled[index - 1] - 1e-6)
        }
        assertEquals(0.0, curve.ease(0.0), 1e-6)
        assertEquals(1.0, curve.ease(1.0), 1e-6)
    }

    @Test
    fun overshootCurvesCanExceedOne() {
        val back = Interpolation.fromPreset("ease_out_back")
        assertTrue(back.ease(0.6) > 1.0)
    }

    @Test
    fun presetNamesRoundTrip() {
        Interpolation.PRESETS.forEach { (name, interpolation) ->
            assertEquals(name, Interpolation.presetName(interpolation))
        }
        assertEquals("custom", Interpolation.presetName(Interpolation.bezier(0.3, 0.7, 0.2, 0.9)))
        assertEquals("linear", Interpolation.presetName(Interpolation.LINEAR))
    }

    @Test
    fun unknownPresetFallsBackToLinear() {
        assertEquals(Interpolation.LINEAR, Interpolation.fromPreset("does_not_exist"))
    }

    @Test
    fun angleInterpolatorTakesShortestPath() {
        val value = AngleInterpolator.interpolate(350.0, 10.0, 0.5)
        assertEquals(360.0, value, 1e-9)
        assertEquals(10.0, AngleInterpolator.interpolate(350.0, 10.0, 1.0), 1e-9)
    }

    @Test
    fun colorInterpolatorBlendsChannels() {
        val mid = ColorInterpolator.interpolate(Color.BLACK, Color.WHITE, 0.5)
        assertTrue(mid.r > 0.0 && mid.r < 1.0)
        assertEquals(1.0, mid.a, 1e-9)
    }

    @Test
    fun vec2InterpolatorBlendsComponents() {
        assertEquals(Vec2(5.0, 10.0), Vec2Interpolator.interpolate(Vec2.ZERO, Vec2(10.0, 20.0), 0.5))
    }
}
