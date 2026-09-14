package com.vynox.app.render.source

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.view.Surface
import com.vynox.app.render.gl.LayerTexture

/**
 * Decodes a local video into an OpenGL external texture.
 *
 * Frames never leave the GPU: the decoder renders straight into the
 * SurfaceTexture backing an OES texture, which the compositor samples. Seeking
 * decodes forward from the previous keyframe, which is what makes both
 * frame-accurate export and timeline scrubbing work offline.
 */
class VideoDecoder(
    private val context: Context,
    uri: String,
    private val oesTextureId: Int
) {

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var trackIndex = -1
    private var inputEos = false
    private var outputEos = false
    private var lastPtsUs = -1L
    private val texMatrix = FloatArray(16)
    private val bufferInfo = MediaCodec.BufferInfo()

    var videoWidth: Int = 0
        private set
    var videoHeight: Int = 0
        private set
    var durationUs: Long = 0
        private set

    val isOpen: Boolean get() = codec != null

    init {
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, Uri.parse(uri), null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    trackIndex = i
                    extractor.selectTrack(i)
                    videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                    videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                    durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        format.getLong(MediaFormat.KEY_DURATION)
                    } else 0L
                    val codec = MediaCodec.createDecoderByType(mime)
                    val surfaceTexture = SurfaceTexture(oesTextureId)
                    val surface = Surface(surfaceTexture)
                    codec.configure(format, surface, null, 0)
                    codec.start()
                    this.extractor = extractor
                    this.codec = codec
                    this.surfaceTexture = surfaceTexture
                    this.surface = surface
                    break
                }
            }
            if (codec == null) {
                extractor.release()
            }
        } catch (e: Exception) {
            release()
        }
    }

    /**
     * Advances to [timeSeconds] and returns the frame as a drawable texture.
     * Returns null when the media cannot be decoded.
     */
    fun frameAt(timeSeconds: Double): LayerTexture? {
        val codec = this.codec ?: return null
        val extractor = this.extractor ?: return null
        val surfaceTexture = this.surfaceTexture ?: return null

        val targetUs = (timeSeconds * 1_000_000.0).toLong()
        val needsSeek = lastPtsUs < 0 || targetUs < lastPtsUs || targetUs > lastPtsUs + 1_000_000L
        if (needsSeek) {
            extractor.seekTo(targetUs.coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            runCatching { codec.flush() }
            resetFrame = true
            inputEos = false
            outputEos = false
            lastPtsUs = -1L
        }

        var guard = 0
        while (!outputEos && guard++ < 600) {
            feedInput()
            val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                index >= 0 -> {
                    val pts = bufferInfo.presentationTimeUs
                    val reachedTarget = pts >= targetUs
                    if (reachedTarget) {
                        codec.releaseOutputBuffer(index, true)
                        surfaceTexture.updateTexImage()
                        surfaceTexture.getTransformMatrix(texMatrix)
                        // Row/column major: the GL shader expects a 3x3 matrix.
                        lastPtsUs = pts
                        return LayerTexture(
                            textureId = oesTextureId,
                            width = videoWidth,
                            height = videoHeight,
                            isExternalOes = true,
                            texMatrix = toMatrix3(texMatrix)
                        )
                    } else {
                        codec.releaseOutputBuffer(index, false)
                        lastPtsUs = pts
                    }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                    videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputEos) outputEos = true
                }
            }
        }

        if (lastPtsUs >= 0) {
            return LayerTexture(
                textureId = oesTextureId,
                width = videoWidth,
                height = videoHeight,
                isExternalOes = true,
                texMatrix = toMatrix3(texMatrix)
            )
        }
        return null
    }

    private fun feedInput() {
        val codec = this.codec ?: return
        val extractor = this.extractor ?: return
        if (inputEos) return
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex < 0) return
        val buffer = codec.getInputBuffer(inputIndex) ?: return
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputEos = true
        } else {
            val pts = extractor.sampleTime
            codec.queueInputBuffer(inputIndex, 0, size, pts, 0)
            extractor.advance()
        }
    }

    /** SurfaceTexture produces a 4x4 matrix; the shader only needs the 3x3 part. */
    private fun toMatrix3(matrix: FloatArray): FloatArray = floatArrayOf(
        matrix[0], matrix[4], matrix[12],
        matrix[1], matrix[5], matrix[13],
        matrix[2], matrix[6], matrix[14]
    )

    /** Marks that the next requested frame must wait for a fresh render. */
    private var resetFrame: Boolean = true

    fun release() {
        try {
            codec?.stop()
            codec?.release()
        } catch (ignored: Exception) {
        }
        try {
            extractor?.release()
        } catch (ignored: Exception) {
        }
        try {
            surface?.release()
            surfaceTexture?.release()
        } catch (ignored: Exception) {
        }
        codec = null
        extractor = null
        surfaceTexture = null
        surface = null
    }
}
