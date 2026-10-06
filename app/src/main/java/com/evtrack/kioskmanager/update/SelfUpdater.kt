package com.evtrack.kioskmanager.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.evtrack.kioskmanager.cdn.arcs.ArcsCatalogueClient
import com.evtrack.kioskmanager.cdn.arcs.ArcsCredentials
import com.evtrack.kioskmanager.cdn.arcs.ArcsException
import com.evtrack.kioskmanager.cdn.CdnClient
import java.io.File

/** A Manager build published on ARCS, newer than the one running. */
data class SelfUpdate(
    val version: String,
    /** Channel it came from, in this app's names: "latest" (Main) or "beta". */
    val channel: String,
    val sha256: String?,
    val sizeBytes: Long,
    val downloadUrl: String,
)

/**
 * Updates the Kiosk Manager itself from ARCS.
 *
 * Nothing on an AOSP kiosk can do this for us: there is no Play Store and no GMS, so the system has
 * no installer of record to defer to (ours is `null` - we were baked into the system image). As
 * Device Owner the Manager can commit a [android.content.pm.PackageInstaller] session for its own
 * package silently, so it updates itself or it is reflashed.
 *
 * **Manual only.** This runs when someone asks it to; there is no scheduled or boot-time check, by
 * decision. The contract in docs/ARCS-RELEASES.md describes a daily/boot cadence - deliberately not
 * implemented.
 *
 * The safety rules from that contract still apply, because a bad self-update bricks the device's
 * management:
 *  - Main (STABLE) or Beta, chosen by whoever runs it. Main is what a kiosk should normally carry;
 *    Beta is for bench devices.
 *  - Upgrade only. Never install an older or equal version, even if STABLE is moved backwards.
 *  - The download must match the catalogue's SHA-256, and the APK must be signed by the SAME
 *    certificate as the running Manager. Either check failing deletes the file and aborts: a
 *    differently-signed APK cannot replace us anyway, and silently failing at install time would
 *    leave the file lying around.
 */
class SelfUpdater(
    private val context: Context,
    private val arcs: ArcsCatalogueClient = ArcsCatalogueClient(),
    private val downloader: ApkDownloader = ApkDownloader(),
    private val installer: ApkInstaller = ApkInstaller(context),
) {

    /** Version name of the running Manager, e.g. "1.2.5". */
    fun installedVersion(): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /**
     * The newest Manager build on [channel] if it is newer than the running one, else `null`.
     *
     * [channel] uses this app's names: "latest" for Main/stable, "beta" for Beta - the same words the
     * FrontDesk buttons use, mapped onto ARCS's channel names by [CdnClient.arcsChannelName].
     *
     * Still upgrade-only in both directions: picking Main while a NEWER Beta is installed reports "no
     * update" rather than rolling the Manager backwards. Going back deliberately means installing the
     * APK by hand.
     *
     * @throws ArcsException when ARCS cannot be reached or refuses the licence - the caller shows it.
     */
    suspend fun check(channel: String = DEFAULT_CHANNEL): SelfUpdate? {
        if (!ArcsCredentials.isConfigured()) {
            throw ArcsException("No ARCS licence configured in this build.")
        }
        val arcsChannel = CdnClient.arcsChannelName(channel)
        val published = arcs.channels(ArcsCredentials.MANAGER_PRODUCT)[arcsChannel]
            ?: throw ArcsException(
                "ARCS has no $arcsChannel channel for ${ArcsCredentials.MANAGER_PRODUCT}."
            )
        val file = published.files.firstOrNull { it.role == "distribution" }
            ?: published.files.firstOrNull()
            ?: throw ArcsException("The $arcsChannel Manager release carries no file.")

        if (!isNewer(published.version, installedVersion())) {
            Log.i(TAG, "Manager ${published.version} on $arcsChannel is not newer than ${installedVersion()}")
            return null
        }
        return SelfUpdate(published.version, channel, file.sha256, file.size, file.url)
    }

    /** The newest Manager version published on [channel], whether or not it is newer than ours. */
    suspend fun published(channel: String): String? = try {
        arcs.channels(ArcsCredentials.MANAGER_PRODUCT)[CdnClient.arcsChannelName(channel)]?.version
    } catch (e: ArcsException) {
        Log.w(TAG, "Could not read the Manager $channel channel: ${e.message}")
        null
    }

    /**
     * Download, verify and install [update], replacing this app.
     *
     * On success the process is killed by the platform part-way through: everything after the commit
     * is best-effort, and re-arming happens in [com.evtrack.kioskmanager.ipc.PackageReplacedReceiver].
     */
    suspend fun apply(update: SelfUpdate, onProgress: ((Long, Long) -> Unit)? = null): UpdateResult {
        val dir = File(context.filesDir, "apks").apply { mkdirs() }
        val apk = File(dir, "manager-${update.version}.apk")

        val downloaded = downloader.download(update.downloadUrl, apk, update.sha256, onProgress)
        downloaded.exceptionOrNull()?.let {
            return UpdateResult(false, "Download failed: ${it.message}")
        }

        if (!isSameSignerAsUs(apk)) {
            apk.delete()
            return UpdateResult(
                false,
                "Refused: ${update.version} is signed by a different certificate than the running Manager.",
            )
        }

        Log.i(TAG, "Installing Manager ${update.version} over ${installedVersion()}")
        val outcome = installer.install(apk)
        return if (outcome.success) {
            UpdateResult(true, "Updated the Manager to ${update.version} (${update.channel}).")
        } else {
            apk.delete()
            UpdateResult(false, "Install failed: ${outcome.message ?: "status ${outcome.status}"}")
        }
    }

    /**
     * True when [apk] is signed by exactly the certificates that signed the running Manager.
     *
     * Compared as a SET of certificate hashes, so signer order is irrelevant. A mismatch means the
     * platform would reject the replace anyway; checking first turns that into a clear message.
     */
    private fun isSameSignerAsUs(apk: File): Boolean = try {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val ours = pm.getPackageInfo(context.packageName, flags).signingInfo?.apkContentsSigners
        val theirs = pm.getPackageArchiveInfo(apk.path, flags)?.signingInfo?.apkContentsSigners
        if (ours.isNullOrEmpty() || theirs.isNullOrEmpty()) {
            false
        } else {
            ours.map { it.toByteArray().contentHashCode() }.toSet() ==
                theirs.map { it.toByteArray().contentHashCode() }.toSet()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Could not compare signing certificates", e)
        false
    }

    companion object {
        private const val TAG = "SelfUpdater"

        /** Main/stable, as this app names it elsewhere. */
        const val DEFAULT_CHANNEL = "latest"

        /**
         * Dotted versions compared segment by segment as NUMBERS, so 1.10.0 > 1.9.9 - which string
         * comparison gets wrong. A shorter version is padded with zeros (1.2 == 1.2.0). Any
         * non-numeric segment makes the comparison refuse to call it newer, rather than guess.
         */
        internal fun isNewer(candidate: String, installed: String): Boolean {
            val a = candidate.trim().split('.')
            val b = installed.trim().split('.')
            if (a.isEmpty() || a.any { it.toIntOrNull() == null } || b.any { it.toIntOrNull() == null }) {
                return false
            }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrNull(i)?.toIntOrNull() ?: 0
                val y = b.getOrNull(i)?.toIntOrNull() ?: 0
                if (x != y) return x > y
            }
            return false
        }
    }
}
