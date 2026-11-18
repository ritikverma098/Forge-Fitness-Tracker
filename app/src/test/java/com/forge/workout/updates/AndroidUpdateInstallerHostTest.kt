package com.forge.workout.updates

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboIntentSender
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowPackageInstaller
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidUpdateInstallerHostTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var application: Application
    private lateinit var platform: PackageInstaller
    private lateinit var installer: AndroidUpdateInstaller

    @Before fun prepare() {
        application = RuntimeEnvironment.getApplication()
        platform = application.packageManager.packageInstaller
        installer = AndroidUpdateInstaller(application)
        shadowOf(application.packageManager).setCanRequestPackageInstalls(true)
    }

    @Test fun waitsForDurableSessionOwnershipBeforeCopyAndLeavesCommitExplicit() = runBlocking {
        val apk = apk()
        val created = CompletableDeferred<Int>()
        val journalWritten = CompletableDeferred<Unit>()
        val operation = async {
            installer.stage(apk) { id ->
                created.complete(id)
                journalWritten.await()
            }
        }
        val id = created.await()
        assertTrue(installer.hasSession(id))
        assertFalse(operation.isCompleted)
        val shadow = Shadow.extract<ShadowPackageInstaller.ShadowSession>(platform.openSession(id))
        assertNull(ReflectionHelpers.getField<Any?>(shadow, "outputStream"))
        journalWritten.complete(Unit)
        assertEquals(id, operation.await())
        assertEquals(ApkIdentityValidation.PACKAGE_NAME, platform.getSessionInfo(id)!!.appPackageName)
        // Robolectric discards session bytes; real APK usability is checked on the emulator.
        assertNotNull(ReflectionHelpers.getField<Any?>(shadow, "outputStream"))
        assertFalse(ReflectionHelpers.getField<Boolean>(shadow, "outputStreamOpen"))
        assertNull(ReflectionHelpers.getField<Any?>(shadow, "statusReceiver"))
        assertTrue(apk.exists())
    }

    @Test fun permissionIsRecheckedAtStageAndCommit() = runBlocking {
        shadowOf(application.packageManager).setCanRequestPackageInstalls(false)
        assertFalse(installer.canRequestInstalls())
        expect(UpdateFailure.INSTALLATION) { installer.stage(apk()) { fail("No session expected") } }
        assertTrue(platform.mySessions.isEmpty())
        shadowOf(application.packageManager).setCanRequestPackageInstalls(true)
        val id = installer.stage(apk()) { }
        shadowOf(application.packageManager).setCanRequestPackageInstalls(false)
        expect(UpdateFailure.INSTALLATION) { installer.commit(id) }
        assertTrue(installer.hasSession(id))
        installer.abandon(id)
        assertFalse(installer.hasSession(id))
    }

    @Test fun callbackFailureAbandonsOnlyItsOwnNewSessionAndPreservesSource() = runBlocking {
        val other = platform.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        val apk = apk()
        var created = -1
        expect(UpdateFailure.STORAGE) {
            installer.stage(apk) { id -> created = id; throw IOException("journal unavailable") }
        }
        assertTrue(created > 0)
        assertFalse(installer.hasSession(created))
        assertTrue(installer.hasSession(other))
        assertTrue(apk.exists())
    }

    @Test fun cancellationWhileJournalIsWaitingAbandonsSessionAndKeepsApk() = runBlocking {
        val apk = apk()
        val created = CompletableDeferred<Int>()
        val neverWritten = CompletableDeferred<Unit>()
        val operation = async { installer.stage(apk) { id -> created.complete(id); neverWritten.await() } }
        val id = created.await()
        assertTrue(installer.hasSession(id))
        operation.cancelAndJoin()
        assertTrue(operation.isCancelled)
        assertFalse(installer.hasSession(id))
        assertTrue(apk.exists())
    }

    @Test fun unreadableSourceAfterOwnershipIsRecordedAbandonsSession() = runBlocking {
        val apk = apk()
        var created = -1
        expect(UpdateFailure.STORAGE) { installer.stage(apk) { id -> created = id; assertTrue(apk.delete()) } }
        assertTrue(created > 0)
        assertFalse(installer.hasSession(created))
    }

    @Test fun incompleteSourceAbandonsItsSessionWithoutChangingTheApk() = runBlocking {
        val actual = apk()
        val original = actual.readBytes()
        val truncated = object : File(actual.path) {
            override fun length(): Long = super.length() + 1
        }
        var created = -1
        expect(UpdateFailure.INVALID_APK) { installer.stage(truncated) { created = it } }
        assertTrue(created > 0)
        assertFalse(installer.hasSession(created))
        assertArrayEquals(original, actual.readBytes())
    }

    @Test fun commitUsesExplicitMutableSessionScopedBroadcastAndNoActivityLaunch() = runBlocking {
        val id = installer.stage(apk()) { }
        val session = platform.openSession(id)
        val shadow = Shadow.extract<ShadowPackageInstaller.ShadowSession>(session)
        installer.commit(id)
        val sender = ReflectionHelpers.getField<RoboIntentSender>(shadow, "statusReceiver")
        val callback = shadowOf(sender.pendingIntent)
        assertTrue(callback.isBroadcastIntent)
        assertFalse(callback.isImmutable)
        assertTrue(callback.flags and PendingIntent.FLAG_MUTABLE != 0)
        assertEquals(UpdateInstallReceiver::class.java.name, callback.savedIntent.component!!.className)
        assertEquals(UpdateInstallReceiver.ACTION_RESULT, callback.savedIntent.action)
        assertEquals(UpdateInstallReceiver.sessionUri(application.packageName, id), callback.savedIntent.data)
        assertEquals(id, callback.savedIntent.getIntExtra(UpdateInstallReceiver.EXTRA_OWNED_SESSION, -1))
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test fun abandonmentAndUnknownSessionQueriesAreIdempotent() = runBlocking {
        val id = installer.stage(apk()) { }
        shadowOf(platform).setSessionActiveState(id, false)
        assertTrue("An inactive session still exists", installer.hasSession(id))
        installer.abandon(id)
        installer.abandon(id)
        assertFalse(installer.hasSession(id))
        assertFalse(installer.hasSession(-1))
        expect(UpdateFailure.INSTALLATION) { installer.commit(id) }
    }

    @Test fun resultParserRequiresMatchingActionUriAndSessionAndNeverOpensConfirmation() {
        val packageName = application.packageName
        val confirmation = Intent("android.intent.action.CONFIRM_INSTALL")
        val valid = Intent(UpdateInstallReceiver.ACTION_RESULT)
            .setData(UpdateInstallReceiver.sessionUri(packageName, 7))
            .putExtra(UpdateInstallReceiver.EXTRA_OWNED_SESSION, 7)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, 7)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, confirmation)
        val parsed = requireNotNull(UpdateInstallReceiver.readResult(packageName, valid))
        assertEquals(7, parsed.sessionId)
        assertEquals(PackageInstaller.STATUS_PENDING_USER_ACTION, parsed.status)
        assertEquals(confirmation.action, parsed.confirmation!!.action)
        for (invalid in listOf(
            Intent(valid).setAction("other.action"),
            Intent(valid).setData(UpdateInstallReceiver.sessionUri(packageName, 8)),
            Intent(valid).putExtra(UpdateInstallReceiver.EXTRA_OWNED_SESSION, 8),
            Intent(valid).putExtra(PackageInstaller.EXTRA_SESSION_ID, 8),
            Intent(valid).apply { removeExtra(PackageInstaller.EXTRA_STATUS) },
        )) assertNull(UpdateInstallReceiver.readResult(packageName, invalid))
        val failure = Intent(valid).putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED)
        assertNull(requireNotNull(UpdateInstallReceiver.readResult(packageName, failure)).confirmation)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    private fun apk(): File = temporary.newFile().apply { writeBytes(ByteArray(4097) { (it % 251).toByte() }) }

    private suspend fun expect(issue: UpdateFailure, operation: suspend () -> Unit) {
        try { operation() } catch (failure: UpdateException) {
            assertEquals(issue, failure.issue)
            return
        }
        fail("Expected $issue")
    }
}
