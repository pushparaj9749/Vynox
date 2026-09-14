package com.vynox.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.vynox.core.effects.BuiltInEffects
import com.vynox.core.effects.EffectRegistry
import com.vynox.core.json.Json
import com.vynox.core.json.JsonValue
import com.vynox.core.json.jsonObject
import com.vynox.core.model.VynoxProject
import com.vynox.core.model.VynoxSchema
import com.vynox.core.vnx.AssetBundler
import com.vynox.core.vnx.VnxLoadResult
import com.vynox.core.vnx.VnxReader
import com.vynox.core.vnx.VnxWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

data class ProjectSummary(
    val id: String,
    val name: String,
    val path: String,
    val modifiedAt: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val duration: Double,
    val layerCount: Int,
    val thumbnailPath: String?,
    val schemaVersion: Int = VynoxSchema.CURRENT
)

/**
 * Persistence for .vnx projects: library, autosave and recovery.
 *
 * All IO is local (app private storage). Autosave writes a plain JSON .vnx
 * snapshot on a timer; if the app is killed the next launch offers recovery.
 */
class ProjectStore(private val context: Context) {

    init {
        if (EffectRegistry.all().isEmpty()) EffectRegistry.registerAll(BuiltInEffects.definitions())
    }

    private val projectsDir: File get() = File(context.filesDir, "projects").apply { mkdirs() }
    private val autosaveDir: File get() = File(context.filesDir, "autosave").apply { mkdirs() }
    private val thumbDir: File get() = File(context.filesDir, "thumbs").apply { mkdirs() }
    private val indexFile: File get() = File(projectsDir, "index.json")

    // ------------------------------------------------------------------ saving

    /**
     * Writes a .vnx file. When [packAssets] is true the media is embedded so the
     * single file can be shared and opened elsewhere.
     */
    fun save(
        project: VynoxProject,
        packAssets: Boolean = false,
        previewPng: ByteArray? = null
    ): File {
        val file = File(projectsDir, "${sanitize(project.name)}-${project.id}${VynoxSchema.EXTENSION}")
        FileOutputStream(file).use { out ->
            if (packAssets) {
                VnxWriter.writeBundle(project, out, object : AssetBundler {
                    override fun openAsset(asset: com.vynox.core.model.Asset): java.io.InputStream? =
                        openUri(asset.uri)
                    override fun previewBytes(): ByteArray? = previewPng
                })
            } else {
                VnxWriter.write(project, out)
            }
        }
        writeThumbnail(project.id, previewPng)
        updateIndex(
            ProjectSummary(
                id = project.id,
                name = project.name,
                path = file.absolutePath,
                modifiedAt = System.currentTimeMillis(),
                width = project.canvas.width,
                height = project.canvas.height,
                fps = project.canvas.fps,
                duration = project.duration,
                layerCount = project.layers.size,
                thumbnailPath = thumbnailFile(project.id)?.absolutePath
            )
        )
        return file
    }

    /** Autosave snapshot (always plain JSON: fast, no media copying). */
    fun autosave(project: VynoxProject, previewPng: ByteArray? = null) {
        val file = File(autosaveDir, "autosave${VynoxSchema.EXTENSION}")
        val temp = File(autosaveDir, "autosave.tmp")
        FileOutputStream(temp).use { out -> VnxWriter.write(project, out) }
        if (file.exists()) file.delete()
        temp.renameTo(file)
        File(autosaveDir, "autosave.meta").writeText(
            Json.write(
                jsonObject {
                    put("id", project.id)
                    put("name", project.name)
                    put("savedAt", System.currentTimeMillis())
                }
            )
        )
        writeThumbnail("autosave_" + project.id, previewPng)
    }

    fun loadAutosave(): VynoxProject? {
        val file = File(autosaveDir, "autosave${VynoxSchema.EXTENSION}")
        if (!file.exists()) return null
        return try {
            VnxReader.read(file.readBytes()).project
        } catch (e: Exception) {
            null
        }
    }

    fun autosaveInfo(): Pair<String, Long>? {
        val meta = File(autosaveDir, "autosave.meta")
        if (!meta.exists()) return null
        return try {
            val json = Json.parse(meta.readText())
            val name = json.get("name").asString() ?: "Untitled"
            val savedAt = json.get("savedAt").asLong() ?: 0L
            name to savedAt
        } catch (e: Exception) {
            null
        }
    }

    fun clearAutosave() {
        File(autosaveDir, "autosave${VynoxSchema.EXTENSION}").delete()
        File(autosaveDir, "autosave.meta").delete()
    }

    // ------------------------------------------------------------------ loading

    fun load(file: File): VnxLoadResult = load(file.readBytes())

    /** Loads a .vnx from anywhere (file, share intent, downloads). */
    fun load(bytes: ByteArray): VnxLoadResult {
        val extracted = LinkedHashMap<String, String>()
        val mediaDir = File(projectsDir, "imported_" + System.currentTimeMillis()).apply { mkdirs() }
        val result = VnxReader.read(bytes) { assetId, entryName, stream ->
            val target = File(mediaDir, entryName.substringAfterLast('/'))
            FileOutputStream(target).use { out -> stream.copyTo(out) }
            extracted[assetId] = target.absolutePath
        }
        val project = if (extracted.isEmpty()) {
            result.project
        } else {
            VnxReader.applyExtractedPaths(result.project) { assetId, _ -> extracted[assetId] }
        }
        return result.copy(project = project)
    }

