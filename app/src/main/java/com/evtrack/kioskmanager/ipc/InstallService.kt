package com.evtrack.kioskmanager.ipc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.evtrack.kioskmanager.R
import com.evtrack.kioskmanager.lockdown.LockdownManager
import com.evtrack.kioskmanager.lockdown.LockdownState
import com.evtrack.kioskmanager.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that carries out a [PendingInstall]. It runs in the foreground so
 * a multi-second CDN download + install survives well past a BroadcastReceiver's short
 * lifetime.
 *
 * Behaviour:
 *  - On success: clears the pending record and stops.
 *  - On failure/crash: LEAVES the pending record in place, so [BootReceiver] retries it
 *    on the next boot (and any re-trigger retries immediately). Naturally idempotent —
 *    installing an already-installed identical build is a no-op-ish PackageInstaller call.
 */
class InstallService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()

        if (running) return START_REDELIVER_INTENT // already processing
        running = true

        scope.launch {
            try {
                val mgr = UpdateManager(applicationContext)
                val pending = PendingInstall.load(applicationContext)
                // A bootstrap means this device had no FrontDesk at all, so nobody has paired it
                // with a server yet. That distinction decides whether it may be locked down below.
                var bootstrapped = false
                when {
                    // 1. Pinned request relayed from FrontDesk (issue #30).
                    pending != null -> {
                        Log.i(TAG, "Processing pinned install ${pending.version}.${pending.build} (req=${pending.requestId})")
                        val result = mgr.updateToPinned(pending.version, pending.build)
                        if (result.success) {
                            Log.i(TAG, "Install succeeded: ${result.message}")
                            PendingInstall.clear(applicationContext)
                        } else {
                            // Keep the pending file for retry on next boot/trigger.
                            Log.w(TAG, "Install failed: ${result.message} (will retry)")
                        }
                    }
                    // 2. Fresh device: no FrontDesk yet, so bootstrap it from the CDN (issue #31).
                    !mgr.isManagedInstalled() -> {
                        bootstrapped = true
                        Log.i(TAG, "Managed app absent; bootstrapping from ARCS")
                        // At first boot the network (Ethernet/DHCP) is often not up yet, so the
                        // CDN fetch fails. Retry with a backoff until it takes or we cap out.
                        var result = mgr.bootstrapLatest()
                        var attempt = 1
                        while (!result.success && attempt < MAX_BOOTSTRAP_ATTEMPTS) {
                            Log.w(TAG, "Bootstrap attempt $attempt failed: ${result.message}; retrying")
                            delay(BOOTSTRAP_RETRY_MS)
                            if (mgr.isManagedInstalled()) break // installed via another path meanwhile
                            result = mgr.bootstrapLatest()
                            attempt++
                        }
                        Log.i(TAG, "Bootstrap result: success=${result.success} — ${result.message} (attempts=$attempt)")
                    }
                    // 3. Nothing to do (managed app present, no pending request).
                    else -> Log.i(TAG, "No pending install and managed app present; nothing to do")
                }

                // Bring the managed app up, and decide whether it may pin itself.
                //
                // A freshly bootstrapped device has never been paired with a server: it comes up on
                // the pairing screen, and whoever is standing in front of it still needs the device
                // to be usable. Granting lock-task here pins an unpaired kiosk, which takes the
                // tablet away from the person setting it up and leaves no way back short of adb.
                // So on that pass the allowlist is cleared rather than granted; the grant happens on
                // the next boot, by which time the device has been set up.
                val lockdown = LockdownManager(applicationContext)
                if (lockdown.isDeviceOwner() && mgr.isManagedInstalled()) {
                    // A bootstrapped device has never been paired, so it cannot yet have asked for
                    // lockdown; anything else follows whatever FrontDesk last told us.
                    if (!bootstrapped && LockdownState.isEnabled(applicationContext)) {
                        lockdown.applyAndLaunch()
                    } else {
                        Log.i(TAG, "Lockdown not enabled (bootstrapped=$bootstrapped); leaving device unlocked")
                        lockdown.clearKioskLockdown()
                        lockdown.launchManagedApp()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Install processing crashed (will retry)", e)
            } finally {
                running = false
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 14 requires a declared FGS type; a network download is dataSync.
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.install_channel_name), NotificationManager.IMPORTANCE_LOW)
            )
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.install_notif_title))
            .setContentText(getString(R.string.install_notif_text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "InstallService"
        private const val CHANNEL_ID = "install"
        private const val NOTIF_ID = 42

        // Bootstrap retry: covers the network coming up shortly after boot.
        private const val MAX_BOOTSTRAP_ATTEMPTS = 20   // ~2 min at 6s spacing
        private const val BOOTSTRAP_RETRY_MS = 6_000L

        /** Start the service (foreground on O+). Safe to call repeatedly. */
        fun start(context: Context) {
            val i = Intent(context, InstallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
