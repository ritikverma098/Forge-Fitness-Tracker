package com.forge.workout.updates

import android.app.Application
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun files() = UpdateFiles(File(temporary.root, "updates"))
    private fun pending() = PendingApk(AvailableUpdate("1.4.2", "v1.4.2", "", "", LocalDate.ofEpochDay(0),
        "https://github.com/ritikverma098/Forge-Fitness-Tracker/releases/download/v1.4.2/Forge-1.4.2.apk", 20, "a".repeat(64)), 11, 100)

    @Test fun privateJournalRoundTripsWithoutNotesOrSignedCdnUrls() {
        val files = files()
        files.write(pending().copy(sessionId = 37))
        assertEquals(pending().copy(sessionId = 37), files.read())
        val json = File(files.directory, "pending.json").readText()
        assertFalse(json.contains("changelog"))
        assertFalse(json.contains("release-assets"))
    }

    @Test fun processRecoveryRetainsOnlyReferencedVerifiedApkAndMetadata() {
        val files = files()
        files.prepare()
        files.partial.writeText("apk")
        files.acceptPartial()
        files.write(pending())
        files.partial.writeText("abandoned")
        File(files.directory, "old.apk").writeText("obsolete")
        files.cleanup(keepVerified = true)
        assertTrue(files.apk.isFile)
        assertNotNull(files.read())
        assertFalse(files.partial.exists())
        assertEquals(setOf("verified.apk", "pending.json"), files.directory.list()?.toSet())
    }

    @Test fun cleanupDeletesAllUpdateFilesButNeverSiblingBackupsOrFoodPacks() {
        val backup = File(temporary.root, "personal-backup.json").apply { writeText("private") }
        val pack = File(temporary.root, "catalog.forgefoods").apply { writeText("food") }
        val files = files()
        files.prepare()
        files.partial.writeText("partial")
        files.apk.writeText("apk")
        files.write(pending())
        assertTrue(files.retainedBytes() > 0)
        files.cleanup()
        assertEquals(0L, files.retainedBytes())
        assertNull(files.read())
        assertEquals("private", backup.readText())
        assertEquals("food", pack.readText())
    }

    @Test fun malformedOversizedAndForeignMetadataAreRejected() {
        val files = files()
        files.prepare()
        for (content in listOf("{", "x".repeat(8193))) {
            File(files.directory, "pending.json").writeText(content)
            assertThrows(UpdateException::class.java) { files.read() }
        }
        files.write(pending())
        val journal = File(files.directory, "pending.json")
        journal.writeText(journal.readText().replace("github.com", "evil.test"))
        assertThrows(UpdateException::class.java) { files.read() }
    }
}
