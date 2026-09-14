package com.vynox.core.vnx

import com.vynox.core.json.Json
import com.vynox.core.json.JsonException
import com.vynox.core.model.Asset
import com.vynox.core.model.VynoxProject
import com.vynox.core.model.VynoxSchema
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Supplies media bytes when a project is packaged as a self contained .vnx. */
interface AssetBundler {
    /** Returns a stream for [asset] or null when the media cannot be read. */
    fun openAsset(asset: Asset): InputStream?
    /** Optional thumbnail embedded in the package (PNG bytes). */
    fun previewBytes(): ByteArray? = null
}

/** Result of loading a .vnx file. */
data class VnxLoadResult(
    val project: VynoxProject,
    val warnings: List<VnxWarning> = emptyList(),
    val isBundle: Boolean = false,
    val entries: List<String> = emptyList(),
    val bundledAssets: Map<String, String> = emptyMap(),
    val previewPng: ByteArray? = null
) {
    val hasWarnings: Boolean get() = warnings.isNotEmpty()
}

/**
 * Writes .vnx files.
 *
 * A .vnx is either
 *  * a ZIP bundle: `project.json` + `assets/<id>` + optional `preview.png`
 *  * or a plain JSON document (used by autosave and quick sharing).
 * Both forms are readable by [VnxReader], and a bundle keeps media with the
 * project so it can be shared as a single file.
 */
object VnxWriter {

    fun writeJson(project: VynoxProject): String =
        Json.write(VnxProjectCodec.encode(project), pretty = true)

    fun writeBundleEntryName(asset: Asset): String {
        val extension = asset.name.substringAfterLast('.', "").let { if (it.isBlank()) "bin" else it }
        return VynoxSchema.ASSET_DIR + asset.id + "." + extension
    }

    /** Writes a plain JSON .vnx document. */
    fun write(project: VynoxProject, out: OutputStream) {
        out.write(writeJson(project).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /**
     * Writes a ZIP packaged .vnx. Assets are copied through [bundler]; an asset
     * whose bytes cannot be read is simply not embedded (the URI reference is
     * kept, so relinking later stays possible).
     */
    fun writeBundle(project: VynoxProject, out: OutputStream, bundler: AssetBundler?) {
        val bundled = LinkedHashMap<String, String>()
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(VynoxSchema.BUNDLE_MAGIC_ENTRY))
            zip.write("${VynoxSchema.FORMAT_ID}\n${VynoxSchema.CURRENT}\n".toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            if (bundler != null) {
                project.assets.forEach { asset ->
                    val stream = bundler.openAsset(asset) ?: return@forEach
                    val name = writeBundleEntryName(asset)
                    stream.use { input ->
                        zip.putNextEntry(ZipEntry(name))
                        input.copyTo(zip)
                        zip.closeEntry()
                    }
                    bundled[asset.id] = name
                }
                val preview = bundler.previewBytes()
                if (preview != null && preview.isNotEmpty()) {
                    zip.putNextEntry(ZipEntry(VynoxSchema.PREVIEW_ENTRY))
                    zip.write(preview)
                    zip.closeEntry()
                }
            }

            // The project is written last and reflects which assets were bundled.
            val packaged = project.copy(
                assets = project.assets.map { asset ->
                    val path = bundled[asset.id]
                    if (path != null) asset.copy(bundledPath = path) else asset.copy(bundledPath = null)
                }
            )
            val payload = writeJson(packaged).toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry(VynoxSchema.PROJECT_ENTRY))
            zip.write(payload)
            zip.closeEntry()
            zip.finish()
        }
    }
}

/**
 * Reads .vnx files of both flavours.
 *
 * Asset payloads are streamed to [assetSink] instead of being buffered, so a
 * multi gigabyte bundle never has to live in memory.
 */
object VnxReader {

    fun isZip(payload: ByteArray): Boolean =
        payload.size >= 2 && payload[0] == 0x50.toByte() && payload[1] == 0x4B.toByte()

    fun readJson(text: String): VnxLoadResult {
        val warnings = mutableListOf<VnxWarning>()
        val root = try {
            Json.parse(text)
        } catch (e: JsonException) {
            throw VnxFormatException("Corrupt project JSON: ${e.message}", e)
        }
        return VnxLoadResult(project = VnxProjectCodec.decode(root, warnings), warnings = warnings)
    }

    fun read(bytes: ByteArray, assetSink: ((assetId: String, entryName: String, stream: InputStream) -> Unit)? = null): VnxLoadResult =
        read(ByteArrayInputStream(bytes), assetSink)

    /** Streams a .vnx from [input]; the caller owns closing the stream. */
    fun read(
        input: InputStream,
        assetSink: ((assetId: String, entryName: String, stream: InputStream) -> Unit)? = null
    ): VnxLoadResult {
        val buffered = input.buffered()
        buffered.mark(4)
        val magic = ByteArray(2)
        val readCount = buffered.read(magic)
        buffered.reset()
        return if (readCount == 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()) {
            readBundle(buffered, assetSink)
        } else {
            readPlain(buffered)
        }
    }

    private fun readPlain(input: InputStream): VnxLoadResult {
        val text = input.readBytes().toString(Charsets.UTF_8)
        return readJson(text)
    }

    private fun readBundle(
        input: InputStream,
        assetSink: ((assetId: String, entryName: String, stream: InputStream) -> Unit)?
    ): VnxLoadResult {
        val entries = ArrayList<String>()
        val bundledAssets = LinkedHashMap<String, String>()
        var projectJson: String? = null
        var preview: ByteArray? = null

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                entries += name
                when {
                    name == VynoxSchema.PROJECT_ENTRY -> projectJson = zip.readBytes().toString(Charsets.UTF_8)
                    name == VynoxSchema.PREVIEW_ENTRY -> preview = zip.readBytes()
                    name == VynoxSchema.BUNDLE_MAGIC_ENTRY -> zip.readBytes()
                    name.startsWith(VynoxSchema.ASSET_DIR) -> {
                        val assetId = name.removePrefix(VynoxSchema.ASSET_DIR).substringBefore('.')
                        bundledAssets[assetId] = name
                        if (assetSink != null) assetSink(assetId, name, zip)
                        else zip.readBytes()
                    }
                    else -> zip.readBytes()
                }
                zip.closeEntry()
            }
        }

        val payload = projectJson
            ?: throw VnxFormatException("Package does not contain ${VynoxSchema.PROJECT_ENTRY}")
        val result = readJson(payload)
        return result.copy(
            isBundle = true,
            entries = entries,
            bundledAssets = bundledAssets,
            previewPng = preview
        )
    }

    /** Reads just the embedded thumbnail without extracting media. */
    fun readPreview(bytes: ByteArray): ByteArray? {
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == VynoxSchema.PREVIEW_ENTRY) return zip.readBytes()
                }
                null
            }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Rewrites a project so every bundled asset points at [mapper]'s location,
     * used after extracting a package onto the device.
     */
    fun applyExtractedPaths(project: VynoxProject, mapper: (assetId: String, entryName: String) -> String?): VynoxProject {
        return project.copy(assets = project.assets.map { asset ->
            val path = asset.bundledPath?.let { mapper(asset.id, it) }
            if (path != null) asset.copy(uri = path) else asset
        })
    }
}
