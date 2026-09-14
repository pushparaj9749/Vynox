package com.vynox.core.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MathTest {

    @Test
    fun vec2SupportsAlgebra() {
        val a = Vec2(3.0, 4.0)
        val b = Vec2(1.0, 2.0)
        assertEquals(Vec2(4.0, 6.0), a + b)
        assertEquals(Vec2(2.0, 2.0), a - b)
        assertEquals(Vec2(6.0, 8.0), a * 2.0)
        assertEquals(5.0, a.length, 1e-9)
        assertEquals(5.0, a.distanceTo(Vec2.ZERO), 1e-9)
        assertEquals(Vec2(1.0, 0.0), Vec2(1.0, 0.0).normalized())
        val turned = Vec2(1.0, 0.0).rotated(Math.PI / 2)
        assertTrue(abs(turned.x) < 1e-9)
        assertTrue(abs(turned.y - 1.0) < 1e-9)
    }

    @Test
    fun colorRoundTripsArgb() {
        val color = Color(0.2, 0.4, 0.6, 0.8)
        val decoded = Color.fromArgb(color.argb)
        assertEquals(color.r, decoded.r, 0.01)
        assertEquals(color.g, decoded.g, 0.01)
        assertEquals(color.b, decoded.b, 0.01)
        assertEquals(color.a, decoded.a, 0.01)
        assertEquals(0xFFFFFFFF.toInt(), Color.WHITE.argb)
        assertEquals(0, Color.TRANSPARENT.argb)
    }

    @Test
    fun colorInterpolationIsMonotonic() {
        val from = Color.BLACK
        val to = Color.WHITE
        val mid = from.lerpTo(to, 0.5)
        assertTrue(mid.r > from.r && mid.r < to.r)
        assertEquals(from, from.lerpTo(to, 0.0))
        assertEquals(to.r, from.lerpTo(to, 1.0).r, 1e-9)
    }

    @Test
    fun rectGeometryIsCorrect() {
        val rect = Rect(10.0, 20.0, 100.0, 50.0)
        assertEquals(Vec2(60.0, 45.0), rect.center)
        assertTrue(rect.contains(Vec2(15.0, 25.0)))
        assertTrue(!rect.contains(Vec2(5.0, 25.0)))
        assertEquals(Rect(0.0, 0.0, 0.0, 0.0), rect.intersect(Rect(200.0, 200.0, 10.0, 10.0)) ?: Rect.ZERO)
        assertEquals(110.0, rect.right, 0.0)
        assertEquals(70.0, rect.bottom, 0.0)
    }

    @Test
    fun matrixAppliesTranslateScaleRotate() {
        val translate = Matrix3.translation(10.0, 20.0)
        assertEquals(Vec2(11.0, 22.0), translate.transformPoint(Vec2(1.0, 2.0)))

        val scale = Matrix3.scale(2.0, 3.0)
        assertEquals(Vec2(2.0, 6.0), scale.transformPoint(Vec2(1.0, 2.0)))

        val rotate = Matrix3.rotation(Math.PI / 2)
        val rotated = rotate.transformPoint(Vec2(1.0, 0.0))
        assertTrue(abs(rotated.x) < 1e-9)
        assertTrue(abs(rotated.y - 1.0) < 1e-9)
    }

    @Test
    fun layerTransformRotatesAroundAnchor() {
        val size = Size(100.0, 100.0)
        val matrix = Matrix3.layerTransform(
            position = Vec2(0.0, 0.0),
            anchor = Vec2.CENTER,
            scale = Vec2.ONE,
            rotationDegrees = 0.0,
            contentSize = size
        )
        // Center anchor maps the layer centre onto the position.
        assertEquals(Vec2(0.0, 0.0), matrix.transformPoint(Vec2(50.0, 50.0)))

        val rotated = Matrix3.layerTransform(
            position = Vec2(0.0, 0.0),
            anchor = Vec2.CENTER,
            scale = Vec2.ONE,
            rotationDegrees = 90.0,
            contentSize = size
        )
        val topLeft = rotated.transformPoint(Vec2(0.0, 0.0))
        assertTrue(abs(topLeft.x + 50.0) < 1e-6)
        assertTrue(abs(topLeft.y + 50.0) < 1e-6)
    }

    @Test
    fun matrixMultiplicationComposes() {
        val a = Matrix3.translation(5.0, 0.0)
        val b = Matrix3.scale(2.0, 2.0)
        val composed = a.multiply(b)
        val point = composed.transformPoint(Vec2(1.0, 1.0))
        assertEquals(Vec2(7.0, 2.0), point)
    }

    @Test
    fun utilityFunctionsBehave() {
        assertEquals(5.0, clamp(10.0, 0.0, 5.0), 0.0)
        assertEquals(2.5, lerp(0.0, 5.0, 0.5), 0.0)
        assertEquals(0.5, 5.0.remap(0.0, 10.0, 0.0, 1.0), 1e-9)
        assertEquals(180.0, radiansToDegrees(Math.PI), 1e-9)
        assertEquals(Math.PI, degreesToRadians(180.0), 1e-9)
    }
}
