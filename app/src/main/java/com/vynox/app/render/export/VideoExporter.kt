package com.vynox.app.render.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import com.vynox.app.render.audio.AudioMixer
import com.vynox.app.render.audio.AudioStream
import com.vynox.app.render.gl.GLRenderer
import com.vynox.app.render.source.AndroidTextMetrics
import com.vynox.app.render.source.RenderSources
import com.vynox.core.composition.CompositionEvaluator
import com.vynox.core.model.LayerType
import com.vynox.core.model.VynoxProject
import java.io.File
import kotlin.math.roundToInt

data class ExportSettings(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val videoBitrateMbps: Float = 12f,
    val audioBitrateKbps: Int = 192,
    val includeAudio: Boolean = true,
    val outputFile: File
)

data class ExportProgress(
    val frame: Int,
    val totalFrames: Int,
    val percent: Float,
    val elapsedMs: Long
)

/**
 * Renders a project to an MP4 file.
 *
 * The very same [GLRenderer] and [CompositionEvaluator] used by the preview are
 * driven frame by frame into a MediaCodec surface encoder and muxed with an AAC
 * track, so the exported file is the composition - not a screen recording.
 * Runs fully offline on the device.
 */
class VideoExporter(private val context: Context) {

    private val sampleRate = 44_100
    private val channels = 2

    @Volatile
    var cancelRequested: Boolean = false

