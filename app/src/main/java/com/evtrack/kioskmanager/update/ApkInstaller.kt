package com.evtrack.kioskmanager.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Outcome of a silent install/uninstall attempt. */
data class InstallOutcome(
    val success: Boolean,
    val status: Int,
    val message: String?,
)

/**
 * Performs SILENT installs/uninstalls of the managed app via [PackageInstaller].
 * This only works when the app holds Device Owner (or profile owner); otherwise
 * the platform would surface a user confirmation dialog.
 *
 * ## The silent-install async bridge
 * `PackageInstaller.Session.commit()` is asynchronous: the result arrives later as
 * a broadcast to the IntentSender we supply. To expose this as a clean `suspend`
 * function we:
 *   1. Create a session and allocate a [CompletableDeferred] keyed by its session id
 *      (stored in [pending], a process-wide map).
 *   2. Commit with a PendingIntent that targets [InstallResultReceiver] by explicit
 *      component, carrying the session id back as an extra.
 *   3. `await()` the deferred. When the receiver fires it calls [completeSession],
 *      which resolves the matching deferred and resumes this coroutine.
 */
class ApkInstaller(private val context: Context) {

    /** Silently install [apk]. Suspends until PackageInstaller reports a result. */
    suspend fun install(apk: File): InstallOutcome {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )

        val sessionId = installer.createSession(params)
        val deferred = CompletableDeferred<InstallOutcome>()
        pending[sessionId] = deferred

        try {
            installer.openSession(sessionId).use { session ->
                // Stream the APK bytes into the session.
                apk.inputStream().use { input ->
                    session.openWrite("apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                session.commit(buildStatusReceiver(sessionId).intentSender)
            }
            Log.i(TAG, "Committed install session $sessionId for ${apk.name}")
            return deferred.await()
        } catch (e: Exception) {
            pending.remove(sessionId)
            Log.e(TAG, "Install session $sessionId failed to commit", e)
            return InstallOutcome(success = false, status = PackageInstaller.STATUS_FAILURE, message = e.message)
        }
    }

    /** Best-effort silent uninstall of [pkg]. Suspends until a result arrives. */
    suspend fun uninstall(pkg: String): InstallOutcome {
        val installer = context.packageManager.packageInstaller
        // Uninstall results are correlated the same way, via a synthetic session id.
        val sessionId = SYNTHETIC_UNINSTALL_ID_BASE + (uninstallCounter++ and 0xFFFF)
        val deferred = CompletableDeferred<InstallOutcome>()
        pending[sessionId] = deferred
        return try {
            installer.uninstall(pkg, buildStatusReceiver(sessionId).intentSender)
            Log.i(TAG, "Requested uninstall of $pkg (session=$sessionId)")
            deferred.await()
        } catch (e: Exception) {
            pending.remove(sessionId)
            Log.e(TAG, "Uninstall of $pkg failed", e)
            InstallOutcome(success = false, status = PackageInstaller.STATUS_FAILURE, message = e.message)
        }
    }

    /**
     * Build the PendingIntent that PackageInstaller broadcasts its result to. It is an
     * explicit intent aimed at [InstallResultReceiver], carrying our session id so the
     * receiver can resolve the right deferred.
     */
    private fun buildStatusReceiver(sessionId: Int): PendingIntent {
        val intent = Intent(context, InstallResultReceiver::class.java).apply {
            action = ACTION_INSTALL_RESULT
            putExtra(InstallResultReceiver.EXTRA_SESSION_ID, sessionId)
        }
        // A distinct request code per session keeps PendingIntents from colliding.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }

    companion object {
        private const val TAG = "ApkInstaller"
        private const val ACTION_INSTALL_RESULT = "com.evtrack.kioskmanager.INSTALL_RESULT"

        // Uninstalls have no PackageInstaller session id, so we mint synthetic ones in a
        // high range that will never collide with real (small, monotonic) session ids.
        private const val SYNTHETIC_UNINSTALL_ID_BASE = 0x0100_0000
        private var uninstallCounter = 0

        /** Process-wide map: session id -> waiting coroutine. */
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<InstallOutcome>>()

        /** Called by [InstallResultReceiver] to resume the coroutine for [sessionId]. */
        fun completeSession(sessionId: Int, status: Int, message: String?) {
            val deferred = pending.remove(sessionId)
            if (deferred == null) {
                Log.w(TAG, "No pending install for session $sessionId (status=$status)")
                return
            }
            deferred.complete(
                InstallOutcome(
                    success = status == PackageInstaller.STATUS_SUCCESS,
                    status = status,
                    message = message,
                )
            )
        }
    }
}
