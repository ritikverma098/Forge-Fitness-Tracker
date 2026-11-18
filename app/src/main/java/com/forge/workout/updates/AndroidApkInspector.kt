package com.forge.workout.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal interface ApkInspector {
    fun verify(file: File, release: AvailableUpdate): Long
    fun isInstalled(version: String, versionCode: Long): Boolean
}

internal data class ApkIdentity(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val currentSigners: Set<String>,
)

internal object ApkIdentityValidation {
    const val PACKAGE_NAME = "com.forge.workout"
    private val signerPattern = Regex("[0-9a-f]{64}")

    fun verify(candidate: ApkIdentity, installed: ApkIdentity, offeredVersion: String): Long {
        val offered = StableVersion.parse(offeredVersion)
        val candidateVersion = candidate.versionName?.let(StableVersion::parse)
        val installedVersion = installed.versionName?.let(StableVersion::parse)
        if (candidate.packageName != PACKAGE_NAME || installed.packageName != PACKAGE_NAME ||
            offered == null || candidateVersion == null || candidateVersion != offered || installedVersion == null ||
            candidateVersion <= installedVersion || candidate.versionCode <= installed.versionCode ||
            installed.versionCode < 0 || !validSigners(candidate.currentSigners) ||
            !validSigners(installed.currentSigners)) {
            throw UpdateException(UpdateFailure.INVALID_APK)
        }
        if (candidate.currentSigners != installed.currentSigners) {
            throw UpdateException(UpdateFailure.SIGNATURE_MISMATCH)
        }
        return candidate.versionCode
    }

    fun isInstalled(installed: ApkIdentity, targetVersion: String, targetCode: Long): Boolean {
        val expected = StableVersion.parse(targetVersion) ?: return false
        val actual = installed.versionName?.let(StableVersion::parse) ?: return false
        return targetCode > 0 && installed.packageName == PACKAGE_NAME && validSigners(installed.currentSigners) &&
            installed.versionCode >= targetCode && actual >= expected
    }

    private fun validSigners(signers: Set<String>) = signers.isNotEmpty() && signers.all(signerPattern::matches)
}

/** Complements the trusted download digest; Android's installer enforces APK signature validity. */
internal class AndroidApkInspector(context: Context) : ApkInspector {
    private val packages = context.applicationContext.packageManager

    override fun verify(file: File, release: AvailableUpdate): Long = try {
        if (!file.isFile || !file.canRead() || file.length() !in 1..GitHubAssetPolicy.MAX_APK_BYTES) {
            throw UpdateException(UpdateFailure.INVALID_APK)
        }
        val candidate = archive(file.canonicalPath) ?: throw UpdateException(UpdateFailure.INVALID_APK)
        ApkIdentityValidation.verify(identity(candidate), identity(installed()), release.version)
    } catch (failure: UpdateException) {
        throw failure
    } catch (failure: Exception) {
        throw UpdateException(UpdateFailure.INVALID_APK, failure)
    }

    override fun isInstalled(version: String, versionCode: Long): Boolean = try {
        ApkIdentityValidation.isInstalled(identity(installed()), version, versionCode)
    } catch (_: Exception) {
        false
    }

    @Suppress("DEPRECATION")
    private fun archive(path: String): PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
        packages.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(signingFlags().toLong()))
    } else {
        packages.getPackageArchiveInfo(path, signingFlags())
    }

    @Suppress("DEPRECATION")
    private fun installed(): PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
        packages.getPackageInfo(ApkIdentityValidation.PACKAGE_NAME,
            PackageManager.PackageInfoFlags.of(signingFlags().toLong()))
    } else {
        packages.getPackageInfo(ApkIdentityValidation.PACKAGE_NAME, signingFlags())
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= 28) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): ApkIdentity {
        // Current signers only: accepting any overlap of histories can permit an unsupported rollback.
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val signers = signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
        }.toSet()
        return ApkIdentity(info.packageName.orEmpty(), info.versionName,
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(), signers)
    }
}
