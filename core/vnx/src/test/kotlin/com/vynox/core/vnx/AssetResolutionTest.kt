package com.vynox.core.vnx

import com.vynox.core.math.Color
import com.vynox.core.model.Asset
import com.vynox.core.model.AssetType
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

class AssetResolutionTest {

    private fun project(): VynoxProject {
        val base = ProjectFactory.create("Relink", Canvas(1280, 720, 30, Color.BLACK, 10.0))
        val video = Layer(
            id = "layer_video",
            name = "Clip",
            type = LayerType.VIDEO,
            content = LayerContent.VideoContent(asset = "asset_video"),
            startTime = 0.0,
            duration = 5.0
        )
        return base.copy(
            assets = listOf(
                Asset(id = "asset_video", name = "holiday.mp4", type = AssetType.VIDEO, uri = "/storage/holiday.mp4"),
                Asset(id = "asset_audio", name = "track.mp3", type = AssetType.AUDIO, uri = null),
                Asset(id = "asset_bundled", name = "inside.png", type = AssetType.IMAGE, bundledPath = "assets/asset_bundled.png")
            ),
            layers = listOf(video)
        )
    }

    @Test
    fun detectsAvailableBundledAndMissing() {
        val status = AssetResolution.analyze(project()) { asset -> asset.uri != null }
        val states = status.associate { it.id to it.state }
        assertEquals(AssetState.EXTERNAL, states["asset_video"])
        assertEquals(AssetState.MISSING, states["asset_audio"])
        assertEquals(AssetState.BUNDLED, states["asset_bundled"])
    }

    @Test
    fun completenessReflectsMissingAssets() {
        assertFalse(AssetResolution.isComplete(project()) { it.uri != null })
        assertTrue(AssetResolution.isComplete(project()) { true })
        assertEquals(1, AssetResolution.missing(project()) { it.uri != null }.size)
    }

    @Test
    fun layersUsingMissingAssetsAreIdentified() {
        val missing = AssetResolution.layersUsingMissing(project()) { it.uri != null && it.id == "asset_video" }
        assertTrue(missing.contains("layer_video"))
        val allPresent = AssetResolution.layersUsingMissing(project()) { true }
        assertTrue(allPresent.isEmpty())
    }

    @Test
    fun relinkUpdatesUriAndMetadata() {
        val relinked = AssetResolution.relink(
            project(),
            "asset_audio",
            "/new/track.mp3",
            AssetMetadata(durationSeconds = 42.0, sampleRate = 48000, channels = 2, sizeBytes = 1234L, mimeType = "audio/mpeg")
        )
        val asset = relinked.asset("asset_audio")!!
        assertEquals("/new/track.mp3", asset.uri)
        assertEquals(42.0, asset.durationSeconds!!, 1e-9)
        assertEquals(48000, asset.sampleRate)
        assertEquals(1234L, asset.sizeBytes)
    }

    @Test
    fun relinkClearsBundledPath() {
        val relinked = AssetResolution.relink(project(), "asset_bundled", "/sdcard/inside.png")
        assertEquals(null, relinked.asset("asset_bundled")!!.bundledPath)
        assertEquals("/sdcard/inside.png", relinked.asset("asset_bundled")!!.uri)
    }

    @Test
    fun batchRelinkWorks() {
        val relinked = AssetResolution.relinkAll(
            project(),
            mapOf("asset_audio" to "/a.mp3", "asset_video" to "/b.mp4")
        )
        assertEquals("/a.mp3", relinked.asset("asset_audio")!!.uri)
        assertEquals("/b.mp4", relinked.asset("asset_video")!!.uri)
    }

    @Test
    fun autoRelinkMatchesByFileName() {
        val candidates = listOf(
            "/Downloads/holiday.mp4" to "/Downloads/holiday.mp4",
            "/Music/track.mp3" to "/Music/track.mp3"
        )
        val relinked = AssetResolution.autoRelink(project(), candidates)
        assertEquals("/Downloads/holiday.mp4", relinked.asset("asset_video")!!.uri)
        // asset_audio has no uri but keeps a name, so it is also a candidate.
        assertEquals("/Music/track.mp3", relinked.asset("asset_audio")!!.uri)
    }

    @Test
    fun assetIdsAreStableAcrossRelink() {
        val relinked = AssetResolution.relink(project(), "asset_video", "/elsewhere.mp4")
        assertEquals(setOf("asset_video", "asset_audio", "asset_bundled"), relinked.assets.map { it.id }.toSet())
        assertEquals("asset_video", relinked.layer("layer_video")!!.content.assetId)
    }

    @Test
    fun statisticsAreReported() {
        val withSizes = project().copy(assets = project().assets.map { it.copy(sizeBytes = 100L) })
        assertEquals(300L, AssetResolution.totalBytes(withSizes))
        assertEquals(1, AssetResolution.countByType(withSizes)[AssetType.VIDEO])
    }
}
