package com.vynox.core.model

import com.vynox.core.math.Color

data class Canvas(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val background: Color = Color.BLACK,
    val duration: Double = 15.0
) {
    val aspect: Double get() = if (height == 0) 1.0 else width.toDouble() / height.toDouble()
    val center: com.vynox.core.math.Vec2 get() = com.vynox.core.math.Vec2(width / 2.0, height / 2.0)

    companion object {
        val FULL_HD = Canvas(1920, 1080, 30)
        val HD = Canvas(1280, 720, 30)
        val VERTICAL = Canvas(1080, 1920, 30)
        val SQUARE = Canvas(1080, 1080, 30)
        val FOUR_K = Canvas(3840, 2160, 30)
    }
}

data class ProjectSettings(
    val snapEnabled: Boolean = true,
    val snapToleranceSeconds: Double = 0.12,
    val autosaveEnabled: Boolean = true,
    val autosaveIntervalSeconds: Int = 60,
    val audioSampleRate: Int = 44100,
    val audioChannels: Int = 2,
    val previewQuality: PreviewQuality = PreviewQuality.BALANCED
)

enum class PreviewQuality(val label: String, val scale: Double) {
    FAST("Fast", 0.5),
    BALANCED("Balanced", 0.75),
    HIGH("High", 1.0)
}

data class Marker(
    val id: String,
    val time: Double,
    val label: String = "Marker",
    val colorArgb: Int = 0xFF6C5CE7.toInt()
)

data class ProjectMeta(
    val createdAt: Long = 0L,
    val modifiedAt: Long = 0L,
    val author: String? = null,
    val description: String? = null,
    val generator: String = "Vynox"
)

/**
 * The complete editable document. Everything the editor can do is expressed as
 * a transformation of this immutable structure, which is what makes undo/redo,
 * autosave and .vnx serialization trivially consistent.
 *
 * [layers] is stored in stacking order: index 0 is the topmost layer.
 */
data class VynoxProject(
    val schemaVersion: Int = VynoxSchema.CURRENT,
    val appVersion: String = "0.1.0",
    val id: String,
    val name: String,
    val canvas: Canvas = Canvas(),
    val assets: List<Asset> = emptyList(),
    val layers: List<Layer> = emptyList(),
    val markers: List<Marker> = emptyList(),
    val settings: ProjectSettings = ProjectSettings(),
    val meta: ProjectMeta = ProjectMeta()
) {
    val duration: Double
        get() = maxOf(canvas.duration, layers.maxOfOrNull { it.endTime } ?: 0.0)

    fun layer(id: String): Layer? = layers.firstOrNull { it.id == id }
    fun asset(id: String): Asset? = assets.firstOrNull { it.id == id }

    fun indexOfLayer(id: String): Int = layers.indexOfFirst { it.id == id }

    fun withLayer(layer: Layer): VynoxProject = copy(layers = layers + layer)

    fun withReplacedLayer(layer: Layer): VynoxProject =
        copy(layers = layers.map { if (it.id == layer.id) layer else it })

    fun withoutLayer(id: String): VynoxProject = copy(layers = layers.filterNot { it.id == id })

    fun withLayers(newLayers: List<Layer>): VynoxProject = copy(layers = newLayers)

    fun withAsset(asset: Asset): VynoxProject =
        copy(assets = assets.filterNot { it.id == asset.id } + asset)

    fun withoutAsset(id: String): VynoxProject = copy(assets = assets.filterNot { it.id == id })

    /** Layers bottom first - the order the compositor draws in. */
    val drawOrder: List<Layer> get() = layers.asReversed()

    fun missingAssets(present: (Asset) -> Boolean): List<Asset> =
        assets.filter { asset -> !asset.isBundled && !present(asset) }
}

/** Versioned on-disk schema for .vnx files. */
object VynoxSchema {
    const val CURRENT = 1
    const val MIN_SUPPORTED = 1
    const val FORMAT_ID = "VYNX"
    const val EXTENSION = ".vnx"
    const val PROJECT_ENTRY = "project.json"
    const val BUNDLE_MAGIC_ENTRY = "vynox.meta"
    const val ASSET_DIR = "assets/"
    const val PREVIEW_ENTRY = "preview.png"
}
