package com.forge.workout.updates

import java.io.File

internal interface UpdateInstaller {
    fun canRequestInstalls(): Boolean
    fun hasSession(sessionId: Int): Boolean
    suspend fun stage(apk: File, onCreated: suspend (Int) -> Unit): Int
    fun commit(sessionId: Int)
    fun abandon(sessionId: Int)
}
