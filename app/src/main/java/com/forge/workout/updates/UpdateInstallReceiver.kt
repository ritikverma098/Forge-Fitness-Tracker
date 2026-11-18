package com.forge.workout.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import com.forge.workout.ForgeApplication

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = readResult(context.packageName, intent) ?: return
        val application = context.applicationContext as? ForgeApplication ?: return
        val pending = goAsync()
        try {
            application.apkUpdates.onInstallerResult(result.sessionId, result.status, result.confirmation)
                .invokeOnCompletion { pending.finish() }
        } catch (_: Exception) { pending.finish() }
    }

    internal data class Result(val sessionId: Int, val status: Int, val confirmation: Intent?)

    internal companion object {
        const val ACTION_RESULT = "com.forge.workout.action.UPDATE_INSTALL_RESULT"
        const val EXTRA_OWNED_SESSION = "com.forge.workout.extra.OWNED_UPDATE_SESSION"

        fun sessionUri(packageName: String, id: Int): Uri = Uri.Builder()
            .scheme("forge-update").authority(packageName).appendPath(id.toString()).build()

        @Suppress("DEPRECATION")
        fun readResult(packageName: String, intent: Intent): Result? = try {
            val id = intent.getIntExtra(EXTRA_OWNED_SESSION, -1)
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
            if (intent.action != ACTION_RESULT || id <= 0 || status == Int.MIN_VALUE ||
                intent.data != sessionUri(packageName, id) ||
                intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, id) != id) null
            else {
                val confirmation = if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) null
                else if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                Result(id, status, confirmation)
            }
        } catch (_: RuntimeException) { null }
    }
}
