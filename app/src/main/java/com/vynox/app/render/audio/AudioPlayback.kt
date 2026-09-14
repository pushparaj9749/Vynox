package com.vynox.app.render.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build

/**
 * Low latency preview playback.
 *
 * A dedicated thread pulls mixed PCM from the editor and writes it to an
 * AudioTrack, which keeps audio in sync with the rendered frames.
 */
class AudioPlayback(private val sampleRate: Int, private val channels: Int = 2) {

    private val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
    private val frameSize = 1024

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    val isPlaying: Boolean get() = running

    fun start(source: (frames: Int) -> FloatArray?) {
        stop()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_FLOAT)
        val bufferSize = (minBuffer * 2).coerceAtLeast(frameSize * channels * 4)
        val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManagerSessionId
            )
        }
        this.track = track
        running = true
        track.play()
        thread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            while (running) {
                val samples = source(frameSize)
                if (samples == null) {
                    Thread.sleep(4)
                    continue
                }
                try {
                    track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                } catch (e: Exception) {
                    break
                }
            }
        }.apply {
            name = "vynox-audio"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        try {
            track?.stop()
            track?.release()
        } catch (ignored: Exception) {
        }
        track = null
    }

    private companion object {
        const val AudioManagerSessionId = 0
    }
}
