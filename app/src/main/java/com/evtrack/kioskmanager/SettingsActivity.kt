package com.evtrack.kioskmanager

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.evtrack.kioskmanager.lockdown.LockdownManager
import com.evtrack.kioskmanager.update.SelfUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Settings: the things that are not day-to-day kiosk operation.
 *
 * Both actions here change the device rather than the managed app, which is why they are off the
 * main screen - updating the Manager restarts the app you are looking at, and releasing Device Owner
 * un-manages the kiosk for good. Neither belongs a mis-tap away from the install buttons.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var txtManager: TextView
    private lateinit var txtOwner: TextView
    private lateinit var txtOwnerHint: TextView
    private lateinit var txtManagerMain: TextView
    private lateinit var txtManagerBeta: TextView
    private lateinit var txtStatus: TextView
    private lateinit var btnManagerUpdate: Button
    private lateinit var btnReleaseOwner: Button

    private val selfUpdater by lazy { SelfUpdater(this) }
    private var checkJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.title = "Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        txtManager = findViewById(R.id.txtSettingsManager)
        txtOwner = findViewById(R.id.txtSettingsOwner)
        txtOwnerHint = findViewById(R.id.txtSettingsOwnerHint)
        txtManagerMain = findViewById(R.id.txtSettingsManagerMain)
        txtManagerBeta = findViewById(R.id.txtSettingsManagerBeta)
        txtStatus = findViewById(R.id.txtSettingsStatus)
        btnManagerUpdate = findViewById(R.id.btnOpenManagerUpdate)
        btnReleaseOwner = findViewById(R.id.btnReleaseOwner)

        btnManagerUpdate.setOnClickListener {
            startActivity(Intent(this, ManagerUpdateActivity::class.java))
        }
        btnReleaseOwner.setOnClickListener { onReleaseDeviceOwner() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        checkArcs()
    }

    override fun onPause() {
        super.onPause()
        // Don't leave a fetch running against a screen nobody is looking at.
        checkJob?.cancel()
    }

    /**
     * Read what ARCS publishes for the Manager, every time this screen is opened.
     *
     * Opening Settings is the moment someone wants to know whether there is a newer Manager, so the
     * versions are already on screen rather than behind another tap. The catalogue is cached for five
     * minutes in [com.evtrack.kioskmanager.cdn.arcs.ArcsCatalogueClient], so re-entering the screen
     * costs nothing.
     */
    private fun checkArcs() {
        txtManagerMain.text = "Kiosk Manager - Main (ARCS): checking…"
        txtManagerBeta.text = "Kiosk Manager - Beta (ARCS): checking…"
        checkJob?.cancel()
        checkJob = lifecycleScope.launch {
            try {
                val main = selfUpdater.published(SelfUpdater.DEFAULT_CHANNEL)
                val beta = selfUpdater.published("beta")
                txtManagerMain.text = "Kiosk Manager - Main (ARCS): " + (main ?: "unavailable")
                txtManagerBeta.text = "Kiosk Manager - Beta (ARCS): " + (beta ?: "unavailable")
                val newer = listOfNotNull(main, beta).any {
                    SelfUpdater.isNewer(it, selfUpdater.installedVersion())
                }
                txtStatus.text = if (newer) "A newer Manager is published." else "Manager is up to date."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                txtManagerMain.text = "Kiosk Manager - Main (ARCS): unavailable"
                txtManagerBeta.text = "Kiosk Manager - Beta (ARCS): unavailable"
                txtStatus.text = "Could not reach ARCS: ${e.message}"
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refresh() {
        txtManager.text = "Kiosk Manager: ${selfUpdater.installedVersion()}"
        val owner = LockdownManager(this).isDeviceOwner()
        txtOwner.text = "Device Owner: ${if (owner) "YES" else "NO"}"
        txtOwnerHint.visibility = if (owner) android.view.View.GONE else android.view.View.VISIBLE
        if (!owner) {
            txtOwnerHint.text =
                "Not provisioned as Device Owner - silent install unavailable.\n" +
                    "Run: adb shell dpm set-device-owner com.evtrack.kioskmanager/.AdminReceiver"
        }
        btnReleaseOwner.isEnabled = owner
    }

    /**
     * Hand back Device Owner.
     *
     * The only other way out is a factory reset: adb cannot remove a non-test admin. That makes this
     * destructive in one direction only, so it asks first, and says plainly what is lost.
     */
    private fun onReleaseDeviceOwner() {
        val lockdown = LockdownManager(this)
        if (!lockdown.isDeviceOwner()) {
            txtStatus.text = getString(R.string.release_device_owner_not_owner)
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.release_device_owner_title)
            .setMessage(R.string.release_device_owner_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.release_device_owner_confirm) { _, _ ->
                val released = lockdown.releaseDeviceOwner()
                txtStatus.text = getString(
                    if (released) R.string.release_device_owner_done
                    else R.string.release_device_owner_failed
                )
                refresh()
            }
            .show()
    }
}
