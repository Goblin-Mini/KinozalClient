package com.goblinmini.kinozalclient;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Server-verified Plus entitlement with a bounded offline grace period. */
final class PlusAccess {
    private static final String PREFS = "kinozal_settings";
    private static final String KEY_ACTIVE = "plus_entitlement_active";
    private static final String KEY_FEATURES = "plus_entitlement_features";
    private static final String KEY_CHECKED = "plus_entitlement_checked_at";
    // A revoked device should lose cached Plus access promptly when offline.
    private static final long OFFLINE_GRACE_MS = 6L * 60L * 60L * 1000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private PlusAccess() { }

    static boolean hasFeature(Context context, String feature) {
        if (context == null || feature == null || feature.isEmpty()) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return false;
        long checkedAt = prefs.getLong(KEY_CHECKED, 0L);
        if (checkedAt <= 0L || System.currentTimeMillis() - checkedAt > OFFLINE_GRACE_MS) return false;
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
        if (!CloudSyncManager.isActivated(app)) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_ACTIVE, false)
                    .putString(KEY_FEATURES, "[]")
                    .putLong(KEY_CHECKED, System.currentTimeMillis())
                    .apply();
            postCompletion(onComplete);
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                JSONObject info = CloudSyncManager.licenseInfo(app);
                String status = info.optString("status", "");
                String plan = info.optString("plan", "");
                boolean active = "active".equalsIgnoreCase(status)
                        && ("plus_lifetime".equalsIgnoreCase(plan)
                        || "owner".equalsIgnoreCase(plan));
                // The server is the authority for the entitlement list.
                // Never manufacture Plus permissions locally from the plan name.
                JSONArray features = new JSONArray();
                JSONArray serverFeatures = info.optJSONArray("features");
                if (active && serverFeatures != null) {
                    String[] supportedFeatures = {
                            "advanced_search", "favorites", "viewing_history", "backup",
                            "screenshots", "comments", "voice_search", "proxy",
                            "android_tv_channels", "similar", "torrent_download",
                    };
                    java.util.HashSet<String> supported =
                            new java.util.HashSet<>(java.util.Arrays.asList(supportedFeatures));
                    for (int i = 0; i < serverFeatures.length(); i++) {
                        String feature = serverFeatures.optString(i, "");
                        if (supported.contains(feature) && !features.toString().contains(
                                "\\"" + feature + "\\"")) {
                            features.put(feature);
                        }
                    }
                    // A valid Plus response with no recognized permissions is not an entitlement.
                    active = features.length() > 0;
                } else {
                    active = false;
                }
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean(KEY_ACTIVE, active)
                        .putString(KEY_FEATURES, features.toString())
                        .putLong(KEY_CHECKED, System.currentTimeMillis())
                        .apply();
            } catch (Exception error) {
                // A definitive server response that the device license no longer exists
                // must revoke cached Plus immediately. Offline grace is only for transport
                // failures (timeouts, DNS, connection errors), never for HTTP 401/404.
                String message = error.getMessage();
                if (message != null && (message.contains("license_not_found")
                        || message.contains("unauthorized")
                        || message.contains("invalid_or_expired_code"))) {
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putBoolean(KEY_ACTIVE, false)
                            .putString(KEY_FEATURES, "[]")
                            .putLong(KEY_CHECKED, System.currentTimeMillis())
                            .apply();
                }
            } finally {
                postCompletion(onComplete);
            }
        });
    }

    private static void postCompletion(Runnable callback) {
        if (callback == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(callback);
    }
}
