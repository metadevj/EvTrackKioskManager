package com.evtrack.kioskmanager.provisioning

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.evtrack.kioskmanager.ipc.InstallService

/**
 * QR / NFC provisioning completion (Android 12+ / API 31).
 *
 * Called by the system after the Manager has been set as Device Owner, to let the
 * DPC confirm setup is complete. At this point the managed kiosk app (FrontDesk) is
 * not installed yet, so we kick the bootstrap install from the CDN (InstallService
 * runs its bootstrap path because the managed app is absent), then report compliance
 * so provisioning finishes and the device lands on the home screen.
 *
 * Without this activity, QR/NFC provisioning fails with "Something went wrong".
 */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "ADMIN_POLICY_COMPLIANCE -> starting FrontDesk bootstrap")
        // We are Device Owner now; bootstrap the managed app from the CDN.
        InstallService.start(this)
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        private const val TAG = "PolicyCompliance"
    }
}
