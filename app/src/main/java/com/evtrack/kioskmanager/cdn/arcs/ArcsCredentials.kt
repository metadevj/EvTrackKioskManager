package com.evtrack.kioskmanager.cdn.arcs

import com.evtrack.kioskmanager.BuildConfig

/**
 * Where the ARCS credential and endpoint live.
 *
 * The licence JWT is NOT held in source. It is read from `arcs.licenseJwt` in `local.properties`
 * (gitignored) and baked into BuildConfig at build time, because the licence carries a private key
 * in its `spr` claim: the licence *is* the credential, not a token pointing at one. Anything that
 * can read it can authenticate to ARCS as this system from anywhere.
 *
 * Note that it still ends up inside the built APK, where a determined reader can recover it. That is
 * inherent to the interim design - every kiosk carries the same fleet licence, so there is no
 * per-device revocation and deactivating the system stops the whole fleet at once. Keeping it out of
 * git limits the blast radius; it does not make the credential secret from the device.
 */
object ArcsCredentials {

    /** The fleet licence JWT, supplied at build time. Blank means the build was never configured. */
    val licenseJwt: String = BuildConfig.ARCS_LICENSE_JWT

    /** ARCS base URL. No trailing slash. */
    const val BASE_URL: String = "https://arcs.evtrack.com"

    /**
     * Registry product name. `evtrack-frontdesk` carries both APK variants in each release, the plain
     * build and the eIDA one. Must be allowlisted in ARCS; anything else returns 404 by design, so the
     * endpoint cannot be used to probe the registry.
     */
    const val PRODUCT: String = "evtrack-frontdesk"

    /** True when a licence was supplied at build time. */
    fun isConfigured(): Boolean = licenseJwt.isNotBlank()
}
