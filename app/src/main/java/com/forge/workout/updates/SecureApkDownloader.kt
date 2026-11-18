package com.forge.workout.updates

import android.annotation.SuppressLint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** A single bounded transfer. The coordinator owns operation identity and the private destination. */
internal class SecureApkDownloader(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val availableSpace: (File) -> Long = ::currentlyFreeSpace,
    private val nanoTime: () -> Long = System::nanoTime,
) : ApkDownloader {
    override suspend fun download(
        release: AvailableUpdate,
        destination: File,
        progress: (Long, Long) -> Unit,
    ): DownloadedApk = coroutineScope {
        val active = AtomicReference<HttpURLConnection?>()
        val worker = async(Dispatchers.IO) { transfer(release, destination, progress, active) }
        var delivered = false
        try {
            worker.await().also { delivered = true }
        } finally {
            if (!delivered) withContext(NonCancellable + Dispatchers.IO) {
                // Unblock an in-flight read before joining. Cleanup never races a still-writing worker.
                disconnect(active.getAndSet(null))
                worker.join()
                destination.delete()
            }
        }
    }

    private suspend fun transfer(
        release: AvailableUpdate,
        destination: File,
        progress: (Long, Long) -> Unit,
        active: AtomicReference<HttpURLConnection?>,
    ): DownloadedApk {
        var completed = false
        try {
            GitHubAssetPolicy.validate(release)
            val expectedBytes = checkNotNull(release.apkSize)
            val expectedHash = checkNotNull(release.apkSha256)
            val directory = destination.absoluteFile.parentFile ?: throw UpdateException(UpdateFailure.STORAGE)
            storage {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Update directory unavailable")
            }
            requireSpace(directory, expectedBytes + STORAGE_RESERVE)
            currentCoroutineContext().ensureActive()
            progress(0L, expectedBytes)
            val started = nanoTime()
            val connection = connection(release.apkUrl, active, started)
            val advertised = connection.contentLengthLong
            if (advertised >= 0 && advertised != expectedBytes) throw UpdateException(UpdateFailure.INTEGRITY)
            if (!connection.getHeaderField("Content-Encoding").isNullOrBlank() &&
                !connection.getHeaderField("Content-Encoding").equals("identity", ignoreCase = true)) {
                throw UpdateException(UpdateFailure.INTEGRITY)
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var spaceCheckAt = SPACE_CHECK_BYTES
            var lastProgress = started
            connection.inputStream.use { input ->
                StorageOutput(storage { FileOutputStream(destination, false) }).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        checkActive(started)
                        val count = input.read(buffer)
                        checkActive(started)
                        if (count == -1) break
                        if (count == 0) continue
                        if (received + count > expectedBytes || received + count > GitHubAssetPolicy.MAX_APK_BYTES) {
                            throw UpdateException(UpdateFailure.INTEGRITY)
                        }
                        if (received + count >= spaceCheckAt) {
                            requireSpace(directory, count + STORAGE_RESERVE)
                            spaceCheckAt = received + count + SPACE_CHECK_BYTES
                        }
                        output.write(buffer, count)
                        digest.update(buffer, 0, count)
                        received += count
                        val now = nanoTime()
                        if (now - lastProgress >= PROGRESS_INTERVAL) {
                            progress(received, expectedBytes)
                            lastProgress = now
                        }
                    }
                    if (received != expectedBytes || received == 0L) throw UpdateException(UpdateFailure.INTEGRITY)
                    output.sync()
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!MessageDigest.isEqual(actualHash.toByteArray(Charsets.US_ASCII), expectedHash.toByteArray(Charsets.US_ASCII))) {
                throw UpdateException(UpdateFailure.INTEGRITY)
            }
            currentCoroutineContext().ensureActive()
            progress(received, expectedBytes)
            completed = true
            return DownloadedApk(received, actualHash)
        } catch (failure: UpdateException) {
            currentCoroutineContext().ensureActive()
            throw failure
        } catch (_: IOException) {
            // Disconnecting a cancelled transfer also causes IOException; cancellation must win.
            currentCoroutineContext().ensureActive()
            // Do not retain URL-bearing network exception messages (CDN queries contain signed tokens).
            throw UpdateException(UpdateFailure.NETWORK)
        } catch (_: SecurityException) {
            currentCoroutineContext().ensureActive()
            throw UpdateException(UpdateFailure.NETWORK)
        } finally {
            disconnect(active.getAndSet(null))
            if (!completed) destination.delete()
        }
    }

    private suspend fun connection(original: String, active: AtomicReference<HttpURLConnection?>, started: Long): HttpURLConnection {
        var current = URI(original)
        var followed = 0
        while (true) {
            checkActive(started)
            val connection = openConnection(current.toURL())
            active.set(connection)
            checkActive(started)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "Forge-Android")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream")
            val status = connection.responseCode
            checkActive(started)
            if (status == HttpURLConnection.HTTP_OK) return connection
            if (status !in REDIRECTS) throw UpdateException(UpdateFailure.NETWORK)
            if (followed++ >= MAX_REDIRECTS) throw UpdateException(UpdateFailure.INVALID_RELEASE)
            val location = connection.getHeaderField("Location") ?: throw UpdateException(UpdateFailure.INVALID_RELEASE)
            val next = try { current.resolve(URI(location)) } catch (_: IllegalArgumentException) {
                throw UpdateException(UpdateFailure.INVALID_RELEASE)
            } catch (_: java.net.URISyntaxException) {
                throw UpdateException(UpdateFailure.INVALID_RELEASE)
            }
            if (next.scheme != "https" || next.rawAuthority?.lowercase() !in REDIRECT_HOSTS ||
                next.rawFragment != null || next.rawPath.isNullOrEmpty()) throw UpdateException(UpdateFailure.INVALID_RELEASE)
            disconnect(active.getAndSet(null))
            current = next
        }
    }

    private suspend fun checkActive(started: Long) {
        currentCoroutineContext().ensureActive()
        if (nanoTime() - started >= MAX_TRANSFER_TIME) throw UpdateException(UpdateFailure.NETWORK)
    }

    private fun requireSpace(directory: File, bytes: Long) {
        if (storage { availableSpace(directory) } < bytes) throw UpdateException(UpdateFailure.STORAGE)
    }

    private class StorageOutput(private val output: FileOutputStream) : Closeable {
        fun write(buffer: ByteArray, size: Int) = storage { output.write(buffer, 0, size) }
        fun sync() = storage { output.fd.sync() }
        override fun close() = storage { output.close() }
    }

    private companion object {
        // Optional updates use existing free space rather than reclaiming other applications' caches.
        @SuppressLint("UsableSpace")
        fun currentlyFreeSpace(directory: File): Long = directory.usableSpace
        const val BUFFER_BYTES = 32 * 1024
        const val SPACE_CHECK_BYTES = 256L * 1024
        const val STORAGE_RESERVE = 1024L * 1024
        const val PROGRESS_INTERVAL = 200_000_000L
        const val MAX_TRANSFER_TIME = 10 * 60 * 1_000_000_000L
        const val MAX_REDIRECTS = 5
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        val REDIRECT_HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

        fun disconnect(connection: HttpURLConnection?) { runCatching { connection?.disconnect() } }
        inline fun <T> storage(block: () -> T): T = try { block() }
            catch (failure: IOException) { throw UpdateException(UpdateFailure.STORAGE, failure) }
            catch (failure: SecurityException) { throw UpdateException(UpdateFailure.STORAGE, failure) }
    }
}
