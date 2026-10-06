package com.evtrack.kioskmanager

import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.evtrack.kioskmanager.cdn.Flavour
import com.evtrack.kioskmanager.lockdown.LockdownManager
import com.evtrack.kioskmanager.cdn.arcs.ArcsCredentials
import com.evtrack.kioskmanager.cdn.ReleaseMeta
import com.evtrack.kioskmanager.update.SelfUpdater
import com.evtrack.kioskmanager.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Simple manual control surface for the kiosk manager (v1).
 *
 * Shows Device Owner status, the installed managed-app version, and the ARCS version of
 * every installable option — both channels (Main = "latest", Beta) for both flavours
 * (Normal + Eida). Each option has its own install button; they all install the same
 * package ([UpdateManager.managedPackage]) and are therefore mutually exclusive.
 * Deliberately plain and readable — server-push (issue #26) reuses the same
 * UpdateManager entry points.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var updateManager: UpdateManager

    private lateinit var txtDeviceOwner: TextView
    private lateinit var txtNotOwnerHint: TextView
    private lateinit var txtInstalled: TextView
    private lateinit var txtStatus: TextView

    private lateinit var progressDownload: ProgressBar
    private lateinit var btnCheck: Button
    private lateinit var btnRollback: Button
    private lateinit var btnReleaseOwner: Button
    private lateinit var btnCancel: Button
    private lateinit var btnManagerMain: Button
    private lateinit var btnManagerBeta: Button
    private lateinit var txtManagerInstalled: TextView
    private lateinit var txtManagerMain: TextView
    private lateinit var txtManagerBeta: TextView

    /** Self-update of this app. Manual only - there is no scheduled or boot-time check. */
    private val selfUpdater by lazy { SelfUpdater(this) }

    /** One installable {flavour × channel} choice, bound to a version row + install button. */
    private data class InstallOption(
        val flavour: Flavour,
        val channel: String,        // "latest" (Main) | "beta"
        val label: String,          // e.g. "FrontDesk — Main"
        val versionViewId: Int,
        val buttonViewId: Int,
    )

    /** The four options, in display order. */
    private val options = listOf(
        InstallOption(Flavour.NORMAL, "latest", "FrontDesk — Main", R.id.txtNormalMain, R.id.btnNormalMain),
        InstallOption(Flavour.NORMAL, "beta", "FrontDesk — Beta", R.id.txtNormalBeta, R.id.btnNormalBeta),
        InstallOption(Flavour.EIDA, "latest", "FrontDesk Eida — Main", R.id.txtEidaMain, R.id.btnEidaMain),
        InstallOption(Flavour.EIDA, "beta", "FrontDesk Eida — Beta", R.id.txtEidaBeta, R.id.btnEidaBeta),
    )

    /** Install buttons, so [setBusy] can enable/disable them together. */
    private val optionButtons = mutableListOf<Button>()

    /** The currently-running operation, so Cancel can abort a stuck download. */
    private var currentJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        updateManager = UpdateManager(applicationContext)

        txtDeviceOwner = findViewById(R.id.txtDeviceOwner)
        txtNotOwnerHint = findViewById(R.id.txtNotOwnerHint)
        txtInstalled = findViewById(R.id.txtInstalled)
        txtStatus = findViewById(R.id.txtStatus)

        progressDownload = findViewById(R.id.progressDownload)
        btnCheck = findViewById(R.id.btnCheck)
        btnRollback = findViewById(R.id.btnRollback)
        btnReleaseOwner = findViewById(R.id.btnReleaseOwner)
        btnCancel = findViewById(R.id.btnCancel)
        btnManagerMain = findViewById(R.id.btnManagerMain)
        btnManagerBeta = findViewById(R.id.btnManagerBeta)
        txtManagerInstalled = findViewById(R.id.txtManagerInstalled)
        txtManagerMain = findViewById(R.id.txtManagerMain)
        txtManagerBeta = findViewById(R.id.txtManagerBeta)

        for (opt in options) {
            val btn = findViewById<Button>(opt.buttonViewId)
            optionButtons.add(btn)
            btn.setOnClickListener { onInstall(opt) }
        }

        btnCheck.setOnClickListener { onCheck() }
        btnRollback.setOnClickListener { onRollback() }
        btnReleaseOwner.setOnClickListener { onReleaseDeviceOwner() }
        btnCancel.setOnClickListener { onCancel() }
        btnManagerMain.setOnClickListener { onSelfUpdate(SelfUpdater.DEFAULT_CHANNEL) }
        btnManagerBeta.setOnClickListener { onSelfUpdate("beta") }

        refreshStatus()
        autoCheckCdn()
    }

    /**
     * Populate every option's ARCS version on launch (without the busy/disable UI) so the
     * rows don't sit on "…". Retries for a while because at first boot the network may
     * not be up yet.
     */
    private fun autoCheckCdn() {
        lifecycleScope.launch {
            repeat(10) {
                var anyResolved = false
                for (opt in options) {
                    val meta = try { updateManager.checkForUpdate(opt.flavour, opt.channel) } catch (e: Exception) { null }
                    setOptionVersion(opt, meta)
                    if (meta != null) anyResolved = true
                }
                if (anyResolved) return@launch
                for (opt in options) versionView(opt).text = "${opt.label} (ARCS): checking…"
                delay(3000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /** Refresh the local (non-network) status rows. */
    private fun refreshStatus() {
        val isOwner = isDeviceOwner()
        txtDeviceOwner.text = "Device Owner: ${if (isOwner) "YES" else "NO"}"
        if (isOwner) {
            txtNotOwnerHint.visibility = TextView.GONE
        } else {
            txtNotOwnerHint.visibility = TextView.VISIBLE
            txtNotOwnerHint.text =
                "Not provisioned as Device Owner — silent install unavailable.\n" +
                    "Run: adb shell dpm set-device-owner com.evtrack.kioskmanager/.AdminReceiver"
        }

        val installed = updateManager.installedVersion()
        txtInstalled.text = "Installed (${updateManager.managedPackage}): " +
            (installed ?: "not installed")

        txtManagerInstalled.text = "Kiosk Manager (this app): ${selfUpdater.installedVersion()}"
    }

    private fun isDeviceOwner(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(packageName)
    }

    private fun versionView(opt: InstallOption): TextView = findViewById(opt.versionViewId)

    private fun setOptionVersion(opt: InstallOption, meta: ReleaseMeta?) {
        versionView(opt).text = "${opt.label} (ARCS): " + (meta?.versionBuild ?: "unavailable")
    }

    private fun setStatus(text: String) {
        txtStatus.text = text
    }

    /**
     * Hand back Device Owner.
     *
     * The only other way out is a factory reset: adb cannot remove a non-test admin. That makes
     * this destructive in one direction only, so it asks first, and says plainly what is lost.
     */
    private fun onReleaseDeviceOwner() {
        val lockdown = LockdownManager(this)
        if (!lockdown.isDeviceOwner()) {
            setStatus(getString(R.string.release_device_owner_not_owner))
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.release_device_owner_title)
            .setMessage(R.string.release_device_owner_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.release_device_owner_confirm) { _, _ ->
                val released = lockdown.releaseDeviceOwner()
                setStatus(
                    getString(
                        if (released) R.string.release_device_owner_done
                        else R.string.release_device_owner_failed
                    )
                )
                refreshStatus()
            }
            .show()
    }

    /** Enable/disable action buttons and show/hide the progress bar for a running op. */
    /** Fill in what ARCS publishes for the Manager itself, on both channels. */
    private suspend fun refreshManagerChannels() {
        txtManagerMain.text =
            "Kiosk Manager - Main (ARCS): " + (selfUpdater.published(SelfUpdater.DEFAULT_CHANNEL) ?: "unavailable")
        txtManagerBeta.text =
            "Kiosk Manager - Beta (ARCS): " + (selfUpdater.published("beta") ?: "unavailable")
    }

    /**
     * Update THIS app from ARCS, on [channel] ("latest" = Main, "beta").
     *
     * Asks first: the install replaces the running Manager and kills this process part-way through,
     * so the screen will simply disappear. Nothing is scheduled - this only ever happens because
     * someone pressed the button.
     */
    private fun onSelfUpdate(channel: String) {
        if (!ArcsCredentials.isConfigured()) {
            setStatus("No ARCS licence in this build - set arcs.licenseJwt in local.properties.")
            return
        }
        setBusy(true)
        setStatus("Checking for a Manager update on ${channelLabel(channel)}…")
        currentJob = lifecycleScope.launch {
            try {
                val update = selfUpdater.check(channel)
                if (update == null) {
                    setStatus(
                        "Manager is up to date on ${channelLabel(channel)} " +
                            "(installed ${selfUpdater.installedVersion()})."
                    )
                    refreshManagerChannels()
                    return@launch
                }
                val go = confirmSelfUpdate(update.version, channelLabel(channel))
                if (!go) {
                    setStatus("Manager update cancelled.")
                    return@launch
                }
                setStatus("Downloading Manager ${update.version}…")
                val result = selfUpdater.apply(update) { read, total ->
                    if (total > 0) runOnUiThread {
                        progressDownload.isIndeterminate = false
                        progressDownload.max = 100
                        progressDownload.progress = ((read * 100) / total).toInt()
                    }
                }
                setStatus(result.message)
                refreshStatus()
            } catch (e: CancellationException) {
                setStatus("Cancelled.")
                throw e
            } catch (e: Exception) {
                setStatus("Manager update failed: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun channelLabel(channel: String): String =
        if (channel.equals("beta", ignoreCase = true)) "Beta" else "Main"

    /** Confirms a self-update, resuming the coroutine with the answer. */
    private suspend fun confirmSelfUpdate(version: String, label: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val dialog = AlertDialog.Builder(this)
                .setTitle("Update the Kiosk Manager?")
                .setMessage(
                    "Replace this app (${selfUpdater.installedVersion()}) with $version from $label.\n\n" +
                        "The Manager restarts as part of the install, so this screen will close. " +
                        "Device Owner and the kiosk lockdown are restored automatically."
                )
                .setPositiveButton("Update") { _, _ -> if (cont.isActive) cont.resume(true) {} }
                .setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(false) {} }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) {} }
                .create()
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }

    private fun setBusy(busy: Boolean) {
        btnCheck.isEnabled = !busy
        btnRollback.isEnabled = !busy
        btnReleaseOwner.isEnabled = !busy
        btnManagerMain.isEnabled = !busy
        btnManagerBeta.isEnabled = !busy
        for (b in optionButtons) b.isEnabled = !busy
        btnCancel.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) {
            progressDownload.isIndeterminate = true
            progressDownload.visibility = View.VISIBLE
        } else {
            progressDownload.visibility = View.GONE
        }
    }

    /** Abort the running operation (e.g. a stuck download). */
    private fun onCancel() {
        setStatus("Cancelling…")
        currentJob?.cancel()
    }

    /** Check every option on ARCS and display the resolved versions. */
    private fun onCheck() {
        if (!ArcsCredentials.isConfigured()) {
            // Without this the screen just reads "unavailable" against every option, which looks
            // like an outage rather than a build that was never given a credential.
            setStatus("No ARCS licence in this build - set arcs.licenseJwt in local.properties.")
            return
        }
        setBusy(true)
        setStatus("Checking ARCS…")
        currentJob = lifecycleScope.launch {
            try {
                for (opt in options) setOptionVersion(opt, safeCheck(opt))
                refreshManagerChannels()
                setStatus("Check complete.")
            } catch (e: CancellationException) {
                setStatus("Cancelled.")
                throw e
            } finally {
                setBusy(false)
            }
        }
    }

    private suspend fun safeCheck(opt: InstallOption): ReleaseMeta? = try {
        updateManager.checkForUpdate(opt.flavour, opt.channel)
    } catch (e: Exception) {
        setStatus("Check failed (${opt.label}): ${e.message}")
        null
    }

    /** Download + install (or update) the given [opt], with a live progress bar. */
    private fun onInstall(opt: InstallOption) {
        if (!isDeviceOwner()) {
            setStatus("Cannot install: not Device Owner. See the note above.")
            return
        }
        setBusy(true)
        setStatus("Resolving ${opt.label}…")
        currentJob = lifecycleScope.launch {
            try {
                val meta = safeCheck(opt)
                if (meta == null) {
                    setStatus("No ${opt.label} release found on ARCS.")
                    return@launch
                }
                val result = updateManager.updateTo(meta) { read, total ->
                    runOnUiThread { onDownloadProgress(meta.versionBuild, read, total) }
                }
                if (result.success) {
                    // Deliberate choice of channel, so a later bootstrap restores this one rather
                    // than dropping the device back to stable.
                    updateManager.rememberChannel(opt.channel)
                }
                setStatus(result.message)
                refreshStatus()
            } catch (e: CancellationException) {
                setStatus("Cancelled — download aborted.")
                refreshStatus()
                throw e
            } finally {
                setBusy(false)
            }
        }
    }

    /**
     * UI-thread progress handler: fills the bar during download, and flips to an
     * indeterminate "Installing…" once all bytes are in (install isn't trackable).
     */
    private fun onDownloadProgress(versionBuild: String, read: Long, total: Long) {
        val mb = 1024L * 1024
        if (total > 0) {
            if (read >= total) {
                progressDownload.isIndeterminate = true
                setStatus("Installing $versionBuild…")
            } else {
                val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                progressDownload.isIndeterminate = false
                progressDownload.progress = pct
                setStatus("Downloading $versionBuild…  $pct%  (${read / mb}/${total / mb} MB)")
            }
        } else {
            progressDownload.isIndeterminate = true
            setStatus("Downloading $versionBuild…  ${read / mb} MB")
        }
    }

    private fun onRollback() {
        if (!isDeviceOwner()) {
            setStatus("Cannot roll back: not Device Owner.")
            return
        }
        setBusy(true)
        setStatus("Rolling back to last known good…")
        currentJob = lifecycleScope.launch {
            try {
                val ok = updateManager.rollback()
                setStatus(if (ok) "Rolled back to last known good." else "Rollback unavailable (no snapshot).")
                refreshStatus()
            } catch (e: CancellationException) {
                setStatus("Cancelled.")
                throw e
            } finally {
                setBusy(false)
            }
        }
    }
}
