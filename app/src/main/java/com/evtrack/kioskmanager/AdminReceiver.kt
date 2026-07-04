package com.evtrack.kioskmanager

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

/**
 * Device admin receiver for the Kiosk Manager. Holding Device Owner (granted via
 * `adb shell dpm set-device-owner com.evtrack.kioskmanager/.AdminReceiver`) is what
 * allows the manager to silently install/uninstall the managed kiosk app.
 *
 * This class is intentionally almost empty — it only exists so the platform has a
 * concrete admin component to bind to.
 */
class AdminReceiver : DeviceAdminReceiver() {

    companion object {
        /** The ComponentName the platform (and DevicePolicyManager calls) refer to. */
        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, AdminReceiver::class.java)
    }
}
