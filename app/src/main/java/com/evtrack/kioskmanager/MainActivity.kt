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
import com.evtrack.kioskmanager.cdn.ReleaseMeta
import com.evtrack.kioskmanager.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Simple manual control surface for the kiosk manager (v1).
 *
 * Shows Device Owner status, the installed managed-app version, and the latest/beta
 * CDN versions; buttons drive the [UpdateManager]. Deliberately plain and readable —
 * server-push (issue #26) will reuse the same UpdateManager entry points.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var updateManager: UpdateManager

    private lateinit var txtDeviceOwner: TextView
    private lateinit var txtNotOwnerHint: TextView
    private lateinit var txtInstalled: TextView
    private lateinit var txtLatest: TextView
    private lateinit var txtBeta: TextView
    private lateinit var txtStatus: TextView

    private lateinit var progressDownload: ProgressBar
    private lateinit var btnCheck: Button
    private lateinit var btnInstallLatest: Button
    private lateinit var btnInstallBeta: Button
    private lateinit var btnRollback: Button
    private lateinit var btnCancel: Button

    /** The currently-running operation, so Cancel can abort a stuck download. */
    private var currentJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        updateManager = UpdateManager(applicationContext)

        txtDeviceOwner = findViewById(R.id.txtDeviceOwner)
        txtNotOwnerHint = findViewById(R.id.txtNotOwnerHint)
        txtInstalled = findViewById(R.id.txtInstalled)
        txtLatest = findViewById(R.id.txtLatest)
        txtBeta = findViewById(R.id.txtBeta)
        txtStatus = findViewById(R.id.txtStatus)

        progressDownload = findViewById(R.id.progressDownload)
        btnCheck = findViewById(R.id.btnCheck)
        btnInstallLatest = findViewById(R.id.btnInstallLatest)
        btnInstallBeta = findViewById(R.id.btnInstallBeta)
        btnRollback = findViewById(R.id.btnRollback)
        btnCancel = findViewById(R.id.btnCancel)

        btnCheck.setOnClickListener { onCheck() }
        btnInstallLatest.setOnClickListener { onInstall("latest") }
        btnInstallBeta.setOnClickListener { onInstall("beta") }
        btnRollback.setOnClickListener { onRollback() }
        btnCancel.setOnClickListener { onCancel() }

        refreshStatus()
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

    private fun setStatus(text: String) {
        txtStatus.text = text
    }

    /** Enable/disable action buttons and show/hide the progress bar for a running op. */
    private fun setBusy(busy: Boolean) {
        btnCheck.isEnabled = !busy
        btnInstallLatest.isEnabled = !busy
        btnInstallBeta.isEnabled = !busy
        btnRollback.isEnabled = !busy
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

    /** Check both variants on the CDN and display the resolved versions. */
    private fun onCheck() {
        setBusy(true)
        setStatus("Checking CDN…")
        currentJob = lifecycleScope.launch {
            try {
                val latest = safeCheck("latest")
                val beta = safeCheck("beta")
                txtLatest.text = "Latest (CDN): " + (latest?.versionBuild ?: "unavailable")
                txtBeta.text = "Beta (CDN): " + (beta?.versionBuild ?: "unavailable")
                setStatus("Check complete.")
            } catch (e: CancellationException) {
                setStatus("Cancelled.")
                throw e
            } finally {
                setBusy(false)
            }
        }
    }

    private suspend fun safeCheck(variant: String): ReleaseMeta? = try {
        updateManager.checkForUpdate(variant)
    } catch (e: Exception) {
        setStatus("Check failed ($variant): ${e.message}")
        null
    }

    /** Download + install (or update) the given [variant], with a live progress bar. */
    private fun onInstall(variant: String) {
        if (!isDeviceOwner()) {
            setStatus("Cannot install: not Device Owner. See the note above.")
            return
        }
        setBusy(true)
        setStatus("Resolving $variant release…")
        currentJob = lifecycleScope.launch {
            try {
                val meta = safeCheck(variant)
                if (meta == null) {
                    setStatus("No $variant release found on CDN.")
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
