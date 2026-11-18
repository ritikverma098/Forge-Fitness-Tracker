package com.forge.workout.updates

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ApkUpdateCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = ByteArray(96) { it.toByte() }
    private val release = AvailableUpdate("1.4.2", "v1.4.2", "Forge", "Notes", LocalDate.of(2026, 11, 16),
        "https://github.com/ritikverma098/Forge-Fitness-Tracker/releases/download/v1.4.2/Forge-1.4.2.apk",
        payload.size.toLong(), sha256(payload))
    private val now = AtomicLong(1_800_000_000_000L)
    private val files by lazy { UpdateFiles(File(temporary.root, "updates")) }
    private val downloader = FakeDownloader()
    private val inspector = FakeInspector()
    private val installer = FakeInstaller()
    private val jobs = mutableListOf<kotlinx.coroutines.Job>()

    @After fun stopWorkers() = runBlocking {
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
    }

    @Test fun duplicateRequestsShareOneTransferAndPublishOnlyVerifiedReadyFile() = runBlocking {
        val barrier = CompletableDeferred<Unit>()
        downloader.barrier = barrier
        val manager = createStarted()
        accepted { manager.download(release) }
        downloader.entered.await()
        assertFalse(manager.download(release))
        assertEquals(1, downloader.calls.get())
        assertTrue(files.partial.isFile)
        assertFalse(files.apk.exists())
        barrier.complete(Unit)
        await(manager, UpdatePhase.READY)
        assertArrayEquals(payload, files.apk.readBytes())
        assertFalse(files.partial.exists())
        assertTrue(manager.state.value.verified)
        assertEquals(11L, files.read()?.versionCode)
        assertEquals(release.apkSize, manager.state.value.total)
        assertEquals(payload.size.toLong(), manager.state.value.bytes)
    }

    @Test fun immediateRetriesHaveDistinctIdentityWithoutResettingItOnRefreshOrDiscard() = runBlocking {
        downloader.failure = UpdateException(UpdateFailure.NETWORK)
        val manager = createStarted()
        accepted { manager.download(release) }
        await(manager, UpdatePhase.FAILED)
        val first = manager.state.value.attempt
        assertTrue(first > 0)
        accepted(manager::retryDownload)
        withTimeout(5_000) { manager.state.first { it.phase == UpdatePhase.FAILED && it.attempt > first } }
        val second = manager.state.value.attempt
        assertEquals(2, downloader.calls.get())
        accepted(manager::refresh)
        accepted(manager::discard)
        await(manager, UpdatePhase.CANCELLED)
        assertEquals(second, manager.state.value.attempt)
    }

    @Test fun cancellationDeletesPartialAndAllowsOneCleanExplicitRetry() = runBlocking {
        downloader.barrier = CompletableDeferred()
        val manager = createStarted()
        accepted { manager.download(release) }
        downloader.entered.await()
        manager.cancelDownload()
        await(manager, UpdatePhase.CANCELLED)
        assertEquals(0L, files.retainedBytes())
        assertFalse(files.partial.exists())
        assertNull(files.read())
        downloader.barrier = null
        accepted(manager::retryDownload)
        await(manager, UpdatePhase.READY)
        assertEquals(2, downloader.calls.get())
        assertArrayEquals(payload, files.apk.readBytes())
    }

    @Test fun networkFailureRemovesPartialAndRetryRetainsOriginalTrustedOffer() = runBlocking {
        downloader.failure = UpdateException(UpdateFailure.NETWORK)
        val manager = createStarted()
        accepted { manager.download(release) }
        await(manager, UpdatePhase.FAILED)
        assertEquals(UpdateFailure.NETWORK, manager.state.value.failure)
        assertFalse(manager.state.value.verified)
        assertEquals(0L, files.retainedBytes())
        downloader.failure = null
        accepted(manager::retryDownload)
        await(manager, UpdatePhase.READY)
        assertEquals(release.apkUrl, files.read()?.release?.apkUrl)
        assertEquals(release.apkSha256, files.read()?.release?.apkSha256)
    }

    @Test fun missingChecksumNeverOpensTransferOrLeavesTemporaryBytes() = runBlocking {
        val manager = createStarted()
        accepted { manager.download(release.copy(apkSha256 = null)) }
        await(manager, UpdatePhase.FAILED)
        assertEquals(UpdateFailure.MISSING_CHECKSUM, manager.state.value.failure)
        assertEquals(0, downloader.calls.get())
        assertEquals(0L, files.retainedBytes())
    }

    @Test fun invalidArchiveIdentityCannotBecomeReadyAndLeavesNoJournal() = runBlocking {
        inspector.reject.set(true)
        val manager = createStarted()
        accepted { manager.download(release) }
        await(manager, UpdatePhase.FAILED)
        assertEquals(UpdateFailure.INVALID_APK, manager.state.value.failure)
        assertFalse(manager.state.value.verified)
        assertEquals(0L, files.retainedBytes())
        assertNull(files.read())
        assertTrue(installer.commits.isEmpty())
    }

    @Test fun startupCleansAbandonedPartialWithoutTouchingNeighborData() = runBlocking {
        files.prepare()
        files.partial.writeBytes(payload.copyOfRange(0, 7))
        File(files.directory, "old.part").writeText("interrupted")
        val backup = File(temporary.root, "personal-backup.json").apply { writeText("personal sentinel") }
        val manager = createStarted()
        assertEquals(UpdatePhase.IDLE, manager.state.value.phase)
        assertEquals(0L, files.retainedBytes())
        assertEquals("personal sentinel", backup.readText())
        assertEquals(0, downloader.calls.get())
    }

    @Test fun signatureMigrationRequirementCleansDownloadAndNeverCreatesAnInstallerSession() = runBlocking {
        inspector.signatureMismatch.set(true)
        val manager = createStarted()
        accepted { manager.download(release) }
        await(manager, UpdatePhase.FAILED)
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH, manager.state.value.failure)
        assertFalse(manager.state.value.verified)
        assertEquals(0L, files.retainedBytes())
        assertNull(files.read())
        assertTrue(installer.sessions.isEmpty())
        assertTrue(installer.commits.isEmpty())
    }

    @Test fun recreatedCoordinatorReverifiesReadyJournalWithoutRedownloading() = runBlocking {
        val first = downloaded()
        stopCurrentProcess()
        val verificationCount = inspector.verifications.get()
        val second = createStarted(expected = UpdatePhase.READY)
        assertEquals(verificationCount + 1, inspector.verifications.get())
        assertEquals(first.state.value.version, second.state.value.version)
        assertEquals(1, downloader.calls.get())
        assertArrayEquals(payload, files.apk.readBytes())
        assertFalse(files.partial.exists())
    }

    @Test fun tamperedRetainedFileIsRejectedAtStartupAndCleaned() = runBlocking {
        downloaded()
        stopCurrentProcess()
        files.apk.writeBytes(payload.copyOf().apply { this[0] = 100 })
        val manager = createStarted(expected = UpdatePhase.FAILED)
        assertEquals(UpdateFailure.INTEGRITY, manager.state.value.failure)
        assertFalse(manager.state.value.verified)
        assertEquals(0L, files.retainedBytes())
        assertTrue(installer.commits.isEmpty())
    }

    @Test fun tamperingAfterReadyRevokesVerifiedStateBeforeInstall() = runBlocking {
        val manager = downloaded()
        files.apk.writeBytes(payload.copyOf().apply { this[0] = 100 })
        accepted(manager::install)
        await(manager, UpdatePhase.FAILED)
        assertEquals(UpdateFailure.INTEGRITY, manager.state.value.failure)
        assertFalse("A changed APK must not remain advertised as verified", manager.state.value.verified)
        assertEquals(0L, files.retainedBytes())
        assertNull(files.read())
        assertTrue(installer.commits.isEmpty())
    }

    @Test fun permissionDenialPreservesReadyFileAndGrantReturnsToExplicitInstallAction() = runBlocking {
        installer.allowed.set(false)
        val manager = downloaded(UpdatePhase.PERMISSION_REQUIRED)
        accepted(manager::install)
        withTimeout(5_000) { while (installer.permissionChecks.get() < 2) yield() }
        await(manager, UpdatePhase.PERMISSION_REQUIRED)
        assertTrue(installer.commits.isEmpty())
        assertTrue(files.apk.isFile)
        installer.allowed.set(true)
        accepted(manager::refresh)
        await(manager, UpdatePhase.READY)
        assertTrue("Granting access must not silently install", installer.commits.isEmpty())
        accepted(manager::install)
        installer.firstCommit.await()
        assertEquals(1, installer.commits.size)
    }

    @Test fun abortedInstallationRetainsVerifiedSourceForGenuineRetry() = runBlocking {
        val manager = installedSession()
        val firstId = installer.commits.single()
        manager.onInstallerResult(firstId, PackageInstaller.STATUS_FAILURE_ABORTED, null).join()
        assertEquals(UpdatePhase.CANCELLED, manager.state.value.phase)
        assertTrue(manager.state.value.verified)
        assertTrue(files.apk.isFile)
        assertNull(files.read()?.sessionId)
        assertTrue(firstId in installer.abandoned)
        accepted(manager::install)
        withTimeout(5_000) { while (installer.commits.size != 2) yield() }
        assertNotEquals(firstId, installer.commits.last())
        assertArrayEquals(payload, installer.lastStagedBytes)
    }

    @Test fun staleCallbacksCannotChangeCurrentSessionOrClaimSuccess() = runBlocking {
        val manager = installedSession()
        val current = installer.commits.single()
        val stateBefore = manager.state.value
        manager.onInstallerResult(current + 99, PackageInstaller.STATUS_SUCCESS, null).join()
        assertEquals(stateBefore, manager.state.value)
        assertEquals(current, files.read()?.sessionId)
        assertTrue(files.apk.isFile)
    }

    @Test fun installerSuccessCallbackAloneCannotClaimInstalledOrDeleteSource() = runBlocking {
        val manager = installedSession()
        manager.onInstallerResult(installer.commits.single(), PackageInstaller.STATUS_SUCCESS, null).join()
        assertEquals(UpdatePhase.FAILED, manager.state.value.phase)
        assertTrue(files.apk.isFile)
        assertEquals(UpdateFailure.INSTALLATION, manager.state.value.failure)
    }

    @Test fun confirmationIsOneShotAndCancelledInstallerCanBeDiscardedSafely() = runBlocking {
        val manager = installedSession()
        val id = installer.commits.single()
        val approval = Intent("test.android.install.confirmation")
        manager.onInstallerResult(id, PackageInstaller.STATUS_PENDING_USER_ACTION, approval).join()
        assertEquals(UpdatePhase.APPROVAL_READY, manager.state.value.phase)
        assertSame(approval, manager.takeConfirmation())
        assertNull(manager.takeConfirmation())
        assertEquals(UpdatePhase.HANDED_TO_INSTALLER, manager.state.value.phase)
        accepted(manager::discard)
        await(manager, UpdatePhase.CANCELLED)
        assertTrue(id in installer.abandoned)
        assertEquals(0L, files.retainedBytes())
        assertNull(manager.confirmation.value)
        assertNull(files.read())
    }

    @Test fun failedConfirmationLaunchRetainsVerifiedApkForExplicitRetryAndKeepsUnreleasedOwnership() = runBlocking {
        val manager = installedSession()
        val firstId = installer.commits.single()
        manager.onInstallerResult(firstId, PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("android.approval")).join()
        assertNotNull(manager.takeConfirmation())
        accepted(manager::installationLaunchFailed)
        await(manager, UpdatePhase.FAILED)
        assertEquals(UpdateFailure.INSTALLATION, manager.state.value.failure)
        assertTrue(manager.state.value.verified)
        assertArrayEquals(payload, files.apk.readBytes())
        assertNull(manager.confirmation.value)
        assertNull(files.read()?.sessionId)
        assertTrue(firstId in installer.abandoned)
        assertEquals("A failed UI handoff must not silently retry", 1, installer.commits.size)

        accepted(manager::install)
        withTimeout(5_000) { while (installer.commits.size != 2) yield() }
        val retryId = installer.commits.last()
        assertNotEquals(firstId, retryId)
        manager.onInstallerResult(retryId, PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("android.approval.retry")).join()
        assertNotNull(manager.takeConfirmation())
        installer.abandonFailure = UpdateException(UpdateFailure.INSTALLATION)
        accepted(manager::installationLaunchFailed)
        await(manager, UpdatePhase.FAILED)
        assertEquals(retryId, files.read()?.sessionId)
        assertTrue(retryId in installer.sessions)
        assertTrue(manager.state.value.verified)
        assertArrayEquals(payload, files.apk.readBytes())
    }

    @Test fun processRecreationKeepsOwnedPendingSessionUntilExplicitRetryOrDiscard() = runBlocking {
        installedSession()
        val current = installer.commits.single()
        stopCurrentProcess()
        val manager = createStarted(expected = UpdatePhase.HANDED_TO_INSTALLER)
        assertEquals(current, files.read()?.sessionId)
        assertTrue(current in installer.sessions)
        assertNull("Lost confirmation must not be fabricated", manager.confirmation.value)
        assertEquals(1, installer.commits.size)
    }

    @Test fun installerLaunchFailureRetainsVerifiedApkAndRequiresExplicitRetry() = runBlocking {
        val manager = installedSession()
        val owned = installer.commits.single()
        manager.onInstallerResult(owned, PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("approval")).join()
        assertNotNull(manager.takeConfirmation())
        accepted(manager::installationLaunchFailed)
        await(manager, UpdatePhase.FAILED)
        assertEquals(UpdateFailure.INSTALLATION, manager.state.value.failure)
        assertTrue(manager.state.value.verified)
        assertTrue(files.apk.isFile)
        assertNull(files.read()?.sessionId)
        assertTrue(owned in installer.abandoned)
        assertEquals(1, installer.commits.size)
        accepted(manager::install)
        withTimeout(5_000) { while (installer.commits.size < 2) yield() }
        assertNotEquals(owned, installer.commits.last())
    }

    @Test fun confirmedInstalledVersionAutomaticallyCleansSourceAndJournalAtStartup() = runBlocking {
        installedSession()
        stopCurrentProcess()
        inspector.installed.set(true)
        val verificationCount = inspector.verifications.get()
        val manager = createStarted(expected = UpdatePhase.INSTALLED)
        assertEquals(release.version, manager.state.value.version)
        assertEquals(0L, manager.state.value.retainedBytes)
        assertEquals(0L, files.retainedBytes())
        assertEquals(verificationCount, inspector.verifications.get())
        assertFalse(files.apk.exists())
        assertFalse(files.partial.exists())
        assertNull(files.read())
    }

    @Test fun stalePendingApprovalIsAbandonedAndCannotSurviveAsLaunchableIntent() = runBlocking {
        val manager = installedSession()
        val id = installer.commits.single()
        manager.onInstallerResult(id, PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("old.approval")).join()
        assertNotNull(manager.confirmation.value)
        now.addAndGet(24 * 60 * 60 * 1000L)
        accepted(manager::refresh)
        await(manager, UpdatePhase.CANCELLED)
        assertTrue(id in installer.abandoned)
        assertNull("An abandoned session cannot retain a confirmation", manager.confirmation.value)
        assertNull(manager.takeConfirmation())
        assertEquals(0L, files.retainedBytes())
    }

    @Test fun disappearedSessionReturnsToReadyAndClearsOldConfirmation() = runBlocking {
        val manager = installedSession()
        val id = installer.commits.single()
        manager.onInstallerResult(id, PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("old.approval")).join()
        installer.sessions.remove(id)
        accepted(manager::refresh)
        await(manager, UpdatePhase.READY)
        assertNull(files.read()?.sessionId)
        assertNull(manager.confirmation.value)
        assertNull(manager.takeConfirmation())
        assertTrue(files.apk.isFile)
    }

    @Test fun stagingFailureAbandonsCreatedSessionAndAllowsSafeRetry() = runBlocking {
        installer.stagingFailure = UpdateException(UpdateFailure.STORAGE)
        val manager = downloaded()
        accepted(manager::install)
        await(manager, UpdatePhase.FAILED)
        assertEquals(UpdateFailure.STORAGE, manager.state.value.failure)
        assertTrue(manager.state.value.verified)
        assertEquals(1, installer.abandoned.size)
        assertTrue(installer.commits.isEmpty())
        assertNull(files.read()?.sessionId)
        installer.stagingFailure = null
        accepted(manager::install)
        installer.firstCommit.await()
        assertEquals(1, installer.commits.size)
    }

    @Test fun scopeCancellationDuringStagingDoesNotBecomeInstallationFailure() = runBlocking {
        installer.stagingBarrier = CompletableDeferred()
        val manager = downloaded()
        accepted(manager::install)
        installer.stageEntered.await()
        stopCurrentProcess()
        assertEquals(UpdatePhase.CANCELLED, manager.state.value.phase)
        assertTrue(manager.state.value.verified)
        assertNull(manager.state.value.failure)
        assertTrue(installer.commits.isEmpty())
        assertEquals(1, installer.abandoned.size)
        assertNull(files.read()?.sessionId)
        assertTrue(files.apk.isFile)
        installer.stagingBarrier = null
        val recreated = createStarted(expected = UpdatePhase.READY)
        accepted(recreated::install)
        installer.firstCommit.await()
        assertEquals(1, installer.commits.size)
    }

    @Test fun failedAbandonmentPreservesOwnershipUntilInstallRetryCanReleaseIt() = runBlocking {
        installer.stagingFailure = UpdateException(UpdateFailure.STORAGE)
        installer.abandonFailure = UpdateException(UpdateFailure.INSTALLATION)
        val manager = downloaded()
        accepted(manager::install)
        await(manager, UpdatePhase.FAILED)
        val owned = requireNotNull(files.read()?.sessionId)
        assertTrue(owned in installer.sessions)
        assertTrue(manager.state.value.verified)
        assertTrue(files.apk.isFile)
        assertTrue(installer.commits.isEmpty())
        installer.stagingFailure = null
        installer.abandonFailure = null
        accepted(manager::install)
        installer.firstCommit.await()
        assertTrue(owned in installer.abandoned)
        assertNotEquals(owned, installer.commits.single())
    }

    @Test fun tamperFailureCannotForgetAnOwnedSessionWhoseAbandonmentFailed() = runBlocking {
        val manager = installedSession()
        val owned = installer.commits.single()
        installer.abandonFailure = UpdateException(UpdateFailure.INSTALLATION)
        files.apk.writeBytes(payload.copyOf().apply { this[0] = 100 })
        accepted(manager::install)
        await(manager, UpdatePhase.FAILED)
        assertFalse(manager.state.value.verified)
        assertEquals(owned, files.read()?.sessionId)
        assertTrue(owned in installer.sessions)
        assertEquals(1, installer.commits.size)
        installer.abandonFailure = null
        accepted(manager::retryDownload)
        await(manager, UpdatePhase.READY)
        assertTrue(owned in installer.abandoned)
        assertNull(files.read()?.sessionId)
        assertArrayEquals(payload, files.apk.readBytes())
    }

    @Test fun scopeCancellationRetainsOwnershipIfAbandonmentNeedsLaterRetry() = runBlocking {
        installer.stagingBarrier = CompletableDeferred()
        installer.abandonFailure = UpdateException(UpdateFailure.INSTALLATION)
        val manager = downloaded()
        accepted(manager::install)
        installer.stageEntered.await()
        stopCurrentProcess()
        val owned = requireNotNull(files.read()?.sessionId)
        assertTrue(owned in installer.sessions)
        assertEquals(UpdatePhase.CANCELLED, manager.state.value.phase)
        assertTrue(manager.state.value.verified)
        installer.abandonFailure = null
        installer.stagingBarrier = null
        val recreated = createStarted(expected = UpdatePhase.HANDED_TO_INSTALLER)
        accepted(recreated::install)
        installer.firstCommit.await()
        assertTrue(owned in installer.abandoned)
        assertNotEquals(owned, installer.commits.single())
    }

    private suspend fun downloaded(expected: UpdatePhase = UpdatePhase.READY): ApkUpdateCoordinator {
        val manager = createStarted()
        accepted { manager.download(release) }
        await(manager, expected)
        return manager
    }

    private suspend fun installedSession(): ApkUpdateCoordinator {
        val manager = downloaded()
        accepted(manager::install)
        installer.firstCommit.await()
        return manager
    }

    private suspend fun createStarted(expected: UpdatePhase = UpdatePhase.IDLE): ApkUpdateCoordinator {
        val job = SupervisorJob()
        jobs += job
        return ApkUpdateCoordinator(files, downloader, inspector, installer,
            CoroutineScope(job + Dispatchers.Default), now::get).also {
            it.start()
            await(it, expected)
        }
    }

    private suspend fun stopCurrentProcess() {
        jobs.last().cancel()
        jobs.last().join()
    }

    private suspend fun accepted(action: () -> Boolean) {
        withTimeout(5_000) { while (!action()) yield() }
    }

    private suspend fun await(manager: ApkUpdateCoordinator, phase: UpdatePhase) {
        withTimeout(5_000) { manager.state.first { it.phase == phase } }
    }

    private inner class FakeDownloader : ApkDownloader {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        @Volatile var barrier: CompletableDeferred<Unit>? = null
        @Volatile var failure: Exception? = null
        override suspend fun download(release: AvailableUpdate, destination: File, progress: (Long, Long) -> Unit): DownloadedApk {
            calls.incrementAndGet()
            destination.writeBytes(payload.copyOfRange(0, 8))
            progress(8, payload.size.toLong())
            entered.complete(Unit)
            barrier?.await()
            failure?.let { throw it }
            destination.writeBytes(payload)
            progress(payload.size.toLong(), payload.size.toLong())
            return DownloadedApk(payload.size.toLong(), sha256(payload))
        }
    }

    private class FakeInspector : ApkInspector {
        val installed = AtomicBoolean()
        val reject = AtomicBoolean()
        val signatureMismatch = AtomicBoolean()
        val verifications = AtomicInteger()
        override fun verify(file: File, release: AvailableUpdate): Long {
            verifications.incrementAndGet()
            if (reject.get()) throw UpdateException(UpdateFailure.INVALID_APK)
            if (signatureMismatch.get()) throw UpdateException(UpdateFailure.SIGNATURE_MISMATCH)
            return 11
        }
        override fun isInstalled(version: String, versionCode: Long) = installed.get()
    }

    private class FakeInstaller : UpdateInstaller {
        val allowed = AtomicBoolean(true)
        val permissionChecks = AtomicInteger()
        val sessions = ConcurrentHashMap.newKeySet<Int>()
        val commits = CopyOnWriteArrayList<Int>()
        val abandoned = CopyOnWriteArrayList<Int>()
        val firstCommit = CompletableDeferred<Unit>()
        val stageEntered = CompletableDeferred<Unit>()
        private val nextId = AtomicInteger(7)
        @Volatile var stagingFailure: Exception? = null
        @Volatile var stagingBarrier: CompletableDeferred<Unit>? = null
        @Volatile var abandonFailure: Exception? = null
        @Volatile var lastStagedBytes: ByteArray? = null
        override fun canRequestInstalls(): Boolean {
            val permission = allowed.get()
            permissionChecks.incrementAndGet()
            return permission
        }
        override fun hasSession(sessionId: Int) = sessionId in sessions
        override suspend fun stage(apk: File, onCreated: suspend (Int) -> Unit): Int {
            val id = nextId.getAndIncrement()
            sessions += id
            onCreated(id)
            stageEntered.complete(Unit)
            stagingBarrier?.await()
            stagingFailure?.let { throw it }
            lastStagedBytes = apk.readBytes()
            return id
        }
        override fun commit(sessionId: Int) {
            commits += sessionId
            firstCommit.complete(Unit)
        }
        override fun abandon(sessionId: Int) {
            abandonFailure?.let { throw it }
            abandoned += sessionId
            sessions -= sessionId
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
