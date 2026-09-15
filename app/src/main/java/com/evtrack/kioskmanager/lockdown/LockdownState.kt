package com.evtrack.kioskmanager.lockdown

import android.content.Context

/**
 * Whether this device is meant to be locked down, as last told by the managed app.
 *
 * FrontDesk declares `android:lockTaskMode="if_whitelisted"` on its activities, which means Android
 * pins its task AUTOMATICALLY as soon as the Device Owner puts it on the lock-task allowlist. There
 * is no second step: for this pair of apps, **granting the allowlist IS the lockdown**, and
 * `stopLockTask()` cannot undo it because the task is locked by policy rather than by request.
 *
 * So the grant cannot be handed out on boot and left to FrontDesk to decline. Only FrontDesk knows
 * whether the kiosk is paired and whether the server enabled lockdown, and it tells the Manager
 * through [LockdownRequestReceiver].
 *
 * Defaults to NOT locked down. A device nobody has configured stays usable, which is what someone
 * standing in front of an unpaired kiosk needs.
 */
object LockdownState {

    private const val PREFS = "kiosk_manager"
    private const val KEY_ENABLED = "lockdown_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