    /** Runs the export synchronously; call from a background thread. */
    fun export(
        project: VynoxProject,
        settings: ExportSettings,
        assets: (String) -> String?,
        onProgress: (ExportProgress) -> Unit = {}
    ): File? {
        cancelRequested = false
        val startTime = System.currentTimeMillis()
        val evaluator = CompositionEvaluator(project, AndroidTextMetrics)
        val sources = RenderSources(context, assets)
        val renderer = GLRenderer()

        var videoEncoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: Surface? = null
        val audioStreams = LinkedHashMap<String, AudioStream>()
        val mixer = AudioMixer(sampleRate, channels)

        try {
            settings.outputFile.parentFile?.mkdirs()
            if (settings.outputFile.exists()) settings.outputFile.delete()

            // ---------------------------------------------------------- video
            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, settings.width, settings.height
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (settings.videoBitrateMbps * 1_000_000).toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = videoEncoder.createInputSurface()
            videoEncoder.start()

            sources.renderWidth = settings.width
            sources.renderHeight = settings.height
            renderer.attach(inputSurface, settings.width, settings.height)

            // ---------------------------------------------------------- audio
            val hasAudio = settings.includeAudio && project.layers.any { layer ->
                layer.type == LayerType.AUDIO || (layer.type == LayerType.VIDEO && !layer.muted)
            }
            if (hasAudio) {
                val audioFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels
                ).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, settings.audioBitrateKbps * 1000)
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 128 * 1024)
                }
                audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audioEncoder.start()
            }

            val muxerInstance = MediaMuxer(
                settings.outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            muxer = muxerInstance
            val state = MuxerState(muxerInstance, needsAudio = hasAudio)

            val totalFrames = (project.duration * settings.fps).roundToInt().coerceAtLeast(1)
            val frameDurationUs = 1_000_000L / settings.fps
            val framesPerChunk = (sampleRate.toDouble() / settings.fps).roundToInt()

            for (frame in 0 until totalFrames) {
                if (cancelRequested) break
                val time = frame.toDouble() / settings.fps
                val presentationUs = frame * frameDurationUs

                val plan = evaluator.evaluate(time)
                renderer.setPresentationTime(presentationUs)
                renderer.render(plan, sources)
                state.drain(videoEncoder, isVideo = true)

                if (hasAudio && audioEncoder != null) {
                    val active = plan.audio.filter { !it.muted && it.volume > 0.0 }
                    val streams = active.mapNotNull { item ->
                        val uri = assets(item.assetId) ?: return@mapNotNull null
                        val stream = audioStreams.getOrPut(item.assetId) {
                            AudioStream(context, uri, sampleRate, channels)
                        }
                        if (!stream.isOpen) return@mapNotNull null
                        stream to item.volume
                    }
                    val pcm = mixer.mix(streams, framesPerChunk)
                    feedAudio(audioEncoder, pcm, presentationUs)
                    state.drain(audioEncoder, isVideo = false)
                }

                state.startIfReady()

                if (frame % 3 == 0 || frame == totalFrames - 1) {
                    onProgress(
                        ExportProgress(
                            frame = frame + 1,
                            totalFrames = totalFrames,
                            percent = ((frame + 1).toFloat() / totalFrames) * 100f,
                            elapsedMs = System.currentTimeMillis() - startTime
                        )
                    )
                }
            }

            if (!cancelRequested) {
                videoEncoder.signalEndOfInputStream()
                if (audioEncoder != null) {
                    feedAudio(audioEncoder, FloatArray(framesPerChunk * channels), totalFrames * frameDurationUs, endOfStream = true)
                }
            }
            state.drainFinal(videoEncoder, audioEncoder)

            if (state.started) {
                muxerInstance.stop()
            }
            return if (cancelRequested) {
                settings.outputFile.delete()
                null
            } else settings.outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            try {
                muxer?.release()
            } catch (ignored: Exception) {
            }
            try {
                videoEncoder?.stop()
                videoEncoder?.release()
            } catch (ignored: Exception) {
            }
            try {
                audioEncoder?.stop()
                audioEncoder?.release()
            } catch (ignored: Exception) {
            }
            inputSurface?.release()
            audioStreams.values.forEach { it.release() }
            renderer.release()
            sources.release()
        }
    }

    /** Tracks muxer state and drains encoder output without blocking. */
    private class MuxerState(private val muxer: MediaMuxer, private val needsAudio: Boolean) {

        var videoTrack = -1
            private set
        var audioTrack = -1
            private set
        var started = false
            private set

        fun startIfReady() {
            if (started) return
            if (videoTrack >= 0 && (!needsAudio || audioTrack >= 0)) {
                muxer.start()
                started = true
            }
        }

        fun drain(encoder: MediaCodec, isVideo: Boolean) {
            val info = MediaCodec.BufferInfo()
            while (true) {
                val index = encoder.dequeueOutputBuffer(info, 0)
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val track = muxer.addTrack(encoder.outputFormat)
                    if (isVideo) videoTrack = track else audioTrack = track
                    continue
                }
                if (index < 0) break
                val buffer = encoder.getOutputBuffer(index) ?: break
                val track = if (isVideo) videoTrack else audioTrack
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (info.size > 0 && track >= 0 && started && !isConfig) {
                    muxer.writeSampleData(track, buffer, info)
                }
                encoder.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }

        fun drainFinal(videoEncoder: MediaCodec, audioEncoder: MediaCodec?) {
            var guard = 0
            while (guard++ < 500) {
                val info = MediaCodec.BufferInfo()
                val index = videoEncoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrack = muxer.addTrack(videoEncoder.outputFormat)
                    continue
                }
                if (index < 0) break
                val buffer = videoEncoder.getOutputBuffer(index) ?: break
                if (info.size > 0 && videoTrack >= 0 && started) {
                    muxer.writeSampleData(videoTrack, buffer, info)
                }
                videoEncoder.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            if (audioEncoder == null) return
            guard = 0
            while (guard++ < 500) {
                val info = MediaCodec.BufferInfo()
                val index = audioEncoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    audioTrack = muxer.addTrack(audioEncoder.outputFormat)
                    continue
                }
                if (index < 0) break
                val buffer = audioEncoder.getOutputBuffer(index) ?: break
                if (info.size > 0 && audioTrack >= 0 && started) {
                    muxer.writeSampleData(audioTrack, buffer, info)
                }
                audioEncoder.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    }

    private fun feedAudio(
        encoder: MediaCodec,
        pcm: FloatArray,
        presentationUs: Long,
        endOfStream: Boolean = false
    ) {
        val inputIndex = encoder.dequeueInputBuffer(10_000)
        if (inputIndex < 0) return
        val buffer = encoder.getInputBuffer(inputIndex) ?: return
        buffer.clear()
        for (sample in pcm) {
            if (buffer.remaining() < 2) break
            buffer.putShort((sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        val flags = if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        encoder.queueInputBuffer(inputIndex, 0, buffer.position(), presentationUs, flags)
    }

    fun estimateSizeBytes(project: VynoxProject, settings: ExportSettings): Long {
        val seconds = project.duration.coerceAtLeast(0.1)
        val video = settings.videoBitrateMbps * 1_000_000f / 8f * seconds
        val audio = if (settings.includeAudio) settings.audioBitrateKbps * 1000 / 8f * seconds else 0f
        return (video + audio).toLong()
    }
}
