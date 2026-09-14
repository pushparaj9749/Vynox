package com.vynox.core.vnx

import com.vynox.core.animation.Animatable
import com.vynox.core.animation.ScalarProperty
import com.vynox.core.animation.StaticValue
import com.vynox.core.effects.EffectRegistry
import com.vynox.core.json.JsonValue
import com.vynox.core.json.JsonObjectBuilder
import com.vynox.core.json.jsonObject
import com.vynox.core.math.Size
import com.vynox.core.math.Vec2
import com.vynox.core.model.Asset
import com.vynox.core.model.AssetType
import com.vynox.core.model.BlendMode
import com.vynox.core.model.Canvas
import com.vynox.core.model.ContentFit
import com.vynox.core.model.EffectInstance
import com.vynox.core.model.Layer
import com.vynox.core.model.LayerContent
import com.vynox.core.model.LayerLabel
import com.vynox.core.model.LayerType
import com.vynox.core.model.Marker
import com.vynox.core.model.Mask
import com.vynox.core.model.MaskMode
import com.vynox.core.model.MaskShape
import com.vynox.core.model.PreviewQuality
import com.vynox.core.model.ProjectMeta
import com.vynox.core.model.ProjectSettings
import com.vynox.core.model.ShapeKind
import com.vynox.core.model.TextAlign
import com.vynox.core.model.TextVerticalAlign
import com.vynox.core.model.Transform
import com.vynox.core.model.VynoxProject
import com.vynox.core.model.VynoxSchema

/** Warnings collected while loading a project (never fatal). */
data class VnxWarning(val code: String, val message: String)

/**
 * Converts a [VynoxProject] to and from the versioned JSON payload used inside
 * .vnx files.
 *
 * Decoding is defensive: unknown layer, effect or content types are skipped with
 * a warning instead of failing the whole project, so a file written by a newer
 * Vynox (or by a future plugin) still opens for editing.
 */
object VnxProjectCodec {

    fun encode(project: VynoxProject): JsonValue.Obj = jsonObject {
        put("format", VynoxSchema.FORMAT_ID)
        put("schema", project.schemaVersion)
        put("appVersion", project.appVersion)
        putObject("project") {
            put("id", project.id)
            put("name", project.name)
            putObject("canvas") {
                put("width", project.canvas.width)
                put("height", project.canvas.height)
                put("fps", project.canvas.fps)
                put("background", project.canvas.background.argb)
                put("duration", project.canvas.duration)
            }
            putObject("settings") {
                put("snapEnabled", project.settings.snapEnabled)
                put("snapToleranceSeconds", project.settings.snapToleranceSeconds)
                put("autosaveEnabled", project.settings.autosaveEnabled)
                put("autosaveIntervalSeconds", project.settings.autosaveIntervalSeconds)
                put("audioSampleRate", project.settings.audioSampleRate)
                put("audioChannels", project.settings.audioChannels)
                put("previewQuality", project.settings.previewQuality.name)
            }
            putObject("meta") {
                put("createdAt", project.meta.createdAt)
                put("modifiedAt", project.meta.modifiedAt)
                put("author", project.meta.author)
                put("description", project.meta.description)
                put("generator", project.meta.generator)
            }
            putArray("markers", project.markers.map { encodeMarker(it) })
            putArray("assets", project.assets.map { encodeAsset(it) })
            putArray("layers", project.layers.map { encodeLayer(it) })
        }
    }

