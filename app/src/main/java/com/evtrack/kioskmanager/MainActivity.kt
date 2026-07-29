package com.evtrack.kioskmanager

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
import com.evtrack.kioskmanager.cdn.ReleaseMeta
import com.evtrack.kioskmanager.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Simple manual control surface for the kiosk manager (v1).
 *
 * Shows Device Owner status, the installed managed-app version, and the CDN version of
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
    private lateinit var btnCancel: Button

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
        btnCancel = findViewById(R.id.btnCancel)

        for (opt in options) {
            val btn = findViewById<Button>(opt.buttonViewId)
            optionButtons.add(btn)
            btn.setOnClickListener { onInstall(opt) }
        }

        btnCheck.setOnClickListener { onCheck() }
        btnRollback.setOnClickListener { onRollback() }
        btnCancel.setOnClickListener { onCancel() }

        refreshStatus()
        autoCheckCdn()
    }

    /**
     * Populate every option's CDN version on launch (without the busy/disable UI) so the
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
                for (opt in options) versionView(opt).text = "${opt.label} (CDN): checking…"
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
    }

    private fun isDeviceOwner(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(packageName)
    }

    private fun versionView(opt: InstallOption): TextView = findViewById(opt.versionViewId)

    private fun setOptionVersion(opt: InstallOption, meta: ReleaseMeta?) {
        versionView(opt).text = "${opt.label} (CDN): " + (meta?.versionBuild ?: "unavailable")
    }

    private fun setStatus(text: String) {
        txtStatus.text = text
    }

    /** Enable/disable action buttons and show/hide the progress bar for a running op. */
    private fun setBusy(busy: Boolean) {
        btnCheck.isEnabled = !busy
        btnRollback.isEnabled = !busy
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

    /** Check every option on the CDN and display the resolved versions. */
    private fun onCheck() {
        setBusy(true)
        setStatus("Checking CDN…")
        currentJob = lifecycleScope.launch {
            try {
                for (opt in options) setOptionVersion(opt, safeCheck(opt))
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
                    setStatus("No ${opt.label} release found on CDN.")
                    return@launch
                }
                val result = updateManager.updateTo(meta) { read, total ->
                    runOnUiThread { onDownloadProgress(meta.versionBuild, read, total) }
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
