package com.evtrack.kioskmanager.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.evtrack.kioskmanager.cdn.CdnClient
import com.evtrack.kioskmanager.cdn.ReleaseMeta
import java.io.File

/** Result of an update / rollback attempt, surfaced to the UI. */
data class UpdateResult(
    val success: Boolean,
    val message: String,
    val rolledBack: Boolean = false,
)

/**
 * Orchestrates the managed app's lifecycle: discover -> download -> verify ->
 * snapshot last-known-good -> install, with rollback on failure.
 *
 * APK working set lives under `filesDir/apks/`:
 *   - current.apk    : the APK we most recently installed
 *   - last_good.apk  : snapshot of the previously-installed APK, for rollback
 *
 * v1 is entirely MANUAL (driven by buttons in MainActivity). The server-push and
 * watchdog seams below are deliberately left as stubs — see the TODOs.
 */
class UpdateManager(
    private val context: Context,
    private val cdn: CdnClient = CdnClient(),
) {
    private val installer = ApkInstaller(context)
    private val downloader = ApkDownloader()

    private val apkDir: File by lazy { File(context.filesDir, "apks").apply { mkdirs() } }
    private val currentApk get() = File(apkDir, "current.apk")
    private val lastGoodApk get() = File(apkDir, "last_good.apk")

    /** Package name of the managed kiosk app. */
    val managedPackage: String = CdnClient.MANAGED_PACKAGE

    /** Returns "versionName (versionCode)" for [pkg], or null if not installed. */
    fun installedVersion(pkg: String = managedPackage): String? {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(pkg, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            }
            "${info.versionName} ($code)"
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** Discover the latest release for [variant] ("latest" | "beta"). */
    suspend fun checkForUpdate(variant: String): ReleaseMeta? = cdn.fetchLatest(variant)

    /**
     * BOOTSTRAP first-install (issue #31). On a fresh OS image the managed app isn't
     * installed yet, so there is no FrontDesk to send a pinned trigger. The Manager
     * ships with the OS and pulls the [variant] ("latest" by default) from the CDN
     * itself. Reuses the same download → verify → install flow.
     */
    suspend fun bootstrapLatest(variant: String = DEFAULT_BOOTSTRAP_VARIANT): UpdateResult {
        val meta = cdn.fetchLatest(variant)
            ?: return UpdateResult(false, "Bootstrap: CDN pointer unavailable for '$variant'")
        Log.i(TAG, "Bootstrap install of ${meta.versionBuild} ($variant); managed app not installed")
        return updateTo(meta)
    }

    /** True if the managed app is currently installed. */
    fun isManagedInstalled(): Boolean = installedVersion() != null

    /**
     * Install an EXACT pinned build — the FrontDesk-triggered path (issue #30). The
     * server pins {version, build}; we resolve the deterministic CDN artifact and run
     * the same download → verify → snapshot → install → rollback-on-failure flow.
     * If that exact build is already installed, [updateTo] still runs (PackageInstaller
     * treats a reinstall of the same build as a benign no-op).
     */
    suspend fun updateToPinned(version: String, build: String): UpdateResult {
        val meta = cdn.fetchPinned(version, build)
        Log.i(TAG, "Pinned install requested: ${meta.versionBuild} (installed=${installedVersion()})")
        return updateTo(meta)
    }

    /**
     * Download, verify, snapshot last-known-good, then install [meta].
     * On install failure, automatically attempts [rollback].
     */
    suspend fun updateTo(meta: ReleaseMeta): UpdateResult {
        // 1. Download + verify SHA-256 into current.apk.
        val dl = downloader.download(meta.downloadUrl, currentApk, meta.sha256)
        if (dl.isFailure) {
            return UpdateResult(false, "Download/verify failed: ${dl.exceptionOrNull()?.message}")
        }

        // 2. BEFORE installing, snapshot the currently-installed APK as last-known-good.
        //    (Only if the managed app is actually installed and its base.apk is readable.)
        snapshotInstalledAsLastGood()

        // 3. Install.
        val outcome = installer.install(currentApk)
        if (outcome.success) {
            return UpdateResult(true, "Installed ${meta.versionBuild}")
        }

        // 4. Install failed — try to roll back to last-known-good.
        Log.w(TAG, "Install of ${meta.versionBuild} failed (status=${outcome.status}); attempting rollback")
        val rolledBack = rollback()
        return UpdateResult(
            success = false,
            message = "Install failed: ${outcome.message}" +
                if (rolledBack) " — rolled back to last known good." else " — rollback unavailable.",
            rolledBack = rolledBack,
        )
    }

    /** Reinstall last_good.apk if we have one. Returns true on success. */
    suspend fun rollback(): Boolean {
        if (!lastGoodApk.exists()) {
            Log.w(TAG, "No last_good.apk to roll back to")
            return false
        }
        val outcome = installer.install(lastGoodApk)
        Log.i(TAG, "Rollback install outcome: success=${outcome.success} status=${outcome.status}")
        return outcome.success
    }

    /**
     * Copy the currently-installed managed app's base APK to last_good.apk, so we can
     * reinstall it if a new install misbehaves. Best-effort: silently no-ops if the
     * app isn't installed or its source dir isn't readable.
     */
    private fun snapshotInstalledAsLastGood() {
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(managedPackage, 0)
            val baseApk = File(appInfo.sourceDir)
            if (baseApk.exists() && baseApk.canRead()) {
                baseApk.copyTo(lastGoodApk, overwrite = true)
                Log.i(TAG, "Snapshotted last-known-good from ${baseApk.path} (${lastGoodApk.length()} bytes)")
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.i(TAG, "Managed app not installed yet — no last-known-good snapshot")
        } catch (e: Exception) {
            Log.w(TAG, "Could not snapshot last-known-good", e)
        }
    }

    // ---------------------------------------------------------------------------
    // PHASE 2 SEAMS — intentionally stubbed for v1. Do NOT implement server push here.
    // ---------------------------------------------------------------------------

    /**
     * TODO(#26): Server-push trigger. Phase 2 will let the backend push an update
     * command (FCM / long-poll / MQTT — TBD) which lands here and drives the same
     * checkForUpdate()/updateTo() flow the UI buttons use today. Keep this the single
     * entry point so the manual and pushed paths converge.
     */
    fun onServerPushTrigger() {
        // TODO(#26): parse push payload -> resolve variant -> checkForUpdate -> updateTo.
        Log.i(TAG, "onServerPushTrigger() called — not implemented in v1 (see issue #26)")
    }

    /**
     * TODO(#24): Watchdog / crash-loop recovery. A watchdog will call this
     * periodically (and/or on managed-app crash signals) to detect a crash-looping
     * kiosk app and auto-recover by reinstalling last-known-good via rollback().
     */
    fun checkManagedAppHealth() {
        // TODO(#24): read crash/heartbeat signals -> if crash-looping, call rollback().
        Log.i(TAG, "checkManagedAppHealth() called — not implemented in v1 (see issue #24)")
    }

    companion object {
        private const val TAG = "UpdateManager"

        /** CDN variant the Manager bootstraps on a fresh device. */
        const val DEFAULT_BOOTSTRAP_VARIANT = "latest"
    }
}
