package com.vynox.core.timeline

import com.vynox.core.animation.AnimatableOps
import com.vynox.core.effects.BuiltInEffects
import com.vynox.core.effects.EffectRegistry
import com.vynox.core.animation.ScalarInterpolator
import com.vynox.core.math.Vec2
import com.vynox.core.model.Canvas
import com.vynox.core.model.Layer
import com.vynox.core.model.LayerContent
import com.vynox.core.model.LayerFactory
import com.vynox.core.model.LayerType
import com.vynox.core.model.ProjectFactory
import com.vynox.core.model.VynoxProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineOperationsTest {

    init {
        if (EffectRegistry.all().isEmpty()) BuiltInEffects.register()
    }

    private fun project(): VynoxProject {
        val base = ProjectFactory.create("Test", Canvas(1920, 1080, 30, com.vynox.core.math.Color.BLACK, 20.0))
        val video = Layer(
            id = "video",
            name = "Clip",
            type = LayerType.VIDEO,
            content = LayerContent.VideoContent(asset = "asset1"),
            startTime = 2.0,
            duration = 10.0,
            sourceIn = 1.0
        )
        val text = LayerFactory.textLayer(base.canvas, "Hello").copy(id = "text", startTime = 0.0, duration = 6.0)
        return base.copy(layers = listOf(text, video))
    }

    @Test
    fun splitCreatesTwoConsecutiveClips() {
        val project = project()
        val split = TimelineOperations.splitLayer(project, "video", 7.0)
        assertEquals(3, split.layers.size)
        val left = split.layer("video")!!
        val right = split.layers.first { it.id != "text" && it.id != "video" }

        assertEquals(2.0, left.startTime, 1e-9)
        assertEquals(5.0, left.duration, 1e-9)
        assertEquals(1.0, left.sourceIn, 1e-9)

        assertEquals(7.0, right.startTime, 1e-9)
        assertEquals(5.0, right.duration, 1e-9)
        assertEquals(6.0, right.sourceIn, 1e-9)
        assertEquals(left.endTime, right.startTime, 1e-9)
    }

    @Test
    fun splitIsRejectedOutsideClip() {
        val project = project()
        assertEquals(project, TimelineOperations.splitLayer(project, "video", 1.0))
        assertEquals(project, TimelineOperations.splitLayer(project, "video", 20.0))
        assertEquals(project, TimelineOperations.splitLayer(project, "missing", 5.0))
    }

    @Test
    fun splitCarriesKeyframesToBothHalves() {
        var project = project()
        val original = project.layer("video")!!
        val animatedPosition = AnimatableOps.setKeyframe(original.transform.position, com.vynox.core.animation.Vec2Interpolator, 0.0, Vec2(0.0, 0.0))
            .let { AnimatableOps.setKeyframe(it, com.vynox.core.animation.Vec2Interpolator, 5.0, Vec2(100.0, 0.0)) }
            .let { AnimatableOps.setKeyframe(it, com.vynox.core.animation.Vec2Interpolator, 10.0, Vec2(200.0, 0.0)) }
        project = project.withReplacedLayer(original.copy(transform = original.transform.copy(position = animatedPosition)))

        val split = TimelineOperations.splitLayer(project, "video", 7.0)
        val left = split.layer("video")!!
        val right = split.layers.first { it.id != "text" && it.id != "video" }

        // Local time 4s of the right clip is local time 9s of the original.
        assertEquals(Vec2(180.0, 0.0).x, right.transform.position.valueAt(4.0).x, 1e-6)
        assertEquals(2, left.transform.position.keyframes.size) // 0 and the boundary at 5s
        assertEquals(0.0, left.transform.position.valueAt(0.0).x, 1e-6)
    }

    @Test
    fun trimStartKeepsContentAnchored() {
        val project = project()
        val trimmed = TimelineOperations.trimLayerStart(project, "video", 4.0)
        val layer = trimmed.layer("video")!!
        assertEquals(4.0, layer.startTime, 1e-9)
        assertEquals(8.0, layer.duration, 1e-9)
        assertEquals(3.0, layer.sourceIn, 1e-9) // source offset followed the in point
    }

    @Test
    fun trimEndShortensClip() {
        val project = project()
        val trimmed = TimelineOperations.trimLayerEnd(project, "video", 8.0)
        val layer = trimmed.layer("video")!!
        assertEquals(2.0, layer.startTime, 1e-9)
        assertEquals(6.0, layer.duration, 1e-9)
        assertEquals(1.0, layer.sourceIn, 1e-9)
    }

    @Test
    fun trimsAreClamped() {
        val project = project()
        val layer = project.layer("video")!!
        val overTrimmed = TimelineOperations.trimLayerStart(project, "video", 100.0)
        assertTrue(overTrimmed.layer("video")!!.duration > 0.0)
        assertTrue(overTrimmed.layer("video")!!.startTime <= layer.endTime)
    }

    @Test
    fun moveLayerInTimeNeverGoesNegative() {
        val project = project()
        val moved = TimelineOperations.moveLayerInTime(project, "video", -5.0)
        assertEquals(0.0, moved.layer("video")!!.startTime, 1e-9)
        assertEquals(10.0, moved.layer("video")!!.duration, 1e-9)
    }

    @Test
    fun reorderChangesStackingOrder() {
        val project = project()
        val reordered = TimelineOperations.reorderLayer(project, "video", 0)
        assertEquals("video", reordered.layers[0].id)
        val clamped = TimelineOperations.reorderLayer(project, "video", 99)
        assertEquals("video", clamped.layers.last().id)
    }

    @Test
    fun duplicateCreatesIndependentCopy() {
        val project = project()
        val duplicated = TimelineOperations.duplicateLayer(project, "video")
        assertEquals(3, duplicated.layers.size)
        val copy = duplicated.layers.first { it.id != "video" && it.id != "text" }
        assertEquals("Clip 2", copy.name)
        assertEquals(2.0, copy.startTime, 1e-9)
        assertTrue(copy.id != "video")
    }

    @Test
    fun removeLayerReParentsChildren() {
        var project = project()
        val group = LayerFactory.groupLayer(project.canvas).copy(id = "group")
        project = project.copy(layers = project.layers + group)
        project = project.withReplacedLayer(project.layer("text")!!.copy(parentId = "group"))
        val removed = TimelineOperations.removeLayer(project, "group")
        assertEquals(null, removed.layer("text")!!.parentId)
        assertTrue(removed.layer("group") == null)
    }

    @Test
    fun parentingRejectsCycles() {
        var project = project()
        project = TimelineOperations.setLayerParent(project, "text", "video")
        assertEquals("video", project.layer("text")!!.parentId)
        val cycle = TimelineOperations.setLayerParent(project, "video", "text")
        assertEquals(null, cycle.layer("video")!!.parentId)
    }

    @Test
    fun setDurationClampsKeyframes() {
        var project = project()
        val layer = project.layer("text")!!
        val animated = AnimatableOps.setKeyframe(layer.transform.opacity, ScalarInterpolator, 0.0, 1.0)
            .let { AnimatableOps.setKeyframe(it, ScalarInterpolator, 6.0, 0.0) }
        project = project.withReplacedLayer(layer.copy(transform = layer.transform.copy(opacity = animated)))
        val shortened = TimelineOperations.setLayerDuration(project, "text", 3.0)
        val updated = shortened.layer("text")!!
        assertEquals(3.0, updated.duration, 1e-9)
        assertTrue(updated.transform.opacity.keyframes.all { it.time <= 3.0001 })
    }

    @Test
    fun speedChangeAdjustsDuration() {
        val project = project()
        val faster = TimelineOperations.setLayerSpeed(project, "video", 2.0)
        assertEquals(2.0, faster.layer("video")!!.speed, 1e-9)
        assertEquals(5.0, faster.layer("video")!!.duration, 1e-9)
    }

    @Test
    fun markersRoundTrip() {
        val project = project()
        val withMarker = TimelineOperations.addMarker(project, 4.5, "Chorus")
        assertEquals(1, withMarker.markers.size)
        assertEquals(4.5, withMarker.markers.first().time, 1e-9)
        val removed = TimelineOperations.removeMarker(withMarker, withMarker.markers.first().id)
        assertTrue(removed.markers.isEmpty())
    }

    @Test
    fun operationsUpdateModifiedAt() {
        val project = project()
        val touched = TimelineOperations.moveLayerInTime(project, "video", 3.0)
        assertTrue(touched.meta.modifiedAt >= project.meta.modifiedAt)
    }

    @Test
    fun fitToContentUsesOutermostLayer() {
        val project = project()
        val fitted = TimelineOperations.fitToContent(project)
        assertEquals(12.0, fitted.canvas.duration, 1e-9)
    }

    @Test
    fun addAndRemoveEffects() {
        var project = project()
        val effect = com.vynox.core.effects.EffectRegistry.create("vynox.blur")!!
        project = TimelineOperations.addEffect(project, "video", effect)
        assertEquals(1, project.layer("video")!!.effects.size)
        project = TimelineOperations.removeEffect(project, "video", effect.id)
        assertTrue(project.layer("video")!!.effects.isEmpty())
        assertNotNull(effect)
    }
}
