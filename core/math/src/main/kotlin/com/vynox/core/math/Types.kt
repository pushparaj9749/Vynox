package com.vynox.core.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

const val EPSILON = 1e-9

fun clamp(value: Double, min: Double, max: Double): Double = when {
    value < min -> min
    value > max -> max
    else -> value
}

fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

fun Double.remap(fromLow: Double, fromHigh: Double, toLow: Double, toHigh: Double): Double {
    if (abs(fromHigh - fromLow) < EPSILON) return toLow
    return toLow + (this - fromLow) * (toHigh - toLow) / (fromHigh - fromLow)
}

fun degreesToRadians(degrees: Double): Double = degrees * PI / 180.0
fun radiansToDegrees(radians: Double): Double = radians * 180.0 / PI

/** 2D vector used for positions, sizes, scale and anchor points. */
data class Vec2(val x: Double = 0.0, val y: Double = 0.0) {

    operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)
    operator fun times(scalar: Double) = Vec2(x * scalar, y * scalar)
    operator fun times(other: Vec2) = Vec2(x * other.x, y * other.y)
    operator fun div(scalar: Double) = Vec2(x / scalar, y / scalar)
    operator fun unaryMinus() = Vec2(-x, -y)

    val length: Double get() = hypot(x, y)
    fun distanceTo(other: Vec2): Double = hypot(x - other.x, y - other.y)
    fun normalized(): Vec2 = if (length < EPSILON) ZERO else Vec2(x / length, y / length)
    fun rotated(radians: Double): Vec2 {
        val c = cos(radians)
        val s = sin(radians)
        return Vec2(x * c - y * s, x * s + y * c)
    }
    fun scaled(sx: Double, sy: Double) = Vec2(x * sx, y * sy)
    fun lerpTo(other: Vec2, t: Double) = Vec2(lerp(x, other.x, t), lerp(y, other.y, t))

    companion object {
        val ZERO = Vec2(0.0, 0.0)
        val ONE = Vec2(1.0, 1.0)
        val CENTER = Vec2(0.5, 0.5)
    }
}

/** 3D vector used for scale and future 3D transforms. */
data class Vec3(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 1.0) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Double) = Vec3(x * scalar, y * scalar, z * scalar)
    fun lerpTo(other: Vec3, t: Double) = Vec3(lerp(x, other.x, t), lerp(y, other.y, t), lerp(z, other.z, t))

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val ONE = Vec3(1.0, 1.0, 1.0)
    }
}

data class Size(val width: Double, val height: Double) {
    val area: Double get() = width * height
    val center: Vec2 get() = Vec2(width / 2.0, height / 2.0)
    fun toVec2() = Vec2(width, height)
    companion object {
        val ZERO = Size(0.0, 0.0)
    }
}

data class Rect(val x: Double = 0.0, val y: Double = 0.0, val width: Double = 0.0, val height: Double = 0.0) {
    val left: Double get() = x
    val top: Double get() = y
    val right: Double get() = x + width
    val bottom: Double get() = y + height
    val center: Vec2 get() = Vec2(x + width / 2.0, y + height / 2.0)
    val position: Vec2 get() = Vec2(x, y)
    val size: Size get() = Size(width, height)

    fun contains(point: Vec2): Boolean =
        point.x >= left && point.x <= right && point.y >= top && point.y <= bottom

    fun inflate(amount: Double): Rect =
        Rect(x - amount, y - amount, width + amount * 2, height + amount * 2)

    fun intersect(other: Rect): Rect? {
        val l = max(left, other.left)
        val t = max(top, other.top)
        val r = min(right, other.right)
        val b = min(bottom, other.bottom)
        return if (r <= l || b <= t) null else Rect(l, t, r - l, b - t)
    }

