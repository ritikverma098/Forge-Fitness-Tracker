package com.forge.workout.updates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SecureApkDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun streamsSmallChunksOffCallerThreadAndReportsVerifiedBytes() = runBlocking {
        val bytes = ByteArray(100_003) { (it % 251).toByte() }
        val stream = ChunkStream(bytes, chunk = 7)
        val caller = Thread.currentThread()
        lateinit var connection: TestConnection
        val downloader = downloader { url ->
            assertNotSame(caller, Thread.currentThread())
            TestConnection(url, stream = stream).also { connection = it }
        }
        val destination = destination()
        val progress = mutableListOf<Pair<Long, Long>>()
        val result = downloader.download(release(bytes), destination) { read, total -> progress += read to total }
        assertArrayEquals(bytes, destination.readBytes())
        assertEquals(DownloadedApk(bytes.size.toLong(), hash(bytes)), result)
        assertEquals(listOf(0L to bytes.size.toLong(), bytes.size.toLong() to bytes.size.toLong()), progress)
        assertTrue(stream.readCalls > 10_000)
        assertTrue(stream.largestRequested <= 32 * 1024)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertNull(connection.getRequestProperty("Authorization"))
        assertTrue(connection.connectTimeout > 0 && connection.readTimeout > 0)
        assertTrue(connection.disconnected)
        assertTrue(stream.closed)
    }

    @Test fun wrongChecksumRemovesTheEntirePartial() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        val destination = destination()
        val connection = TestConnection(URL(OFFICIAL), stream = ByteArrayInputStream(bytes))
        expect(UpdateFailure.INTEGRITY) {
            downloader { connection }.download(release(bytes).copy(apkSha256 = "0".repeat(64)), destination, ::ignoreProgress)
        }
        assertFalse(destination.exists())
        assertTrue(connection.disconnected)
    }

    @Test fun missingOrMalformedChecksumNeverStartsNetwork() = runBlocking {
        for (digest in listOf(null, "", "f".repeat(63), "sha256:" + "f".repeat(64))) {
            val destination = destination()
            expect(UpdateFailure.MISSING_CHECKSUM) {
                downloader { fail("Unexpected connection"); error("unreachable") }
                    .download(release().copy(apkSha256 = digest), destination, ::ignoreProgress)
            }
            assertFalse(destination.exists())
        }
    }

    @Test fun untrustedOriginalUrlAndInvalidExpectedSizesFailBeforeIo() = runBlocking {
        val invalid = listOf(
            release().copy(apkUrl = OFFICIAL.replace("github.com", "github.com.evil.test")),
            release().copy(apkSize = 0),
            release().copy(apkSize = GitHubAssetPolicy.MAX_APK_BYTES + 1),
            release().copy(apkSize = null),
        )
        for (offer in invalid) expect(UpdateFailure.INVALID_RELEASE) {
            downloader { fail("Unexpected connection"); error("unreachable") }.download(offer, destination(), ::ignoreProgress)
        }
    }

    @Test fun unknownLengthIsAllowedButAdvertisedMismatchIsRejected() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val complete = destination()
        downloader { TestConnection(it, stream = ByteArrayInputStream(bytes), length = -1) }
            .download(release(bytes), complete, ::ignoreProgress)
        assertArrayEquals(bytes, complete.readBytes())
        for (length in listOf(0L, 3L, 5L, GitHubAssetPolicy.MAX_APK_BYTES + 1)) {
            val destination = destination()
            val connection = TestConnection(URL(OFFICIAL), length = length)
            expect(UpdateFailure.INTEGRITY) {
                downloader { connection }.download(release(bytes), destination, ::ignoreProgress)
            }
            assertFalse(connection.openedInput)
            assertFalse(destination.exists())
            assertTrue(connection.disconnected)
        }
    }

    @Test fun emptyTruncatedAndOversizedBodiesAreRemoved() = runBlocking {
        val expected = byteArrayOf(1, 2, 3, 4)
        for (actual in listOf(byteArrayOf(), byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3, 4, 5))) {
            val destination = destination()
            expect(UpdateFailure.INTEGRITY) {
                downloader { TestConnection(it, stream = ByteArrayInputStream(actual)) }
                    .download(release(expected), destination, ::ignoreProgress)
            }
            assertFalse(destination.exists())
        }
    }

    @Test fun httpErrorsAreNotTreatedAsApkBodies() = runBlocking {
        for (status in listOf(204, 206, 403, 404, 429, 500)) {
            val connection = TestConnection(URL(OFFICIAL), status = status)
            expect(UpdateFailure.NETWORK) {
                downloader { connection }.download(release(), destination(), ::ignoreProgress)
            }
            assertFalse(connection.openedInput)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun followsBoundedHttpsGithubCdnRedirectsWithoutSendingCredentials() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val locations = listOf(
            OFFICIAL,
            "https://release-assets.githubusercontent.com/github-production-release-asset/fixture?token=fixture",
            "https://objects.githubusercontent.com/github-production-release-asset/fixture?token=fixture2",
        )
        val connections = mutableListOf<TestConnection>()
        val downloader = downloader { url ->
            val index = connections.size
            assertEquals(locations[index], url.toString())
            TestConnection(url, status = if (index < 2) 302 else 200,
                stream = ByteArrayInputStream(bytes), location = locations.getOrNull(index + 1))
                .also { connections += it }
        }
        val destination = destination()
        downloader.download(release(bytes), destination, ::ignoreProgress)
        assertArrayEquals(bytes, destination.readBytes())
        assertEquals(3, connections.size)
        assertTrue(connections.all { it.disconnected && !it.instanceFollowRedirects })
        assertTrue(connections.all { it.getRequestProperty("Authorization") == null })
    }

    @Test fun rejectsUnexpectedRedirectsBeforeOpeningTheirConnections() = runBlocking {
        for (target in listOf(
            "http://release-assets.githubusercontent.com/apk",
            "https://evil.test/apk",
            "https://release-assets.githubusercontent.com.evil.test/apk",
            "https://someone@release-assets.githubusercontent.com/apk",
            "https://release-assets.githubusercontent.com:8443/apk",
            "https://release-assets.githubusercontent.com/apk#fragment",
            "https://release-assets.githubusercontent.com",
            "not a valid URI",
        )) {
            val connections = AtomicInteger()
            val destination = destination()
            expect(UpdateFailure.INVALID_RELEASE) {
                downloader {
                    connections.incrementAndGet()
                    TestConnection(it, status = 302, location = target)
                }.download(release(), destination, ::ignoreProgress)
            }
            assertEquals(target, 1, connections.get())
            assertFalse(destination.exists())
        }
    }

    @Test fun redirectLoopAndMissingLocationFailClosed() = runBlocking {
        val connections = mutableListOf<TestConnection>()
        expect(UpdateFailure.INVALID_RELEASE) {
            downloader { TestConnection(it, status = 307, location = OFFICIAL).also(connections::add) }
                .download(release(), destination(), ::ignoreProgress)
        }
        assertEquals(6, connections.size)
        assertTrue(connections.all { it.disconnected })
        expect(UpdateFailure.INVALID_RELEASE) {
            downloader { TestConnection(it, status = 301) }.download(release(), destination(), ::ignoreProgress)
        }
        assertTrue(connections.all { it.disconnected })
    }

    @Test fun compressedResponseIsRejectedRatherThanUsingAmbiguousByteCounts() = runBlocking {
        val connection = TestConnection(URL(OFFICIAL), encoding = "gzip")
        expect(UpdateFailure.INTEGRITY) {
            downloader { connection }.download(release(), destination(), ::ignoreProgress)
        }
        assertFalse(connection.openedInput)
        assertTrue(connection.disconnected)
    }

    @Test fun offlineAndTimeoutFailuresAreTypedWithoutUrlBearingCauses() = runBlocking {
        for (failure in listOf(UnknownHostException("host"), SocketTimeoutException("signed URL must not leak"))) {
            val destination = destination().apply { writeText("abandoned partial") }
            val exception = expect(UpdateFailure.NETWORK) {
                downloader { throw failure }.download(release(), destination, ::ignoreProgress)
            }
            assertNull(exception.cause)
            assertFalse(destination.exists())
        }
    }

    @Test fun interruptedReadRemovesAlreadyWrittenBytes() = runBlocking {
        var reads = 0
        val connection = TestConnection(URL(OFFICIAL), stream = object : InputStream() {
            override fun read(): Int = error("Use bounded reads")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (++reads > 1) throw IOException("connection interrupted")
                buffer[offset] = 1
                return 1
            }
        })
        val destination = destination()
        expect(UpdateFailure.NETWORK) {
            downloader { connection }.download(release(), destination, ::ignoreProgress)
        }
        assertEquals(2, reads)
        assertFalse(destination.exists())
        assertTrue(connection.disconnected)
    }

    @Test fun insufficientStorageIsReportedBeforeNetworkOrWriting() = runBlocking {
        val destination = destination()
        expect(UpdateFailure.STORAGE) {
            SecureApkDownloader(openConnection = { fail("Unexpected network"); error("unreachable") }, availableSpace = { 0 })
                .download(release(), destination, ::ignoreProgress)
        }
        assertFalse(destination.exists())
    }

    @Test fun storageExhaustionDuringTransferDeletesPartialFile() = runBlocking {
        val bytes = ByteArray(600 * 1024) { 4 }
        val spaceChecks = AtomicInteger()
        val destination = destination()
        val downloader = SecureApkDownloader(
            openConnection = { TestConnection(it, stream = ByteArrayInputStream(bytes)) },
            availableSpace = { if (spaceChecks.incrementAndGet() == 1) Long.MAX_VALUE else 0 },
        )
        expect(UpdateFailure.STORAGE) { downloader.download(release(bytes), destination, ::ignoreProgress) }
        assertEquals(2, spaceChecks.get())
        assertFalse(destination.exists())
    }

    @Test fun fileCreationFailureIsStorageRatherThanNetwork() = runBlocking {
        val destination = File(temporary.newFolder(), "download.part").apply { mkdir() }
        expect(UpdateFailure.STORAGE) {
            downloader { TestConnection(it, stream = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))) }
                .download(release(), destination, ::ignoreProgress)
        }
        assertFalse(destination.exists())
    }

    @Test fun cancellationDisconnectsBlockedReadAndDeletesPartialBeforeReturning() = runBlocking {
        val entered = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val connection = TestConnection(URL(OFFICIAL), stream = object : InputStream() {
            override fun read(): Int = error("Use bounded reads")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                if (!disconnected.await(5, TimeUnit.SECONDS)) throw IOException("Cancellation did not disconnect")
                throw IOException("Closed")
            }
        }, onDisconnect = { disconnected.countDown() })
        val destination = destination()
        val transfer = async { downloader { connection }.download(release(), destination, ::ignoreProgress) }
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
        assertTrue(destination.exists())
        withTimeout(3_000) { transfer.cancelAndJoin() }
        assertTrue(transfer.isCancelled)
        assertTrue(connection.disconnected)
        assertFalse(destination.exists())
    }

    @Test fun progressIsThrottledAndTotalTransferTimeIsBounded() = runBlocking {
        val bytes = ByteArray(10 * 1024)
        val now = AtomicLong()
        val stream = ChunkStream(bytes, 1024) { now.addAndGet(100_000_000L) }
        val updates = mutableListOf<Long>()
        SecureApkDownloader({ TestConnection(it, stream = stream) }, { Long.MAX_VALUE }, now::get)
            .download(release(bytes), destination()) { read, _ -> updates += read }
        assertEquals(0L, updates.first())
        assertEquals(bytes.size.toLong(), updates.last())
        assertTrue(updates.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(updates.size < stream.readCalls)

        now.set(0)
        val expired = destination()
        val expiredStream = ChunkStream(bytes, 1024) { now.set(601_000_000_000L) }
        expect(UpdateFailure.NETWORK) {
            SecureApkDownloader({ TestConnection(it, stream = expiredStream) }, { Long.MAX_VALUE }, now::get)
                .download(release(bytes), expired, ::ignoreProgress)
        }
        assertFalse(expired.exists())
    }

    private fun destination() = File(temporary.newFolder(), "download.part")
    private fun downloader(factory: (URL) -> HttpURLConnection) =
        SecureApkDownloader(factory, availableSpace = { Long.MAX_VALUE }, nanoTime = { 0L })

    private fun release(bytes: ByteArray = byteArrayOf(1, 2, 3, 4)) = AvailableUpdate(
        "1.4.2", "v1.4.2", "Forge", "", LocalDate.of(2026, 11, 16), OFFICIAL,
        bytes.size.toLong(), hash(bytes),
    )

    private suspend fun expect(issue: UpdateFailure, operation: suspend () -> Unit): UpdateException {
        try { operation() } catch (failure: UpdateException) {
            assertEquals(issue, failure.issue)
            return failure
        }
        throw AssertionError("Expected $issue")
    }

    private class ChunkStream(bytes: ByteArray, private val chunk: Int, private val onRead: () -> Unit = {}) : ByteArrayInputStream(bytes) {
        var readCalls = 0
        var largestRequested = 0
        var closed = false
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readCalls++
            largestRequested = maxOf(largestRequested, length)
            onRead()
            return super.read(buffer, offset, minOf(length, chunk))
        }
        override fun close() { closed = true; super.close() }
    }

    private class TestConnection(
        url: URL,
        private val status: Int = 200,
        private val stream: InputStream = ByteArrayInputStream(byteArrayOf()),
        private val length: Long = -1,
        private val location: String? = null,
        private val encoding: String? = null,
        private val onDisconnect: () -> Unit = {},
    ) : HttpURLConnection(url) {
        @Volatile var disconnected = false
        var openedInput = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun getInputStream(): InputStream { openedInput = true; return stream }
        override fun getHeaderField(name: String?): String? = when (name) {
            "Location" -> location
            "Content-Encoding" -> encoding
            else -> null
        }
        override fun connect() = Unit
        override fun disconnect() { disconnected = true; onDisconnect() }
        override fun usingProxy() = false
    }

    private companion object {
        const val OFFICIAL = "https://github.com/ritikverma098/Forge-Fitness-Tracker/releases/download/v1.4.2/Forge-1.4.2.apk"
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun ignoreProgress(read: Long, total: Long) = Unit
    }
}
