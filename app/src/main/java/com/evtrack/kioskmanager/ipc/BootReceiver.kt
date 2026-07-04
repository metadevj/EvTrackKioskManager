package com.evtrack.kioskmanager.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.evtrack.kioskmanager.cdn.CdnClient
import com.evtrack.kioskmanager.lockdown.LockdownManager

/**
 * Runs on boot to kick [InstallService] when there's work:
 *  - a [PendingInstall] record survived (resume an interrupted pinned install), or
 *  - the managed app is absent (fresh device → bootstrap it from the CDN, issue #31,
 *    since FrontDesk isn't installed yet to trigger it).
 * The service itself decides which of the two applies, and no-ops otherwise.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        val installed = isInstalled(context, CdnClient.MANAGED_PACKAGE)
        val hasPending = PendingInstall.load(context) != null
        if (hasPending || !installed) {
            // Install / bootstrap first; InstallService applies the lockdown afterwards.
            Log.i(TAG, "Boot: hasPending=$hasPending installed=$installed → starting InstallService")
            InstallService.start(context)
        } else {
            // Managed app already present: just re-apply the kiosk lockdown and bring it up.
            Log.i(TAG, "Boot: managed app present → applying kiosk lockdown")
            LockdownManager(context).applyAndLaunch()
        }
    }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
