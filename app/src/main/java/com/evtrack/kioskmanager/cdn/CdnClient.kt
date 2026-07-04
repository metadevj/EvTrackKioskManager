package com.evtrack.kioskmanager.cdn

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Metadata for one CDN release of the managed app.
 *
 * @param version marketing/version string (e.g. "2.4.1")
 * @param build   build number (e.g. "531")
 * @param sha256  optional expected SHA-256 of the APK (hex); verified on download
 * @param downloadUrl fully-resolved URL of the universal release APK
 */
data class ReleaseMeta(
    val version: String,
    val build: String,
    val sha256: String?,
    val downloadUrl: String,
) {
    /** Combined "version.build" identifier, matching the CDN folder scheme. */
    val versionBuild get() = "$version.$build"
}

/**
 * Discovers the latest published build of the managed kiosk app on the public CDN.
 *
 * Ported from the EvTrack web updater JS — the URL scheme is reused verbatim so both
 * clients resolve the same artifacts.
 *
 * CDN layout (base = [DEFAULT_BASE]):
 *   $base/evtrack-front-desk/<variant>/evtrack-front-desk-universal-release.json   (pointer)
 *   $base/evtrack-front-desk/<version>.<build>/evtrack-front-desk-universal-release.apk (artifact)
 *
 * The pointer JSON looks like:
 *   { "apps": [ { "version": "...", "build": "...", "sha256": "..." }, ... ] }
 * and we take apps[0] as the current release for that variant.
 *
 * @param base override for the CDN base URL (tests / staging). Defaults to [DEFAULT_BASE].
 */
class CdnClient(private val base: String = DEFAULT_BASE) {

    suspend fun fetchLatest(variant: String): ReleaseMeta? = withContext(Dispatchers.IO) {
        // Cache-buster: the pointer JSON changes in place, so defeat any CDN/proxy cache.
        val url = "$base/$MANAGED_FOLDER/$variant/$POINTER_JSON?r=${System.nanoTime()}"
        val body = httpGet(url) ?: return@withContext null

        try {
            val apps = JSONObject(body).optJSONArray("apps") ?: return@withContext null
            if (apps.length() == 0) return@withContext null

            val app = apps.getJSONObject(0)
            val version = app.optString("version").takeIf { it.isNotEmpty() }
                ?: return@withContext null
            val build = app.optString("build").takeIf { it.isNotEmpty() }
                ?: return@withContext null
            val sha256 = app.optString("sha256").takeIf { it.isNotEmpty() }

            val downloadUrl = "$base/$MANAGED_FOLDER/$version.$build/$APK_FILENAME"
            ReleaseMeta(version = version, build = build, sha256 = sha256, downloadUrl = downloadUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse release pointer from $url", e)
            null
        }
    }

    /**
     * Resolve a PINNED build (exact version + build) to a downloadable [ReleaseMeta].
     *
     * The APK URL is deterministic ($base/<folder>/<version>.<build>/<apk>), so this
     * never needs the variant pointer. We additionally *try* the per-version pointer
     * JSON in the same folder for an optional sha256, but its absence is fine — the
     * install still enforces the APK signature regardless.
     */
    suspend fun fetchPinned(version: String, build: String): ReleaseMeta = withContext(Dispatchers.IO) {
        val folder = "$version.$build"
        val downloadUrl = "$base/$MANAGED_FOLDER/$folder/$APK_FILENAME"
        val sha256 = try {
            val body = httpGet("$base/$MANAGED_FOLDER/$folder/$POINTER_JSON?r=${System.nanoTime()}")
            body?.let {
                JSONObject(it).optJSONArray("apps")
                    ?.optJSONObject(0)
                    ?.optString("sha256")
                    ?.takeIf { s -> s.isNotEmpty() }
            }
        } catch (e: Exception) {
            null
        }
        ReleaseMeta(version = version, build = build, sha256 = sha256, downloadUrl = downloadUrl)
    }

    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "GET $urlStr -> HTTP $code")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "GET $urlStr failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "CdnClient"

        /** Public CDN base URL for released APKs. Overridable via the constructor. */
        const val DEFAULT_BASE = "https://downloads.evtrack.com/public/apk"

        /** CDN folder + artifact names for the managed app. */
        const val MANAGED_FOLDER = "evtrack-front-desk"
        const val APK_FILENAME = "evtrack-front-desk-universal-release.apk"
        const val POINTER_JSON = "evtrack-front-desk-universal-release.json"

        /** Package name of the app this manager installs/updates. */
        const val MANAGED_PACKAGE = "com.evtrack.frontdesk"
    }
}
