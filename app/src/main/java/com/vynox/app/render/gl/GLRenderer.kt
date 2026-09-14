package com.vynox.app.render.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import com.vynox.core.composition.RenderContent
import com.vynox.core.composition.RenderMask
import com.vynox.core.composition.RenderNode
import com.vynox.core.composition.RenderPlan
import com.vynox.core.math.Matrix3
import com.vynox.core.math.Size
import com.vynox.core.model.BlendMode
import com.vynox.core.model.MaskShape
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A texture the compositor can draw. */
data class LayerTexture(
    val textureId: Int,
    val width: Int,
    val height: Int,
    val isExternalOes: Boolean = false,
    val texMatrix: FloatArray = IDENTITY_MATRIX,
    /** Padding (in content pixels) around rasterized graphics, e.g. text stroke. */
    val padLeft: Float = 0f,
    val padTop: Float = 0f
) {
    companion object {
        val IDENTITY_MATRIX = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }
}

/** Supplies decoded media and rasterized graphics to the renderer. */
interface RenderSourceProvider {
    fun videoTexture(assetId: String, sourceTime: Double): LayerTexture?
    fun imageTexture(assetId: String): LayerTexture?
    fun textTexture(node: RenderNode): LayerTexture?
    fun shapeTexture(node: RenderNode): LayerTexture?
    fun maskPathTexture(mask: RenderMask, scale: Float): LayerTexture?
    fun placeholder(): LayerTexture?
}

/**
 * OpenGL ES 2.0 compositor.
 *
 * Renders a [RenderPlan] produced by the engine. The same code path is used for
 * the interactive preview and for export (the encoder's input surface is simply
 * another EGLSurface), which guarantees "what you see is what you export".
 *
 * Pipeline per layer:
 *   content -> mask -> effect chain -> blend onto scene
 */
class GLRenderer {

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var eglConfig: EGLConfig? = null
    private var attachedSurface: Surface? = null

    private var renderWidth = 0
    private var renderHeight = 0

    private var sceneA: Framebuffer? = null
    private var sceneB: Framebuffer? = null
    private var layerA: Framebuffer? = null
    private var layerB: Framebuffer? = null
    private var maskBuffer: Framebuffer? = null
    private var glowA: Framebuffer? = null
    private var glowB: Framebuffer? = null

    private var programTexture: GLProgram? = null
    private var programVideo: GLProgram? = null
    private var programSolid: GLProgram? = null
    private var programMask: GLProgram? = null
    private var programMaskApply: GLProgram? = null
    private var programGrade: GLProgram? = null
    private var programBlur: GLProgram? = null
    private var programSharpen: GLProgram? = null
    private var programGlowBright: GLProgram? = null
    private var programGlowCombine: GLProgram? = null
    private var programBlend: GLProgram? = null

    private val identityClip = floatArrayOf(2f, 0f, 0f, 0f, -1f, 0f, -1f, 1f, 1f)

