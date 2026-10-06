package com.evtrack.kioskmanager.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.evtrack.kioskmanager.lockdown.LockdownManager
import com.evtrack.kioskmanager.lockdown.LockdownState

/**
 * Re-arms the Manager after it has replaced ITSELF.
 *
 * A self-update kills our process part-way through the install, so anything held only in memory is
 * gone and the device can be left on the launcher with no kiosk lockdown - on a wall-mounted kiosk
 * that is the difference between a kiosk and a tablet someone can poke at.
 *
 * Device Owner status, the installer-of-record role and app data all survive a replace, so there is
 * nothing to restore there; what needs doing is re-applying the lockdown we were enforcing and
 * putting the managed app back in front.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val enabled = LockdownState.isEnabled(context)
        Log.i(TAG, "Replaced with a new Manager build; lockdown enabled=$enabled")
        if (enabled) {
            // Re-pin: the install dropped the lock task, and an unpinned wall-mounted kiosk is just
            // a tablet someone can poke at.
            LockdownManager(context).applyAndLaunch()
        } else {
            // Deliberately nothing. Unlike boot, a replace happens while someone is USING the device -
            // they pressed Update in the Manager. Launching the managed app here would throw them out
            // of the screen they are standing in, and this device is not meant to be pinned anyway.
            Log.i(TAG, "Lockdown not enabled - leaving the foreground alone")
        }
    }

    companion object {
        private const val TAG = "PackageReplacedReceiver"
    }
}