    fun decode(root: JsonValue, warnings: MutableList<VnxWarning> = mutableListOf()): VynoxProject {
        if (root !is JsonValue.Obj) throw VnxFormatException("Project root must be a JSON object")
        val format = root.get("format").asString()
        if (format != null && format != VynoxSchema.FORMAT_ID) {
            throw VnxFormatException("Not a Vynox project (format='$format')")
        }
        val schema = root.get("schema").asInt() ?: 1
        if (schema > VynoxSchema.CURRENT) {
            warnings += VnxWarning(
                "newer_schema",
                "Project was written by a newer Vynox (schema $schema). Unknown data will be preserved where possible."
            )
        }
        val body = root.get("project").let { if (it is JsonValue.Obj) it else root }
        val project = VynoxProject(
            schemaVersion = schema,
            appVersion = root.get("appVersion").asString() ?: "0.0.0",
            id = body.get("id").asString() ?: "project",
            name = body.get("name").asString() ?: "Untitled",
            canvas = decodeCanvas(body.get("canvas")),
            settings = decodeSettings(body.get("settings")),
            meta = decodeMeta(body.get("meta")),
            markers = body.get("markers").asArray().mapNotNull { item -> decodeMarker(item) },
            assets = body.get("assets").asArray().mapNotNull { item ->
                try { decodeAsset(item) } catch (e: Exception) {
                    warnings += VnxWarning("asset", "Skipped unreadable asset: ${e.message}")
                    null
                }
            },
            layers = body.get("layers").asArray().mapNotNull { item ->
                try { decodeLayer(item, warnings) } catch (e: Exception) {
                    warnings += VnxWarning("layer", "Skipped unreadable layer: ${e.message}")
                    null
                }
            }
        )
        return project
    }

    // ------------------------------------------------------------------ canvas

    private fun decodeCanvas(json: JsonValue): Canvas {
        if (json.isNull) return Canvas()
        return Canvas(
            width = json.get("width").asInt() ?: 1920,
            height = json.get("height").asInt() ?: 1080,
            fps = json.get("fps").asInt() ?: 30,
            background = ValueCodecs.decodeColor(json.get("background")),
            duration = json.get("duration").asDouble() ?: 15.0
        )
    }

    private fun decodeSettings(json: JsonValue): ProjectSettings {
        if (json.isNull) return ProjectSettings()
        return ProjectSettings(
            snapEnabled = json.get("snapEnabled").asBool(),
            snapToleranceSeconds = json.get("snapToleranceSeconds").asDouble() ?: 0.12,
            autosaveEnabled = json.get("autosaveEnabled").asBool(),
            autosaveIntervalSeconds = json.get("autosaveIntervalSeconds").asInt() ?: 60,
            audioSampleRate = json.get("audioSampleRate").asInt() ?: 44100,
            audioChannels = json.get("audioChannels").asInt() ?: 2,
            previewQuality = runCatching { PreviewQuality.valueOf(json.get("previewQuality").asString() ?: "BALANCED") }
                .getOrDefault(PreviewQuality.BALANCED)
        )
    }

    private fun decodeMeta(json: JsonValue): ProjectMeta {
        if (json.isNull) return ProjectMeta()
        return ProjectMeta(
            createdAt = json.get("createdAt").asLong() ?: 0L,
            modifiedAt = json.get("modifiedAt").asLong() ?: 0L,
            author = json.get("author").asString(),
            description = json.get("description").asString(),
            generator = json.get("generator").asString() ?: "Vynox"
        )
    }

    private fun encodeMarker(marker: Marker): JsonValue = jsonObject {
        put("id", marker.id)
        put("time", marker.time)
        put("label", marker.label)
        put("color", marker.colorArgb)
    }

    private fun decodeMarker(json: JsonValue): Marker? {
        if (json !is JsonValue.Obj) return null
        return Marker(
            id = json.get("id").asString() ?: "marker",
            time = json.get("time").asDouble() ?: 0.0,
            label = json.get("label").asString() ?: "Marker",
            colorArgb = json.get("color").asInt() ?: 0xFF6C5CE7.toInt()
        )
    }

    // ------------------------------------------------------------------ assets

    private fun encodeAsset(asset: Asset): JsonValue = jsonObject {
        put("id", asset.id)
        put("name", asset.name)
        put("type", asset.type.name)
        put("uri", asset.uri)
        put("bundledPath", asset.bundledPath)
        put("mime", asset.mimeType)
        put("size", asset.sizeBytes)
        put("checksum", asset.checksum)
        put("duration", asset.durationSeconds)
        put("width", asset.width)
        put("height", asset.height)
        put("sampleRate", asset.sampleRate)
        put("channels", asset.channels)
        put("importedAt", asset.importedAt)
    }

