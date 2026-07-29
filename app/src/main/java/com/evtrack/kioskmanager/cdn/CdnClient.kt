package com.evtrack.kioskmanager.cdn

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A flavour of the managed app as published on the CDN.
 *
 * Normal and Eida install as the **same** package ([CdnClient.MANAGED_PACKAGE]) — they
 * differ only in the CDN folder and the artifact/pointer basename (Eida enables the
 * Emirates-ID scanning build type). Because they share a package they are mutually
 * exclusive: installing one replaces the other.
 */
enum class Flavour(
    val displayName: String,
    val folder: String,
    private val basename: String,
) {
    NORMAL("FrontDesk", "evtrack-front-desk", "evtrack-front-desk-universal-release"),
    EIDA("FrontDesk Eida", "evtrack-front-desk-eida", "evtrack-front-desk-eida-universal-release");

    /** Per-version pointer JSON filename for this flavour. */
    val pointerJson get() = "$basename.json"

    /** Universal release APK filename for this flavour. */
    val apkFilename get() = "$basename.apk"
}

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
 * Discovers published builds of the managed kiosk app on the public CDN.
 *
 * The URL scheme matches the EvTrack web updater (see the deployment app's
 * `apk-discover.js`) so all clients resolve the same artifacts.
 *
 * CDN layout (base = [DEFAULT_BASE]) — parameterised by [Flavour] and channel:
 *   $base/<flavour.folder>/<channel>/<flavour.pointerJson>         (pointer)
 *   $base/<flavour.folder>/<version>.<build>/<flavour.apkFilename> (artifact)
 * where <channel> is "latest" (main) or "beta".
 *
 * The pointer JSON looks like:
 *   { "apps": [ { "version": "...", "build": "...", "sha256": "..." }, ... ] }
 * and we take apps[0] as the current release for that flavour + channel.
 *
 * @param base override for the CDN base URL (tests / staging). Defaults to [DEFAULT_BASE].
 */
class CdnClient(private val base: String = DEFAULT_BASE) {

    /**
     * Discover the latest published build of [flavour] on [channel] ("latest" | "beta"),
     * or `null` if that flavour/channel isn't published or the fetch fails.
     */
    suspend fun fetchLatest(flavour: Flavour, channel: String): ReleaseMeta? = withContext(Dispatchers.IO) {
        // Cache-buster: the pointer JSON changes in place, so defeat any CDN/proxy cache.
        val url = "$base/${flavour.folder}/$channel/${flavour.pointerJson}?r=${System.nanoTime()}"
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

            val downloadUrl = "$base/${flavour.folder}/$version.$build/${flavour.apkFilename}"
            ReleaseMeta(version = version, build = build, sha256 = sha256, downloadUrl = downloadUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse release pointer from $url", e)
            null
        }
    }

    /** Legacy convenience: latest of the [Flavour.NORMAL] flavour on [channel]. */
    suspend fun fetchLatest(channel: String): ReleaseMeta? = fetchLatest(Flavour.NORMAL, channel)

    /**
     * Resolve a PINNED build (exact version + build) of [flavour] to a downloadable
     * [ReleaseMeta].
     *
     * The APK URL is deterministic ($base/<folder>/<version>.<build>/<apk>), so this
     * never needs the channel pointer. We additionally *try* the per-version pointer
     * JSON in the same folder for an optional sha256, but its absence is fine — the
     * install still enforces the APK signature regardless.
     */
    suspend fun fetchPinned(flavour: Flavour, version: String, build: String): ReleaseMeta = withContext(Dispatchers.IO) {
        val folder = "$version.$build"
        val downloadUrl = "$base/${flavour.folder}/$folder/${flavour.apkFilename}"
        val sha256 = try {
            val body = httpGet("$base/${flavour.folder}/$folder/${flavour.pointerJson}?r=${System.nanoTime()}")
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

    /** Legacy convenience: pinned build of the [Flavour.NORMAL] flavour. */
    suspend fun fetchPinned(version: String, build: String): ReleaseMeta =
        fetchPinned(Flavour.NORMAL, version, build)

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

        /** Package name of the app this manager installs/updates (same for every flavour). */
        const val MANAGED_PACKAGE = "com.evtrack.frontdesk"
    }
}
