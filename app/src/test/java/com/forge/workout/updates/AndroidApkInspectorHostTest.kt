package com.forge.workout.updates

import android.app.Application
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = Application::class)
class AndroidApkInspectorHostTest {
    @get:Rule val files = TemporaryFolder()
    private val inspector get() = AndroidApkInspector(RuntimeEnvironment.getApplication())
    private val release = AvailableUpdate("1.4.2", "v1.4.2", "Forge", "", LocalDate.of(2026, 11, 16),
        "https://github.com/ritikverma098/Forge-Fitness-Tracker/releases/download/v1.4.2/Forge-1.4.2.apk")

    @Test fun missingEmptyAndCorruptFilesCannotAuthorizeInstallation() {
        val missing = File(files.root, "missing.apk")
        val empty = files.newFile("empty.apk")
        val corrupt = files.newFile("corrupt.apk").apply { writeBytes(byteArrayOf(0x50, 0x4b, 0, 1, 2)) }
        for (file in listOf(missing, empty, corrupt, files.root)) {
            assertEquals(UpdateFailure.INVALID_APK, assertThrows(UpdateException::class.java) {
                inspector.verify(file, release)
            }.issue)
        }
    }

    @Test fun absentInstalledOfficialPackageDoesNotClaimSuccessfulUpdate() {
        assertFalse(inspector.isInstalled("1.4.2", 11))
    }
}
