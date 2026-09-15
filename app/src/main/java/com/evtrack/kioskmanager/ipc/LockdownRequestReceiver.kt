package com.evtrack.kioskmanager.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.evtrack.kioskmanager.lockdown.LockdownManager
import com.evtrack.kioskmanager.lockdown.LockdownState

/**
 * FrontDesk → Manager lockdown control.
 *
 * Only FrontDesk knows whether this kiosk is paired and whether the server turned lockdown on, so it
 * owns the decision; the Manager owns the Device Owner powers to carry it out. This is how the two
 * meet.
 *
 * Secured by the same signature-level permission as [InstallRequestReceiver], so only apps signed
 * with the shared evtrack-release key can fire it.
 *
 * Applies immediately as well as recording the state, so turning lockdown off gives the device back
 * without waiting for a reboot.
 */
class LockdownRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_LOCKDOWN) return
        if (!intent.hasExtra(EXTRA_ENABLED)) {
            Log.w(TAG, "Ignoring lockdown request with no '$EXTRA_ENABLED' extra")
            return
        }

        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
        LockdownState.setEnabled(context, enabled)

        val lockdown = LockdownManager(context)
        if (enabled) {
            lockdown.applyKioskLockdown()
        } else {
            lockdown.clearKioskLockdown()
        }
        Log.i(TAG, "Lockdown set to $enabled by request")
    }

    companion object {
        private const val TAG = "LockdownRequestReceiver"

        const val ACTION_SET_LOCKDOWN = "com.evtrack.kioskmanager.action.SET_LOCKDOWN"
        const val EXTRA_ENABLED = "enabled"
    }
}
