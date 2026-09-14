package com.vynox.app.data

import com.vynox.core.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val version: String,
    val name: String,
    val notes: String,
    val apkUrl: String?,
    val publishedAt: String?,
    val htmlUrl: String
)

/**
 * Optional update check against GitHub Releases.
 *
 * This is the ONLY network use in the app: editing, rendering and export never
 * touch the internet, and a failure here is silently ignored.
 */
class UpdateRepository(private val repoOwner: String = "pushparaj9749", private val repoName: String = "Vynox") {

    suspend fun fetchLatest(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/$repoOwner/$repoName/releases/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            val body = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val root = Json.parse(body)
            val tag = root.get("tag_name").asString() ?: return@withContext null
            val version = tag.removePrefix("v")
            if (!isNewer(version, currentVersion)) return@withContext null

            val apk = root.get("assets").asArray()
                .firstOrNull { it.get("name").asString()?.endsWith(".apk") == true }
                ?.get("browser_download_url")?.asString()

            UpdateInfo(
                version = version,
                name = root.get("name").asString() ?: tag,
                notes = root.get("body").asString().orEmpty(),
                apkUrl = apk,
                publishedAt = root.get("published_at").asString(),
                htmlUrl = root.get("html_url").asString() ?: "https://github.com/$repoOwner/$repoName/releases/latest"
            )
        } catch (e: Exception) {
            null
        }
    }

    fun downloadPage(): String = "https://$repoOwner.github.io/$repoName/"

    /** Simple semantic comparison; unknown formats never trigger an update. */
    internal fun isNewer(candidate: String, current: String): Boolean {
        fun parts(value: String): List<Int> = value.split('.').mapNotNull { it.trim().toIntOrNull() }
        val a = parts(candidate)
        val b = parts(current)
        if (a.isEmpty() || b.isEmpty()) return candidate != current
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }
}
