package com.evtrack.kioskmanager.ipc

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * A durable, pending install request handed to the Manager by FrontDesk over the
 * signature-gated broadcast (see [InstallRequestReceiver]).
 *
 * It is persisted to the Manager's OWN private storage (`filesDir/pending_install.json`)
 * — this is the sandbox-safe version of "an instruction file the manager picks up":
 * FrontDesk cannot write here, it only *triggers*; the Manager owns the record, so it
 * survives Manager restarts and reboots and doubles as a one-shot retry queue.
 *
 * The request is always a PINNED build (exact version + build).
 */
data class PendingInstall(
    val target: String,
    val version: String,
    val build: String,
    val requestId: String,
) {
    private fun toJson(): String = JSONObject()
        .put("target", target)
        .put("version", version)
        .put("build", build)
        .put("requestId", requestId)
        .toString()

    companion object {
        private const val TAG = "PendingInstall"
        private fun file(context: Context) = File(context.filesDir, "pending_install.json")

        fun save(context: Context, p: PendingInstall) {
            try {
                file(context).writeText(p.toJson())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist pending install", e)
            }
        }

        fun load(context: Context): PendingInstall? {
            val f = file(context)
            if (!f.exists()) return null
            return try {
                val o = JSONObject(f.readText())
                val version = o.optString("version")
                val build = o.optString("build")
                if (version.isEmpty() || build.isEmpty()) null
                else PendingInstall(
                    target = o.optString("target"),
                    version = version,
                    build = build,
                    requestId = o.optString("requestId"),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Corrupt pending_install.json; ignoring", e)
                null
            }
        }

        fun clear(context: Context) {
            file(context).delete()
        }
    }
}