    /**
     * Stamps the next swapped buffer with a presentation timestamp.
     * Required by the export encoder to produce a correctly timed MP4.
     */
    fun setPresentationTime(presentationTimeUs: Long) {
        val display = eglDisplay ?: return
        val surface = eglSurface ?: return
        EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeUs * 1000L)
    }

    val isReady: Boolean get() = eglContext != null

    // ------------------------------------------------------------------- setup

    fun attach(surface: Surface, width: Int, height: Int) {
        val recreateContext = eglContext == null
        if (recreateContext) {
            createContext()
        }
        createWindowSurface(surface)
        renderWidth = width.coerceAtLeast(1)
        renderHeight = height.coerceAtLeast(1)
        attachedSurface = surface
        makeCurrent()
        if (recreateContext) {
            buildPrograms()
        }
        ensureBuffers()
    }

    fun resize(width: Int, height: Int) {
        renderWidth = width.coerceAtLeast(1)
        renderHeight = height.coerceAtLeast(1)
        if (eglContext == null) return
        makeCurrent()
        ensureBuffers()
    }

    private fun createContext() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        require(display != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL display" }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw IllegalStateException("Unable to initialize EGL")
        }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0)
        val config = configs[0] ?: throw IllegalStateException("No EGL config found")
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (context == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("Unable to create EGL context")
        eglDisplay = display
        eglConfig = config
        eglContext = context
    }

    private fun createWindowSurface(surface: Surface) {
        val display = eglDisplay ?: return
        val config = eglConfig ?: return
        releaseWindowSurface()
        val attribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, attribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("Unable to create EGL window surface")
        }
        this.eglSurface = eglSurface
    }

    private fun makeCurrent() {
        val display = eglDisplay ?: return
        val context = eglContext ?: return
        val surface = eglSurface ?: return
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw IllegalStateException("eglMakeCurrent failed")
        }
    }

    private fun buildPrograms() {
        programTexture = GLProgram(Shaders.VERTEX_QUAD, Shaders.FRAG_TEXTURE)
        programVideo = GLProgram(Shaders.VERTEX_QUAD, Shaders.FRAG_VIDEO)
        programSolid = GLProgram(Shaders.VERTEX_QUAD, Shaders.FRAG_SOLID)
        programMask = GLProgram(Shaders.VERTEX_QUAD, Shaders.FRAG_MASK)
        programMaskApply = GLProgram(Shaders.VERTEX_QUAD, Shaders.FRAG_MASK_APPLY)
        programGrade = GLProgram(Shaders.VERTEX_QUAD, Shaders.FRAG_GRADE)
        programBlur = GLProgram(Shaders.VERTEX_QUAD, Shaders.FRAG_BLUR)
        programSharpen = GLProgram(Shaders.VERTEX_QUAD, Shaders.FRAG_SHARPEN)
        programGlowBright = GLProgram(Shaders.VERTEX_QUAD, Shaders.FRAG_GLOW_BRIGHT)
        programGlowCombine = GLProgram(Shaders.VERTEX_QUAD, Shaders.FRAG_GLOW_COMBINE)
        programBlend = GLProgram(Shaders.VERTEX_QUAD, Shaders.FRAG_BLEND)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
    }

    private fun ensureBuffers() {
        val width = renderWidth
        val height = renderHeight
        sceneA?.resize(width, height) ?: run { sceneA = Framebuffer(width, height) }
        sceneB?.resize(width, height) ?: run { sceneB = Framebuffer(width, height) }
        layerA?.resize(width, height) ?: run { layerA = Framebuffer(width, height) }
        layerB?.resize(width, height) ?: run { layerB = Framebuffer(width, height) }
        maskBuffer?.resize(width, height) ?: run { maskBuffer = Framebuffer(width, height) }
        glowA?.resize(width, height) ?: run { glowA = Framebuffer(width, height) }
        glowB?.resize(width, height) ?: run { glowB = Framebuffer(width, height) }
    }

    // ----------------------------------------------------------------- render

    fun render(plan: RenderPlan, sources: RenderSourceProvider) {
        if (eglContext == null) return
        val scene = sceneA ?: return
        val scratch = sceneB ?: return
        val layerTarget = layerA ?: return
        val layerScratch = layerB ?: return

        makeCurrent()
        ensureBuffers()

        var currentScene = scene
        var otherScene = scratch

        currentScene.bind()
        GLES20.glDisable(GLES20.GL_BLEND)
        val background = plan.background
        currentScene.clear(background.r.toFloat(), background.g.toFloat(), background.b.toFloat(), 1f)

        plan.nodes.forEach { node ->
            val texture = textureFor(node, sources) ?: return@forEach
            val contentSize = Size(
                if (texture.width > 0) texture.width.toDouble() else node.contentSize.width,
                if (texture.height > 0) texture.height.toDouble() else node.contentSize.height
            )

            var src = layerTarget
            var dst = layerScratch

            // 1. Draw the content with its transform into the layer buffer.
            src.bind()
            GLES20.glDisable(GLES20.GL_BLEND)
            src.clear(0f, 0f, 0f, 0f)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            drawContent(texture, node, 1f)

            // 2. Masks
            if (node.masks.isNotEmpty()) {
                applyMasks(node, sources, src, maskScale(plan))
            }

            // 3. Effect chain
            val passes = EffectPlanner.plan(node.effects)
            if (passes.isNotEmpty()) {
                val ended = applyEffects(passes, src, dst)
                src = ended
                dst = if (ended === layerTarget) layerScratch else layerTarget
            }

            // 4. Composite onto the scene with the layer's blend mode.
            otherScene.bind()
            GLES20.glDisable(GLES20.GL_BLEND)
            otherScene.clear(0f, 0f, 0f, 0f)
            val blend = programBlend ?: return
            blend.use()
            Quad.bind(blend)
            blend.setMatrix3("uMatrix", identityClip)
            blend.bindTexture("uBackdrop", 0, currentScene.textureId)
            blend.bindTexture("uSource", 1, src.textureId)
            blend.setFloat("uOpacity", node.opacity.toFloat())
            blend.setInt("uMode", blendModeCode(node.blendMode))
            Quad.draw()

            val swapScene = currentScene
            currentScene = otherScene
            otherScene = swapScene
        }

        // 5. Present
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, renderWidth, renderHeight)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val copy = programTexture ?: return
        copy.use()
        Quad.bind(copy)
        copy.setMatrix3("uMatrix", identityClip)
        copy.setFloat("uOpacity", 1f)
        copy.bindTexture("uTexture", 0, currentScene.textureId)
        Quad.draw()

        sceneA = currentScene
        sceneB = otherScene
        layerA = layerTarget
        layerB = layerScratch

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun textureFor(node: RenderNode, sources: RenderSourceProvider): LayerTexture? {
        return when (val content = node.content) {
            is RenderContent.Video -> sources.videoTexture(content.assetId, content.sourceTime)
                ?: sources.placeholder()
            is RenderContent.Image -> sources.imageTexture(content.assetId) ?: sources.placeholder()
            is RenderContent.Text -> sources.textTexture(node)
            is RenderContent.Shape -> sources.shapeTexture(node)
            is RenderContent.Group -> null
        }
    }

    private fun drawContent(texture: LayerTexture, node: RenderNode, opacity: Float) {
        val width = renderWidth.toDouble()
        val height = renderHeight.toDouble()
        val clipFromCanvas = Matrix3(
            2.0 / width, 0.0, -1.0,
            0.0, -2.0 / height, 1.0,
            0.0, 0.0, 1.0
        )
        // Unit quad -> texture pixels, shifted so rasterizer padding lines up
        // with the content box the engine used for transforms and hit testing.
        val contentScale = Matrix3(
            texture.width.toDouble(), 0.0, 0.0,
            0.0, texture.height.toDouble(), 0.0,
            0.0, 0.0, 1.0
        )
        val padding = Matrix3.translation(-texture.padLeft.toDouble(), -texture.padTop.toDouble())
        val matrix = clipFromCanvas.multiply(node.matrix).multiply(padding).multiply(contentScale)

        val program = if (texture.isExternalOes) programVideo else programTexture
        program ?: return
        program.use()
        Quad.bind(program)
        program.setMatrix3("uMatrix", matrix.toColumnMajorFloatArray())
        program.setFloat("uOpacity", opacity)
        if (texture.isExternalOes) {
            program.setMatrix3("uTexMatrix", texture.texMatrix)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture.textureId)
            program.setInt("uTexture", 0)
        } else {
            program.bindTexture("uTexture", 0, texture.textureId)
        }
        Quad.draw()
    }

    private fun applyMasks(
        node: RenderNode,
        sources: RenderSourceProvider,
        layer: Framebuffer,
        scale: Float
    ) {
        val maskBuffer = this.maskBuffer ?: return
        maskBuffer.bind()
        GLES20.glDisable(GLES20.GL_BLEND)
        maskBuffer.clear(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)

        node.masks.forEach { mask ->
            when (mask.mode) {
                com.vynox.core.model.MaskMode.ADD -> {
                    GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
                    GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
                }
                com.vynox.core.model.MaskMode.SUBTRACT -> {
                    GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
                    GLES20.glBlendFunc(GLES20.GL_ZERO, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                }
                com.vynox.core.model.MaskMode.INTERSECT -> {
                    GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
                    GLES20.glBlendFunc(GLES20.GL_ZERO, GLES20.GL_SRC_ALPHA)
                }
            }
            drawMask(mask, sources, scale)
        }

        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Combine the mask alpha with the layer.
        val apply = programMaskApply ?: return
        val target = layerA ?: return
        val scratch = layerB ?: return
        val destination = if (layer === target) scratch else target
        destination.bind()
        GLES20.glDisable(GLES20.GL_BLEND)
        destination.clear(0f, 0f, 0f, 0f)
        apply.use()
        Quad.bind(apply)
        apply.setMatrix3("uMatrix", identityClip)
        apply.bindTexture("uTexture", 0, layer.textureId)
        apply.bindTexture("uMask", 1, maskBuffer.textureId)
        apply.setInt("uMode", 0)
        apply.setFloat("uFeather", 0f)
        Quad.draw()
        copyFramebuffer(destination, layer)
    }

    private fun drawMask(mask: RenderMask, sources: RenderSourceProvider, scale: Float) {
        val program = programMask ?: return
        program.use()
        Quad.bind(program)
        program.setMatrix3("uMatrix", identityClip)
        val shapeCode = when (mask.shape) {
            MaskShape.RECT -> 0
            MaskShape.ELLIPSE -> 1
            MaskShape.PATH -> 2
        }
        program.setInt("uShape", shapeCode)
        program.setVec2("uCenter", (mask.rect.center.x * scale).toFloat(), (mask.rect.center.y * scale).toFloat())
        program.setVec2("uHalfSize", (mask.rect.width / 2f * scale).toFloat(), (mask.rect.height / 2f * scale).toFloat())
        program.setFloat("uRotation", Math.toRadians(mask.rotation).toFloat())
        program.setFloat("uFeather", (mask.feather * scale).toFloat())
        program.setFloat("uOpacity", mask.opacity.toFloat())
        program.setFloat("uInvert", if (mask.invert) 1f else 0f)
        program.setVec2("uCanvasSize", renderWidth.toFloat(), renderHeight.toFloat())
        val pathTexture = if (mask.shape == MaskShape.PATH) sources.maskPathTexture(mask, scale) else null
        program.bindTexture("uPath", 0, pathTexture?.textureId ?: 0)
        Quad.draw()
    }

    /** Applies the effect chain, returning the buffer holding the result. */
    private fun applyEffects(passes: List<EffectPass>, start: Framebuffer, scratch: Framebuffer): Framebuffer {
        var src = start
        var dst = scratch
        val glowA = this.glowA
        val glowB = this.glowB

        fun swap() {
            val temp = src
            src = dst
            dst = temp
        }

        for (pass in passes) {
            when (pass) {
                is EffectPass.Grade -> {
                    val program = programGrade ?: continue
                    dst.bind()
                    GLES20.glDisable(GLES20.GL_BLEND)
                    dst.clear(0f, 0f, 0f, 0f)
                    program.use()
                    Quad.bind(program)
                    program.setMatrix3("uMatrix", identityClip)
                    program.bindTexture("uTexture", 0, src.textureId)
                    program.setFloat("uBrightness", pass.params.brightness)
                    program.setFloat("uContrast", pass.params.contrast)
                    program.setFloat("uSaturation", pass.params.saturation)
                    program.setFloat("uHue", pass.params.hue)
                    program.setFloat("uLightness", pass.params.lightness)
                    program.setFloat("uExposure", pass.params.exposure)
                    program.setFloat("uTemperature", pass.params.temperature)
                    program.setFloat("uGreenTint", pass.params.greenTint)
                    program.setFloat("uInvert", pass.params.invert)
                    program.setFloat("uOpacity", pass.params.opacity)
                    program.setVec3("uTintColor", pass.params.tintColor[0], pass.params.tintColor[1], pass.params.tintColor[2])
                    program.setFloat("uTintAmount", pass.params.tintAmount)
                    program.setFloat("uVignetteAmount", pass.params.vignetteAmount)
                    program.setFloat("uVignetteSize", pass.params.vignetteSize)
                    program.setFloat("uVignetteRoundness", pass.params.vignetteRoundness)
                    Quad.draw()
                    swap()
                }
                is EffectPass.Blur -> {
                    val program = programBlur ?: continue
                    dst.bind()
                    GLES20.glDisable(GLES20.GL_BLEND)
                    dst.clear(0f, 0f, 0f, 0f)
                    program.use()
                    Quad.bind(program)
                    program.setMatrix3("uMatrix", identityClip)
                    program.bindTexture("uTexture", 0, src.textureId)
                    program.setVec2("uTexelSize", 1f / renderWidth, 1f / renderHeight)
                    program.setVec2("uDirection", if (pass.horizontal) 1f else 0f, if (pass.horizontal) 0f else 1f)
                    program.setFloat("uRadius", pass.radius)
                    Quad.draw()
                    swap()
                }
                is EffectPass.Sharpen -> {
                    val program = programSharpen ?: continue
                    dst.bind()
                    GLES20.glDisable(GLES20.GL_BLEND)
                    dst.clear(0f, 0f, 0f, 0f)
                    program.use()
                    Quad.bind(program)
                    program.setMatrix3("uMatrix", identityClip)
                    program.bindTexture("uTexture", 0, src.textureId)
                    program.setVec2("uTexelSize", 1f / renderWidth, 1f / renderHeight)
                    program.setFloat("uAmount", pass.amount)
                    program.setFloat("uRadius", pass.radius)
                    Quad.draw()
                    swap()
                }
                is EffectPass.GlowBright -> {
                    if (glowA == null) continue
                    val program = programGlowBright ?: continue
                    glowA.bind()
                    GLES20.glDisable(GLES20.GL_BLEND)
                    glowA.clear(0f, 0f, 0f, 0f)
                    program.use()
                    Quad.bind(program)
                    program.setMatrix3("uMatrix", identityClip)
                    program.bindTexture("uTexture", 0, src.textureId)
                    program.setFloat("uThreshold", pass.threshold)
                    Quad.draw()
                }
                is EffectPass.GlowBlur -> {
                    if (glowA == null || glowB == null) continue
                    val program = programBlur ?: continue
                    glowB.bind()
                    GLES20.glDisable(GLES20.GL_BLEND)
                    glowB.clear(0f, 0f, 0f, 0f)
                    program.use()
                    Quad.bind(program)
                    program.setMatrix3("uMatrix", identityClip)
                    program.bindTexture("uTexture", 0, glowA.textureId)
                    program.setVec2("uTexelSize", 1f / renderWidth, 1f / renderHeight)
                    program.setVec2("uDirection", if (pass.horizontal) 1f else 0f, if (pass.horizontal) 0f else 1f)
                    program.setFloat("uRadius", pass.radius)
                    Quad.draw()
                    copyFramebuffer(glowB, glowA)
                }
                is EffectPass.GlowCombine -> {
                    if (glowA == null) continue
                    val program = programGlowCombine ?: continue
                    dst.bind()
                    GLES20.glDisable(GLES20.GL_BLEND)
                    dst.clear(0f, 0f, 0f, 0f)
                    program.use()
                    Quad.bind(program)
                    program.setMatrix3("uMatrix", identityClip)
                    program.bindTexture("uTexture", 0, src.textureId)
                    program.bindTexture("uGlow", 1, glowA.textureId)
                    program.setFloat("uIntensity", pass.intensity)
                    program.setVec3("uColor", pass.color[0], pass.color[1], pass.color[2])
                    Quad.draw()
                    swap()
                }
            }
        }
        return src
    }

    private fun copyFramebuffer(from: Framebuffer, to: Framebuffer) {
        val program = programTexture ?: return
        to.bind()
        GLES20.glDisable(GLES20.GL_BLEND)
        program.use()
        Quad.bind(program)
        program.setMatrix3("uMatrix", identityClip)
        program.setFloat("uOpacity", 1f)
        program.bindTexture("uTexture", 0, from.textureId)
        Quad.draw()
    }

    /** Masks are authored in composition pixels; the target may be scaled. */
    private fun maskScale(plan: RenderPlan): Float =
        (renderWidth.toFloat() / plan.canvas.width.toFloat().coerceAtLeast(1f))

    private fun blendModeCode(mode: BlendMode): Int = when (mode) {
        BlendMode.NORMAL -> 0
        BlendMode.MULTIPLY -> 1
        BlendMode.SCREEN -> 2
        BlendMode.OVERLAY -> 3
        BlendMode.DARKEN -> 4
        BlendMode.LIGHTEN -> 5
        BlendMode.ADD -> 6
        BlendMode.DIFFERENCE -> 7
        BlendMode.EXCLUSION -> 8
        BlendMode.SOFT_LIGHT -> 9
    }

    // ------------------------------------------------------------------ output

    /** Reads the last rendered frame (used for project thumbnails). */
    fun capture(): Bitmap? {
        val scene = sceneA ?: return null
        val width = scene.width
        val height = scene.height
        if (width <= 0 || height <= 0) return null
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        scene.bind()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        // GL origin is bottom-left; flip into image orientation.
        val matrix = android.graphics.Matrix().apply { postScale(1f, -1f) }
        return Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
    }

    fun release() {
        listOfNotNull(sceneA, sceneB, layerA, layerB, maskBuffer, glowA, glowB).forEach { it.release() }
        sceneA = null; sceneB = null; layerA = null; layerB = null
        maskBuffer = null; glowA = null; glowB = null
        listOfNotNull(
            programTexture, programVideo, programSolid, programMask, programMaskApply,
            programGrade, programBlur, programSharpen, programGlowBright, programGlowCombine, programBlend
        ).forEach { it.dispose() }
        programTexture = null; programVideo = null; programSolid = null; programMask = null
        programMaskApply = null; programGrade = null; programBlur = null; programSharpen = null
        programGlowBright = null; programGlowCombine = null; programBlend = null
        releaseWindowSurface()
        eglContext?.let { EGL14.eglDestroyContext(eglDisplay, it) }
        eglContext = null
        eglDisplay?.let { EGL14.eglTerminate(it) }
        eglDisplay = null
        attachedSurface = null
    }

    private fun releaseWindowSurface() {
        eglSurface?.let {
            if (it != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, it)
            }
        }
        eglSurface = null
    }
}
