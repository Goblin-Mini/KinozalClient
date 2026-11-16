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
    private static final long OFFLINE_GRACE_MS = 48L * 60L * 60L * 1000L;
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

    static void refresh(Context context) {
        if (context == null || !CloudSyncManager.isActivated(context)) return;
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                JSONObject info = CloudSyncManager.licenseInfo(app);
                String status = info.optString("status", "");
                String plan = info.optString("plan", "");
                boolean active = "active".equalsIgnoreCase(status)
                        && ("plus_lifetime".equalsIgnoreCase(plan)
                        || "owner".equalsIgnoreCase(plan));
                JSONArray features = new JSONArray();
                if (active) {
                    // The license endpoint may be deployed without the feature list.
                    // An active lifetime/owner license grants the app's complete Plus feature set.
                    String[] knownFeatures = {
                            "advanced_search", "favorites", "viewing_history", "backup",
                            "screenshots", "comments", "voice_search", "proxy",
                            "android_tv_channels", "similar", "torrent_download"
                    };
                    JSONArray serverFeatures = info.optJSONArray("features");
                    if (serverFeatures != null && serverFeatures.length() > 0) {
                        for (int i = 0; i < serverFeatures.length(); i++) {
                            String feature = serverFeatures.optString(i, "");
                            if (!feature.isEmpty() && !features.toString().contains("\"" + feature + "\"")) {
                                features.put(feature);
                            }
                        }
                    } else {
                        for (String feature : knownFeatures) features.put(feature);
                    }
                }
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean(KEY_ACTIVE, active)
                        .putString(KEY_FEATURES, features.toString())
                        .putLong(KEY_CHECKED, System.currentTimeMillis())
                        .apply();
            } catch (Exception ignored) {
                // Keep the last server-verified entitlement only within the grace window.
            }
        });
    }
}
