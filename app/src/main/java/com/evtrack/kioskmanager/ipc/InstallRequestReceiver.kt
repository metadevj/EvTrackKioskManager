package com.evtrack.kioskmanager.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.evtrack.kioskmanager.cdn.CdnClient

/**
 * Entry point for FrontDesk → Manager install requests. This is how the Manager is
 * "notified": FrontDesk (which is paired with the server) sends an explicit broadcast
 * with a PINNED {version, build}; the OS instantiates this receiver on delivery.
 *
 * Secured by the signature-level permission
 * `com.evtrack.kioskmanager.permission.INSTALL_REQUEST` (declared on this receiver in
 * the manifest), so ONLY apps signed with the shared evtrack-release key — i.e.
 * FrontDesk — can fire it. No socket, no port, no polling.
 *
 * We do minimal work here (a receiver has a short lifetime): validate, persist a
 * durable [PendingInstall], and hand off to [InstallService] which performs the
 * download+install in the foreground.
 */
class InstallRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL) return

        val target = intent.getStringExtra(EXTRA_TARGET)?.takeIf { it.isNotBlank() }
            ?: CdnClient.MANAGED_PACKAGE
        val version = intent.getStringExtra(EXTRA_VERSION)?.trim().orEmpty()
        val build = intent.getStringExtra(EXTRA_BUILD)?.trim().orEmpty()
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)?.trim().orEmpty()

        // Pinned install requires an explicit version + build.
        if (version.isEmpty() || build.isEmpty()) {
            Log.w(TAG, "Ignoring install request with missing version/build (req=$requestId)")
            return
        }
        // We only manage the one kiosk app.
        if (target != CdnClient.MANAGED_PACKAGE) {
            Log.w(TAG, "Ignoring install request for unmanaged package '$target'")
            return
        }

        Log.i(TAG, "Install request: $target -> $version.$build (req=$requestId)")
        PendingInstall.save(context, PendingInstall(target, version, build, requestId))
        InstallService.start(context)
    }

    companion object {
        private const val TAG = "InstallRequestReceiver"

        /** The action FrontDesk broadcasts (explicitly, via setPackage). */
        const val ACTION_INSTALL = "com.evtrack.kioskmanager.action.INSTALL"

        /** Signature-level permission guarding [ACTION_INSTALL]. FrontDesk holds it (same key). */
        const val PERMISSION_INSTALL_REQUEST = "com.evtrack.kioskmanager.permission.INSTALL_REQUEST"

        const val EXTRA_TARGET = "target"
        const val EXTRA_VERSION = "version"
        const val EXTRA_BUILD = "build"
        const val EXTRA_REQUEST_ID = "requestId"
    }
}
