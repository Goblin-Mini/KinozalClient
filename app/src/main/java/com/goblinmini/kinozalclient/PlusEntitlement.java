package com.goblinmini.kinozalclient;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Short-lived cache of server-verified Plus entitlements. */
public final class PlusEntitlement {
    private static final String PREFS = "kinozal_settings";
    private static final String CACHE = "plus_entitlement_cache";
    private static final String CHECKED_AT = "plus_entitlement_checked_at";
    private static final long MAX_AGE_MS = 5L * 60L * 1000L;

    private PlusEntitlement() { }

    public static void store(Context context, JSONObject license) {
        if (context == null || license == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(CACHE, license.toString())
                .putLong(CHECKED_AT, System.currentTimeMillis()).apply();
    }

    public static boolean hasFeature(Context context, String feature) {
        if (context == null || feature == null || feature.isEmpty()) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long checkedAt = prefs.getLong(CHECKED_AT, 0L);
        if (checkedAt <= 0L || System.currentTimeMillis() - checkedAt > MAX_AGE_MS) return false;
        try {
            JSONObject license = new JSONObject(prefs.getString(CACHE, "{}"));
            if (!"active".equalsIgnoreCase(license.optString("status", ""))) return false;
            JSONArray features = license.optJSONArray("features");
            if (features == null) return false;
            for (int i = 0; i < features.length(); i++) {
                if (feature.equals(features.optString(i))) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    public static void clear(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(CACHE).remove(CHECKED_AT).apply();
    }
}
