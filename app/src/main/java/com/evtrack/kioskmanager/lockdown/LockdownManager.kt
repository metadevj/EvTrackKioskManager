package com.evtrack.kioskmanager.lockdown

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.evtrack.kioskmanager.AdminReceiver
import com.evtrack.kioskmanager.cdn.CdnClient

/**
 * Applies the kiosk lockdown as Device Owner.
 *
 * The Manager does NOT lock the screen itself — locking (pinning) is done by the
 * managed app calling `startLockTask()`. What the Manager provides is the
 * Device-Owner-only GRANT that permits the managed app to pin: the lock-task
 * allowlist. Once granted, FrontDesk runs its existing lockdown UX unchanged
 * (touch-corner + exit PIN).
 *
 * NOTE: FrontDesk only takes the pin once it calls `startLockTask()` when it is
 * ALLOWLISTED rather than only when it is Device Owner (EvTrackFrontDesk#124). Until
 * that ships, this grant is set but FrontDesk won't fully pin.
 */
class LockdownManager(private val context: Context) {

    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = AdminReceiver.componentName(context)

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)

    /**
     * Grant lock-task to the managed app (and the Manager itself, so it stays
     * reachable for recovery), and set full-lockdown features. Device-Owner only;
     * a no-op otherwise. Idempotent — safe to call on every boot / after installs.
     */
    fun applyKioskLockdown() {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not Device Owner; cannot set lock-task allowlist")
            return
        }
        val allow = arrayOf(CdnClient.MANAGED_PACKAGE, context.packageName)
        dpm.setLockTaskPackages(admin, allow)
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        Log.i(TAG, "Lock-task allowlist set: ${allow.joinToString()}")
    }

    /** Bring the managed kiosk app to the foreground so it can pin itself. */
    fun launchManagedApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(CdnClient.MANAGED_PACKAGE)
        if (intent == null) {
            Log.w(TAG, "Managed app ${CdnClient.MANAGED_PACKAGE} not launchable (installed?)")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Log.i(TAG, "Launched managed app ${CdnClient.MANAGED_PACKAGE}")
    }

    /** Grant lockdown, then bring the kiosk app to the front. */
    fun applyAndLaunch() {
        applyKioskLockdown()
        launchManagedApp()
    }

    /**
     * Give up Device Owner.
     *
     * Android offers exactly two ways out of Device Owner: the owning app relinquishes it, or the
     * device is factory reset. `adb shell dpm remove-active-admin` is not one of them - it refuses
     * anything that is not a test-only build, with "Attempt to remove non-test admin". Without this
     * method a tablet provisioned for a trial could never be handed back or repurposed without a
     * wipe, which is how the first bench unit ended up stuck.
     *
     * Clears the lock-task allowlist first, so the managed app cannot be left pinned by a policy
     * nothing owns any more.
     *
     * @return true when the device is no longer owned.
     */
    fun releaseDeviceOwner(): Boolean {
        if (!isDeviceOwner()) {
            Log.i(TAG, "Not Device Owner; nothing to release")
            return true
        }
        return try {
            clearKioskLockdown()
            dpm.clearDeviceOwnerApp(context.packageName)
            val stillOwner = isDeviceOwner()
            Log.i(TAG, "Released Device Owner; stillOwner=$stillOwner")
            !stillOwner
        } catch (e: Exception) {
            Log.e(TAG, "Could not release Device Owner", e)
            false
        }
    }

    /** Remove the lock-task allowlist (maintenance). Device-Owner only. */
    fun clearKioskLockdown() {
        if (!isDeviceOwner()) return
        dpm.setLockTaskPackages(admin, emptyArray())
        Log.i(TAG, "Lock-task allowlist cleared")
    }

    companion object {
        private const val TAG = "LockdownManager"
    }
}