    private fun decodeAsset(json: JsonValue): Asset {
        if (json !is JsonValue.Obj) throw VnxFormatException("Asset must be an object")
        val name = json.get("name").asString() ?: "asset"
        return Asset(
            id = json.get("id").asString() ?: name,
            name = name,
            type = runCatching { AssetType.valueOf(json.get("type").asString() ?: "IMAGE") }.getOrDefault(AssetType.IMAGE),
            uri = json.get("uri").asString(),
            bundledPath = json.get("bundledPath").asString(),
            mimeType = json.get("mime").asString(),
            sizeBytes = json.get("size").asLong() ?: 0L,
            checksum = json.get("checksum").asString(),
            durationSeconds = json.get("duration").asDouble(),
            width = json.get("width").asInt(),
            height = json.get("height").asInt(),
            sampleRate = json.get("sampleRate").asInt(),
            channels = json.get("channels").asInt(),
            importedAt = json.get("importedAt").asLong() ?: 0L
        )
    }

    // ------------------------------------------------------------------ layers

    private fun encodeLayer(layer: Layer): JsonValue = jsonObject {
        put("id", layer.id)
        put("name", layer.name)
        put("type", layer.type.name)
        put("start", layer.startTime)
        put("duration", layer.duration)
        put("sourceIn", layer.sourceIn)
        put("speed", layer.speed)
        put("enabled", layer.enabled)
        put("locked", layer.locked)
        put("muted", layer.muted)
        put("solo", layer.solo)
        put("blend", layer.blendMode.name)
        put("label", layer.label.name)
        put("parent", layer.parentId)
        putObject("transform") {
            put("position", ValueCodecs.encodeProperty(layer.transform.position))
            put("scale", ValueCodecs.encodeProperty(layer.transform.scale))
            put("rotation", ValueCodecs.encodeProperty(layer.transform.rotation))
            put("opacity", ValueCodecs.encodeProperty(layer.transform.opacity))
            put("anchor", ValueCodecs.encodeVec2(layer.transform.anchor))
            put("skew", ValueCodecs.encodeVec2(layer.transform.skew))
        }
        putObject("content") { encodeContent(layer, this) }
        putArray("effects", layer.effects.map { encodeEffect(it) })
        putArray("masks", layer.masks.map { encodeMask(it) })
    }

    private fun encodeContent(layer: Layer, builder: JsonObjectBuilder) {
        with(builder) {
            when (val content = layer.content) {
                is LayerContent.VideoContent -> {
                    put("kind", "video")
                    put("asset", content.asset)
                    put("volume", ValueCodecs.encodeProperty(content.volume))
                    put("muted", content.muted)
                    put("fadeIn", content.fadeIn)
                    put("fadeOut", content.fadeOut)
                }
                is LayerContent.ImageContent -> {
                    put("kind", "image")
                    put("asset", content.asset)
                    put("fit", content.fit.name)
                }
                is LayerContent.AudioContent -> {
                    put("kind", "audio")
                    put("asset", content.asset)
                    put("volume", ValueCodecs.encodeProperty(content.volume))
                    put("muted", content.muted)
                    put("fadeIn", content.fadeIn)
                    put("fadeOut", content.fadeOut)
                }
                is LayerContent.TextContent -> {
                    put("kind", "text")
                    put("text", content.text)
                    put("fontFamily", content.fontFamily)
                    put("fontSize", content.fontSize)
                    put("fontWeight", content.fontWeight)
                    put("italic", content.italic)
                    put("alignment", content.alignment.name)
                    put("verticalAlign", content.verticalAlign.name)
                    put("letterSpacing", content.letterSpacing)
                    put("lineSpacing", content.lineSpacing)
                    put("color", ValueCodecs.encodeProperty(content.color))
                    put("strokeEnabled", content.strokeEnabled)
                    put("strokeColor", ValueCodecs.encodeProperty(content.strokeColor))
                    put("strokeWidth", content.strokeWidth)
                    put("shadowEnabled", content.shadowEnabled)
                    put("shadowColor", ValueCodecs.encodeProperty(content.shadowColor))
                    put("shadowRadius", content.shadowRadius)
                    put("shadowOffset", ValueCodecs.encodeVec2(content.shadowOffset))
                    put("backgroundEnabled", content.backgroundEnabled)
                    put("backgroundColor", ValueCodecs.encodeProperty(content.backgroundColor))
                    put("backgroundPadding", content.backgroundPadding)
                    put("backgroundRadius", content.backgroundRadius)
                    put("maxWidth", content.maxWidth)
                }
                is LayerContent.ShapeContent -> {
                    put("kind", "shape")
                    put("shape", content.shape.name)
                    put("size", ValueCodecs.encodeVec2(content.size))
                    put("cornerRadius", content.cornerRadius)
                    put("sides", content.sides)
                    put("fillEnabled", content.fillEnabled)
                    put("fillColor", ValueCodecs.encodeProperty(content.fillColor))
                    put("strokeEnabled", content.strokeEnabled)
                    put("strokeColor", ValueCodecs.encodeProperty(content.strokeColor))
                    put("strokeWidth", content.strokeWidth)
                }
                is LayerContent.GroupContent -> {
                    put("kind", "group")
                    put("passThrough", content.passThrough)
                }
            }
        }
    }

