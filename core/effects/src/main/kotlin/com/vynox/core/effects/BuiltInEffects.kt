package com.vynox.core.effects

import com.vynox.core.math.Color

/**
 * Built-in effect catalogue.
 *
 * Each entry declares its parameters; the GPU renderer maps [EffectDefinition.shaderId]
 * to a shader pass, so the list can grow without touching editor code.
 */
object EffectIds {
    const val BLUR = "vynox.blur"
    const val BRIGHTNESS = "vynox.brightness"
    const val CONTRAST = "vynox.contrast"
    const val SATURATION = "vynox.saturation"
    const val HUE = "vynox.hue"
    const val EXPOSURE = "vynox.exposure"
    const val SHARPEN = "vynox.sharpen"
    const val GLOW = "vynox.glow"
    const val OPACITY = "vynox.opacity"
    const val VIGNETTE = "vynox.vignette"
    const val TINT = "vynox.tint"
    const val INVERT = "vynox.invert"
    const val TEMPERATURE = "vynox.temperature"
}

object BuiltInEffects {

    fun definitions(): List<EffectDefinition> = listOf(
        EffectDefinition(
            typeId = EffectIds.BLUR,
            name = "Blur",
            category = EffectCategory.BLUR,
            description = "Separable gaussian blur, radius in pixels.",
            parameters = listOf(
                ParameterDescriptor("radius", "Radius", ParamKind.SCALAR, 8.0, 0.0, 100.0, 0.5, unit = "px"),
                ParameterDescriptor("horizontal", "Horizontal", ParamKind.SCALAR, 1.0, 0.0, 1.0, 0.01, animatable = false),
                ParameterDescriptor("vertical", "Vertical", ParamKind.SCALAR, 1.0, 0.0, 1.0, 0.01, animatable = false)
            )
        ),
        EffectDefinition(
            typeId = EffectIds.BRIGHTNESS,
            name = "Brightness",
            category = EffectCategory.COLOR,
            description = "Additive brightness shift.",
            parameters = listOf(
                ParameterDescriptor("amount", "Amount", ParamKind.SCALAR, 0.0, -1.0, 1.0, 0.01)
            )
        ),
        EffectDefinition(
            typeId = EffectIds.CONTRAST,
            name = "Contrast",
            category = EffectCategory.COLOR,
            description = "Contrast around mid grey.",
            parameters = listOf(
                ParameterDescriptor("amount", "Amount", ParamKind.SCALAR, 0.0, -1.0, 1.0, 0.01)
            )
        ),
        EffectDefinition(
            typeId = EffectIds.SATURATION,
            name = "Saturation",
            category = EffectCategory.COLOR,
            description = "0 removes all colour, 2 doubles it.",
            parameters = listOf(
                ParameterDescriptor("amount", "Amount", ParamKind.SCALAR, 1.0, 0.0, 2.0, 0.01)
            )
        ),
        EffectDefinition(
            typeId = EffectIds.HUE,
            name = "Hue / Saturation",
            category = EffectCategory.COLOR,
            description = "Rotates hue while scaling saturation and lightness.",
            parameters = listOf(
                ParameterDescriptor("hue", "Hue", ParamKind.ANGLE, 0.0, -180.0, 180.0, 1.0, unit = "°"),
                ParameterDescriptor("saturation", "Saturation", ParamKind.SCALAR, 1.0, 0.0, 2.0, 0.01),
                ParameterDescriptor("lightness", "Lightness", ParamKind.SCALAR, 0.0, -1.0, 1.0, 0.01)
            )
        ),
        EffectDefinition(
            typeId = EffectIds.EXPOSURE,
            name = "Exposure",
            category = EffectCategory.LIGHT,
            description = "Stops of exposure, 1 stop doubles the light.",
            parameters = listOf(
                ParameterDescriptor("stops", "Stops", ParamKind.SCALAR, 0.0, -3.0, 3.0, 0.05, unit = "EV")
            )
        ),
        EffectDefinition(
            typeId = EffectIds.SHARPEN,
            name = "Sharpen",
            category = EffectCategory.BLUR,
            description = "Unsharp mask style sharpening.",
            parameters = listOf(
                ParameterDescriptor("amount", "Amount", ParamKind.SCALAR, 0.5, 0.0, 2.0, 0.01),
                ParameterDescriptor("radius", "Radius", ParamKind.SCALAR, 1.0, 0.5, 4.0, 0.1, unit = "px")
            )
        ),
        EffectDefinition(
            typeId = EffectIds.GLOW,
            name = "Glow",
            category = EffectCategory.LIGHT,
            description = "Bright pass blurred and added back over the layer.",
            parameters = listOf(
                ParameterDescriptor("threshold", "Threshold", ParamKind.SCALAR, 0.6, 0.0, 1.0, 0.01),
                ParameterDescriptor("intensity", "Intensity", ParamKind.SCALAR, 1.0, 0.0, 3.0, 0.01),
                ParameterDescriptor("radius", "Radius", ParamKind.SCALAR, 12.0, 0.0, 60.0, 0.5, unit = "px"),
                ParameterDescriptor("color", "Tint", ParamKind.COLOR, Color.WHITE)
            )
        ),
        EffectDefinition(
            typeId = EffectIds.OPACITY,
            name = "Opacity",
            category = EffectCategory.UTILITY,
            description = "Multiplies layer opacity.",
            parameters = listOf(
                ParameterDescriptor("opacity", "Opacity", ParamKind.SCALAR, 1.0, 0.0, 1.0, 0.01)
            )
        ),
        EffectDefinition(
            typeId = EffectIds.VIGNETTE,
            name = "Vignette",
            category = EffectCategory.STYLIZE,
            description = "Darkens the corners of the layer.",
            parameters = listOf(
                ParameterDescriptor("amount", "Amount", ParamKind.SCALAR, 0.5, 0.0, 1.0, 0.01),
                ParameterDescriptor("size", "Size", ParamKind.SCALAR, 0.6, 0.0, 1.0, 0.01),
                ParameterDescriptor("roundness", "Roundness", ParamKind.SCALAR, 0.5, 0.0, 1.0, 0.01)
            )
        ),
        EffectDefinition(
            typeId = EffectIds.TINT,
            name = "Tint",
            category = EffectCategory.COLOR,
            description = "Blends a solid colour over the layer.",
            parameters = listOf(
                ParameterDescriptor("color", "Colour", ParamKind.COLOR, Color.fromHex("#6C5CE7")),
                ParameterDescriptor("amount", "Amount", ParamKind.SCALAR, 0.5, 0.0, 1.0, 0.01)
            )
        ),
        EffectDefinition(
            typeId = EffectIds.INVERT,
            name = "Invert",
            category = EffectCategory.STYLIZE,
            description = "Inverts RGB channels.",
            parameters = listOf(
                ParameterDescriptor("amount", "Amount", ParamKind.SCALAR, 1.0, 0.0, 1.0, 0.01)
            )
        ),
        EffectDefinition(
            typeId = EffectIds.TEMPERATURE,
            name = "Temperature",
            category = EffectCategory.COLOR,
            description = "Warm (+) or cool (-) white balance shift.",
            parameters = listOf(
                ParameterDescriptor("temperature", "Temperature", ParamKind.SCALAR, 0.0, -1.0, 1.0, 0.01),
                ParameterDescriptor("tint", "Tint", ParamKind.SCALAR, 0.0, -1.0, 1.0, 0.01)
            )
        )
    )

    fun register() {
        EffectRegistry.registerAll(definitions())
    }
}
