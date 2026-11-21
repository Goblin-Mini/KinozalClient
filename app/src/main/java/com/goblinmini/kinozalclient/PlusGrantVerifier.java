package com.goblinmini.kinozalclient;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/** Verifies short-lived RS256 Plus grants issued by the license Worker. */
final class PlusGrantVerifier {
    private static final String PUBLIC_KEY_DER_BASE64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAo0tCtUSow6g+uR7xeb9nPDr6DWqEfSjnySdhejl6OrjIJxwk4ZWHw91FOCjJEEDAxv3FuTaqkL6MX8GCROs+GHdO8y1BKtNQT6BsR00MDLpntZcWGuS0YlOYOgD2VQ5/bxz6fEse2suc9ueoCcn5igAJAyb6quFtN2lB5R4msp8q6lQspKoKESlQ/pqstJAdtBzy8UKqE23Ynm4MMJCyzCD9JRyKH1gaPMNwJqi2HRqQXcuaWVaeW6j5ddElvq0q+lH7+I8dwLZwP6I7a3rmzB80apZGRT5LplFmNa/E3xtJCh8nY0gt3Mf9eYtlbsTAyiN+Ynns/dsOUR8J0COx0wIDAQAB";
    private static final long MAX_GRANT_LIFETIME_SECONDS = 6L * 60L * 60L;
    private static final long CLOCK_SKEW_SECONDS = 5L * 60L;

    private PlusGrantVerifier() { }

    static JSONObject verify(Context context, String compactGrant) throws Exception {
        try {
        if (context == null || compactGrant == null) throw new SecurityException("missing_plus_grant");
        String[] parts = compactGrant.split("\\.", -1);
        if (parts.length != 3) throw new SecurityException("malformed_plus_grant");

        byte[] headerBytes = Base64.decode(parts[0], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        byte[] payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        byte[] signatureBytes = Base64.decode(parts[2], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        JSONObject header = new JSONObject(new String(headerBytes, StandardCharsets.UTF_8));
        if (!"RS256".equals(header.optString("alg")) ||
                !"JWT".equals(header.optString("typ")) ||
                !"plus-rsa-2026-01".equals(header.optString("kid"))) {
            throw new SecurityException("unsupported_plus_grant");
        }

        byte[] publicKeyBytes = Base64.decode(PUBLIC_KEY_DER_BASE64, Base64.DEFAULT);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(publicKeyBytes)));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!verifier.verify(signatureBytes)) throw new SecurityException("invalid_plus_signature");

        JSONObject claims = new JSONObject(new String(payloadBytes, StandardCharsets.UTF_8));
        if (!"kinozal-tv-license".equals(claims.optString("iss")) ||
                !"ru.gm.ktv".equals(claims.optString("aud")) ||
                !"active".equalsIgnoreCase(claims.optString("status"))) {
            throw new SecurityException("invalid_plus_claims");
        }
        String plan = claims.optString("plan", "");
        if (!"plus_lifetime".equalsIgnoreCase(plan) && !"owner".equalsIgnoreCase(plan)) {
            throw new SecurityException("invalid_plus_plan");
        }
        if (claims.optString("sub", "").isEmpty()) throw new SecurityException("missing_device_binding");

        long issuedAt = claims.optLong("iat", 0L);
        long expiresAt = claims.optLong("exp", 0L);
        long now = System.currentTimeMillis() / 1000L;
        if (issuedAt <= 0L || expiresAt <= issuedAt ||
                issuedAt > now + CLOCK_SKEW_SECONDS ||
                expiresAt <= now ||
                expiresAt - issuedAt > MAX_GRANT_LIFETIME_SECONDS) {
            throw new SecurityException("expired_or_invalid_plus_grant");
        }

        String localInstallationId = CloudSyncManager.installationId(context);
        String expectedBinding = sha256(localInstallationId);
        if (!constantTimeEquals(expectedBinding, claims.optString("iid", ""))) {
            throw new SecurityException("plus_device_binding_mismatch");
        }

        JSONArray supplied = claims.optJSONArray("features");
        if (supplied == null) throw new SecurityException("missing_plus_features");
        String[] supportedFeatures = {
                "advanced_search", "favorites", "viewing_history", "backup",
                "screenshots", "comments", "voice_search", "proxy",
                "android_tv_channels", "similar", "torrent_download"
        };
        java.util.HashSet<String> supported =
                new java.util.HashSet<>(java.util.Arrays.asList(supportedFeatures));
        java.util.HashSet<String> granted = new java.util.HashSet<>();
        JSONArray verifiedFeatures = new JSONArray();
        for (int i = 0; i < supplied.length(); i++) {
            String feature = supplied.optString(i, "");
            if (supported.contains(feature) && granted.add(feature)) verifiedFeatures.put(feature);
        }
        if (verifiedFeatures.length() == 0) throw new SecurityException("empty_plus_features");
        claims.put("verified_features", verifiedFeatures);
        return claims;
        } catch (SecurityException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new SecurityException("plus_grant_verification_failed", invalid);
        }
    }
    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) result.append(String.format(java.util.Locale.US, "%02x", item & 0xff));
        return result.toString();
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null || left.length() != right.length()) return false;
        int difference = 0;
        for (int i = 0; i < left.length(); i++) difference |= left.charAt(i) ^ right.charAt(i);
        return difference == 0;
    }
}
