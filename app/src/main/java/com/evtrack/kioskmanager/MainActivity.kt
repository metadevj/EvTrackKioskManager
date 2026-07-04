package com.evtrack.kioskmanager

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.evtrack.kioskmanager.cdn.ReleaseMeta
import com.evtrack.kioskmanager.update.UpdateManager
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

        findViewById<Button>(R.id.btnCheck).setOnClickListener { onCheck() }
        findViewById<Button>(R.id.btnInstallLatest).setOnClickListener { onInstall("latest") }
        findViewById<Button>(R.id.btnInstallBeta).setOnClickListener { onInstall("beta") }
        findViewById<Button>(R.id.btnRollback).setOnClickListener { onRollback() }

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

    /** Check both variants on the CDN and display the resolved versions. */
    private fun onCheck() {
        setStatus("Checking CDN…")
        lifecycleScope.launch {
            val latest = safeCheck("latest")
            val beta = safeCheck("beta")
            txtLatest.text = "Latest (CDN): " + (latest?.versionBuild ?: "unavailable")
            txtBeta.text = "Beta (CDN): " + (beta?.versionBuild ?: "unavailable")
            setStatus("Check complete.")
        }
    }

    private suspend fun safeCheck(variant: String): ReleaseMeta? = try {
        updateManager.checkForUpdate(variant)
    } catch (e: Exception) {
        setStatus("Check failed ($variant): ${e.message}")
        null
    }

    /** Download + install (or update) the given [variant]. */
    private fun onInstall(variant: String) {
        if (!isDeviceOwner()) {
            setStatus("Cannot install: not Device Owner. See the note above.")
            return
        }
        setStatus("Resolving $variant release…")
        lifecycleScope.launch {
            val meta = safeCheck(variant)
            if (meta == null) {
                setStatus("No $variant release found on CDN.")
                return@launch
            }
            setStatus("Downloading & installing ${meta.versionBuild}…")
            val result = updateManager.updateTo(meta)
            setStatus(result.message)
            refreshStatus()
        }
    }

    private fun onRollback() {
        if (!isDeviceOwner()) {
            setStatus("Cannot roll back: not Device Owner.")
            return
        }
        setStatus("Rolling back to last known good…")
        lifecycleScope.launch {
            val ok = updateManager.rollback()
            setStatus(if (ok) "Rolled back to last known good." else "Rollback unavailable (no snapshot).")
            refreshStatus()
        }
    }
}
