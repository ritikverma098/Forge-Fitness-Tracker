package com.forge.workout.updates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal class AndroidUpdateInstaller(context: Context) : UpdateInstaller {
    private val application = context.applicationContext
    private val packages = application.packageManager
    private val installer = packages.packageInstaller

    override fun canRequestInstalls(): Boolean = try {
        packages.canRequestPackageInstalls()
    } catch (_: RuntimeException) { false }

    override fun hasSession(sessionId: Int): Boolean = try {
        sessionId > 0 && installer.mySessions.any { it.sessionId == sessionId }
    } catch (failure: RuntimeException) { throw UpdateException(UpdateFailure.INSTALLATION, failure) }

    override suspend fun stage(apk: File, onCreated: suspend (Int) -> Unit): Int = withContext(Dispatchers.IO) {
        var created: Int? = null
        try {
            if (!canRequestInstalls()) throw UpdateException(UpdateFailure.INSTALLATION)
            val size = apk.length()
            if (!apk.isFile || size !in 1..GitHubAssetPolicy.MAX_APK_BYTES) throw UpdateException(UpdateFailure.INVALID_APK)
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ApkIdentityValidation.PACKAGE_NAME)
                setSize(size)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            val id = installer.createSession(params)
            created = id
            // The coordinator must durably own this session before a copy or commit can occur.
            onCreated(id)
            currentCoroutineContext().ensureActive()
            if (!canRequestInstalls()) throw UpdateException(UpdateFailure.INSTALLATION)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, size).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var copied = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            copied += count
                            if (copied > size) throw UpdateException(UpdateFailure.INVALID_APK)
                            output.write(buffer, 0, count)
                        }
                        if (copied != size) throw UpdateException(UpdateFailure.INVALID_APK)
                        session.fsync(output)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            id
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                try { created?.let(::abandon) }
                catch (cleanupFailure: Exception) { failure.addSuppressed(cleanupFailure) }
            }
            currentCoroutineContext().ensureActive()
            when (failure) {
                is CancellationException -> throw failure
                is UpdateException -> throw failure
                is IOException -> throw UpdateException(UpdateFailure.STORAGE, failure)
                else -> throw UpdateException(UpdateFailure.INSTALLATION, failure)
            }
        }
    }

    override fun commit(sessionId: Int) {
        if (!canRequestInstalls() || !hasSession(sessionId)) throw UpdateException(UpdateFailure.INSTALLATION)
        try {
            val callback = Intent(application, UpdateInstallReceiver::class.java)
                .setAction(UpdateInstallReceiver.ACTION_RESULT)
                .setData(UpdateInstallReceiver.sessionUri(application.packageName, sessionId))
                .putExtra(UpdateInstallReceiver.EXTRA_OWNED_SESSION, sessionId)
            val flags = PendingIntent.FLAG_CANCEL_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(application, sessionId, callback, flags)
            installer.openSession(sessionId).use { it.commit(pending.intentSender) }
        } catch (failure: IOException) {
            throw UpdateException(UpdateFailure.STORAGE, failure)
        } catch (failure: RuntimeException) {
            throw UpdateException(UpdateFailure.INSTALLATION, failure)
        }
    }

    override fun abandon(sessionId: Int) {
        if (!hasSession(sessionId)) return
        try { installer.abandonSession(sessionId) }
        catch (failure: RuntimeException) {
            // Completion may remove the session between the ownership check and abandonment.
            if (hasSession(sessionId)) throw UpdateException(UpdateFailure.INSTALLATION, failure)
        }
    }
}
