package com.forge.workout.updates

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Uses only a synthetic cache fixture, including its sibling sentinel directory. */
@RunWith(AndroidJUnit4::class)
class UpdateFileSafetyTest {
    @get:Rule val temporary = TemporaryFolder(ApplicationProvider.getApplicationContext<Context>().cacheDir)
    private var directoryLink: File? = null

    @After fun unlinkFixture() {
        directoryLink?.let { if (Files.isSymbolicLink(it.toPath())) Files.delete(it.toPath()) }
    }

    @Test fun prepareRejectsDirectorySymlinkWithoutTouchingSiblingData() {
        val (updates, sentinel) = linkedRoot()
        val result = runCatching { updates.prepare() }
        assertTrue(result.exceptionOrNull() is UpdateException)
        assertEquals(UpdateFailure.STORAGE, (result.exceptionOrNull() as UpdateException).issue)
        assertEquals("personal sentinel", sentinel.readText())
    }

    @Test fun cleanupRejectsDirectorySymlinkWithoutDeletingSiblingData() {
        val (updates, sentinel) = linkedRoot()
        val result = runCatching { updates.cleanup() }
        assertTrue("Cleanup must not traverse the linked sibling directory", sentinel.isFile)
        assertEquals("personal sentinel", sentinel.readText())
        assertTrue(result.exceptionOrNull() is UpdateException)
        assertEquals(UpdateFailure.STORAGE, (result.exceptionOrNull() as UpdateException).issue)
    }

    private fun linkedRoot(): Pair<UpdateFiles, File> {
        val sibling = temporary.newFolder("personal-data")
        val sentinel = File(sibling, "personal-backup.json").apply { writeText("personal sentinel") }
        val link = File(temporary.root, "updates")
        Os.symlink(sibling.absolutePath, link.absolutePath)
        directoryLink = link
        assertTrue("A real symbolic link is required; this test must not silently skip", Files.isSymbolicLink(link.toPath()))
        return UpdateFiles(link) to sentinel
    }
}
