package com.vynox.app.ui.editor

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.vynox.app.VynoxServices
import com.vynox.app.render.audio.AudioMixer
import com.vynox.app.render.audio.AudioPlayback
import com.vynox.app.render.audio.AudioStream
import com.vynox.app.render.gl.GLRenderer
import com.vynox.app.render.source.AndroidTextMetrics
import com.vynox.app.render.source.RenderSources
import com.vynox.core.composition.CompositionEvaluator
import com.vynox.core.model.PreviewQuality
import com.vynox.core.model.VynoxProject
import kotlin.math.abs

/**
 * Owns the preview surface, the GL render loop and preview audio.
 *
 * The composition is re-evaluated every frame from the live project, so any
 * edit - including keyframe drags - is visible immediately and matches what the
 * exporter will produce.
 */
class PreviewEngine(
    private val viewModel: EditorViewModel,
    private var quality: PreviewQuality = PreviewQuality.BALANCED
) : SurfaceHolder.Callback {

    private val renderer = GLRenderer()
    private lateinit var sources: RenderSources
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var surface: SurfaceHolder? = null
    private var view: SurfaceView? = null

    private val audioPlayback = AudioPlayback(SAMPLE_RATE, CHANNELS)
    private val audioMixer = AudioMixer(SAMPLE_RATE, CHANNELS)
    private val audioStreams = HashMap<String, AudioStream>()
    private var lastAudioTime = -1.0

    @Volatile
    private var running = false
    @Volatile
    private var width = 0
    @Volatile
    private var height = 0

    fun attach(view: SurfaceView) {
        this.view = view
        view.holder.addCallback(this)
        sources = RenderSources(VynoxServices.context) { assetId -> viewModel.assetUri(assetId) }
        sources.textMetrics.let { /* platform metrics already wired via AndroidTextMetrics */ }
        viewModel.sources = sources
    }

    fun setQuality(quality: PreviewQuality) {
        this.quality = quality
    }

    fun capture(): Bitmap? = if (::sources.isInitialized.not()) null else renderer.capture()

    fun detach() {
        stop()
        view?.holder?.removeCallback(this)
        audioStreams.values.forEach { it.release() }
        audioStreams.clear()
        audioPlayback.stop()
        renderer.release()
        if (::sources.isInitialized) sources.release()
        viewModel.sources = null
        view = null
    }

    // ------------------------------------------------------------ surface life

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder
        start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        this.width = width
        this.height = height
        surface = holder
        renderer.resize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stop()
        surface = null
    }

    // ------------------------------------------------------------------- loop

    private fun start() {
        if (running) return
        running = true
        val thread = HandlerThread("vynox-preview").apply { start() }
        this.thread = thread
        val handler = Handler(thread.looper)
        this.handler = handler
        val holder = surface ?: return
        val project = viewModel.project.value
        val renderWidth = scaled(project.canvas.width)
        val renderHeight = scaled(project.canvas.height)
        sources.renderWidth = renderWidth
        sources.renderHeight = renderHeight
        renderer.attach(holder.surface, renderWidth, renderHeight)

        audioPlayback.start { frames -> audioFrames(frames) }

        val frameDurationMs = (1000L / project.canvas.fps.coerceAtLeast(1)).coerceAtLeast(8L)
        handler.post(object : Runnable {
            override fun run() {
                if (!running) return
                renderFrame()
                handler.postDelayed(this, frameDurationMs)
            }
        })
    }

    private fun stop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
        audioPlayback.stop()
    }

    private fun scaled(value: Int): Int {
        val scaled = (value * quality.scale).toInt().coerceAtMost(MAX_PREVIEW_WIDTH)
        return if (scaled % 2 == 0) scaled else scaled + 1
    }

    private fun renderFrame() {
        val holder = surface ?: return
        if (holder.surface == null || !holder.surface.isValid) return
        val project: VynoxProject = viewModel.project.value
        val evaluator = CompositionEvaluator(project, AndroidTextMetrics)
        val plan = evaluator.evaluate(viewModel.playhead.value)
        val renderWidth = scaled(project.canvas.width)
        val renderHeight = scaled(project.canvas.height)
        if (sources.renderWidth != renderWidth || sources.renderHeight != renderHeight) {
            sources.renderWidth = renderWidth
            sources.renderHeight = renderHeight
            renderer.resize(renderWidth, renderHeight)
        }
        renderer.render(plan, sources)
    }

    // ------------------------------------------------------------------ audio

    private fun audioFrames(frames: Int): FloatArray {
        if (!viewModel.isPlaying.value) {
            lastAudioTime = -1.0
            return FloatArray(frames * CHANNELS)
        }
        val project = viewModel.project.value
        val time = viewModel.playhead.value
        val evaluator = CompositionEvaluator(project, AndroidTextMetrics)
        val active = evaluator.evaluateAudio(time).filter { !it.muted && it.volume > 0.0 }
        if (active.isEmpty()) return FloatArray(frames * CHANNELS)

        // Re-sync decoders when the playhead jumps (scrub, seek, loop).
        if (lastAudioTime < 0 || abs(time - lastAudioTime) > 0.35) {
            active.forEach { item -> streamFor(item.assetId)?.seek(item.sourceTime) }
        }
        lastAudioTime = time

        val streams = active.mapNotNull { item ->
            streamFor(item.assetId)?.let { it to item.volume }
        }
        return audioMixer.mix(streams, frames)
    }

    private fun streamFor(assetId: String): AudioStream? {
        val uri = viewModel.assetUri(assetId) ?: return null
        val existing = audioStreams[assetId]
        if (existing != null) return existing
        val stream = AudioStream(VynoxServices.context, uri, SAMPLE_RATE, CHANNELS)
        if (!stream.isOpen) {
            stream.release()
            return null
        }
        audioStreams[assetId] = stream
        return stream
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHANNELS = 2
        private const val MAX_PREVIEW_WIDTH = 1600
    }
}
