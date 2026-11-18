package com.forge.workout.updates

import android.util.AtomicFile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate

internal data class PendingApk(
    val release: AvailableUpdate,
    val versionCode: Long,
    val createdAt: Long,
    val sessionId: Int? = null
)

/** Fixed private paths only. Neither backups nor food catalogs are under this directory. */
internal class UpdateFiles(val directory: File) {
    val partial = File(directory, "download.part")
    val apk = File(directory, "verified.apk")
    private val journalFile = File(directory, "pending.json")
    private val journal get() = AtomicFile(journalFile)

    fun prepare() {
        if (Files.isSymbolicLink(directory.toPath())) throw UpdateException(UpdateFailure.STORAGE)
        if ((!directory.isDirectory && !directory.mkdirs()) || directory.listFiles()?.size?.let { it > 64 } == true) {
            throw UpdateException(UpdateFailure.STORAGE)
        }
        val root = directory.canonicalFile
        for (file in listOf(partial, apk, journalFile)) {
            if (file.canonicalFile.parentFile != root) throw UpdateException(UpdateFailure.STORAGE)
        }
    }

    fun read(): PendingApk? {
        prepare()
        val input = try { journal.openRead() } catch (_: FileNotFoundException) { return null }
        try {
            val bytes = input.use { stream ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (result.size() + count > 8192) throw UpdateException(UpdateFailure.RECOVERY)
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            require(json.length() == 9 && json.getInt("format") == 1)
            fun integer(key: String) = (json.get(key) as? Number)?.toString()?.toLongOrNull()
                ?: error("Invalid journal integer")
            val release = AvailableUpdate(json.getString("version"), json.getString("tag"), "", "", LocalDate.ofEpochDay(0),
                json.getString("url"), integer("bytes"), json.getString("sha256"))
            GitHubAssetPolicy.validate(release)
            val code = integer("code").also { require(it > 0) }
            val time = integer("at").also { require(it >= 0) }
            val session = if (json.isNull("session")) null else integer("session").also { require(it in 1..Int.MAX_VALUE) }.toInt()
            return PendingApk(release, code, time, session)
        } catch (failure: Exception) {
            if (failure is UpdateException) throw failure
            throw UpdateException(UpdateFailure.RECOVERY, failure)
        }
    }

    fun write(pending: PendingApk) {
        prepare()
        GitHubAssetPolicy.validate(pending.release)
        require(pending.versionCode > 0 && pending.createdAt >= 0 && (pending.sessionId == null || pending.sessionId > 0))
        val json = JSONObject().put("format", 1).put("version", pending.release.version).put("tag", pending.release.tag)
            .put("url", pending.release.apkUrl).put("bytes", pending.release.apkSize).put("sha256", pending.release.apkSha256)
            .put("code", pending.versionCode).put("at", pending.createdAt).put("session", pending.sessionId ?: JSONObject.NULL)
        val output = try { journal.startWrite() } catch (failure: IOException) { throw UpdateException(UpdateFailure.STORAGE, failure) }
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            journal.finishWrite(output)
        } catch (failure: Exception) {
            journal.failWrite(output)
            throw UpdateException(UpdateFailure.STORAGE, failure)
        }
    }

    fun acceptPartial() {
        prepare()
        if (!partial.isFile || !partial.renameTo(apk)) throw UpdateException(UpdateFailure.STORAGE)
    }

    /** Never follows directory trees or symbolic links outside the dedicated root. */
    fun cleanup(keepVerified: Boolean = false) {
        if (Files.isSymbolicLink(directory.toPath())) throw UpdateException(UpdateFailure.STORAGE)
        if (!directory.exists()) return
        val root = directory.canonicalFile
        directory.listFiles()?.take(64)?.forEach { file ->
            if (keepVerified && file.name in setOf(apk.name, journalFile.name)) return@forEach
            if ((file.isFile && file.canonicalFile.parentFile == root) || Files.isSymbolicLink(file.toPath())) {
                if (!file.delete() && file.exists()) throw UpdateException(UpdateFailure.STORAGE)
            }
        }
    }

    fun retainedBytes(): Long = directory.listFiles()?.take(64)?.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
        ?.sumOf { it.length() } ?: 0
}
