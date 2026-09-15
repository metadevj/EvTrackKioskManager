package com.evtrack.kioskmanager.cdn

import android.util.Log
import com.evtrack.kioskmanager.cdn.arcs.ArcsCatalogueClient
import com.evtrack.kioskmanager.cdn.arcs.ArcsCredentials
import com.evtrack.kioskmanager.cdn.arcs.ArcsException

/**
 * A flavour of the managed app as published on ARCS.
 *
 * Normal and Eida install as the **same** package ([CdnClient.MANAGED_PACKAGE]) - they differ only in
 * the artefact, the Eida build enabling Emirates-ID scanning. Because they share a package they are
 * mutually exclusive: installing one replaces the other.
 *
 * ARCS distinguishes them by FILENAME SUFFIX alone (`...-releaseEida.apk`); there is no field for it
 * and `role` is `distribution` for both.
 */
enum class Flavour(val displayName: String, val eida: Boolean) {
    NORMAL("FrontDesk", false),
    EIDA("FrontDesk Eida", true),
}

/**
 * Metadata for one release of the managed app.
 *
 * @param version marketing version (e.g. "2.30")
 * @param build   build number (e.g. "5374"), which is also the Android versionCode
 * @param sha256  expected SHA-256 of the APK, verified on download
 * @param downloadUrl a CloudFront-signed URL, valid ~24h from when the catalogue was fetched
 */
data class ReleaseMeta(
    val version: String,
    val build: String,
    val sha256: String?,
    val downloadUrl: String,
) {
    /** Combined "version.build" identifier, as ARCS names the release. */
    val versionBuild get() = "$version.$build"
}

/**
 * Discovers published builds of the managed kiosk app.
 *
 * Reads the ARCS downloads catalogue rather than the old public CDN at downloads.evtrack.com, which
 * stopped being updated when release publishing moved to ARCS and is frozen at 2.24.5366.
 *
 * One call returns the newest released build on every channel, so this resolves channels from a single
 * cached catalogue rather than fetching a pointer per flavour and channel.
 *
 * @param arcs override for tests / staging.
 */
class CdnClient(private val arcs: ArcsCatalogueClient = ArcsCatalogueClient()) {

    /**
     * Discover the latest published build of [flavour] on [channel], or `null` if that combination is
     * not published or the fetch fails.
     *
     * [channel] keeps the caller-facing names this app has always used: "latest" for the stable
     * channel, "beta" for beta.
     */
    suspend fun fetchLatest(flavour: Flavour, channel: String): ReleaseMeta? {
        if (!ArcsCredentials.isConfigured()) {
            Log.e(TAG, "No ARCS licence configured - set arcs.licenseJwt in local.properties")
            return null
        }
        return try {
            val arcsChannel = arcs.channels()[arcsChannelName(channel)] ?: return null
            val file = arcsChannel.files.firstOrNull { it.isEida == flavour.eida } ?: return null
            val (version, build) = splitVersion(arcsChannel.version) ?: return null
            ReleaseMeta(
                version = version,
                build = build,
                sha256 = file.sha256,
                downloadUrl = file.url,
            )
        } catch (e: ArcsException) {
            Log.w(TAG, "ARCS lookup failed for ${flavour.displayName}/$channel: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "ARCS lookup failed for ${flavour.displayName}/$channel", e)
            null
        }
    }

    /** Legacy convenience: latest of the [Flavour.NORMAL] flavour on [channel]. */
    suspend fun fetchLatest(channel: String): ReleaseMeta? = fetchLatest(Flavour.NORMAL, channel)

    /**
     * Resolve a PINNED build (exact version + build) of [flavour].
     *
     * The ARCS downloads endpoint publishes only the NEWEST released build per channel, and artefact
     * URLs are signed per response rather than being derivable from the version. So a pinned build can
     * only be resolved while it is still the head of a channel; anything older is no longer reachable.
     *
     * This is a real reduction from the old CDN, whose per-version folders made any published build
     * addressable forever. Pinning to something other than the current head needs an ARCS endpoint
     * that can serve a named version.
     */
    suspend fun fetchPinned(flavour: Flavour, version: String, build: String): ReleaseMeta {
        val wanted = "$version.$build"
        for (channel in listOf("latest", "beta")) {
            val meta = fetchLatest(flavour, channel) ?: continue
            if (meta.versionBuild == wanted) return meta
        }
        throw ArcsException(
            "ARCS cannot serve pinned build $wanted: it only publishes the newest release per " +
                "channel, and $wanted is not the current stable or beta build."
        )
    }

    /** Legacy convenience: pinned build of the [Flavour.NORMAL] flavour. */
    suspend fun fetchPinned(version: String, build: String): ReleaseMeta =
        fetchPinned(Flavour.NORMAL, version, build)

    /** Forces the next lookup to go to ARCS rather than the 5 minute cache. */
    fun invalidate() = arcs.invalidate()

    companion object {
        private const val TAG = "CdnClient"

        /** Package name of the app this manager installs/updates (same for every flavour). */
        const val MANAGED_PACKAGE = "com.evtrack.frontdesk"

        /** This app's channel names mapped onto the ones ARCS uses. */
        internal fun arcsChannelName(channel: String): String =
            if (channel.equals("beta", ignoreCase = true)) "beta" else "stable"

        /**
         * ARCS names a release "2.30.5374"; this app has always carried marketing version and build
         * number separately, the build being the Android versionCode. The build is the last component.
         */
        internal fun splitVersion(version: String): Pair<String, String>? {
            val cut = version.lastIndexOf('.')
            if (cut <= 0 || cut == version.length - 1) return null
            return version.substring(0, cut) to version.substring(cut + 1)
        }
    }
}
