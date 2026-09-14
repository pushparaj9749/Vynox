package com.vynox.core.model

/** Every layer kind the compositor understands. */
enum class LayerType {
    VIDEO,
    IMAGE,
    TEXT,
    SHAPE,
    AUDIO,
    /** Null object / group used to parent and animate other layers. */
    GROUP
}

enum class AssetType { VIDEO, IMAGE, AUDIO, FONT }

/**
 * Compositing modes. The subset marked [gpuSupported] is implemented by the
 * OpenGL compositor; the rest fall back to normal blending with a warning in
 * the UI rather than rendering something incorrect.
 */
enum class BlendMode(val label: String, val gpuSupported: Boolean = true) {
    NORMAL("Normal"),
    MULTIPLY("Multiply"),
    SCREEN("Screen"),
    OVERLAY("Overlay"),
    DARKEN("Darken"),
    LIGHTEN("Lighten"),
    ADD("Add"),
    DIFFERENCE("Difference"),
    EXCLUSION("Exclusion"),
    SOFT_LIGHT("Soft Light")
}

enum class ShapeKind(val label: String) {
    RECT("Rectangle"),
    ROUNDED_RECT("Rounded Rectangle"),
    ELLIPSE("Ellipse"),
    LINE("Line"),
    POLYGON("Polygon")
}

enum class TextAlign(val label: String) { LEFT("Left"), CENTER("Center"), RIGHT("Right") }

enum class TextVerticalAlign(val label: String) { TOP("Top"), CENTER("Center"), BOTTOM("Bottom") }

enum class MaskShape(val label: String) { RECT("Rectangle"), ELLIPSE("Ellipse"), PATH("Path") }

enum class MaskMode(val label: String) { ADD("Add"), SUBTRACT("Subtract"), INTERSECT("Intersect") }

enum class LayerLabel(val label: String, val colorArgb: Int) {
    NONE("None", 0x00000000.toInt()),
    RED("Red", 0xFFEF5350.toInt()),
    ORANGE("Orange", 0xFFFFA726.toInt()),
    YELLOW("Yellow", 0xFFFFEE58.toInt()),
    GREEN("Green", 0xFF66BB6A.toInt()),
    BLUE("Blue", 0xFF42A5F5.toInt()),
    PURPLE("Purple", 0xFFAB47BC.toInt()),
    GREY("Grey", 0xFFBDBDBD.toInt())
}
