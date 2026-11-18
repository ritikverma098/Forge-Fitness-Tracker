package com.forge.workout.updates

import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

internal enum class UpdatePhase { IDLE, PREPARING, DOWNLOADING, VERIFYING, READY, PERMISSION_REQUIRED, STAGING, HANDED_TO_INSTALLER, APPROVAL_READY, INSTALLED, CANCELLED, FAILED }
internal data class ApkUpdateState(
    val phase: UpdatePhase = UpdatePhase.PREPARING,
    val version: String? = null,
    val bytes: Long = 0,
    val total: Long? = null,
    val verified: Boolean = false,
    val failure: UpdateFailure? = null,
    val retainedBytes: Long = 0,
    val attempt: Long = 0,
)

/** One application-owned transfer/session. All durable file/session work is serialized on IO. */
internal class ApkUpdateCoordinator(
    private val files: UpdateFiles,
    private val downloader: ApkDownloader,
    private val inspector: ApkInspector,
    private val installer: UpdateInstaller,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val operationLock = Any()
    private var operation: Job? = null
    private var started = false
    private var pending: PendingApk? = null
    @Volatile private var lastOffer: AvailableUpdate? = null
    private val _state = MutableStateFlow(ApkUpdateState())
    val state = _state.asStateFlow()
    private val _confirmation = MutableStateFlow<Intent?>(null)
    val confirmation = _confirmation.asStateFlow()

    fun start() {
        synchronized(operationLock) {
            if (!started) {
                started = true
                launchOperation { recover() }
            }
        }
    }

    fun download(release: AvailableUpdate): Boolean = launchOperation {
        pending?.let { saved ->
            if (_state.value.verified) return@launchOperation
            invalidate(saved)
        }
        lastOffer = release
        val attempt = _state.value.attempt + 1
        _state.value = ApkUpdateState(UpdatePhase.PREPARING, release.version, total = release.apkSize, attempt = attempt)
        try {
            GitHubAssetPolicy.validate(release)
            files.prepare()
            files.cleanup()
            downloader.download(release, files.partial) { bytes, total ->
                _state.value = ApkUpdateState(UpdatePhase.DOWNLOADING, release.version, bytes, total, attempt = attempt)
            }
            _state.value = _state.value.copy(phase = UpdatePhase.VERIFYING)
            val code = inspector.verify(files.partial, release)
            currentCoroutineContext().ensureActive()
            files.acceptPartial()
            val ready = PendingApk(release, code, clock())
            files.write(ready)
            pending = ready
            readyState(ready)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                pending = null
                files.cleanup()
                _state.value = ApkUpdateState(UpdatePhase.CANCELLED, release.version, attempt = attempt)
            }
            throw cancelled
        } catch (failure: Exception) {
            pending = null
            files.cleanup()
            fail(failure, release.version)
        }
    }

    fun retryDownload(): Boolean = lastOffer?.let(::download) ?: false

    fun cancelDownload() = synchronized(operationLock) {
        if (_state.value.phase in setOf(UpdatePhase.PREPARING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)) {
            operation?.cancel()
        }
    }

    fun install(): Boolean = launchOperation {
        val ready = pending ?: return@launchOperation
        try {
            verifyRetained(ready)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _state.value = _state.value.copy(verified = false)
            invalidate(ready)
            fail(failure, ready.release.version)
            return@launchOperation
        }
        try {
            if (!installer.canRequestInstalls()) {
                _state.value = _state.value.copy(phase = UpdatePhase.PERMISSION_REQUIRED, failure = null)
                return@launchOperation
            }
            ready.sessionId?.let(installer::abandon)
            _confirmation.value = null
            _state.value = _state.value.copy(phase = UpdatePhase.STAGING, failure = null)
            val id = installer.stage(files.apk) { session ->
                val staged = ready.copy(sessionId = session)
                files.write(staged)
                pending = staged
            }
            currentCoroutineContext().ensureActive()
            _state.value = _state.value.copy(phase = UpdatePhase.HANDED_TO_INSTALLER)
            installer.commit(id)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                _confirmation.value = null
                val saved = pending ?: ready
                pending = releaseSession(saved, cancelled)
                try { files.write(checkNotNull(pending)) } catch (failure: Exception) { cancelled.addSuppressed(failure) }
                _state.value = ApkUpdateState(UpdatePhase.CANCELLED, ready.release.version,
                    verified = true, retainedBytes = files.retainedBytes(), attempt = _state.value.attempt)
            }
            throw cancelled
        } catch (failure: Exception) {
            _confirmation.value = null
            val saved = pending ?: ready
            pending = releaseSession(saved, failure)
            files.write(checkNotNull(pending))
            fail(failure, ready.release.version, verified = true)
        }
    }

    fun refresh(): Boolean = launchOperation { recover() }

    fun installationLaunchFailed(): Boolean = launchOperation {
        val saved = pending ?: return@launchOperation
        _confirmation.value = null
        val failure = UpdateException(UpdateFailure.INSTALLATION)
        pending = releaseSession(saved, failure)
        files.write(checkNotNull(pending))
        fail(failure, saved.release.version, verified = true)
    }

    fun discard(): Boolean = launchOperation {
        _confirmation.value = null
        pending?.sessionId?.let(installer::abandon)
        files.cleanup()
        pending = null
        _state.value = ApkUpdateState(UpdatePhase.CANCELLED, attempt = _state.value.attempt)
    }

    /** Only a persisted, owned session may affect this update. No callback alone claims success. */
    fun onInstallerResult(sessionId: Int, status: Int, intent: Intent?): Job = scope.launch(Dispatchers.IO) {
        mutex.withLock {
            try {
                val saved = pending ?: return@withLock
                if (saved.sessionId != sessionId) return@withLock
                if (inspector.isInstalled(saved.release.version, saved.versionCode)) {
                    installed(saved)
                } else if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && intent != null) {
                    _state.value = _state.value.copy(phase = UpdatePhase.APPROVAL_READY)
                    _confirmation.value = intent
                } else if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    _confirmation.value = null
                    installer.abandon(sessionId)
                    pending = saved.copy(sessionId = null)
                    files.write(checkNotNull(pending))
                    _confirmation.value = null
                    _state.value = _state.value.copy(
                        phase = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) UpdatePhase.CANCELLED else UpdatePhase.FAILED,
                        verified = true, failure = UpdateFailure.INSTALLATION)
                }
            } catch (failure: Exception) { fail(failure, _state.value.version, _state.value.verified) }
        }
    }

    fun takeConfirmation(): Intent? = synchronized(operationLock) {
        _confirmation.value.also {
            _confirmation.value = null
            if (it != null) _state.value = _state.value.copy(phase = UpdatePhase.HANDED_TO_INSTALLER)
        }
    }

    private fun launchOperation(block: suspend () -> Unit): Boolean = synchronized(operationLock) {
        if (operation?.isActive == true) return false
        operation = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            mutex.withLock {
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { fail(failure, _state.value.version, _state.value.verified) }
            }
        }
        operation?.start()
        true
    }

    private suspend fun recover() {
        files.prepare()
        val saved = try { files.read() } catch (failure: Exception) {
            _state.value = _state.value.copy(verified = false)
            invalidate(pending)
            throw failure
        }
        val previous = pending
        pending = saved
        if (saved == null) {
            invalidate(previous)
            if (_state.value.phase !in setOf(UpdatePhase.CANCELLED, UpdatePhase.FAILED, UpdatePhase.INSTALLED)) {
                _state.value = ApkUpdateState(UpdatePhase.IDLE, attempt = _state.value.attempt)
            } else {
                _state.value = _state.value.copy(verified = false, retainedBytes = files.retainedBytes())
            }
            return
        }
        lastOffer = saved.release
        if (inspector.isInstalled(saved.release.version, saved.versionCode)) {
            installed(saved)
            return
        }
        val now = clock()
        if (saved.createdAt > now + RETENTION || (now >= saved.createdAt && now - saved.createdAt >= RETENTION)) {
            _state.value = _state.value.copy(verified = false)
            invalidate(saved)
            _state.value = ApkUpdateState(UpdatePhase.CANCELLED, saved.release.version, attempt = _state.value.attempt)
            return
        }
        try { verifyRetained(saved) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _state.value = _state.value.copy(verified = false)
            invalidate(saved)
            throw failure
        }
        files.cleanup(keepVerified = true)
        if (saved.sessionId?.let(installer::hasSession) == true) {
            _state.value = ApkUpdateState(UpdatePhase.HANDED_TO_INSTALLER, saved.release.version,
                saved.release.apkSize ?: 0, saved.release.apkSize, verified = true, retainedBytes = files.retainedBytes(),
                attempt = _state.value.attempt)
        } else {
            _confirmation.value = null
            val ready = saved.copy(sessionId = null)
            if (ready != saved) files.write(ready)
            pending = ready
            readyState(ready)
        }
    }

    private suspend fun verifyRetained(saved: PendingApk) {
        GitHubAssetPolicy.validate(saved.release)
        if (files.apk.length() != saved.release.apkSize || !files.apk.isFile) throw UpdateException(UpdateFailure.INTEGRITY)
        val digest = MessageDigest.getInstance("SHA-256")
        files.apk.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (actual != saved.release.apkSha256 || inspector.verify(files.apk, saved.release) != saved.versionCode) {
            throw UpdateException(UpdateFailure.INTEGRITY)
        }
    }

    private fun installed(saved: PendingApk) {
        _confirmation.value = null
        files.cleanup()
        pending = null
        _state.value = ApkUpdateState(UpdatePhase.INSTALLED, saved.release.version,
            retainedBytes = files.retainedBytes(), attempt = _state.value.attempt)
    }

    private fun invalidate(saved: PendingApk?) {
        _confirmation.value = null
        pending = saved
        if (saved?.sessionId != null) {
            // Keep ownership durable until Android confirms abandonment, including after a failed retry.
            files.write(saved)
            installer.abandon(saved.sessionId)
        }
        files.cleanup()
        pending = null
    }

    private fun releaseSession(saved: PendingApk, failure: Exception): PendingApk {
        val id = saved.sessionId ?: return saved
        return try {
            installer.abandon(id)
            saved.copy(sessionId = null)
        } catch (cleanupFailure: Exception) {
            failure.addSuppressed(cleanupFailure)
            saved
        }
    }

    private fun readyState(saved: PendingApk) {
        _state.value = ApkUpdateState(
            if (installer.canRequestInstalls()) UpdatePhase.READY else UpdatePhase.PERMISSION_REQUIRED,
            saved.release.version, saved.release.apkSize ?: 0, saved.release.apkSize,
            verified = true, retainedBytes = files.retainedBytes(), attempt = _state.value.attempt)
    }

    private fun fail(failure: Exception, version: String?, verified: Boolean = false) {
        _state.value = ApkUpdateState(UpdatePhase.FAILED, version, verified = verified,
            failure = (failure as? UpdateException)?.issue ?: if (failure is IOException) UpdateFailure.STORAGE else UpdateFailure.INSTALLATION,
            attempt = _state.value.attempt)
    }

    private companion object { const val RETENTION = 24 * 60 * 60 * 1000L }
}
