package com.vynox.core.vnx

import com.vynox.core.model.Asset
import com.vynox.core.model.AssetType
import com.vynox.core.model.VynoxProject

enum class AssetState { BUNDLED, EXTERNAL, MISSING }

data class AssetStatus(
    val asset: Asset,
    val state: AssetState,
    val resolvedPath: String? = null
) {
    val isMissing: Boolean get() = state == AssetState.MISSING
    val id: String get() = asset.id
}

/** Metadata discovered when relinking media (filled by the platform probe). */
data class AssetMetadata(
    val durationSeconds: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null
)

/**
 * Keeps imported media addressable across machines.
 *
 * Assets are referenced by stable ids, so a moved or deleted file degrades into
 * a "missing asset" state that the UI can relink - layer, effect and keyframe
 * bindings survive because they point at the id, never at the path.
 */
object AssetResolution {

    /**
     * Classifies every asset. [exists] answers whether the platform can currently
     * read the asset (bundled assets inside the .vnx always count as present).
     */
    fun analyze(project: VynoxProject, exists: (Asset) -> Boolean): List<AssetStatus> {
        return project.assets.map { asset ->
            when {
                asset.isBundled -> AssetStatus(asset, AssetState.BUNDLED, asset.bundledPath)
                exists(asset) -> AssetStatus(asset, AssetState.EXTERNAL, asset.uri)
                else -> AssetStatus(asset, AssetState.MISSING)
            }
        }
    }

    fun missing(project: VynoxProject, exists: (Asset) -> Boolean): List<Asset> =
        analyze(project, exists).filter { it.isMissing }.map { it.asset }

    fun isComplete(project: VynoxProject, exists: (Asset) -> Boolean): Boolean =
        missing(project, exists).isEmpty()

    /** Layers that reference a missing asset - surfaced in the editor UI. */
    fun layersUsingMissing(project: VynoxProject, exists: (Asset) -> Boolean): Set<String> {
        val missingIds = missing(project, exists).map { it.id }.toSet()
        if (missingIds.isEmpty()) return emptySet()
        return project.layers.filter { it.content.assetId in missingIds }.map { it.id }.toSet()
    }

    /** Repoints an asset at a new location, optionally refreshing metadata. */
    fun relink(project: VynoxProject, assetId: String, uri: String, metadata: AssetMetadata? = null): VynoxProject {
        val asset = project.asset(assetId) ?: return project
        val updated = asset.copy(
            uri = uri,
            bundledPath = null,
            mimeType = metadata?.mimeType ?: asset.mimeType,
            sizeBytes = metadata?.sizeBytes ?: asset.sizeBytes,
            durationSeconds = metadata?.durationSeconds ?: asset.durationSeconds,
            width = metadata?.width ?: asset.width,
            height = metadata?.height ?: asset.height,
            sampleRate = metadata?.sampleRate ?: asset.sampleRate,
            channels = metadata?.channels ?: asset.channels
        )
        return project.withAsset(updated)
    }

    /** Relinks several assets at once (batch relink from a folder). */
    fun relinkAll(project: VynoxProject, links: Map<String, String>): VynoxProject {
        var result = project
        links.forEach { (assetId, uri) -> result = relink(result, assetId, uri) }
        return result
    }

    /**
     * Best effort automatic relink: matches by file name inside [candidates].
     *
     * Only assets that cannot currently be resolved are repointed - an asset
     * that already resolves keeps its location, so pointing the editor at a
     * folder of copies never silently rewrites a healthy project.
     */
    fun autoRelink(
        project: VynoxProject,
        candidates: List<Pair<String, String>>,
        exists: (Asset) -> Boolean = { it.uri != null }
    ): VynoxProject {
        val byName = candidates.groupBy({ it.first.substringAfterLast('/') }, { it.second })
        var result = project
        project.assets.forEach { asset ->
            if (asset.isBundled) return@forEach
            if (exists(asset)) return@forEach
            val match = byName[asset.name]?.firstOrNull() ?: return@forEach
            result = relink(result, asset.id, match)
        }
        return result
    }

    fun totalBytes(project: VynoxProject): Long = project.assets.sumOf { it.sizeBytes.coerceAtLeast(0) }

    fun countByType(project: VynoxProject): Map<AssetType, Int> =
        project.assets.groupingBy { it.type }.eachCount()
}
