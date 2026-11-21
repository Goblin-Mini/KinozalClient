package com.goblinmini.kinozalclient;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Server-signed Plus entitlement with a bounded offline grace period. */
final class PlusAccess {
    private static final String PREFS = "kinozal_settings";
    private static final String KEY_ACTIVE = "plus_entitlement_active";
    private static final String KEY_FEATURES = "plus_entitlement_features";
    private static final String KEY_CHECKED = "plus_entitlement_checked_at";
    private static final String KEY_EXPIRES = "plus_entitlement_expires_at";
    private static final long OFFLINE_GRACE_MS = 6L * 60L * 60L * 1000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private PlusAccess() { }

    static boolean hasFeature(Context context, String feature) {
        if (context == null || feature == null || feature.isEmpty()) return false;
        if (!NativeAppGuard.isTrusted(context)) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return false;
        long checkedAt = prefs.getLong(KEY_CHECKED, 0L);
        long now = System.currentTimeMillis();
        long expiresAt = prefs.getLong(KEY_EXPIRES, 0L);
        if (checkedAt <= 0L || now < checkedAt || now - checkedAt > OFFLINE_GRACE_MS
                || expiresAt <= now) return false;
        try {
            JSONArray features = new JSONArray(prefs.getString(KEY_FEATURES, "[]"));
            for (int i = 0; i < features.length(); i++) {
                if (feature.equals(features.optString(i))) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    static void refresh(Context context, Runnable onComplete) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        if (!NativeAppGuard.isTrusted(app)) {
            clearEntitlement(app);
            postCompletion(onComplete);
            return;
        }
        if (!CloudSyncManager.isActivated(app)) {
            clearEntitlement(app);
            postCompletion(onComplete);
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                JSONObject info = CloudSyncManager.licenseInfo(app);
                String compactGrant = info.optString("grant", "");
                String expectedDeviceId = info.optString("device_id", "");
                JSONObject verifiedGrant = PlusGrantVerifier.verify(app, compactGrant, expectedDeviceId);
                JSONArray verifiedFeatures = verifiedGrant.optJSONArray("verified_features");
                long expiresAt = verifiedGrant.optLong("exp", 0L) * 1000L;
                if (verifiedFeatures == null || expiresAt <= System.currentTimeMillis()) {
                    throw new SecurityException("invalid_plus_expiry");
                }
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean(KEY_ACTIVE, true)
                        .putString(KEY_FEATURES, verifiedFeatures.toString())
                        .putLong(KEY_CHECKED, System.currentTimeMillis())
                        .putLong(KEY_EXPIRES, expiresAt)
                        .apply();
            } catch (SecurityException invalidGrant) {
                // A bad signature, wrong device binding, wrong audience or expired
                // grant is a definitive failure, never an offline-grace condition.
                clearEntitlement(app);
            } catch (Exception error) {
                // A definitive server response that the device license no longer exists
                // must revoke cached Plus immediately. Offline grace is only for
                // transport/server availability failures.
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
                .putBoolean(KEY_ACTIVE, false)
                .putString(KEY_FEATURES, "[]")
                .putLong(KEY_CHECKED, System.currentTimeMillis())
                .putLong(KEY_EXPIRES, 0L)
                .apply();
    }

    private static void postCompletion(Runnable callback) {
        if (callback == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(callback);
    }
}