    fun loadFromUri(uri: Uri): VnxLoadResult {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Cannot read file")
        return load(bytes)
    }

    /** Re-points assets at files the user picked during relinking. */
    fun relinkAsset(project: VynoxProject, assetId: String, uri: Uri): VynoxProject {
        val probe = MediaProbe(context).probe(uri)
        val asset = project.asset(assetId) ?: return project
        return com.vynox.core.vnx.AssetResolution.relink(
            project,
            assetId,
            uri.toString(),
            com.vynox.core.vnx.AssetMetadata(
                durationSeconds = probe.durationSeconds,
                width = probe.width,
                height = probe.height,
                sampleRate = probe.sampleRate,
                channels = probe.channels,
                sizeBytes = probe.sizeBytes,
                mimeType = probe.mimeType
            )
        )
    }

    // ------------------------------------------------------------------ library

    fun list(): List<ProjectSummary> {
        val stored = readIndex().toMutableList()
        // Reconcile with the files actually on disk.
        val onDisk = projectsDir.listFiles { file -> file.extension == "vnx" }?.toList() ?: emptyList()
        val known = stored.map { it.path }.toSet()
        onDisk.filter { it.absolutePath !in known }.forEach { file ->
            runCatching {
                val project = load(file).project
                stored += ProjectSummary(
                    id = project.id,
                    name = project.name,
                    path = file.absolutePath,
                    modifiedAt = project.meta.modifiedAt,
                    width = project.canvas.width,
                    height = project.canvas.height,
                    fps = project.canvas.fps,
                    duration = project.duration,
                    layerCount = project.layers.size,
                    thumbnailPath = null
                )
            }
        }
        return stored.filter { File(it.path).exists() }.sortedByDescending { it.modifiedAt }
    }

    fun delete(summary: ProjectSummary) {
        File(summary.path).delete()
        summary.thumbnailPath?.let { File(it).delete() }
        updateIndex(remove = summary.id)
    }

    fun rename(project: VynoxProject, newName: String): VynoxProject = project.copy(name = newName)

    // ------------------------------------------------------------------ helpers

    private fun readIndex(): List<ProjectSummary> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val root = Json.parse(indexFile.readText())
            root.get("projects").asArray().mapNotNull { entry ->
                val id = entry.get("id").asString() ?: return@mapNotNull null
                ProjectSummary(
                    id = id,
                    name = entry.get("name").asString() ?: "Untitled",
                    path = entry.get("path").asString() ?: return@mapNotNull null,
                    modifiedAt = entry.get("modifiedAt").asLong() ?: 0L,
                    width = entry.get("width").asInt() ?: 1920,
                    height = entry.get("height").asInt() ?: 1080,
                    fps = entry.get("fps").asInt() ?: 30,
                    duration = entry.get("duration").asDouble() ?: 0.0,
                    layerCount = entry.get("layerCount").asInt() ?: 0,
                    thumbnailPath = entry.get("thumbnail").asString(),
                    schemaVersion = entry.get("schema").asInt() ?: VynoxSchema.CURRENT
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun updateIndex(summary: ProjectSummary? = null, remove: String? = null) {
        val items = readIndex().toMutableList()
        if (remove != null) items.removeAll { it.id == remove }
        if (summary != null) {
            items.removeAll { it.id == summary.id }
            items += summary
        }
        val root = jsonObject {
            put("version", 1)
            putArray("projects", items.map { item ->
                jsonObject {
                    put("id", item.id)
                    put("name", item.name)
                    put("path", item.path)
                    put("modifiedAt", item.modifiedAt)
                    put("width", item.width)
                    put("height", item.height)
                    put("fps", item.fps)
                    put("duration", item.duration)
                    put("layerCount", item.layerCount)
                    put("thumbnail", item.thumbnailPath)
                    put("schema", item.schemaVersion)
                }
            })
        }
        indexFile.writeText(Json.write(root))
    }

    fun writeThumbnail(key: String, png: ByteArray?) {
        if (png == null) return
        runCatching { File(thumbDir, "$key.png").writeBytes(png) }
    }

    fun thumbnailFile(key: String): File? {
        val file = File(thumbDir, "$key.png")
        return if (file.exists()) file else null
    }

    fun loadThumbnail(key: String): Bitmap? {
        val file = File(thumbDir, "$key.png")
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun loadThumbnail(result: VnxLoadResult): Bitmap? {
        val bytes = result.previewPng ?: return null
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }

    fun exportDirectory(): File = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }

    fun shareCache(): File = File(context.cacheDir, "share").apply { mkdirs() }

    fun encodePreview(bitmap: Bitmap?): ByteArray? {
        if (bitmap == null) return null
        return runCatching {
            val scaled = if (bitmap.width > 480) {
                Bitmap.createScaledBitmap(bitmap, 480, (480 * bitmap.height / bitmap.width).coerceAtLeast(1), true)
            } else bitmap
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 80, out)
            out.toByteArray()
        }.getOrNull()
    }

    private fun openUri(uri: String?): java.io.InputStream? {
        if (uri == null) return null
        return try {
            when {
                uri.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(uri))
                else -> java.io.FileInputStream(uri.removePrefix("file://"))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "project" }
}
