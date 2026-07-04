package com.evtrack.kioskmanager.provisioning

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * QR / NFC Device Owner provisioning entry point (Android 12+ / API 31).
 *
 * During QR/NFC enrollment the system downloads the Manager APK, sets it as the DPC,
 * then calls this activity to ask which management mode to use. We request a fully
 * managed (Device Owner) device.
 *
 * Without this activity, QR/NFC provisioning fails with "Something went wrong" on
 * Android 12+. (adb `dpm set-device-owner` does NOT go through this path.)
 */
class GetProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "GET_PROVISIONING_MODE -> FULLY_MANAGED_DEVICE")
        val result = Intent().putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_MODE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
        )
        setResult(RESULT_OK, result)
        finish()
    }

    companion object {
        private const val TAG = "GetProvisioningMode"
    }
}
