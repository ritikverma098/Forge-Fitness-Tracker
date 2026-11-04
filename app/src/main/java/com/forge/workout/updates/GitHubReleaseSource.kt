package com.forge.workout.updates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

internal data class AvailableUpdate(
    val version: String,
    val tag: String,
    val title: String,
    val changelog: String,
    val publishedOn: LocalDate,
    val apkUrl: String
)

internal sealed interface UpdateCheckResult {
    data class Available(val release: AvailableUpdate) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object NoValidRelease : UpdateCheckResult
    data object Unavailable : UpdateCheckResult
}

internal class GitHubReleaseSource(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    suspend fun check(installedVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        val installed = StableVersion.parse(installedVersion) ?: return@withContext UpdateCheckResult.NoValidRelease
        try {
            val connection = openConnection(URI.create(API_URL).toURL())
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "Forge-Android")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> connection.readRelease(installed)
                    HttpURLConnection.HTTP_NOT_FOUND -> UpdateCheckResult.NoValidRelease
                    else -> UpdateCheckResult.Unavailable
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            UpdateCheckResult.Unavailable
        } catch (_: JSONException) {
            UpdateCheckResult.NoValidRelease
        }
    }

    private fun HttpURLConnection.readRelease(installed: StableVersion): UpdateCheckResult {
        if (contentLengthLong > MAX_RESPONSE_BYTES) return UpdateCheckResult.NoValidRelease
        val deadline = System.nanoTime() + 10_000_000_000L
        val body = inputStream.use { input ->
            val buffer = ByteArray(8192)
            val output = ByteArrayOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (System.nanoTime() - deadline >= 0) return UpdateCheckResult.Unavailable
                if (count == -1) break
                if (output.size() + count > MAX_RESPONSE_BYTES) return UpdateCheckResult.NoValidRelease
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
        val json = JSONTokener(body)
        val release = json.nextValue() as? JSONObject ?: return UpdateCheckResult.NoValidRelease
        if (json.nextClean() != '\u0000' || release.opt("draft") != false || release.opt("prerelease") != false) {
            return UpdateCheckResult.NoValidRelease
        }
        val tag = release.opt("tag_name") as? String ?: return UpdateCheckResult.NoValidRelease
        val version = StableVersion.parse(tag) ?: return UpdateCheckResult.NoValidRelease
        val published = (release.opt("published_at") as? String)?.let {
            try { OffsetDateTime.parse(it).toLocalDate() }
            catch (_: DateTimeParseException) { null }
        } ?: return UpdateCheckResult.NoValidRelease
        val apkUrl = release.apkUrl(tag, version.toString()) ?: return UpdateCheckResult.NoValidRelease
        if (version <= installed) return UpdateCheckResult.UpToDate
        return UpdateCheckResult.Available(AvailableUpdate(
            version = version.toString(),
            tag = tag,
            title = (release.opt("name") as? String).orEmpty().trim().take(160),
            changelog = (release.opt("body") as? String).orEmpty().trim(),
            publishedOn = published,
            apkUrl = apkUrl
        ))
    }

    private fun JSONObject.apkUrl(tag: String, version: String): String? {
        val assets = optJSONArray("assets") ?: return null
        val candidates = (0 until assets.length()).mapNotNull { index ->
            val asset = assets.optJSONObject(index) ?: return@mapNotNull null
            val name = asset.opt("name") as? String ?: return@mapNotNull null
            val url = asset.opt("browser_download_url") as? String ?: return@mapNotNull null
            if (asset.opt("state") != "uploaded" || (asset.opt("size") as? Number)?.toLong()?.let { it > 0 } != true ||
                !APK_NAME.matches(name) || !isOfficialAsset(url, tag, name)) return@mapNotNull null
            name to url
        }.distinct()
        return candidates.singleOrNull { (name, _) -> name == "Forge-$version.apk" }?.second
            ?: candidates.singleOrNull()?.second
    }

    private fun isOfficialAsset(url: String, tag: String, name: String): Boolean {
        val uri = try { URI(url) } catch (_: URISyntaxException) { return false }
        return uri.scheme == "https" && uri.rawAuthority.equals("github.com", ignoreCase = true) &&
            uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath?.count { it == '/' } == 6 &&
            uri.path == "/$REPOSITORY/releases/download/$tag/$name"
    }

    companion object {
        private const val REPOSITORY = "ritikverma098/Forge-Fitness-Tracker"
        const val REPOSITORY_URL = "https://github.com/$REPOSITORY"
        const val LATEST_RELEASE_URL = "$REPOSITORY_URL/releases/latest"
        private const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        private const val MAX_RESPONSE_BYTES = 512 * 1024
        private val APK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,195}\\.apk", RegexOption.IGNORE_CASE)
    }
}

private data class StableVersion(
    val major: BigInteger,
    val minor: BigInteger,
    val patch: BigInteger
) : Comparable<StableVersion> {
    override fun compareTo(other: StableVersion): Int =
        compareValuesBy(this, other, StableVersion::major, StableVersion::minor, StableVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex(
            "[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\+[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*)?"
        )

        fun parse(value: String): StableVersion? {
            if (value.length > 128) return null
            val (major, minor, patch) = pattern.matchEntire(value)?.destructured ?: return null
            return StableVersion(major.toBigInteger(), minor.toBigInteger(), patch.toBigInteger())
        }
    }
}
