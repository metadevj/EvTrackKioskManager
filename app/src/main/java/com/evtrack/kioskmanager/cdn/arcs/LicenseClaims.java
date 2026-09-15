package com.evtrack.kioskmanager.cdn.arcs;

import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * The three claims this app needs out of the ARCS licence: the system id it authenticates as, and the
 * ES256 key pair ARCS embedded for exactly this purpose.
 *
 * <p>Ported from the EvTrackArcsDownloadsClient reference, with Jackson swapped for org.json (already
 * on the classpath here) and android.util.Base64 for the URL-safe decode.</p>
 *
 * <p><b>The licence is not verified here, and that is correct.</b> ARCS signed it and ARCS verifies it
 * on every call; this is simply reading three values out of a token it was handed. Verifying it locally
 * would mean shipping ARCS's public signing key to every kiosk for no security gain - a client cannot
 * be the judge of its own credential.</p>
 *
 * <p><b>{@code spr} is a private key.</b> It travels inside the licence, which means the licence JWT
 * <em>is</em> the credential, not a token that references one. Anything that can read it can
 * authenticate as this system. Never log it, and never paste the licence into an online JWT decoder.</p>
 */
public final class LicenseClaims {

    private final String sid;
    private final String spr;
    private final String spk;

    private LicenseClaims(String sid, String spr, String spk) {
        this.sid = sid;
        this.spr = spr;
        this.spk = spk;
    }

    public String sid() {
        return sid;
    }

    public String spr() {
        return spr;
    }

    public String spk() {
        return spk;
    }

    public static LicenseClaims parse(String licenseJwt) {
        if (licenseJwt == null || licenseJwt.trim().isEmpty()) {
            throw new IllegalArgumentException("No ARCS licence supplied - set arcs.licenseJwt in "
                    + "local.properties (see ArcsCredentials)");
        }
        String[] parts = licenseJwt.trim().split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("That is not a JWT (expected three dot-separated parts, "
                    + "got " + parts.length + "). Use the licence token itself, not the file it was "
                    + "delivered in.");
        }

        JSONObject payload;
        try {
            byte[] decoded = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            payload = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not decode the licence payload", e);
        }

        String sid = text(payload, "sid");
        if (sid == null) {
            throw new IllegalArgumentException("Licence carries no 'sid' claim - it cannot identify a "
                    + "system to ARCS");
        }
        String spr = text(payload, "spr");
        if (spr == null) {
            throw new IllegalArgumentException("Licence carries no 'spr' claim, so there is no private "
                    + "key to sign the proof with. Re-issue the licence from ARCS.");
        }
        String spk = text(payload, "spk");
        if (spk == null) {
            // Fail here rather than in the field. Without spk, ARCS falls back to the SHARED transitional
            // default key, which authenticates fine today and stops working the moment that flag is
            // turned off - taking the whole kiosk fleet out at once with 401 stale_credential.
            throw new IllegalArgumentException("Licence carries no 'spk' claim. ARCS would authenticate "
                    + "it against the shared transitional default key, which is being retired - re-issue "
                    + "the licence so it carries the system's own credential.");
        }
        return new LicenseClaims(sid, spr, spk);
    }

    private static String text(JSONObject node, String field) {
        String value = node.optString(field, null);
        return value == null || value.trim().isEmpty() ? null : value;
    }

    /** Never includes {@link #spr()} - see the class javadoc. */
    @Override
    public String toString() {
        return "LicenseClaims{sid='" + sid + "', spk='" + abbreviate(spk) + "', spr=[REDACTED]}";
    }

    private static String abbreviate(String value) {
        return value == null || value.length() <= 12 ? "***" : value.substring(0, 12) + "...";
    }
}
