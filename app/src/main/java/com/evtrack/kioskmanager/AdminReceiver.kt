package com.evtrack.kioskmanager

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.evtrack.kioskmanager.ipc.InstallService

/**
 * Device admin receiver for the Kiosk Manager. Holding Device Owner (granted via
 * `adb shell dpm set-device-owner com.evtrack.kioskmanager/.AdminReceiver`) is what
 * allows the manager to silently install/uninstall the managed kiosk app.
 */
class AdminReceiver : DeviceAdminReceiver() {

    /**
     * Fired when this app becomes Device Owner. This is the reliable FIRST-BOOT
     * trigger: on a freshly flashed image the manager is a preinstalled system app
     * that has never been launched, so it sits in the "stopped" state and never
     * receives BOOT_COMPLETED — its [com.evtrack.kioskmanager.ipc.BootReceiver]
     * cannot fire, so FrontDesk was never bootstrapped. DEVICE_ADMIN_ENABLED, by
     * contrast, IS delivered here even while stopped (and un-stops the app), right
     * when rpi5_set_device_owner.sh's `dpm set-device-owner` succeeds. Kick the same
     * [InstallService] BootReceiver would: it bootstraps FrontDesk if absent and
     * applies the kiosk lockdown. Subsequent boots go through BootReceiver as usual
     * (the app is no longer stopped once it has run).
     */
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Owner enabled — starting InstallService (first-boot bootstrap)")
        InstallService.start(context)
    }

    companion object {
        private const val TAG = "AdminReceiver"

        /** The ComponentName the platform (and DevicePolicyManager calls) refer to. */
        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, AdminReceiver::class.java)
    }
}
