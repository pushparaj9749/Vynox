package com.vynox.core.timeline

import com.vynox.core.model.Canvas
import com.vynox.core.model.Layer
import com.vynox.core.model.LayerContent
import com.vynox.core.model.LayerType
import com.vynox.core.model.ProjectFactory
import com.vynox.core.model.VynoxProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {

    private fun project(): VynoxProject {
        val base = ProjectFactory.create("Undo", Canvas(1280, 720, 30, com.vynox.core.math.Color.BLACK, 10.0))
        val layer = Layer(
            id = "a",
            name = "A",
            type = LayerType.SHAPE,
            content = LayerContent.ShapeContent(),
            startTime = 0.0,
            duration = 5.0
        )
        return base.copy(layers = listOf(layer))
    }

    @Test
    fun undoRestoresPreviousState() {
        val history = History(project())
        val moved = TimelineOperations.moveLayerInTime(history.current, "a", 3.0)
        history.commit("Move layer", moved)
        assertEquals(3.0, history.current.layer("a")!!.startTime, 1e-9)
        assertTrue(history.canUndo)
        assertTrue(history.undo())
        assertEquals(0.0, history.current.layer("a")!!.startTime, 1e-9)
        assertTrue(history.canRedo)
        assertTrue(history.redo())
        assertEquals(3.0, history.current.layer("a")!!.startTime, 1e-9)
    }

    @Test
    fun newEditClearsRedoStack() {
        val history = History(project())
        history.commit("Move", TimelineOperations.moveLayerInTime(history.current, "a", 2.0))
        history.undo()
        history.commit("Rename", TimelineOperations.setLayerName(history.current, "a", "Renamed"))
        assertFalse(history.canRedo)
        assertEquals("Renamed", history.current.layer("a")!!.name)
    }

    @Test
    fun dragEditsCoalesceIntoSingleEntry() {
        val history = History(project())
        repeat(5) { index ->
            history.commit(
                "Move layer",
                TimelineOperations.moveLayerInTime(history.current, "a", index * 0.5),
                coalesceKey = "move:a"
            )
        }
        assertEquals(1, history.undoDepth)
        history.undo()
        assertEquals(0.0, history.current.layer("a")!!.startTime, 1e-9)
    }

    @Test
    fun distinctEditsCreateDistinctEntries() {
        val history = History(project())
        history.commit("Move", TimelineOperations.moveLayerInTime(history.current, "a", 1.0))
        history.commit("Rename", TimelineOperations.setLayerName(history.current, "a", "B"))
        assertEquals(2, history.undoDepth)
    }

    @Test
    fun undoingEmptyHistoryIsSafe() {
        val history = History(project())
        assertFalse(history.undo())
        assertFalse(history.redo())
    }

    @Test
    fun labelsAreExposedForUi() {
        val history = History(project())
        history.commit("Split clip", TimelineOperations.splitLayer(history.current, "a", 2.5))
        assertEquals("Split clip", history.undoLabel)
    }
}

class SnappingTest {

    private fun project(): VynoxProject {
        val base = ProjectFactory.create("Snap", Canvas(1280, 720, 30, com.vynox.core.math.Color.BLACK, 20.0))
        val first = Layer(
            id = "a",
            name = "A",
            type = LayerType.VIDEO,
            content = LayerContent.VideoContent(asset = "x"),
            startTime = 0.0,
            duration = 4.0
        )
        val second = Layer(
            id = "b",
            name = "B",
            type = LayerType.VIDEO,
            content = LayerContent.VideoContent(asset = "y"),
            startTime = 10.0,
            duration = 4.0
        )
        return base.copy(layers = listOf(first, second))
    }

    @Test
    fun snapsToNearbyLayerEdge() {
        val project = project()
        val result = Snapping.snap(project, 10.05, tolerance = 0.2, excludeLayerIds = setOf("b"))
        assertEquals(10.0, result.time, 1e-9)
        assertEquals(SnapKind.LAYER_EDGE, result.kind)
    }

    @Test
    fun snapsToPlayheadAndZero() {
        val project = project()
        assertEquals(7.0, Snapping.snap(project, 7.03, tolerance = 0.1, playhead = 7.0).time, 1e-9)
        assertEquals(0.0, Snapping.snap(project, 0.05, tolerance = 0.1).time, 1e-9)
    }

    @Test
    fun noSnapOutsideTolerance() {
        val project = project()
        val result = Snapping.snap(project, 5.0, tolerance = 0.2)
        assertEquals(5.0, result.time, 1e-9)
        assertEquals(SnapKind.NONE, result.kind)
    }

    @Test
    fun snappingCanBeDisabled() {
        val disabled = project().copy(settings = project().settings.copy(snapEnabled = false))
        assertEquals(SnapKind.NONE, Snapping.snap(disabled, 10.05).kind)
    }

    @Test
    fun movingSnapsEitherEdge() {
        val project = project()
        val layer = project.layer("b")!!
        // Out point (14s) is near 14s? Move so the in point lands near 4s (end of clip A).
        val result = Snapping.snapMove(project, layer, 4.05, tolerance = 0.2)
        assertEquals(4.0, result.time, 1e-9)
    }

    @Test
    fun markersAreSnapTargets() {
        val withMarker = TimelineOperations.addMarker(project(), 6.0, "Beat")
        val result = Snapping.snap(withMarker, 6.04, tolerance = 0.1)
        assertEquals(6.0, result.time, 1e-9)
        assertEquals(SnapKind.MARKER, result.kind)
    }
}
