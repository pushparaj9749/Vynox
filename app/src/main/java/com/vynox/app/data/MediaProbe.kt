package com.vynox.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.vynox.core.model.Asset
import com.vynox.core.model.AssetType

/**
 * Reads real metadata from local media.
 *
 * Everything is done with platform APIs on the device - no network, no external
 * service - so importing works in airplane mode.
 */
class MediaProbe(private val context: Context) {

    data class ProbeResult(
        val durationSeconds: Double?,
        val width: Int?,
        val height: Int?,
        val mimeType: String?,
        val sampleRate: Int?,
        val channels: Int?,
        val sizeBytes: Long?
    )

    fun probe(uri: Uri, fallbackMime: String? = null, fallbackName: String? = null): ProbeResult {
        val size = querySize(uri)
        var duration: Double? = null
        var width: Int? = null
        var height: Int? = null
        var mime = fallbackMime ?: context.contentResolver.getType(uri)
        var sampleRate: Int? = null
        var channels: Int? = null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.let { it / 1000.0 }
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (w != null && w > 0) width = w
            if (h != null && h > 0) height = h
            val trackMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            if (!trackMime.isNullOrBlank()) mime = trackMime
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()?.let { sampleRate = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)?.toIntOrNull()?.let { channels = it }
        } catch (e: Exception) {
            // Metadata is best effort: a missing probe never blocks an import.
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
            }
        }
        return ProbeResult(duration, width, height, mime, sampleRate, channels, size)
    }

    private fun querySize(uri: Uri): Long? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment ?: "media"
    }

    /** Guesses the asset type from uri, mime and name. */
    fun guessType(uri: Uri, name: String): AssetType {
        val mime = context.contentResolver.getType(uri)
        return Asset.guessType(mime, name)
    }
}
