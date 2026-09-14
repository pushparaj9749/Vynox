package com.vynox.core.model

/**
 * A media item referenced by one or more layers.
 *
 * Assets are tracked by stable [id], never by file path alone: a .vnx keeps the
 * id even when the file moves, which is what makes relinking possible without
 * losing layer/effect/keyframe bindings.
 *
 * [uri] is the best known location of the media (content:// or file path).
 * [bundledPath] is set when the asset travels inside the .vnx package.
 */
data class Asset(
    val id: String,
    val name: String,
    val type: AssetType,
    val uri: String? = null,
    val bundledPath: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long = 0L,
    val checksum: String? = null,
    val durationSeconds: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val importedAt: Long = 0L
) {
    val isBundled: Boolean get() = bundledPath != null

    fun withUri(newUri: String): Asset = copy(uri = newUri)
    fun withBundled(path: String?): Asset = copy(bundledPath = path)

    companion object {
        fun guessType(mimeType: String?, name: String): AssetType {
            val lower = (mimeType ?: name).lowercase()
            return when {
                lower.startsWith("video/") -> AssetType.VIDEO
                lower.startsWith("audio/") -> AssetType.AUDIO
                lower.startsWith("image/") -> AssetType.IMAGE
                lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".mov") || lower.endsWith(".3gp") || lower.endsWith(".m4v") -> AssetType.VIDEO
                lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".flac") || lower.endsWith(".m4a") -> AssetType.AUDIO
                lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp") -> AssetType.IMAGE
                else -> AssetType.IMAGE
            }
        }
    }
}
