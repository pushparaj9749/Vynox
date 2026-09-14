package com.vynox.core.animation

import com.vynox.core.math.Color
import com.vynox.core.math.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeTest {

    private fun scalarTrack(vararg pairs: Pair<Double, Double>): KeyframeTrack<Double> =
        KeyframeTrack(ScalarInterpolator, pairs.map { Keyframe(it.first, it.second) })

    @Test
    fun emptyTrackReturnsFallback() {
        val track = KeyframeTrack(ScalarInterpolator)
        assertEquals(7.0, track.valueAt(0.0, 7.0), 0.0)
        assertEquals(7.0, track.valueAt(10.0, 7.0), 0.0)
    }

    @Test
    fun valuesAreHeldOutsideKeyframeRange() {
        val track = scalarTrack(1.0 to 10.0, 3.0 to 30.0)
        assertEquals(10.0, track.valueAt(-5.0, 0.0), 0.0)
        assertEquals(30.0, track.valueAt(99.0, 0.0), 0.0)
    }

    @Test
    fun linearInterpolationBetweenKeyframes() {
        val track = scalarTrack(0.0 to 0.0, 2.0 to 10.0)
        assertEquals(0.0, track.valueAt(0.0, 0.0), 1e-9)
        assertEquals(5.0, track.valueAt(1.0, 0.0), 1e-9)
        assertEquals(2.5, track.valueAt(0.5, 0.0), 1e-9)
        assertEquals(10.0, track.valueAt(2.0, 0.0), 1e-9)
    }

    @Test
    fun easingModifiesInterpolation() {
        val track = KeyframeTrack(
            ScalarInterpolator,
            listOf(
                Keyframe(0.0, 0.0),
                Keyframe(1.0, 10.0, Interpolation.fromPreset("ease_in"))
            )
        )
        val eased = track.valueAt(0.5, 0.0)
        val linear = 5.0
        assertTrue("eased value $eased should differ from linear", eased != linear)
        assertTrue(eased < linear)
    }

    @Test
    fun holdKeepsPreviousValue() {
        val track = KeyframeTrack(
            ScalarInterpolator,
            listOf(
                Keyframe(0.0, 0.0),
                Keyframe(1.0, 10.0, Interpolation.HOLD)
            )
        )
        assertEquals(0.0, track.valueAt(0.5, 0.0), 1e-9)
        assertEquals(0.0, track.valueAt(0.999, 0.0), 1e-9)
        assertEquals(10.0, track.valueAt(1.0, 0.0), 1e-9)
    }

    @Test
    fun addingKeyframeReplacesSameTime() {
        var track = scalarTrack(0.0 to 0.0)
        track = track.withKeyframe(0.0, 5.0)
        assertEquals(1, track.size)
        assertEquals(5.0, track.valueAt(0.0, 0.0), 0.0)
    }

    @Test
    fun keyframesStaySorted() {
        var track = KeyframeTrack(ScalarInterpolator)
        track = track.withKeyframe(5.0, 1.0).withKeyframe(1.0, 2.0).withKeyframe(3.0, 3.0)
        assertEquals(listOf(1.0, 3.0, 5.0), track.times)
    }

    @Test
    fun removingAndMovingKeyframes() {
        var track = scalarTrack(0.0 to 0.0, 1.0 to 10.0, 2.0 to 20.0)
        track = track.withoutKeyframe(1.0)
        assertEquals(2, track.size)
        track = track.moveKeyframe(2.0, 3.0)
        assertEquals(listOf(0.0, 3.0), track.times)
        assertEquals(20.0, track.valueAt(3.0, 0.0), 0.0)
    }

    @Test
    fun movingKeyframeOntoAnotherReplacesIt() {
        var track = scalarTrack(0.0 to 0.0, 1.0 to 10.0)
        track = track.moveKeyframe(0.0, 1.0)
        assertEquals(1, track.size)
        assertEquals(0.0, track.valueAt(1.0, 0.0), 0.0)
    }

    @Test
    fun trimRangeAddsBoundaryKeyframes() {
        val track = scalarTrack(0.0 to 0.0, 2.0 to 20.0, 4.0 to 40.0)
        val trimmed = track.trimToRange(1.0, 3.0, 0.0)
        assertEquals(listOf(1.0, 2.0, 3.0), trimmed.times)
        assertEquals(10.0, trimmed.valueAt(1.0, 0.0), 1e-9)
        assertEquals(30.0, trimmed.valueAt(3.0, 0.0), 1e-9)
    }

    @Test
    fun offsetShiftsEveryKeyframe() {
        val track = scalarTrack(0.0 to 0.0, 2.0 to 20.0).offsetBy(1.5)
        assertEquals(listOf(1.5, 3.5), track.times)
        assertEquals(20.0, track.valueAt(3.5, 0.0), 0.0)
    }

    @Test
    fun pasteKeyframesShiftsRelativeToBase() {
        val source = scalarTrack(0.0 to 0.0, 1.0 to 10.0)
        val target = KeyframeTrack(ScalarInterpolator).pasteKeyframes(source.keyframes, 5.0, 0.0)
        assertEquals(listOf(5.0, 6.0), target.times)
        assertEquals(10.0, target.valueAt(6.0, 0.0), 0.0)
    }

    @Test
    fun animatablePromotionAndMutation() {
        var property: Animatable<Double> = StaticValue(4.0)
        assertEquals(false, property.isAnimated)
        property = AnimatableOps.promote(property, ScalarInterpolator, 1.0)
        assertEquals(true, property.isAnimated)
        assertEquals(4.0, property.valueAt(1.0), 0.0)
        assertEquals(4.0, property.valueAt(0.0), 0.0)

        property = AnimatableOps.setKeyframe(property, ScalarInterpolator, 2.0, 8.0)
        assertEquals(2, property.keyframes.size)
        assertEquals(6.0, property.valueAt(1.5), 1e-9)

        property = AnimatableOps.moveKeyframe(property, 2.0, 3.0)
        assertEquals(listOf(1.0, 3.0), AnimatableOps.times(property))

        property = AnimatableOps.setInterpolation(property, 3.0, Interpolation.HOLD)
        assertEquals(4.0, property.valueAt(2.5), 1e-9)

        property = AnimatableOps.removeKeyframe(property, 3.0)
        assertEquals(1, property.keyframes.size)
    }

    @Test
    fun collapseFreezesCurrentValue() {
        var property: Animatable<Double> = StaticValue(1.0)
        property = AnimatableOps.setKeyframe(property, ScalarInterpolator, 0.0, 2.0)
        property = AnimatableOps.setKeyframe(property, ScalarInterpolator, 2.0, 6.0)
        val collapsed = AnimatableOps.collapse(property, 1.0)
        assertEquals(false, collapsed.isAnimated)
        assertEquals(4.0, collapsed.valueAt(0.0), 1e-9)
        assertEquals(4.0, collapsed.valueAt(9.0), 1e-9)
    }

    @Test
    fun colorTracksInterpolate() {
        val track = KeyframeTrack(
            ColorInterpolator,
            listOf(Keyframe(0.0, Color.BLACK), Keyframe(1.0, Color.WHITE))
        )
        val mid = track.valueAt(0.5, Color.BLACK)
        assertTrue(mid.r > 0.0 && mid.r < 1.0)
    }

    @Test
    fun vec2TracksInterpolate() {
        val track = KeyframeTrack(
            Vec2Interpolator,
            listOf(Keyframe(0.0, Vec2.ZERO), Keyframe(2.0, Vec2(10.0, -10.0)))
        )
        assertEquals(Vec2(5.0, -5.0), track.valueAt(1.0, Vec2.ZERO))
    }
}
