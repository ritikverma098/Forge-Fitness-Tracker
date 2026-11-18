package com.forge.workout.updates

import java.io.File
import java.io.IOException
import java.net.URI

internal enum class UpdateFailure { NETWORK, MISSING_CHECKSUM, INVALID_RELEASE, INTEGRITY, INVALID_APK, SIGNATURE_MISMATCH, STORAGE, INSTALLATION, RECOVERY }
internal class UpdateException(val issue: UpdateFailure, cause: Throwable? = null) : IOException(issue.name, cause)
internal data class DownloadedApk(val bytes: Long, val sha256: String)
internal fun interface ApkDownloader {
    suspend fun download(release: AvailableUpdate, destination: File, progress: (Long, Long) -> Unit): DownloadedApk
}

internal object GitHubAssetPolicy {
    const val MAX_APK_BYTES = 128L * 1024 * 1024
    private val namePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,195}\\.apk", RegexOption.IGNORE_CASE)
    private val digestPattern = Regex("sha256:[0-9a-fA-F]{64}")
    private val hashPattern = Regex("[0-9a-f]{64}")

    fun parseDigest(value: String?): String? = value?.takeIf { digestPattern.matches(it) }?.substring(7)?.lowercase()

    fun isOfficialAsset(url: String, tag: String, name: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.rawAuthority.equals("github.com", ignoreCase = true) &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath?.count { it == '/' } == 6 &&
            uri.path == "/ritikverma098/Forge-Fitness-Tracker/releases/download/$tag/$name"
    }

    fun validate(release: AvailableUpdate) {
        val name = runCatching { URI(release.apkUrl).path.substringAfterLast('/') }.getOrDefault("")
        if (!namePattern.matches(name) || !isOfficialAsset(release.apkUrl, release.tag, name) ||
            StableVersion.parse(release.tag)?.toString() != release.version ||
            release.apkSize?.let { it in 1..MAX_APK_BYTES } != true) throw UpdateException(UpdateFailure.INVALID_RELEASE)
        if (release.apkSha256?.let(hashPattern::matches) != true) throw UpdateException(UpdateFailure.MISSING_CHECKSUM)
    }
}
