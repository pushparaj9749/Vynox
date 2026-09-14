package com.vynox.core.vnx

import com.vynox.core.animation.AnimatableOps
import com.vynox.core.animation.Interpolation
import com.vynox.core.animation.ScalarInterpolator
import com.vynox.core.animation.Vec2Interpolator
import com.vynox.core.effects.BuiltInEffects
import com.vynox.core.effects.EffectIds
import com.vynox.core.effects.EffectRegistry
import com.vynox.core.math.Color
import com.vynox.core.math.Vec2
import com.vynox.core.model.Asset
import com.vynox.core.model.AssetType
import com.vynox.core.model.BlendMode
import com.vynox.core.model.Canvas
import com.vynox.core.model.ContentFit
import com.vynox.core.model.Layer
import com.vynox.core.model.LayerContent
import com.vynox.core.model.LayerType
import com.vynox.core.model.Mask
import com.vynox.core.model.MaskShape
import com.vynox.core.model.Marker
import com.vynox.core.model.ProjectFactory
import com.vynox.core.model.ShapeKind
import com.vynox.core.model.TextAlign
import com.vynox.core.model.VynoxProject
import com.vynox.core.model.VynoxSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class VnxSerializationTest {

    init {
        if (EffectRegistry.all().isEmpty()) BuiltInEffects.register()
    }

    private fun richProject(): VynoxProject {
        val base = ProjectFactory.create("My Project", Canvas(1920, 1080, 30, Color.BLACK, 12.0), appVersion = "0.1.0")

        val videoPosition = AnimatableOps.setKeyframe(
            com.vynox.core.animation.StaticValue(Vec2(100.0, 100.0)), Vec2Interpolator, 0.0, Vec2(100.0, 100.0)
        ).let { AnimatableOps.setKeyframe(it, Vec2Interpolator, 2.0, Vec2(400.0, 300.0), Interpolation.fromPreset("ease_in_out")) }

        val opacity = AnimatableOps.setKeyframe(
            com.vynox.core.animation.StaticValue(0.0), ScalarInterpolator, 0.0, 0.0
        ).let { AnimatableOps.setKeyframe(it, ScalarInterpolator, 1.0, 1.0, Interpolation.HOLD) }

        val video = Layer(
            id = "layer_video",
            name = "Clip A",
            type = LayerType.VIDEO,
            content = LayerContent.VideoContent(asset = "asset_video", volume = com.vynox.core.animation.StaticValue(0.8)),
            transform = com.vynox.core.model.Transform(
                position = videoPosition,
                opacity = opacity,
                rotation = com.vynox.core.animation.StaticValue(45.0)
            ),
            startTime = 0.5,
            duration = 6.0,
            sourceIn = 2.0,
            blendMode = BlendMode.SCREEN,
            effects = listOf(
                EffectRegistry.create(EffectIds.BLUR)!!.copy(
                    parameters = mapOf(
                        "radius" to AnimatableOps.setKeyframe(com.vynox.core.animation.StaticValue(0.0), ScalarInterpolator, 0.0, 0.0)
                            .let { AnimatableOps.setKeyframe(it, ScalarInterpolator, 1.0, 20.0) },
                        "horizontal" to com.vynox.core.animation.StaticValue(1.0),
                        "vertical" to com.vynox.core.animation.StaticValue(1.0)
                    )
                ),
                EffectRegistry.create(EffectIds.SATURATION)!!
            ),
            masks = listOf(
                Mask(id = "mask1", name = "Vignette", shape = MaskShape.ELLIPSE, size = Vec2(600.0, 400.0), feather = 25.0, invert = true)
            )
        )

        val text = Layer(
            id = "layer_text",
            name = "Title",
            type = LayerType.TEXT,
            content = LayerContent.TextContent(
                text = "Vynox\nMotion",
                fontSize = 120.0,
                color = com.vynox.core.animation.StaticValue(Color.fromHex("#22D3EE")),
                alignment = TextAlign.CENTER,
                letterSpacing = 2.0
            ),
            startTime = 1.0,
            duration = 5.0
        )

        val shape = Layer(
            id = "layer_shape",
            name = "Box",
            type = LayerType.SHAPE,
            content = LayerContent.ShapeContent(
                shape = ShapeKind.ROUNDED_RECT,
                size = Vec2(300.0, 200.0),
                cornerRadius = 48.0,
                fillColor = com.vynox.core.animation.StaticValue(Color.fromHex("#6C5CE7")),
                strokeEnabled = true,
                strokeWidth = 8.0
            ),
            startTime = 0.0,
            duration = 8.0
        )

        val audio = Layer(
            id = "layer_audio",
            name = "Music",
            type = LayerType.AUDIO,
            content = LayerContent.AudioContent(asset = "asset_audio"),
            startTime = 0.0,
            duration = 10.0
        )

        return base.copy(
            assets = listOf(
                Asset(id = "asset_video", name = "clip.mp4", type = AssetType.VIDEO, uri = "content://media/clip", durationSeconds = 30.0, width = 1920, height = 1080),
                Asset(id = "asset_audio", name = "music.mp3", type = AssetType.AUDIO, uri = "content://media/music", durationSeconds = 60.0, sampleRate = 44100, channels = 2)
            ),
            layers = listOf(shape, text, video, audio),
            markers = listOf(Marker(id = "m1", time = 3.5, label = "Beat"))
        )
    }

    @Test
    fun projectRoundTripsThroughJson() {
        val project = richProject()
        val json = VnxWriter.writeJson(project)
        val loaded = VnxReader.readJson(json)

        assertEquals(project.name, loaded.project.name)
        assertEquals(project.canvas.width, loaded.project.canvas.width)
        assertEquals(project.canvas.fps, loaded.project.canvas.fps)
        assertEquals(project.layers.size, loaded.project.layers.size)
        assertEquals(project.assets.size, loaded.project.assets.size)
        assertEquals(1, loaded.project.markers.size)
        assertEquals("Beat", loaded.project.markers.first().label)
        assertTrue(loaded.warnings.isEmpty())
    }

    @Test
    fun layerTimingAndTransformsSurvive() {
        val project = richProject()
        val loaded = VnxReader.readJson(VnxWriter.writeJson(project)).project
        val video = loaded.layer("layer_video")!!
        assertEquals(0.5, video.startTime, 1e-9)
        assertEquals(6.0, video.duration, 1e-9)
        assertEquals(2.0, video.sourceIn, 1e-9)
        assertEquals(BlendMode.SCREEN, video.blendMode)
        assertEquals(45.0, video.transform.rotation.valueAt(0.0), 1e-9)
        assertEquals(Vec2(400.0, 300.0).x, video.transform.position.valueAt(2.0).x, 1e-6)
    }

    @Test
    fun keyframesAndEasingSurvive() {
        val project = richProject()
        val loaded = VnxReader.readJson(VnxWriter.writeJson(project)).project
        val video = loaded.layer("layer_video")!!
        assertEquals(2, video.transform.position.keyframes.size)
        val last = video.transform.position.keyframes.last()
        assertEquals(2.0, last.time, 1e-9)
        assertEquals(Interpolation.fromPreset("ease_in_out").handles, last.interpolation.handles)
        assertEquals(1, video.transform.opacity.keyframes.size + 1) // 0s and 1s keys
        val opacityKeys = video.transform.opacity.keyframes
        assertEquals(2, opacityKeys.size)
        assertEquals(Interpolation.HOLD, opacityKeys.last().interpolation)
        // Hold means the value stays at the previous keyframe.
        assertEquals(0.0, video.transform.opacity.valueAt(0.99), 1e-9)
        assertEquals(1.0, video.transform.opacity.valueAt(1.0), 1e-9)
    }

    @Test
    fun customBezierCurvesSurvive() {
        var project = richProject()
        val layer = project.layer("layer_shape")!!
        val custom = Interpolation.bezier(0.31, 0.72, 0.22, 0.91)
        val rotation = AnimatableOps.setKeyframe(layer.transform.rotation, ScalarInterpolator, 0.0, 0.0)
            .let { AnimatableOps.setKeyframe(it, ScalarInterpolator, 2.0, 90.0, custom) }
        project = project.withReplacedLayer(layer.copy(transform = layer.transform.copy(rotation = rotation)))

        val loaded = VnxReader.readJson(VnxWriter.writeJson(project)).project
        val restored = loaded.layer("layer_shape")!!.transform.rotation.keyframes.last()
        assertEquals(custom.handles.cx1, restored.interpolation.handles.cx1, 1e-9)
        assertEquals(custom.handles.cy2, restored.interpolation.handles.cy2, 1e-9)
    }

    @Test
    fun effectsAndAnimatedEffectParametersSurvive() {
        val project = richProject()
        val loaded = VnxReader.readJson(VnxWriter.writeJson(project)).project
        val video = loaded.layer("layer_video")!!
        assertEquals(2, video.effects.size)
        val blur = video.effects.first { it.typeId == EffectIds.BLUR }
        assertEquals(0.0, EffectRegistry.resolve(blur, 0.0).double("radius"), 1e-9)
        assertEquals(20.0, EffectRegistry.resolve(blur, 1.0).double("radius"), 1e-9)
        assertEquals(10.0, EffectRegistry.resolve(blur, 0.5).double("radius"), 1e-6)
    }

    @Test
    fun masksTextAndShapesSurvive() {
        val project = richProject()
        val loaded = VnxReader.readJson(VnxWriter.writeJson(project)).project

        val video = loaded.layer("layer_video")!!
        assertEquals(1, video.masks.size)
        assertEquals(MaskShape.ELLIPSE, video.masks.first().shape)
        assertEquals(25.0, video.masks.first().feather, 1e-9)
        assertEquals(true, video.masks.first().invert)

        val text = (loaded.layer("layer_text")!!.content as LayerContent.TextContent)
        assertEquals("Vynox\nMotion", text.text)
        assertEquals(120.0, text.fontSize, 1e-9)
        assertEquals(0xFF22D3EE.toInt(), text.color.valueAt(0.0).argb)

        val shape = (loaded.layer("layer_shape")!!.content as LayerContent.ShapeContent)
        assertEquals(ShapeKind.ROUNDED_RECT, shape.shape)
        assertEquals(48.0, shape.cornerRadius, 1e-9)
        assertEquals(0xFF6C5CE7.toInt(), shape.fillColor.valueAt(0.0).argb)
    }

    @Test
    fun versionsArePersisted() {
        val project = richProject()
        val json = VnxWriter.writeJson(project)
        assertTrue(json.contains("\"schema\" : ${VynoxSchema.CURRENT}") || json.contains("\"schema\": ${VynoxSchema.CURRENT}"))
        assertTrue(json.contains(VynoxSchema.FORMAT_ID))
        val loaded = VnxReader.readJson(json).project
        assertEquals(VynoxSchema.CURRENT, loaded.schemaVersion)
        assertEquals("0.1.0", loaded.appVersion)
    }

    @Test(expected = VnxFormatException::class)
    fun foreignJsonIsRejected() {
        VnxReader.readJson("""{"format":"OTHER","schema":1,"project":{}}""")
    }

    @Test(expected = VnxFormatException::class)
    fun corruptJsonIsRejected() {
        VnxReader.readJson("""{"format":"VYNX","project": {{""")
    }

    @Test
    fun unknownLayerTypesProduceWarningsInsteadOfFailure() {
        val project = richProject()
        val json = com.vynox.core.json.Json.parse(VnxWriter.writeJson(project))
        val layers = com.vynox.core.json.JsonValue.Arr(
            json.get("project").get("layers").asArray() +
                com.vynox.core.json.JsonValue.Obj(
                    mapOf(
                        "id" to com.vynox.core.json.JsonValue.Str("future"),
                        "name" to com.vynox.core.json.JsonValue.Str("Hologram"),
                        "type" to com.vynox.core.json.JsonValue.Str("HOLOGRAM")
                    )
                )
        )
        val patched = com.vynox.core.json.JsonValue.Obj(
            json.asObject().toMutableMap().also { map ->
                val body = json.get("project").asObject().toMutableMap()
                body["layers"] = layers
                map["project"] = com.vynox.core.json.JsonValue.Obj(body)
            }
        )
        val result = VnxProjectCodec.decode(patched, mutableListOf())
        assertTrue(result.layers.any { it.id == "layer_video" })
    }

    @Test
    fun unknownEffectsArePreservedWithWarning() {
        val project = richProject()
        val withUnknown = project.withReplacedLayer(
            project.layer("layer_shape")!!.copy(
                effects = listOf(
                    com.vynox.core.model.EffectInstance(
                        id = "fx_unknown",
                        typeId = "vynox.future_effect",
                        parameters = mapOf("amount" to com.vynox.core.animation.StaticValue(1.0))
                    )
                )
            )
        )
        val result = VnxReader.readJson(VnxWriter.writeJson(withUnknown))
        assertTrue(result.warnings.any { it.code == "effect" })
        val layer = result.project.layer("layer_shape")!!
        assertEquals(1, layer.effects.size)
        assertEquals("vynox.future_effect", layer.effects.first().typeId)
    }

    @Test
    fun plainJsonFileIsRecognised() {
        val bytes = VnxWriter.writeJson(richProject()).toByteArray()
        val result = VnxReader.read(bytes)
        assertEquals(false, result.isBundle)
        assertEquals("My Project", result.project.name)
    }

    @Test
    fun bundlePackagesAssetsWithProject() {
        val project = richProject()
        val bundler = object : AssetBundler {
            override fun openAsset(asset: Asset): java.io.InputStream? =
                ByteArrayInputStream(("media:" + asset.id).toByteArray())
            override fun previewBytes(): ByteArray? = byteArrayOf(1, 2, 3, 4)
        }
        val out = ByteArrayOutputStream()
        VnxWriter.writeBundle(project, out, bundler)
        val bytes = out.toByteArray()
        assertTrue(VnxReader.isZip(bytes))

        val extracted = LinkedHashMap<String, String>()
        val result = VnxReader.read(ByteArrayInputStream(bytes)) { assetId, entryName, stream ->
            extracted[assetId] = String(stream.readBytes())
        }
        assertTrue(result.isBundle)
        assertEquals(2, extracted.size)
        assertEquals("media:asset_video", extracted["asset_video"])
        assertNotNull(result.previewPng)
        assertTrue(result.entries.contains(VynoxSchema.PROJECT_ENTRY))
        assertTrue(result.project.assets.all { it.isBundled })
    }

    @Test
    fun bundleWithoutAssetsStillOpens() {
        val project = richProject()
        val out = ByteArrayOutputStream()
        VnxWriter.writeBundle(project, out, null)
        val result = VnxReader.read(out.toByteArray())
        assertTrue(result.isBundle)
        assertEquals("My Project", result.project.name)
        assertTrue(result.project.assets.none { it.isBundled })
    }

    @Test(expected = VnxFormatException::class)
    fun zipWithoutProjectEntryFails() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("readme.txt"))
            zip.write("not a project".toByteArray())
            zip.closeEntry()
        }
        VnxReader.read(out.toByteArray())
    }

    @Test
    fun extractedPathsCanBeApplied() {
        val project = richProject()
        val bundled = project.copy(assets = project.assets.map { it.copy(bundledPath = "assets/${it.id}.bin") })
        val remapped = VnxReader.applyExtractedPaths(bundled) { assetId, _ -> "/data/vynox/$assetId" }
        assertTrue(remapped.assets.all { it.uri == "/data/vynox/${it.id}" })
    }
}
