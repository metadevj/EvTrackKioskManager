package com.evtrack.kioskmanager.cdn.arcs

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One downloadable artefact in a channel. */
data class ArcsFile(
    val fileName: String,
    val role: String?,
    val size: Long,
    val sha256: String?,
    val url: String,
) {
    /**
     * The eIDA variant is distinguished ONLY by its filename suffix (`...-releaseEida.apk`). There is
     * no field for it, and `role` is `distribution` for both builds.
     */
    val isEida: Boolean get() = fileName.contains("eida", ignoreCase = true)
}

/** The newest RELEASED build on one channel. */
data class ArcsChannel(
    val version: String,
    val buildTime: String?,
    val files: List<ArcsFile>,
)

/** Raised with a message already translated into something actionable. */
class ArcsException(message: String) : Exception(message)

/**
 * Calls the ARCS system-authenticated downloads endpoint.
 *
 *   GET https://arcs.evtrack.com/api/v1/system/downloads/evtrack-frontdesk
 *
 * Returns the newest released build per channel ("stable", "beta") with CloudFront-signed URLs valid
 * for 24 hours. Authentication is two headers: the licence as a bearer token, and a freshly minted
 * ES256 proof JWT bound to this exact method and path.
 *
 * Ported from the EvTrackArcsDownloadsClient reference; only the HTTP stack differs (HttpURLConnection
 * rather than java.net.http, which Android does not have).
 */
class ArcsCatalogueClient(
    private val baseUrl: String = ArcsCredentials.BASE_URL,
    private val licenseJwt: String = ArcsCredentials.licenseJwt,
) {

    private val claims by lazy { LicenseClaims.parse(licenseJwt) }
    private val signer by lazy { ArcsProofSigner(claims.sid(), claims.spr()) }

    @Volatile
    private var cached: Pair<Long, Map<String, ArcsChannel>>? = null

    /**
     * The latest released build per channel, keyed by lowercase channel name.
     *
     * Held for [CACHE_MILLIS] to honour the endpoint's `Cache-Control: private, max-age=300`. A fleet
     * of kiosks polling per check becomes a load generator against one shared identity, and the UI
     * checks four options at once, which would otherwise be four calls for one answer.
     */
    suspend fun channels(product: String = ArcsCredentials.PRODUCT): Map<String, ArcsChannel> =
        withContext(Dispatchers.IO) {
            cached?.let { (at, value) ->
                if (System.currentTimeMillis() - at < CACHE_MILLIS) return@withContext value
            }
            val fetched = fetch(product)
            cached = System.currentTimeMillis() to fetched
            fetched
        }

    /** Drops the cached catalogue so the next call goes to ARCS. */
    fun invalidate() {
        cached = null
    }

    private fun fetch(product: String): Map<String, ArcsChannel> {
        // The proof is bound to the PATH ONLY - ARCS compares htu against getRequestURI(), so a scheme,
        // host or query string here can never match.
        val path = "/api/v1/system/downloads/$product"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                setRequestProperty("Authorization", "Bearer ${licenseJwt.trim()}")
                setRequestProperty(ArcsProofSigner.PROOF_HEADER, signer.sign("GET", path))
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code != 200) {
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw ArcsException(describeFailure(code, body, product))
            }
            return parse(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: ArcsException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ARCS catalogue fetch failed", e)
            throw ArcsException("Could not reach ARCS: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(body: String): Map<String, ArcsChannel> {
        val channels = JSONObject(body).optJSONObject("channels") ?: return emptyMap()
        val result = mutableMapOf<String, ArcsChannel>()
        for (name in channels.keys()) {
            val node = channels.optJSONObject(name) ?: continue
            val version = node.optString("version").takeIf { it.isNotEmpty() } ?: continue
            val files = mutableListOf<ArcsFile>()
            val array = node.optJSONArray("files")
            for (i in 0 until (array?.length() ?: 0)) {
                val f = array!!.optJSONObject(i) ?: continue
                // The key is fileName, NOT name. ARCS hand-assembles this map, so there is no shared
                // schema to keep us honest: guess wrong and every field reads back null without error.
                val fileName = f.optString("fileName").takeIf { it.isNotEmpty() } ?: continue
                val url = f.optString("url").takeIf { it.isNotEmpty() } ?: continue
                files += ArcsFile(
                    fileName = fileName,
                    role = f.optString("role").takeIf { it.isNotEmpty() },
                    size = f.optLong("size"),
                    sha256 = f.optString("sha256").takeIf { it.isNotEmpty() },
                    url = url,
                )
            }
            result[name.lowercase()] = ArcsChannel(
                version = version,
                buildTime = node.optString("buildTime").takeIf { it.isNotEmpty() },
                files = files,
            )
        }
        return result
    }

    /**
     * ARCS discloses exactly six reason codes on a 401, each with a different remedy, so they are
     * translated rather than surfaced as a bare status. Anything else is reported as unrecognised on
     * purpose: this is a trust boundary, and an unknown string must not be echoed as if meaningful.
     */
    private fun describeFailure(status: Int, body: String, product: String): String {
        if (status == 404) {
            return "404 - '$product' is not an allowlisted product in ARCS, or does not exist. " +
                "The two are deliberately indistinguishable."
        }
        if (status != 401) return "ARCS returned HTTP $status"

        val code = try {
            JSONObject(body).optString("code")
        } catch (e: Exception) {
            ""
        }
        val reason = when (code) {
            "unknown_system" -> "the licence's sid does not match any system in ARCS."
            "system_inactive" -> "the system exists but has been deactivated in ARCS."
            "license_revoked" -> "this licence has been revoked. Issue a new one."
            "license_expired" -> "the licence's slt has passed. Re-issue it with Expiry Days blank."
            "stale_credential" -> "the licence's spk no longer matches the system's credential - " +
                "it was rotated. Re-issue the licence."
            "proof_rejected" -> "the proof failed verification. Check the device clock (ARCS allows " +
                "30s skew), that htu is the path only, and that the signature is JOSE R||S not DER."
            else -> "ARCS rejected the credentials."
        }
        return "401 $code - $reason"
    }

    companion object {
        private const val TAG = "ArcsCatalogueClient"

        /** Matches the endpoint's own Cache-Control: private, max-age=300. */
        private const val CACHE_MILLIS = 5 * 60 * 1000L

        /**
         * ARCS's database auto-pauses when idle and the first call after a pause waits out the resume.
         * A short timeout turns every cold hit into a spurious failure.
         */
        private const val TIMEOUT_MILLIS = 30_000
    }
}