    private fun encodeEffect(effect: EffectInstance): JsonValue = jsonObject {
        put("id", effect.id)
        put("type", effect.typeId)
        put("enabled", effect.enabled)
        putObject("params") {
            effect.parameters.forEach { (key, property) -> put(key, ValueCodecs.encodeProperty(property)) }
        }
    }

    private fun encodeMask(mask: Mask): JsonValue = jsonObject {
        put("id", mask.id)
        put("name", mask.name)
        put("shape", mask.shape.name)
        put("mode", mask.mode.name)
        put("position", ValueCodecs.encodeVec2(mask.position))
        put("size", ValueCodecs.encodeVec2(mask.size))
        put("rotation", mask.rotation)
        put("feather", mask.feather)
        put("opacity", mask.opacity)
        put("invert", mask.invert)
        put("enabled", mask.enabled)
        put("path", ValueCodecs.encodePath(mask.path))
    }

    private fun decodeLayer(json: JsonValue, warnings: MutableList<VnxWarning>): Layer {
        if (json !is JsonValue.Obj) throw VnxFormatException("Layer must be an object")
        val typeName = json.get("type").asString() ?: "IMAGE"
        val type = runCatching { LayerType.valueOf(typeName) }.getOrElse {
            warnings += VnxWarning("layer_type", "Unknown layer type '$typeName' - layer skipped")
            throw VnxFormatException("Unknown layer type '$typeName'")
        }
        val content = decodeContent(type, json.get("content"), warnings)
        return Layer(
            id = json.get("id").asString() ?: "layer",
            name = json.get("name").asString() ?: "Layer",
            type = type,
            content = content,
            transform = decodeTransform(json.get("transform")),
            startTime = json.get("start").asDouble() ?: 0.0,
            duration = json.get("duration").asDouble() ?: 5.0,
            sourceIn = json.get("sourceIn").asDouble() ?: 0.0,
            speed = json.get("speed").asDouble() ?: 1.0,
            enabled = json.get("enabled").asBool(),
            locked = json.get("locked").asBool(),
            muted = json.get("muted").asBool(),
            solo = json.get("solo").asBool(),
            blendMode = runCatching { BlendMode.valueOf(json.get("blend").asString() ?: "NORMAL") }.getOrDefault(BlendMode.NORMAL),
            label = runCatching { LayerLabel.valueOf(json.get("label").asString() ?: "NONE") }.getOrDefault(LayerLabel.NONE),
            parentId = json.get("parent").asString(),
            effects = json.get("effects").asArray().mapNotNull { entry -> decodeEffect(entry, warnings) },
            masks = json.get("masks").asArray().mapNotNull { entry -> decodeMask(entry) }
        )
    }

