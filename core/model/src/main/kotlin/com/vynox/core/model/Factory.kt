package com.vynox.core.model

import com.vynox.core.animation.StaticValue
import com.vynox.core.math.Color
import com.vynox.core.math.Vec2
import kotlin.math.abs
import kotlin.random.Random

object Ids {
    private var counter: Int = Random.nextInt(1000, 9999)

    /** Monotonic, human readable ids - easier to debug than raw UUIDs. */
    fun next(prefix: String): String {
        counter = (counter + 1) % 1_000_000
        val time = (System.currentTimeMillis() % 1_000_000_000L).toString(36)
        return "${prefix}_${time}_${counter.toString(36)}"
    }
}

object LayerFactory {

    fun textLayer(canvas: Canvas, text: String = "Vynox"): Layer = Layer(
        id = Ids.next("layer"),
        name = "Text",
        type = LayerType.TEXT,
        content = LayerContent.TextContent(text = text),
        transform = Transform(position = StaticValue(canvas.center)),
        startTime = 0.0,
        duration = canvas.duration
    )

    fun shapeLayer(canvas: Canvas, shape: ShapeKind = ShapeKind.RECT): Layer = Layer(
        id = Ids.next("layer"),
        name = shape.label,
        type = LayerType.SHAPE,
        content = LayerContent.ShapeContent(
            shape = shape,
            size = Vec2(canvas.width * 0.4, canvas.width * 0.4 * 9.0 / 16.0)
        ),
        transform = Transform(position = StaticValue(canvas.center)),
        startTime = 0.0,
        duration = canvas.duration
    )

    fun groupLayer(canvas: Canvas): Layer = Layer(
        id = Ids.next("layer"),
        name = "Group",
        type = LayerType.GROUP,
        content = LayerContent.GroupContent(),
        transform = Transform(position = StaticValue(canvas.center)),
        startTime = 0.0,
        duration = canvas.duration
    )

    fun mediaLayer(
        asset: Asset,
        canvas: Canvas,
        startTime: Double = 0.0
    ): Layer {
        val duration = when (asset.type) {
            AssetType.IMAGE -> minOf(canvas.duration, 5.0)
            else -> asset.durationSeconds?.takeIf { it > 0.0 } ?: 5.0
        }
        return when (asset.type) {
            AssetType.VIDEO -> Layer(
                id = Ids.next("layer"),
                name = asset.name,
                type = LayerType.VIDEO,
                content = LayerContent.VideoContent(asset = asset.id),
                transform = Transform(position = StaticValue(canvas.center)),
                startTime = startTime,
                duration = duration
            )
            AssetType.IMAGE -> Layer(
                id = Ids.next("layer"),
                name = asset.name,
                type = LayerType.IMAGE,
                content = LayerContent.ImageContent(asset = asset.id),
                transform = Transform(position = StaticValue(canvas.center)),
                startTime = startTime,
                duration = duration
            )
            AssetType.AUDIO -> Layer(
                id = Ids.next("layer"),
                name = asset.name,
                type = LayerType.AUDIO,
                content = LayerContent.AudioContent(asset = asset.id),
                transform = Transform(),
                startTime = startTime,
                duration = duration
            )
            AssetType.FONT -> textLayer(canvas)
        }
    }
}

object ProjectFactory {

    fun create(
        name: String,
        canvas: Canvas = Canvas(),
        appVersion: String = "0.1.0"
    ): VynoxProject {
        val now = System.currentTimeMillis()
        return VynoxProject(
            schemaVersion = VynoxSchema.CURRENT,
            appVersion = appVersion,
            id = Ids.next("project"),
            name = name,
            canvas = canvas.copy(duration = canvas.duration),
            meta = ProjectMeta(createdAt = now, modifiedAt = now),
            layers = emptyList()
        )
    }

    fun preset(name: String, width: Int, height: Int, fps: Int, duration: Double): Canvas =
        Canvas(width = width, height = height, fps = fps, background = Color.BLACK, duration = duration)
}

/** Small helper used by timeline snapping and hit testing. */
fun Double.almostEquals(other: Double, tolerance: Double = 1e-4): Boolean = abs(this - other) <= tolerance
