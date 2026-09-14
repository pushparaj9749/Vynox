package com.vynox.app.render.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Sequential PCM reader for a local audio file (or the audio track of a video).
 *
 * Audio is pulled frame by frame, resampled on the fly and never fully buffered,
 * so a long soundtrack costs the same memory as a short one.
 */
class AudioStream(
    private val context: Context,
    private val uri: String,
    private val targetSampleRate: Int = 44_100,
    private val targetChannels: Int = 2
) {

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var trackIndex = -1
    private var sourceSampleRate = 44_100
    private var sourceChannels = 2
    private var inputEos = false
    private var outputEos = false
    private val bufferInfo = MediaCodec.BufferInfo()

    /** Decoded samples not yet consumed (target rate, interleaved). */
    private var pending = FloatArray(0)
    private var pendingFrames = 0

    /** Resampling state carried across decoded chunks. */
    private var fraction = 0.0
    private var previous = FloatArray(targetChannels)

    var isOpen: Boolean = false
        private set

    init {
        open(0.0)
    }

    private fun open(startSeconds: Double) {
        release()
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, Uri.parse(uri), null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (!mime.startsWith("audio/")) continue
                trackIndex = i
                extractor.selectTrack(i)
                sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()
                this.extractor = extractor
                this.codec = codec
                isOpen = true
                break
            }
            if (!isOpen) {
                extractor.release()
                return
            }
            if (startSeconds > 0.0) {
                extractor.seekTo((startSeconds * 1_000_000).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }
        } catch (e: Exception) {
            release()
        }
    }

    fun seek(seconds: Double) {
        open(seconds.coerceAtLeast(0.0))
        pending = FloatArray(0)
        pendingFrames = 0
        fraction = 0.0
        previous = FloatArray(targetChannels)
    }

    /** Reads [frames] multi-channel frames, zero padded at end of stream. */
    fun read(frames: Int): FloatArray {
        val out = FloatArray(frames * targetChannels)
        if (!isOpen) return out
        var filled = 0
        var guard = 0
        while (filled < frames && guard++ < 64) {
            if (pendingFrames == 0) {
                if (!decodeChunk()) break
            }
            val available = min(pendingFrames, frames - filled)
            if (available > 0) {
                System.arraycopy(pending, 0, out, filled * targetChannels, available * targetChannels)
                val remaining = pendingFrames - available
                if (remaining > 0) {
                    val next = FloatArray(remaining * targetChannels)
                    System.arraycopy(pending, available * targetChannels, next, 0, remaining * targetChannels)
                    pending = next
                } else {
                    pending = FloatArray(0)
                }
                pendingFrames = remaining
                filled += available
            }
        }
        return out
    }

    /** Decodes the next codec buffer and resamples it into [pending]. */
    private fun decodeChunk(): Boolean {
        val codec = this.codec ?: return false
        val extractor = this.extractor ?: return false
        var decoded = false
        var guard = 0
        while (!decoded && !outputEos && guard++ < 32) {
            if (!inputEos) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val buffer = codec.getInputBuffer(inputIndex)
                    if (buffer != null) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
            }
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && bufferInfo.size > 0) {
                        val pcm = toFloatArray(buffer, bufferInfo.size)
                        val resampled = resample(pcm)
                        append(resampled)
                        decoded = resampled.isNotEmpty()
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEos = true
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputEos) outputEos = true
                }
            }
        }
        return decoded
    }

    private fun append(samples: FloatArray) {
        if (samples.isEmpty()) return
        val merged = FloatArray(pendingFrames * targetChannels + samples.size)
        if (pendingFrames > 0) System.arraycopy(pending, 0, merged, 0, pendingFrames * targetChannels)
        System.arraycopy(samples, 0, merged, pendingFrames * targetChannels, samples.size)
        pending = merged
        pendingFrames += samples.size / targetChannels
    }

    private fun toFloatArray(buffer: ByteBuffer, size: Int): FloatArray {
        val copy = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        val position = buffer.position()
        buffer.limit(position + size)
        copy.put(buffer)
        buffer.limit(buffer.capacity())
        buffer.position(position)
        copy.rewind()
        val shortBuffer = copy.asShortBuffer()
        val samples = FloatArray(shortBuffer.remaining())
        var index = 0
        while (shortBuffer.hasRemaining()) {
            samples[index++] = shortBuffer.get() / 32768f
        }
        return samples
    }

    /** Linear resampling with channel mixing to the target format. */
    private fun resample(input: FloatArray): FloatArray {
        val sourceFrames = input.size / sourceChannels
        if (sourceFrames == 0) return FloatArray(0)
        val ratio = sourceSampleRate.toDouble() / targetSampleRate.toDouble()
        val out = ArrayList<Float>((sourceFrames / ratio).toInt() * targetChannels + targetChannels)
        var position = fraction
        while (position < sourceFrames) {
            val index = position.toInt()
            val nextIndex = min(index + 1, sourceFrames - 1)
            val frac = (position - index).toFloat()
            for (channel in 0 until targetChannels) {
                val sourceChannel = if (sourceChannels == 1) 0 else channel.coerceAtMost(sourceChannels - 1)
                val a = input[index * sourceChannels + sourceChannel]
                val b = input[nextIndex * sourceChannels + sourceChannel]
                out.add(a + (b - a) * frac)
            }
            position += ratio
        }
        fraction = position - sourceFrames
        return out.toFloatArray()
    }

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
        codec = null
        extractor = null
        isOpen = false
    }
}

/** Mixes several streams with individual gains into one interleaved buffer. */
class AudioMixer(private val sampleRate: Int, private val channels: Int = 2) {

    fun mix(sources: List<Pair<AudioStream, Double>>, frames: Int): FloatArray {
        val output = FloatArray(frames * channels)
        if (sources.isEmpty()) return output
        sources.forEach { (stream, gain) ->
            val samples = stream.read(frames)
            for (index in samples.indices) {
                output[index] += samples[index] * gain.toFloat()
            }
        }
        // Soft limiter so loud mixes distort gently instead of clipping.
        for (index in output.indices) {
            output[index] = kotlin.math.tanh(output[index] * 1.1f) * 0.9f
        }
        return output
    }
}
