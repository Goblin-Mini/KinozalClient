package com.goblinmini.kinozalclient;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cloud synchronization for favorites and recently viewed entries.
 * Local preferences remain authoritative while offline; per-item timestamps
 * and tombstones make removals propagate to devices that reconnect later.
 */
public final class CloudSyncManager {
    private static final String PREFS = "kinozal_settings";
    private static final String META = "cloud_sync_meta";
    private static final String FAVORITES = "local_favorites";
    private static final String HISTORY = "recently_viewed";
    private static final int TIMEOUT_MS = 15000;

    private CloudSyncManager() { }

    public static void configure(Context context, String endpoint) {
        prefs(context).edit().putString("cloud_sync_endpoint", normalizeEndpoint(endpoint)).apply();
    }

    public static String endpoint(Context context) {
        return prefs(context).getString("cloud_sync_endpoint", "");
    }

    public static boolean isActivated(Context context) {
        return !prefs(context).getString("cloud_sync_token", "").isEmpty();
    }

    public static String status(Context context) {
        return prefs(context).getString("cloud_sync_status", "Не настроена");
    }

    public static void activate(Context context, String code) throws Exception {
        SharedPreferences p = prefs(context);
        String base = endpoint(context);
        if (base.isEmpty()) throw new IllegalArgumentException("Сначала укажите адрес сервера синхронизации");
        String installId = p.getString("cloud_sync_installation_id", "");
        if (installId.isEmpty()) {
            installId = UUID.randomUUID().toString().replace("-", "");
            p.edit().putString("cloud_sync_installation_id", installId).apply();
        }
        JSONObject request = new JSONObject();
        request.put("code", code);
        request.put("installation_id", installId);
        request.put("device_label", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        JSONObject result = request(base + "/v1/devices/claim", "POST", null, request);
        String token = result.optString("device_token", "");
        if (token.isEmpty()) throw new IllegalStateException("Сервер не выдал токен устройства");
        p.edit().putString("cloud_sync_token", token)
                .putString("cloud_sync_status", "Устройство активировано")
                .putLong("cloud_sync_last_success", System.currentTimeMillis()).apply();
    }

    public static void sync(Context context) throws Exception {
        SharedPreferences p = prefs(context);
        String base = endpoint(context);
        String token = p.getString("cloud_sync_token", "");
        if (base.isEmpty() || token.isEmpty()) throw new IllegalStateException("Синхронизация не активирована");
        JSONObject local = readLocalSnapshot(p);
        JSONArray outgoing = buildOutgoing(p, local);
        JSONObject pushed = request(base + "/v1/sync", "PUT", token, new JSONObject().put("items", outgoing));
        applyServerSnapshot(p, pushed.optJSONArray("items"));
        p.edit().putString("cloud_sync_status", "Синхронизировано")
                .putLong("cloud_sync_last_success", System.currentTimeMillis()).apply();
    }

    private static JSONObject readLocalSnapshot(SharedPreferences p) throws Exception {
        JSONObject result = new JSONObject();
        result.put("favorite", new JSONArray(p.getString(FAVORITES, "[]")));
        result.put("history", new JSONArray(p.getString(HISTORY, "[]")));
        return result;
    }

    private static JSONArray buildOutgoing(SharedPreferences p, JSONObject local) throws Exception {
        JSONObject meta = readMeta(p);
        long now = System.currentTimeMillis();
        JSONArray outgoing = new JSONArray();
        addLocalItems(outgoing, "favorite", local.optJSONArray("favorite"), meta, now);
        addLocalItems(outgoing, "history", local.optJSONArray("history"), meta, now);
        return outgoing;
    }

    private static void addLocalItems(JSONArray out, String type, JSONArray values,
                                      JSONObject meta, long now) throws Exception {
        JSONObject section = meta.optJSONObject(type);
        if (section == null) section = new JSONObject();
        Set<String> current = new HashSet<>();
        if (values != null) for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i);
            if (item == null) continue;
            String key = item.optString("href", "");
            if (key.isEmpty()) continue;
            current.add(key);
            JSONObject payloads = meta.optJSONObject(type + "_payloads");
            if (payloads == null) payloads = new JSONObject();
            String serialized = item.toString();
            long timestamp = section.optLong(key, now);
            boolean changed = !section.has(key)
                    || !serialized.equals(payloads.optString(key, ""));
            if (changed) {
                timestamp = now;
                section.put(key, timestamp);
                payloads.put(key, serialized);
                out.put(new JSONObject().put("type", type).put("key", key)
                        .put("data", item).put("deleted", false).put("updated_at", timestamp));
            }
            meta.put(type + "_payloads", payloads);
        }
        JSONArray known = meta.optJSONArray(type + "_known");
        if (known != null) for (int i = 0; i < known.length(); i++) {
            String key = known.optString(i, "");
            if (!key.isEmpty() && !current.contains(key)) {
                long timestamp = now;
                section.put(key, timestamp);
                JSONObject payloads = meta.optJSONObject(type + "_payloads");
                if (payloads != null) payloads.remove(key);
                out.put(new JSONObject().put("type", type).put("key", key)
                        .put("data", new JSONObject()).put("deleted", true).put("updated_at", timestamp));
            }
        }
        meta.put(type, section);
        JSONArray keys = new JSONArray();
        for (String key : current) keys.put(key);
        meta.put(type + "_known", keys);
    }

    private static void applyServerSnapshot(SharedPreferences p, JSONArray items) throws Exception {
        if (items == null) return;
        JSONObject meta = readMeta(p);
        Map<String, JSONObject> favorites = new LinkedHashMap<>();
        Map<String, JSONObject> history = new LinkedHashMap<>();
        JSONArray localFavorites = new JSONArray(p.getString(FAVORITES, "[]"));
        JSONArray localHistory = new JSONArray(p.getString(HISTORY, "[]"));
        indexLocal(localFavorites, favorites);
        indexLocal(localHistory, history);

        JSONObject favoriteMeta = meta.optJSONObject("favorite");
        if (favoriteMeta == null) favoriteMeta = new JSONObject();
        JSONObject historyMeta = meta.optJSONObject("history");
        if (historyMeta == null) historyMeta = new JSONObject();

        for (int i = 0; i < items.length(); i++) {
            JSONObject remote = items.optJSONObject(i);
            if (remote == null) continue;
            String type = remote.optString("type", "");
            String key = remote.optString("key", "");
            if (key.isEmpty() || (!"favorite".equals(type) && !"history".equals(type))) continue;
            Map<String, JSONObject> target = "favorite".equals(type) ? favorites : history;
            JSONObject timestamps = "favorite".equals(type) ? favoriteMeta : historyMeta;
            long remoteTime = remote.optLong("updated_at", 0);
            long localTime = timestamps.optLong(key, 0);
            if (remoteTime < localTime) continue;
            timestamps.put(key, remoteTime);
            if (remote.optBoolean("deleted", false)) {
                target.remove(key);
                JSONObject payloads = meta.optJSONObject(type + "_payloads");
                if (payloads != null) payloads.remove(key);
            } else {
                JSONObject data = remote.optJSONObject("data");
                if (data != null) {
                    target.put(key, data);
                    JSONObject payloads = meta.optJSONObject(type + "_payloads");
                    if (payloads == null) payloads = new JSONObject();
                    payloads.put(key, data.toString());
                    meta.put(type + "_payloads", payloads);
                }
            }
        }

        SharedPreferences.Editor editor = p.edit();
        editor.putString(FAVORITES, valuesJson(favorites, false));
        editor.putString(HISTORY, valuesJson(history, true));
        meta.put("favorite", favoriteMeta);
        meta.put("history", historyMeta);
        meta.put("favorite_known", keysJson(favorites));
        meta.put("history_known", keysJson(history));
        editor.putString(META, meta.toString());
        editor.apply();
    }

    private static void indexLocal(JSONArray array, Map<String, JSONObject> target) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String href = item.optString("href", "");
            if (!href.isEmpty()) target.put(href, item);
        }
    }

    private static String valuesJson(Map<String, JSONObject> values, boolean newestFirst) throws Exception {
        JSONArray result = new JSONArray();
        java.util.ArrayList<JSONObject> ordered = new java.util.ArrayList<>(values.values());
        if (newestFirst) java.util.Collections.reverse(ordered);
        int start = newestFirst ? Math.max(0, ordered.size() - 30) : 0;
        for (int i = start; i < ordered.size(); i++) result.put(ordered.get(i));
        return result.toString();
    }

    private static JSONArray keysJson(Map<String, JSONObject> values) {
        JSONArray result = new JSONArray();
        for (String key : values.keySet()) result.put(key);
        return result;
    }

    private static JSONObject readMeta(SharedPreferences p) {
        try { return new JSONObject(p.getString(META, "{}")); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String normalizeEndpoint(String endpoint) {
        if (endpoint == null) return "";
        return endpoint.trim().replaceAll("/+$", "");
    }

    private static JSONObject request(String url, String method, String token, JSONObject body)
            throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int code = connection.getResponseCode();
            InputStream input = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder response = new StringBuilder();
            if (input != null) try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
            }
            JSONObject parsed = response.length() == 0 ? new JSONObject() : new JSONObject(response.toString());
            if (code < 200 || code >= 300) {
                String error = parsed.optString("error", "HTTP " + code);
                throw new java.io.IOException(error);
            }
            return parsed;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
