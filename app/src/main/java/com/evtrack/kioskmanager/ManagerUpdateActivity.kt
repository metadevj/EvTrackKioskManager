package com.evtrack.kioskmanager

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.evtrack.kioskmanager.cdn.arcs.ArcsCredentials
import com.evtrack.kioskmanager.update.SelfUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Updating the Kiosk Manager ITSELF, on its own screen.
 *
 * Kept away from the main screen because it is a different act: the other buttons manage FrontDesk,
 * this one replaces the app you are looking at. Mixing them invites a mis-tap that restarts the
 * Manager mid-shift.
 *
 * Manual only - there is no scheduled or boot-time check anywhere in this app.
 */
class ManagerUpdateActivity : AppCompatActivity() {

    private lateinit var txtInstalled: TextView
    private lateinit var txtMain: TextView
    private lateinit var txtBeta: TextView
    private lateinit var txtStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnCheck: Button
    private lateinit var btnMain: Button
    private lateinit var btnBeta: Button

    private val selfUpdater by lazy { SelfUpdater(this) }
    private var currentJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manager_update)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.title = "Kiosk Manager update"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        txtInstalled = findViewById(R.id.txtManagerInstalled)
        txtMain = findViewById(R.id.txtManagerMain)
        txtBeta = findViewById(R.id.txtManagerBeta)
        txtStatus = findViewById(R.id.txtManagerStatus)
        progress = findViewById(R.id.progressManager)
        btnCheck = findViewById(R.id.btnManagerCheck)
        btnMain = findViewById(R.id.btnManagerMain)
        btnBeta = findViewById(R.id.btnManagerBeta)

        btnCheck.setOnClickListener { onCheck() }
        btnMain.setOnClickListener { onUpdate(SelfUpdater.DEFAULT_CHANNEL) }
        btnBeta.setOnClickListener { onUpdate("beta") }

        txtInstalled.text = "Installed: ${selfUpdater.installedVersion()}"
        onCheck()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** Read what ARCS publishes on both channels, without installing anything. */
    private fun onCheck() {
        if (!licenceConfigured()) return
        setBusy(true)
        setStatus("Checking ARCS…")
        currentJob = lifecycleScope.launch {
            try {
                refreshChannels()
                setStatus("Check complete.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("Check failed: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private suspend fun refreshChannels() {
        txtInstalled.text = "Installed: ${selfUpdater.installedVersion()}"
        txtMain.text = "Main (ARCS): " + (selfUpdater.published(SelfUpdater.DEFAULT_CHANNEL) ?: "unavailable")
        txtBeta.text = "Beta (ARCS): " + (selfUpdater.published("beta") ?: "unavailable")
    }

    /** Replace this app with the newest build on [channel], after confirming. */
    private fun onUpdate(channel: String) {
        if (!licenceConfigured()) return
        val label = channelLabel(channel)
        setBusy(true)
        setStatus("Checking $label…")
        currentJob = lifecycleScope.launch {
            try {
                val update = selfUpdater.check(channel)
                refreshChannels()
                if (update == null) {
                    setStatus("Already up to date on $label (${selfUpdater.installedVersion()}).")
                    return@launch
                }
                if (!confirm(update.version, label)) {
                    setStatus("Cancelled.")
                    return@launch
                }
                setStatus("Downloading ${update.version}…")
                val result = selfUpdater.apply(update) { read, total ->
                    if (total > 0) runOnUiThread {
                        progress.isIndeterminate = false
                        progress.max = 100
                        progress.progress = ((read * 100) / total).toInt()
                    }
                }
                setStatus(result.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("Update failed: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun licenceConfigured(): Boolean {
        if (ArcsCredentials.isConfigured()) return true
        setStatus("No ARCS licence in this build - set arcs.licenseJwt in local.properties.")
        return false
    }

    private fun channelLabel(channel: String): String =
        if (channel.equals("beta", ignoreCase = true)) "Beta" else "Main"

    private suspend fun confirm(version: String, label: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val dialog = AlertDialog.Builder(this)
                .setTitle("Update the Kiosk Manager?")
                .setMessage(
                    "Replace this app (${selfUpdater.installedVersion()}) with $version from $label.\n\n" +
                        "The Manager restarts as part of the install, so this screen will close."
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
        btnMain.isEnabled = !busy
        btnBeta.isEnabled = !busy
        progress.isIndeterminate = busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun setStatus(text: String) {
        txtStatus.text = text
    }
}