    private fun decodeTransform(json: JsonValue): Transform {
        if (json.isNull) return Transform()
        return Transform(
            position = ValueCodecs.decodeTyped<Vec2>(json.get("position"), Vec2.ZERO),
            scale = ValueCodecs.decodeTyped<Vec2>(json.get("scale"), Vec2.ONE),
            rotation = ValueCodecs.decodeTyped<Double>(json.get("rotation"), 0.0),
            anchor = ValueCodecs.decodeVec2(json.get("anchor")).let {
                if (it == Vec2.ZERO && json.get("anchor").isNull) Vec2.CENTER else it
            },
            opacity = ValueCodecs.decodeTyped<Double>(json.get("opacity"), 1.0),
            skew = ValueCodecs.decodeVec2(json.get("skew"))
        )
    }

    private fun decodeContent(type: LayerType, json: JsonValue, warnings: MutableList<VnxWarning>): LayerContent {
        val kind = json.get("kind").asString()
        return when (kind) {
            "video" -> LayerContent.VideoContent(
                asset = json.get("asset").asString() ?: "",
                volume = ValueCodecs.decodeTyped<Double>(json.get("volume"), 1.0),
                muted = json.get("muted").asBool(),
                fadeIn = json.get("fadeIn").asDouble() ?: 0.0,
                fadeOut = json.get("fadeOut").asDouble() ?: 0.0
            )
            "image" -> LayerContent.ImageContent(
                asset = json.get("asset").asString() ?: "",
                fit = runCatching { ContentFit.valueOf(json.get("fit").asString() ?: "CONTAIN") }.getOrDefault(ContentFit.CONTAIN)
            )
            "audio" -> LayerContent.AudioContent(
                asset = json.get("asset").asString() ?: "",
                volume = ValueCodecs.decodeTyped<Double>(json.get("volume"), 1.0),
                muted = json.get("muted").asBool(),
                fadeIn = json.get("fadeIn").asDouble() ?: 0.0,
                fadeOut = json.get("fadeOut").asDouble() ?: 0.0
            )
            "text" -> LayerContent.TextContent(
                text = json.get("text").asString() ?: "",
                fontFamily = json.get("fontFamily").asString() ?: "sans-serif",
                fontSize = json.get("fontSize").asDouble() ?: 96.0,
                fontWeight = json.get("fontWeight").asInt() ?: 700,
                italic = json.get("italic").asBool(),
                alignment = runCatching { TextAlign.valueOf(json.get("alignment").asString() ?: "CENTER") }.getOrDefault(TextAlign.CENTER),
                verticalAlign = runCatching { TextVerticalAlign.valueOf(json.get("verticalAlign").asString() ?: "CENTER") }.getOrDefault(TextVerticalAlign.CENTER),
                letterSpacing = json.get("letterSpacing").asDouble() ?: 0.0,
                lineSpacing = json.get("lineSpacing").asDouble() ?: 1.2,
                color = ValueCodecs.decodeProperty(json.get("color")).asColor(com.vynox.core.math.Color.WHITE),
                strokeEnabled = json.get("strokeEnabled").asBool(),
                strokeColor = ValueCodecs.decodeProperty(json.get("strokeColor")).asColor(com.vynox.core.math.Color.BLACK),
                strokeWidth = json.get("strokeWidth").asDouble() ?: 4.0,
                shadowEnabled = json.get("shadowEnabled").asBool(),
                shadowColor = ValueCodecs.decodeProperty(json.get("shadowColor")).asColor(com.vynox.core.math.Color(0.0, 0.0, 0.0, 0.6)),
                shadowRadius = json.get("shadowRadius").asDouble() ?: 12.0,
                shadowOffset = ValueCodecs.decodeVec2(json.get("shadowOffset")).let {
                    if (json.get("shadowOffset").isNull) Vec2(0.0, 6.0) else it
                },
                backgroundEnabled = json.get("backgroundEnabled").asBool(),
                backgroundColor = ValueCodecs.decodeProperty(json.get("backgroundColor")).asColor(com.vynox.core.math.Color(0.0, 0.0, 0.0, 0.45)),
                backgroundPadding = json.get("backgroundPadding").asDouble() ?: 16.0,
                backgroundRadius = json.get("backgroundRadius").asDouble() ?: 12.0,
                maxWidth = json.get("maxWidth").asDouble() ?: 0.0
            )
            "shape" -> LayerContent.ShapeContent(
                shape = runCatching { ShapeKind.valueOf(json.get("shape").asString() ?: "RECT") }.getOrDefault(ShapeKind.RECT),
                size = ValueCodecs.decodeVec2(json.get("size")).let {
                    if (json.get("size").isNull) Vec2(512.0, 288.0) else it
                },
                cornerRadius = json.get("cornerRadius").asDouble() ?: 24.0,
                sides = json.get("sides").asInt() ?: 5,
                fillEnabled = json.get("fillEnabled").asBool(),
                fillColor = ValueCodecs.decodeProperty(json.get("fillColor")).asColor(com.vynox.core.math.Color.fromHex("#6C5CE7")),
                strokeEnabled = json.get("strokeEnabled").asBool(),
                strokeColor = ValueCodecs.decodeProperty(json.get("strokeColor")).asColor(com.vynox.core.math.Color.WHITE),
                strokeWidth = json.get("strokeWidth").asDouble() ?: 6.0
            )
            "group" -> LayerContent.GroupContent(passThrough = json.get("passThrough").asBool())
            else -> {
                warnings += VnxWarning("content", "Unknown content kind '$kind' for layer type $type")
                LayerContent.GroupContent()
            }
        }
    }

