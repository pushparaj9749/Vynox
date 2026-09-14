package com.vynox.app.data

import android.content.Context
import android.net.Uri
import com.vynox.core.model.Asset
import com.vynox.core.model.Ids
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Local asset library.
 *
 * Imported media is copied into the app's private storage so a project stays
 * editable after the user moves or deletes the original file. The original URI
 * is kept for provenance and as a relinking hint.
 */
class AssetStore(private val context: Context) {

    private val assetsDir: File
        get() = File(context.filesDir, "assets").apply { mkdirs() }

    fun import(uri: Uri, name: String, type: com.vynox.core.model.AssetType, probe: MediaProbe.ProbeResult): Asset {
        val id = Ids.next("asset")
        val extension = name.substringAfterLast('.', "").ifBlank { defaultExtension(type) }
        val target = File(assetsDir, "$id.$extension")
        var copied = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> copied = input.copyTo(output) }
        }
        return Asset(
            id = id,
            name = name,
            type = type,
            uri = target.absolutePath,
            mimeType = probe.mimeType,
            sizeBytes = if (copied > 0) copied else probe.sizeBytes ?: 0L,
            durationSeconds = probe.durationSeconds,
            width = probe.width,
            height = probe.height,
            sampleRate = probe.sampleRate,
            channels = probe.channels,
            importedAt = System.currentTimeMillis()
        )
    }

    /** Registers a file that is already inside app storage (imported .vnx bundles). */
    fun registerLocal(file: File, name: String, type: com.vynox.core.model.AssetType, probe: MediaProbe.ProbeResult): Asset =
        Asset(
            id = Ids.next("asset"),
            name = name,
            type = type,
            uri = file.absolutePath,
            mimeType = probe.mimeType,
            sizeBytes = file.length(),
            durationSeconds = probe.durationSeconds,
            width = probe.width,
            height = probe.height,
            sampleRate = probe.sampleRate,
            channels = probe.channels,
            importedAt = System.currentTimeMillis()
        )

    fun registerExternal(uri: Uri, name: String, type: com.vynox.core.model.AssetType, probe: MediaProbe.ProbeResult): Asset =
        Asset(
            id = Ids.next("asset"),
            name = name,
            type = type,
            uri = uri.toString(),
            mimeType = probe.mimeType,
            sizeBytes = probe.sizeBytes ?: 0L,
            durationSeconds = probe.durationSeconds,
            width = probe.width,
            height = probe.height,
            sampleRate = probe.sampleRate,
            channels = probe.channels,
            importedAt = System.currentTimeMillis()
        )

    fun open(asset: Asset): InputStream? {
        val candidate = asset.uri ?: return null
        return try {
            when {
                candidate.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(candidate))
                candidate.startsWith("file://") -> FileInputStream(Uri.parse(candidate).path!!)
                else -> FileInputStream(candidate)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun exists(asset: Asset): Boolean {
        val candidate = asset.uri ?: return false
        return try {
            when {
                candidate.startsWith("content://") ->
                    context.contentResolver.openInputStream(Uri.parse(candidate))?.use { true } ?: false
                else -> File(candidate.removePrefix("file://")).exists()
            }
        } catch (e: Exception) {
            false
        }
    }

    fun delete(asset: Asset) {
        asset.uri?.let { path ->
            if (!path.startsWith("content://")) {
                File(path.removePrefix("file://")).delete()
            }
        }
    }

    fun importDirectory(): File = assetsDir

    private fun defaultExtension(type: com.vynox.core.model.AssetType): String = when (type) {
        com.vynox.core.model.AssetType.VIDEO -> "mp4"
        com.vynox.core.model.AssetType.AUDIO -> "m4a"
        com.vynox.core.model.AssetType.IMAGE -> "png"
        com.vynox.core.model.AssetType.FONT -> "ttf"
    }
}
