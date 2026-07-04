package com.evtrack.kioskmanager.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives the asynchronous result of a [PackageInstaller] session commit
 * (install or uninstall). Declared `exported="false"` in the manifest — the
 * IntentSender we hand to `commit()` targets this class explicitly by component,
 * so it never needs to be reachable by other apps.
 *
 * It simply forwards the outcome to [ApkInstaller], which parks a
 * CompletableDeferred keyed by session id and resumes the waiting coroutine.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)

        Log.i(TAG, "Install result: session=$sessionId status=$status message=$message")

        // As Device Owner we should never get STATUS_PENDING_USER_ACTION, but handle
        // it defensively: fire the confirmation intent so we don't silently hang.
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Log.w(TAG, "Unexpected STATUS_PENDING_USER_ACTION as Device Owner — launching confirm UI")
            @Suppress("DEPRECATION")
            val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmIntent)
            }
            // Do not complete the deferred yet; a subsequent broadcast will carry the
            // final status. (Still completed below as failure if we cannot proceed.)
            return
        }

        ApkInstaller.completeSession(sessionId, status, message)
    }

    companion object {
        private const val TAG = "InstallResultReceiver"

        /** Our own session-id extra, echoed back to correlate the result. */
        const val EXTRA_SESSION_ID = "com.evtrack.kioskmanager.SESSION_ID"
    }
}