    companion object {
        fun fromCenter(center: Vec2, size: Size) =
            Rect(center.x - size.width / 2.0, center.y - size.height / 2.0, size.width, size.height)

        val ZERO = Rect(0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Linear RGBA colour with components in 0..1. Serialized as ARGB integer so the
 * on-disk format stays compact and easy to inspect.
 */
data class Color(val r: Double = 0.0, val g: Double = 0.0, val b: Double = 0.0, val a: Double = 1.0) {

    val argb: Int
        get() {
            val ai = (clamp(a, 0.0, 1.0) * 255.0 + 0.5).toInt()
            val ri = (clamp(r, 0.0, 1.0) * 255.0 + 0.5).toInt()
            val gi = (clamp(g, 0.0, 1.0) * 255.0 + 0.5).toInt()
            val bi = (clamp(b, 0.0, 1.0) * 255.0 + 0.5).toInt()
            return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
        }

    fun withAlpha(alpha: Double) = copy(a = alpha)

    fun lerpTo(other: Color, t: Double): Color {
        // Interpolating in linear space keeps gradients free of muddy mid tones.
        val sr = sqrt(clamp(r, 0.0, 1.0))
        val sg = sqrt(clamp(g, 0.0, 1.0))
        val sb = sqrt(clamp(b, 0.0, 1.0))
        val or = sqrt(clamp(other.r, 0.0, 1.0))
        val og = sqrt(clamp(other.g, 0.0, 1.0))
        val ob = sqrt(clamp(other.b, 0.0, 1.0))
        val nr = lerp(sr, or, t)
        val ng = lerp(sg, og, t)
        val nb = lerp(sb, ob, t)
        return Color(nr * nr, ng * ng, nb * nb, lerp(a, other.a, t))
    }

    fun toFloatArray(): FloatArray =
        floatArrayOf(clamp(r, 0.0, 1.0).toFloat(), clamp(g, 0.0, 1.0).toFloat(), clamp(b, 0.0, 1.0).toFloat(), clamp(a, 0.0, 1.0).toFloat())

    companion object {
        val TRANSPARENT = Color(0.0, 0.0, 0.0, 0.0)
        val BLACK = Color(0.0, 0.0, 0.0, 1.0)
        val WHITE = Color(1.0, 1.0, 1.0, 1.0)

        fun fromArgb(argb: Int): Color {
            val a = ((argb ushr 24) and 0xFF) / 255.0
            val r = ((argb ushr 16) and 0xFF) / 255.0
            val g = ((argb ushr 8) and 0xFF) / 255.0
            val b = (argb and 0xFF) / 255.0
            return Color(r, g, b, a)
        }

        fun fromHex(hex: String): Color {
            val clean = hex.removePrefix("#")
            return when (clean.length) {
                6 -> fromArgb(0xFF000000.toInt() or clean.toLong(16).toInt())
                8 -> fromArgb(clean.toLong(16).toInt())
                else -> WHITE
            }
        }
    }
}

/**
 * Row major 3x3 matrix for affine 2D transforms (the only transform type the
 * compositor needs). Column major conversion helpers exist for OpenGL upload.
 */
class Matrix3(
    val m00: Double, val m01: Double, val m02: Double,
    val m10: Double, val m11: Double, val m12: Double,
    val m20: Double, val m21: Double, val m22: Double
) {

    fun transformPoint(point: Vec2): Vec2 {
        val x = m00 * point.x + m01 * point.y + m02
        val y = m10 * point.x + m11 * point.y + m12
        val w = m20 * point.x + m21 * point.y + m22
        return if (abs(w - 1.0) < EPSILON) Vec2(x, y) else Vec2(x / w, y / w)
    }

    fun multiply(other: Matrix3): Matrix3 {
        fun dot(r: Int, c: Int): Double {
            val a = when (r) { 0 -> doubleArrayOf(m00, m01, m02); 1 -> doubleArrayOf(m10, m11, m12); else -> doubleArrayOf(m20, m21, m22) }
            val b = when (c) {
                0 -> doubleArrayOf(other.m00, other.m10, other.m20)
                1 -> doubleArrayOf(other.m01, other.m11, other.m21)
                else -> doubleArrayOf(other.m02, other.m12, other.m22)
            }
            return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        }
        return Matrix3(
            dot(0, 0), dot(0, 1), dot(0, 2),
            dot(1, 0), dot(1, 1), dot(1, 2),
            dot(2, 0), dot(2, 1), dot(2, 2)
        )
    }

    fun toFloatArray(): FloatArray = floatArrayOf(
        m00.toFloat(), m01.toFloat(), m02.toFloat(),
        m10.toFloat(), m11.toFloat(), m12.toFloat(),
        m20.toFloat(), m21.toFloat(), m22.toFloat()
    )

    companion object {
        val IDENTITY = Matrix3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        fun translation(tx: Double, ty: Double) = Matrix3(1.0, 0.0, tx, 0.0, 1.0, ty, 0.0, 0.0, 1.0)

        fun scale(sx: Double, sy: Double) = Matrix3(sx, 0.0, 0.0, 0.0, sy, 0.0, 0.0, 0.0, 1.0)

        fun rotation(radians: Double): Matrix3 {
            val c = cos(radians)
            val s = sin(radians)
            return Matrix3(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)
        }

        /**
         * Builds the classic layer transform: scale about the anchor, rotate,
         * then translate to the layer position.
         */
        fun layerTransform(
            position: Vec2,
            anchor: Vec2,
            scale: Vec2,
            rotationDegrees: Double,
            contentSize: Size
        ): Matrix3 {
            val ax = anchor.x * contentSize.width
            val ay = anchor.y * contentSize.height
            val translate = translation(position.x, position.y)
            val rotate = rotation(degreesToRadians(rotationDegrees))
            val scaleM = scale(scale.x, scale.y)
            val preAnchor = translation(-ax, -ay)
            return translate.multiply(rotate).multiply(scaleM).multiply(preAnchor)
        }
    }
}
