package com.evtrack.kioskmanager.cdn.arcs;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Mints the {@code X-Arcs-Proof} JWT that ARCS requires alongside the licence.
 *
 * <p>Deliberately written against nothing but {@code java.security} and {@code java.util.Base64} - no
 * jjwt, no Nimbus, no Spring. Copy this file into the Kiosk Manager's Android module and it compiles
 * as-is; pulling a JWT library onto Android to emit one small signed token is not worth the method
 * count. (The project's tests DO use jjwt, at the same 0.12.7 ARCS runs, to prove the output verifies.)</p>
 *
 * <p>Every field below is checked by ARCS's {@code SystemAuthService#verifyProof}. Get one wrong and the
 * response is a bare {@code 401 proof_rejected} that says nothing about which one, so they are documented
 * individually.</p>
 */
public class ArcsProofSigner {

    /** The header ARCS reads the proof from ({@code SystemAuthFilter.PROOF_HEADER}). */
    public static final String PROOF_HEADER = "X-Arcs-Proof";

    /** ARCS: {@code arcs.system-auth.proof-max-age-seconds=300}. Proof lifetime must be within this. */
    private static final int PROOF_LIFETIME_SECONDS = 120;

    private final String sid;
    private final String privateKeyPkcs8Base64;

    /**
     * @param sid                   the {@code sid} claim from the licence - ARCS requires the proof's
     *                              {@code sid} to equal the licence's
     * @param privateKeyPkcs8Base64 the licence's {@code spr} claim verbatim: base64 PKCS#8 DER, no PEM
     */
    public ArcsProofSigner(String sid, String privateKeyPkcs8Base64) {
        this.sid = sid;
        this.privateKeyPkcs8Base64 = privateKeyPkcs8Base64;
    }

    /**
     * A fresh proof bound to one request. Call it per request - never cache the result. The proof is
     * valid for {@value #PROOF_LIFETIME_SECONDS} seconds and is bound to this exact method and path.
     *
     * @param httpMethod e.g. {@code GET} - becomes the {@code htm} claim
     * @param path       the request path ALONE, e.g. {@code /api/v1/system/downloads/evtrack-frontdesk}
     *                   - becomes {@code htu}. ARCS compares it against
     *                   {@code HttpServletRequest#getRequestURI()}, so a scheme/host or query string
     *                   here can never match.
     */
    public String sign(String httpMethod, String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException(
                    "htu must be the request path only (e.g. /api/v1/system/downloads/evtrack-frontdesk), "
                            + "not a full URL - ARCS compares it against HttpServletRequest#getRequestURI(). "
                            + "Got: " + path);
        }
        long issuedAt = Instant.now().getEpochSecond();
        long expiresAt = issuedAt + PROOF_LIFETIME_SECONDS;

        String header = "{\"alg\":\"ES256\",\"typ\":\"JWT\"}";
        // Hand-built rather than Jackson-serialised so this class stays dependency-free. Every value is
        // either a UUID, a long, or caller-supplied; the two caller-supplied ones are escaped below.
        String payload = "{"
                + "\"sid\":\"" + escape(sid) + "\","
                + "\"htm\":\"" + escape(httpMethod) + "\","
                + "\"htu\":\"" + escape(path) + "\","
                // Required and must be non-blank. ARCS keeps no replay cache in phase 1 and so never
                // compares it against anything, but a proof carrying no jti at all is rejected outright.
                + "\"jti\":\"" + UUID.randomUUID() + "\","
                + "\"iat\":" + issuedAt + ","
                + "\"exp\":" + expiresAt
                + "}";

        String signingInput = base64Url(header.getBytes(StandardCharsets.UTF_8))
                + "." + base64Url(payload.getBytes(StandardCharsets.UTF_8));

        byte[] joseSignature = derToJose(signRaw(signingInput));
        return signingInput + "." + base64Url(joseSignature);
    }

    private byte[] signRaw(String signingInput) {
        try {
            byte[] pkcs8 = Base64.getDecoder().decode(privateKeyPkcs8Base64);
            PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign the ARCS proof - check the spr claim is the "
                    + "base64 PKCS#8 private key copied verbatim from the licence", e);
        }
    }

    /**
     * Converts the JDK's ASN.1 DER ECDSA signature into the fixed-width {@code R || S} pair JOSE ES256
     * requires (RFC 7518 section 3.4).
     *
     * <p><b>This is the step everyone misses.</b> {@code SHA256withECDSA} produces a DER SEQUENCE of two
     * INTEGERs whose length varies with the values and which carries sign-padding bytes. Sent as-is it
     * still parses as a JWT - it just never verifies, and ARCS reports only {@code proof_rejected}. For
     * P-256 the correct output is always exactly 64 bytes: R left-padded to 32, then S left-padded to
     * 32.</p>
     */
    static byte[] derToJose(byte[] der) {
        int offset = 3;                     // 0x30, total-length, 0x02
        int rLength = der[offset] & 0xFF;
        offset++;
        BigInteger r = new BigInteger(1, der, offset, rLength);
        offset += rLength + 1;              // skip R, then the 0x02 tag of S
        int sLength = der[offset] & 0xFF;
        offset++;
        BigInteger s = new BigInteger(1, der, offset, sLength);

        byte[] jose = new byte[64];
        writeFixedWidth(r, jose, 0);
        writeFixedWidth(s, jose, 32);
        return jose;
    }

    /** Left-pads a positive BigInteger into 32 bytes, dropping BigInteger's sign byte if present. */
    private static void writeFixedWidth(BigInteger value, byte[] target, int targetOffset) {
        byte[] bytes = value.toByteArray();
        int sourceOffset = Math.max(0, bytes.length - 32);
        int length = bytes.length - sourceOffset;
        System.arraycopy(bytes, sourceOffset, target, targetOffset + (32 - length), length);
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
