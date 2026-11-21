package com.goblinmini.kinozalclient;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client-side Plus gate. Local preferences contain only the signed grant;
 * entitlement decisions are made from claims that pass RSA verification.
 */
final class PlusAccess {
    private static final String PREFS = "kinozal_settings";
    private static final String KEY_SIGNED_GRANT = "plus_signed_grant";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private PlusAccess() { }

    static boolean hasFeature(Context context, String feature) {
        if (context == null || feature == null || feature.isEmpty()) return false;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String compactGrant = prefs.getString(KEY_SIGNED_GRANT, "");
            if (compactGrant == null || compactGrant.isEmpty()) return false;

            // Never trust editable booleans or feature arrays from preferences.
            // Every access decision is derived from a freshly verified RS256 grant.
            JSONObject verifiedGrant = PlusGrantVerifier.verify(
                    context.getApplicationContext(), compactGrant);
            long expiresAt = verifiedGrant.optLong("exp", 0L);
            long now = System.currentTimeMillis() / 1000L;
            if (expiresAt <= now) return false;

            JSONArray features = verifiedGrant.optJSONArray("verified_features");
            if (features == null) return false;
            for (int i = 0; i < features.length(); i++) {
                if (feature.equals(features.optString(i))) return true;
            }
        } catch (Exception invalidOrUnavailableGrant) {
            // Fail closed: an unreadable, altered, expired or incorrectly bound
            // grant must never enable a Plus feature.
        }
        return false;
    }

    static void refresh(Context context, Runnable onComplete) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        if (!CloudSyncManager.isActivated(app)) {
            clearEntitlement(app);
            postCompletion(onComplete);
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                JSONObject info = CloudSyncManager.licenseInfo(app);
                String compactGrant = info.optString("grant", "");
                JSONObject verifiedGrant = PlusGrantVerifier.verify(app, compactGrant);
                JSONArray verifiedFeatures = verifiedGrant.optJSONArray("verified_features");
                long expiresAt = verifiedGrant.optLong("exp", 0L) * 1000L;
                if (verifiedFeatures == null || verifiedFeatures.length() == 0
                        || expiresAt <= System.currentTimeMillis()) {
                    throw new SecurityException("invalid_plus_expiry");
                }

                // Store the original signed token, not locally editable entitlement claims.
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_SIGNED_GRANT, compactGrant)
                        .remove("plus_entitlement_active")
                        .remove("plus_entitlement_features")
                        .remove("plus_entitlement_checked_at")
                        .remove("plus_entitlement_expires_at")
                        .apply();
            } catch (SecurityException invalidGrant) {
                clearEntitlement(app);
            } catch (Exception error) {
                // Preserve a previously verified, unexpired signed grant during
                // temporary transport/server failures. Its signed exp remains authoritative.
                String message = error.getMessage();
                if (message != null && (message.contains("license_not_found")
                        || message.contains("unauthorized")
                        || message.contains("invalid_or_expired_code")
                        || message.contains("plus_signing_not_configured"))) {
                    clearEntitlement(app);
                }
            } finally {
                postCompletion(onComplete);
            }
        });
    }

    private static void clearEntitlement(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_SIGNED_GRANT)
                .putBoolean("plus_entitlement_active", false)
                .putString("plus_entitlement_features", "[]")
                .putLong("plus_entitlement_checked_at", System.currentTimeMillis())
                .putLong("plus_entitlement_expires_at", 0L)
                .apply();
    }

    private static void postCompletion(Runnable callback) {
        if (callback == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(callback);
    }
}
