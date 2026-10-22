package com.forge.workout.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.CharacterCodingException
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalBackupDocumentsHostTest {
    private lateinit var context: Context
    private lateinit var documents: LocalBackupDocuments
    private lateinit var directory: File
    private val files = mutableListOf<File>()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        documents = LocalBackupDocuments(context)
        directory = File(context.cacheDir, "backup-doc-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
    }

    @After fun tearDown() {
        files.asReversed().forEach { it.delete() }
        directory.delete()
    }

    @Test fun `native file document preserves UTF8 exercise and workout notes through backup roundtrip`() {
        val fixture = completeBackup()
        val original = fixture.copy(workouts = fixture.workouts.map { it.copy(notes = "Séance réussie 💪\nधीरे और नियंत्रित") })
        val encoded = BackupCodec.encode(original)
        val file = tempFile()

        documents.write(Uri.fromFile(file), encoded)
        val restoredText = documents.read(Uri.fromFile(file))
        assertEquals(encoded, restoredText)
        assertArrayEquals(encoded.toByteArray(Charsets.UTF_8), file.readBytes())
        val restored = BackupCodec.decode(restoredText)
        assertEquals(original, restored)
        assertTrue(restored.exercises.first().notes.contains("हिन्दी"))
        assertEquals(original.workouts.first().notes, restored.workouts.first().notes)
    }

    @Test fun `write mode truncates an existing longer document without stale trailing bytes`() {
        val file = tempFile()
        file.writeText("obsolete content ".repeat(4096), Charsets.UTF_8)
        val replacement = BackupCodec.encode(ForgeBackup(createdAt = 123))
        val oldSize = file.length()

        documents.write(Uri.fromFile(file), replacement)

        assertTrue(oldSize > file.length())
        assertEquals(replacement.toByteArray(Charsets.UTF_8).size.toLong(), file.length())
        assertEquals(replacement, file.readText(Charsets.UTF_8))
        assertEquals(replacement, documents.read(Uri.fromFile(file)))
    }

    @Test fun `malformed UTF8 is rejected as invalid backup rather than silently replaced`() {
        val file = tempFile()
        file.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))

        val failure = invalidBackup { documents.read(Uri.fromFile(file)) }
        assertEquals(BackupIssue.INVALID_DATA, failure.issue)
        assertTrue(failure.cause is CharacterCodingException)
    }

    @Test fun `document larger than the byte limit is rejected while reading`() {
        val file = tempFile()
        RandomAccessFile(file, "rw").use { it.setLength(BackupCodec.MAX_BYTES.toLong() + 1) }
        assertEquals(BackupCodec.MAX_BYTES.toLong() + 1, file.length())

        val failure = invalidBackup { documents.read(Uri.fromFile(file)) }
        assertEquals(BackupIssue.INVALID_DATA, failure.issue)
    }

    @Test(expected = IOException::class)
    fun `opening missing input file propagates IO failure`() {
        documents.read(Uri.fromFile(File(directory, "does-not-exist.json")))
    }

    @Test(expected = IOException::class)
    fun `opening a directory as an output file propagates IO failure`() {
        documents.write(Uri.fromFile(directory), "{}")
    }

    @Test fun `mid read IO failure propagates unchanged and closes the stream`() {
        val uri = Uri.parse("content://forge-host-failure/read.json")
        val failure = IOException("Simulated read failure")
        var closed = false
        val stream = object : InputStream() {
            override fun read(): Int = throw failure
            override fun close() { closed = true }
        }
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { stream }

        val thrown = ioFailure { documents.read(uri) }
        assertSame(failure, thrown)
        assertTrue(closed)
    }

    @Test fun `mid write IO failure propagates unchanged and closes the stream`() {
        val uri = Uri.parse("content://forge-host-failure/write.json")
        val failure = IOException("Simulated write failure")
        var closed = false
        val stream = object : OutputStream() {
            override fun write(value: Int) { throw failure }
            override fun close() { closed = true }
        }
        shadowOf(context.contentResolver).registerOutputStreamSupplier(uri) { stream }

        val thrown = ioFailure { documents.write(uri, "{}") }
        assertSame(failure, thrown)
        assertTrue(closed)
    }

    private fun tempFile(): File = File.createTempFile("forge-backup-", ".json", directory).also { files += it }

    private fun invalidBackup(operation: () -> Unit): BackupValidationException {
        try {
            operation()
            fail("Expected invalid backup data to be rejected")
        } catch (failure: BackupValidationException) {
            return failure
        }
        error("Unreachable")
    }

    private fun ioFailure(operation: () -> Unit): IOException {
        try {
            operation()
            fail("Expected IO failure to propagate")
        } catch (failure: IOException) {
            return failure
        }
        error("Unreachable")
    }
}
