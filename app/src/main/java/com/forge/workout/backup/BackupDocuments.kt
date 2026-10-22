package com.forge.workout.backup

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException

interface BackupDocuments {
    fun read(uri: Uri): String
    fun write(uri: Uri, contents: String)
}

class LocalBackupDocuments(context: Context) : BackupDocuments {
    private val resolver = context.applicationContext.contentResolver

    override fun read(uri: Uri): String {
        val input = resolver.openInputStream(uri) ?: throw IOException("Document could not be opened")
        return input.use { stream ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while(true) {
                val count = stream.read(buffer)
                if(count < 0) break
                if(bytes.size().toLong() + count > BackupCodec.MAX_BYTES) {
                    throw BackupValidationException(BackupIssue.INVALID_DATA)
                }
                bytes.write(buffer, 0, count)
            }
            try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            } catch(failure: CharacterCodingException) {
                throw BackupValidationException(BackupIssue.INVALID_DATA, cause = failure)
            }
        }
    }

    override fun write(uri: Uri, contents: String) {
        val bytes = contents.toByteArray(Charsets.UTF_8)
        if(bytes.size > BackupCodec.MAX_BYTES) throw BackupValidationException(BackupIssue.INVALID_DATA)
        val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Document could not be created")
        output.use { it.write(bytes); it.flush() }
    }
}
