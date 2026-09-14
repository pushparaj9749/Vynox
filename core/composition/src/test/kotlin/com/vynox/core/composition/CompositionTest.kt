package com.vynox.core.composition

import com.vynox.core.animation.AnimatableOps
import com.vynox.core.animation.ScalarInterpolator
import com.vynox.core.animation.Vec2Interpolator
import com.vynox.core.effects.BuiltInEffects
import com.vynox.core.effects.EffectIds
import com.vynox.core.effects.EffectRegistry
import com.vynox.core.math.Size
import com.vynox.core.math.Vec2
import com.vynox.core.model.Asset
import com.vynox.core.model.AssetType
import com.vynox.core.model.BlendMode
import com.vynox.core.model.Canvas
import com.vynox.core.model.Layer
import com.vynox.core.model.LayerContent
import com.vynox.core.model.LayerType
import com.vynox.core.model.MaskMode
import com.vynox.core.model.MaskShape
import com.vynox.core.model.Mask
import com.vynox.core.model.ProjectFactory
import com.vynox.core.model.ShapeKind
import com.vynox.core.model.VynoxProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionTest {

    init {
        if (EffectRegistry.all().isEmpty()) BuiltInEffects.register()
    }

    private fun project(): VynoxProject {
        val base = ProjectFactory.create("Composition", Canvas(1000, 1000, 30, com.vynox.core.math.Color.BLACK, 10.0))
        val image = Layer(
            id = "img",
            name = "Image",
            type = LayerType.IMAGE,
            content = LayerContent.ImageContent(asset = "asset_img"),
            transform = com.vynox.core.model.Transform(
                position = com.vynox.core.animation.StaticValue(Vec2(500.0, 500.0)),
                anchor = Vec2(0.5, 0.5)
            ),
            startTime = 0.0,
            duration = 5.0
        )
        val shape = Layer(
            id = "shape",
            name = "Shape",
            type = LayerType.SHAPE,
            content = LayerContent.ShapeContent(shape = ShapeKind.RECT, size = Vec2(200.0, 200.0)),
            transform = com.vynox.core.model.Transform(position = com.vynox.core.animation.StaticValue(Vec2(100.0, 100.0))),
            startTime = 2.0,
            duration = 4.0
        )
        return base.copy(
            assets = listOf(Asset(id = "asset_img", name = "pic.png", type = AssetType.IMAGE, width = 400, height = 300)),
            layers = listOf(shape, image) // index 0 is topmost
        )
    }

    @Test
    fun drawsBottomLayerFirst() {
        val evaluator = CompositionEvaluator(project())
        val plan = evaluator.evaluate(1.0)
        assertEquals(listOf("img", "shape"), plan.nodes.map { it.layerId })
    }

    @Test
    fun inactiveLayersAreExcluded() {
        val evaluator = CompositionEvaluator(project())
        assertEquals(1, evaluator.evaluate(0.5).nodes.size)
        assertEquals(2, evaluator.evaluate(3.0).nodes.size)
        assertEquals(1, evaluator.evaluate(4.5).nodes.size) // shape ended at 6, image at 5
    }

    @Test
    fun hiddenLayersAreExcluded() {
        val hidden = project().withReplacedLayer(project().layer("img")!!.copy(enabled = false))
        val plan = CompositionEvaluator(hidden).evaluate(1.0)
        assertTrue(plan.nodes.none { it.layerId == "img" })
    }

    @Test
    fun transformCentresLayerContent() {
        val evaluator = CompositionEvaluator(project())
        val node = evaluator.evaluate(1.0).nodes.first { it.layerId == "img" }
        assertEquals(Size(400.0, 300.0), node.contentSize)
        val center = node.matrix.transformPoint(Vec2(200.0, 150.0))
        assertEquals(500.0, center.x, 1e-6)
        assertEquals(500.0, center.y, 1e-6)
    }

    @Test
    fun animatedPropertiesEvaluateInLayerLocalTime() {
        var project = project()
        val layer = project.layer("shape")!!
        val opacity = AnimatableOps.setKeyframe(layer.transform.opacity, ScalarInterpolator, 0.0, 0.0)
            .let { AnimatableOps.setKeyframe(it, ScalarInterpolator, 2.0, 1.0) }
        project = project.withReplacedLayer(layer.copy(transform = layer.transform.copy(opacity = opacity)))
        val evaluator = CompositionEvaluator(project)
        // Shape starts at 2s on the timeline; local 1s == composition 3s.
        assertEquals(0.0, evaluator.evaluate(2.0).nodes.first { it.layerId == "shape" }.opacity, 1e-6)
        assertEquals(0.5, evaluator.evaluate(3.0).nodes.first { it.layerId == "shape" }.opacity, 1e-6)
        assertEquals(1.0, evaluator.evaluate(4.0).nodes.first { it.layerId == "shape" }.opacity, 1e-6)
    }

    @Test
    fun parentTransformsCompose() {
        var project = project()
        val group = Layer(
            id = "group",
            name = "Group",
            type = LayerType.GROUP,
            content = LayerContent.GroupContent(),
            transform = com.vynox.core.model.Transform(position = com.vynox.core.animation.StaticValue(Vec2(100.0, 0.0))),
            startTime = 0.0,
            duration = 10.0
        )
        project = project.copy(layers = project.layers + group)
        project = project.withReplacedLayer(project.layer("img")!!.copy(parentId = "group"))
        val evaluator = CompositionEvaluator(project)
        val node = evaluator.evaluate(1.0).nodes.first { it.layerId == "img" }
        val center = node.matrix.transformPoint(Vec2(200.0, 150.0))
        assertEquals(600.0, center.x, 1e-6)
    }

    @Test
    fun effectsAndMasksAreResolved() {
        var project = project()
        val layer = project.layer("img")!!
        val blur = EffectRegistry.create(EffectIds.BLUR)!!
        project = project.withReplacedLayer(
            layer.copy(
                effects = listOf(blur),
                masks = listOf(Mask(id = "m1", shape = MaskShape.ELLIPSE, mode = MaskMode.SUBTRACT, size = Vec2(100.0, 100.0)))
            )
        )
        val node = CompositionEvaluator(project).evaluate(1.0).nodes.first { it.layerId == "img" }
        assertEquals(1, node.effects.size)
        assertEquals(EffectIds.BLUR, node.effects.first().typeId)
        assertEquals(1, node.masks.size)
        assertEquals(MaskShape.ELLIPSE, node.masks.first().shape)
    }

    @Test
    fun opacityEffectMultipliesLayerOpacity() {
        var project = project()
        val layer = project.layer("img")!!
        val opacity = EffectRegistry.create(EffectIds.OPACITY)!!
        val half = opacity.copy(parameters = mapOf("opacity" to com.vynox.core.animation.StaticValue(0.5)))
        project = project.withReplacedLayer(layer.copy(effects = listOf(half)))
        val node = CompositionEvaluator(project).evaluate(1.0).nodes.first { it.layerId == "img" }
        assertEquals(0.5, node.opacity, 1e-6)
    }

    @Test
    fun blendModesAreCarriedThrough() {
        val project = project().withReplacedLayer(project().layer("shape")!!.copy(blendMode = BlendMode.MULTIPLY))
        val node = CompositionEvaluator(project).evaluate(3.0).nodes.first { it.layerId == "shape" }
        assertEquals(BlendMode.MULTIPLY, node.blendMode)
    }

    @Test
    fun hitTestingPicksTopmostLayer() {
        val evaluator = CompositionEvaluator(project())
        // Shape occupies (0..200, 0..200) at composition time 3s (local 1s).
        val hit = evaluator.hitTest(Vec2(100.0, 100.0), 3.0)
        assertEquals("shape", hit?.id)
        val miss = evaluator.hitTest(Vec2(900.0, 900.0), 3.0)
        assertEquals(null, miss)
    }

    @Test
    fun layerBoundsCoverTransformedContent() {
        val evaluator = CompositionEvaluator(project())
        val bounds = evaluator.layerBounds(project().layer("img")!!, 1.0)
        assertEquals(400.0, bounds.width, 1e-6)
        assertEquals(300.0, bounds.height, 1e-6)
        assertEquals(300.0, bounds.left, 1e-6)
    }

    @Test
    fun audioPlanIncludesActiveAudioLayers() {
        var project = project()
        val audio = Layer(
            id = "audio",
            name = "Music",
            type = LayerType.AUDIO,
            content = LayerContent.AudioContent(asset = "asset_audio"),
            startTime = 1.0,
            duration = 3.0
        )
        project = project.copy(layers = project.layers + audio)
        val evaluator = CompositionEvaluator(project)
        assertTrue(evaluator.evaluateAudio(0.5).isEmpty())
        assertEquals(1, evaluator.evaluateAudio(2.0).size)
        assertEquals("asset_audio", evaluator.evaluateAudio(2.0).first().assetId)
        assertTrue(evaluator.evaluateAudio(5.0).isEmpty())
    }

    @Test
    fun sourceTimeRespectsTrimAndSpeed() {
        var project = project()
        project = project.withReplacedLayer(project().layer("img")!!.copy(startTime = 0.0, duration = 5.0, sourceIn = 2.0, speed = 2.0))
        val node = CompositionEvaluator(project).evaluate(1.0).nodes.first { it.layerId == "img" }
        val content = node.content as RenderContent.Image
        assertEquals("asset_img", content.assetId)
        assertEquals(4.0, node.sourceTime, 1e-6) // sourceIn 2 + 1s * speed 2
    }

    @Test
    fun audioLayersAreNotDrawn() {
        val project = project().copy(
            layers = project().layers + Layer(
                id = "audio",
                name = "Audio",
                type = LayerType.AUDIO,
                content = LayerContent.AudioContent(asset = "asset_audio"),
                startTime = 0.0,
                duration = 10.0
            )
        )
        val plan = CompositionEvaluator(project).evaluate(1.0)
        assertTrue(plan.nodes.none { it.layerId == "audio" })
    }
}
