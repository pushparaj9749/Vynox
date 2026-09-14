package com.vynox.core.effects

import com.vynox.core.animation.AnimatableOps
import com.vynox.core.animation.ScalarInterpolator
import com.vynox.core.math.Color
import com.vynox.core.model.EffectInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectsTest {

    init {
        if (EffectRegistry.all().isEmpty()) BuiltInEffects.register()
    }

    @Test
    fun registryExposesBuiltInEffects() {
        val all = EffectRegistry.all()
        assertTrue(all.size >= 9)
        listOf(
            EffectIds.BLUR, EffectIds.BRIGHTNESS, EffectIds.CONTRAST, EffectIds.SATURATION,
            EffectIds.HUE, EffectIds.EXPOSURE, EffectIds.SHARPEN, EffectIds.GLOW, EffectIds.OPACITY
        ).forEach { id ->
            assertNotNull("expected effect $id", EffectRegistry.get(id))
        }
    }

    @Test
    fun effectsAreGroupedByCategory() {
        val grouped = EffectRegistry.byCategory()
        assertTrue(grouped.containsKey(EffectCategory.COLOR))
        assertTrue(grouped.containsKey(EffectCategory.BLUR))
    }

    @Test
    fun instantiateSeedsDefaultParameters() {
        val instance = EffectRegistry.create(EffectIds.BLUR)!!
        assertEquals(EffectIds.BLUR, instance.typeId)
        assertTrue(instance.enabled)
        val radius = instance.parameters["radius"]!!.valueAt(0.0) as Double
        assertEquals(8.0, radius, 1e-9)
    }

    @Test
    fun resolveReturnsValuesAtTime() {
        BuiltInEffects.register()
        val instance = EffectRegistry.create(EffectIds.EXPOSURE)!!
        val resolved = EffectRegistry.resolve(instance, 0.0)
        assertEquals(EffectIds.EXPOSURE, resolved.typeId)
        assertEquals(0.0, resolved.double("stops"), 1e-9)
    }

    @Test
    fun animatedEffectParametersInterpolate() {
        BuiltInEffects.register()
        var instance = EffectRegistry.create(EffectIds.BRIGHTNESS)!!
        val property = instance.parameters["amount"]!!
        val animated = AnimatableOps.setKeyframe(property as com.vynox.core.animation.Animatable<Double>, ScalarInterpolator, 0.0, 0.0)
            .let { AnimatableOps.setKeyframe(it, ScalarInterpolator, 2.0, 1.0) }
        instance = EffectRegistry.withAnimatedProperty(instance, "amount", animated)

        assertEquals(0.0, EffectRegistry.resolve(instance, 0.0).double("amount"), 1e-9)
        assertEquals(0.5, EffectRegistry.resolve(instance, 1.0).double("amount"), 1e-9)
        assertEquals(1.0, EffectRegistry.resolve(instance, 2.0).double("amount"), 1e-9)
    }

    @Test
    fun parametersCanBePromotedToAnimated() {
        BuiltInEffects.register()
        val instance = EffectRegistry.create(EffectIds.SATURATION)!!
        val promoted = EffectRegistry.promoteParameter(instance, "amount", 1.5)
        assertTrue(promoted.parameters["amount"]!!.isAnimated)
        assertEquals(1.0, (promoted.parameters["amount"]!!.valueAt(1.5) as Double), 1e-9)
    }

    @Test
    fun normalizeAddsMissingParameters() {
        BuiltInEffects.register()
        val partial = EffectInstance(id = "fx", typeId = EffectIds.GLOW, enabled = true, parameters = emptyMap())
        val normalized = EffectRegistry.normalize(partial)
        val definition = EffectRegistry.require(EffectIds.GLOW)
        definition.parameters.forEach { desc ->
            assertTrue("missing ${desc.key}", normalized.parameters.containsKey(desc.key))
        }
    }

    @Test
    fun normalizePreservesUnknownParameters() {
        BuiltInEffects.register()
        val extra = EffectInstance(
            id = "fx",
            typeId = EffectIds.BLUR,
            parameters = mapOf("future_param" to com.vynox.core.animation.StaticValue(1.0))
        )
        val normalized = EffectRegistry.normalize(extra)
        assertTrue(normalized.parameters.containsKey("future_param"))
        assertTrue(normalized.parameters.containsKey("radius"))
    }

    @Test
    fun colorParametersResolveToColor() {
        BuiltInEffects.register()
        val instance = EffectRegistry.create(EffectIds.TINT)!!
        val resolved = EffectRegistry.resolve(instance, 0.0)
        val color = resolved.color("color")
        assertEquals(0xFF6C5CE7.toInt(), color.argb)
    }

    @Test
    fun disabledEffectsAreFilteredOut() {
        BuiltInEffects.register()
        val enabled = EffectRegistry.create(EffectIds.BLUR)!!
        val disabled = EffectRegistry.create(EffectIds.GLOW)!!.copy(enabled = false)
        val resolved = EffectRegistry.resolveAll(listOf(enabled, disabled), 0.0)
        assertEquals(1, resolved.size)
        assertEquals(EffectIds.BLUR, resolved.first().typeId)
    }

    @Test
    fun customEffectsCanBeRegisteredAtRuntime() {
        BuiltInEffects.register()
        val custom = EffectDefinition(
            typeId = "com.example.custom",
            name = "Custom",
            category = EffectCategory.STYLIZE,
            parameters = listOf(ParameterDescriptor("power", "Power", ParamKind.SCALAR, 2.0, 0.0, 10.0, 0.1))
        )
        EffectRegistry.register(custom)
        assertEquals("Custom", EffectRegistry.require("com.example.custom").name)
        val instance = EffectRegistry.create("com.example.custom")!!
        assertEquals(2.0, EffectRegistry.resolve(instance, 0.0).double("power"), 1e-9)
    }
}