    private fun decodeEffect(json: JsonValue, warnings: MutableList<VnxWarning>): EffectInstance? {
        if (json !is JsonValue.Obj) return null
        val typeId = json.get("type").asString() ?: return null
        val params = LinkedHashMap<String, Animatable<*>>()
        val paramsJson = json.get("params")
        if (paramsJson is JsonValue.Obj) {
            paramsJson.fields.forEach { (key, value) -> params[key] = ValueCodecs.decodeProperty(value) }
        }
        val instance = EffectInstance(
            id = json.get("id").asString() ?: typeId,
            typeId = typeId,
            enabled = json.get("enabled").asBool(),
            parameters = params
        )
        if (!EffectRegistry.contains(typeId)) {
            warnings += VnxWarning("effect", "Effect '$typeId' is not available in this build - it is preserved but not rendered")
            return instance
        }
        // Fill in parameters added since the file was written.
        return EffectRegistry.normalize(instance)
    }

    private fun decodeMask(json: JsonValue): Mask? {
        if (json !is JsonValue.Obj) return null
        return Mask(
            id = json.get("id").asString() ?: "mask",
            name = json.get("name").asString() ?: "Mask",
            shape = runCatching { MaskShape.valueOf(json.get("shape").asString() ?: "RECT") }.getOrDefault(MaskShape.RECT),
            mode = runCatching { MaskMode.valueOf(json.get("mode").asString() ?: "ADD") }.getOrDefault(MaskMode.ADD),
            position = ValueCodecs.decodeVec2(json.get("position")),
            size = ValueCodecs.decodeVec2(json.get("size")).let { if (it == Vec2.ZERO) Vec2(512.0, 512.0) else it },
            rotation = json.get("rotation").asDouble() ?: 0.0,
            feather = json.get("feather").asDouble() ?: 0.0,
            opacity = json.get("opacity").asDouble() ?: 1.0,
            invert = json.get("invert").asBool(),
            enabled = json.get("enabled").asBool(),
            path = ValueCodecs.decodePath(json.get("path"))
        )
    }
}

class VnxFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Colour coercion helpers so a malformed colour never breaks a load. */
private fun Animatable<*>.asColor(fallback: com.vynox.core.math.Color): com.vynox.core.animation.ColorProperty {
    val sample = this.valueAt(0.0)
    @Suppress("UNCHECKED_CAST")
    return if (sample is com.vynox.core.math.Color) this as com.vynox.core.animation.ColorProperty
    else StaticValue(fallback)
}

private fun Int.asEngineColor(): com.vynox.core.math.Color = com.vynox.core.math.Color.fromArgb(this)
