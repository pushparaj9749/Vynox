package com.vynox.app.render.gl

import com.vynox.core.effects.ResolvedEffect

/** Fused colour grading parameters (neutral by default). */
data class GradeParams(
    var brightness: Float = 0f,
    var contrast: Float = 0f,
    var saturation: Float = 1f,
    var hue: Float = 0f,
    var lightness: Float = 0f,
    var exposure: Float = 0f,
    var temperature: Float = 0f,
    var greenTint: Float = 0f,
    var invert: Float = 0f,
    var opacity: Float = 1f,
    var tintColor: FloatArray = floatArrayOf(1f, 1f, 1f),
    var tintAmount: Float = 0f,
    var vignetteAmount: Float = 0f,
    var vignetteSize: Float = 0.6f,
    var vignetteRoundness: Float = 0.5f
) {
    fun isNeutral(): Boolean =
        brightness == 0f && contrast == 0f && saturation == 1f && hue == 0f &&
            lightness == 0f && exposure == 0f && temperature == 0f && greenTint == 0f &&
            invert == 0f && opacity == 1f && tintAmount == 0f && vignetteAmount == 0f
}

sealed class EffectPass {
    data class Grade(val params: GradeParams) : EffectPass()
    data class Blur(val radius: Float, val horizontal: Boolean) : EffectPass()
    data class Sharpen(val amount: Float, val radius: Float) : EffectPass()
    data class GlowBright(val threshold: Float) : EffectPass()
    data class GlowBlur(val radius: Float, val horizontal: Boolean) : EffectPass()
    data class GlowCombine(val intensity: Float, val color: FloatArray) : EffectPass()
}

/**
 * Translates resolved effects into GPU passes.
 *
 * Colour-family effects are fused into one pass (fast and artefact free);
 * blur/sharpen/glow expand into their required multi-pass sequences.
 */
object EffectPlanner {

    private const val ID_BLUR = "vynox.blur"
    private const val ID_SHARPEN = "vynox.sharpen"
    private const val ID_GLOW = "vynox.glow"

    fun plan(effects: List<ResolvedEffect>): List<EffectPass> {
        val passes = ArrayList<EffectPass>()
        var grade = GradeParams()
        var pendingGlow: Pair<Float, FloatArray>? = null

        fun flushGrade() {
            if (!grade.isNeutral()) passes += EffectPass.Grade(grade)
            grade = GradeParams()
        }

        effects.forEach { effect ->
            when (effect.shaderId) {
                "vynox.brightness" -> grade.brightness += effect.double("amount").toFloat()
                "vynox.contrast" -> grade.contrast += effect.double("amount").toFloat()
                "vynox.saturation" -> grade.saturation *= effect.double("amount", 1.0).toFloat()
                "vynox.hue" -> {
                    grade.hue += effect.double("hue").toFloat()
                    grade.saturation *= effect.double("saturation", 1.0).toFloat()
                    grade.lightness += effect.double("lightness").toFloat()
                }
                "vynox.exposure" -> grade.exposure += effect.double("stops").toFloat()
                "vynox.invert" -> grade.invert = grade.invert.coerceAtLeast(effect.double("amount").toFloat())
                "vynox.opacity" -> grade.opacity *= effect.double("opacity", 1.0).toFloat()
                "vynox.temperature" -> {
                    grade.temperature += effect.double("temperature").toFloat()
                    grade.greenTint += effect.double("tint").toFloat()
                }
                "vynox.tint" -> {
                    grade.tintColor = effect.color("color").toFloatArray()
                    grade.tintAmount = effect.double("amount").toFloat()
                }
                "vynox.vignette" -> {
                    grade.vignetteAmount = effect.double("amount").toFloat()
                    grade.vignetteSize = effect.double("size", 0.6).toFloat()
                    grade.vignetteRoundness = effect.double("roundness", 0.5).toFloat()
                }
                ID_BLUR -> {
                    flushGrade()
                    val radius = effect.double("radius").toFloat()
                    if (radius > 0f) {
                        passes += EffectPass.Blur(radius, horizontal = true)
                        passes += EffectPass.Blur(radius, horizontal = false)
                    }
                }
                ID_SHARPEN -> {
                    flushGrade()
                    val amount = effect.double("amount").toFloat()
                    if (amount > 0f) {
                        passes += EffectPass.Sharpen(amount, effect.double("radius", 1.0).toFloat())
                    }
                }
                ID_GLOW -> {
                    flushGrade()
                    val radius = effect.double("radius").toFloat()
                    val intensity = effect.double("intensity", 1.0).toFloat()
                    val color = effect.color("color").toFloatArray()
                    if (intensity > 0f) {
                        passes += EffectPass.GlowBright(effect.double("threshold", 0.6).toFloat())
                        if (radius > 0f) {
                            passes += EffectPass.GlowBlur(radius, horizontal = true)
                            passes += EffectPass.GlowBlur(radius, horizontal = false)
                        }
                        pendingGlow = intensity to color
                        passes += EffectPass.GlowCombine(intensity, color)
                    }
                }
                else -> {
                    // Unknown effect: ignored (architecture supports adding it later).
                }
            }
        }
        flushGrade()
        return passes
    }
}
