package com.goblinmini.kinozalclient;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

final class UpdateManager {
    private static final String TAG = "KinozalUpdate";
    private static final String RELEASE_API =
            "https://api.github.com/repos/Goblin-Mini/KinozalClient/releases?per_page=30";
    private static final String UPDATE_PREFS = "kinozal_update_prefs";
    private static final String LAST_CHECK = "last_successful_check";
    private static final long CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private final Activity activity;

    UpdateManager(Activity activity) { this.activity = activity; }

    void check() { check(false); }

    void checkAutomatically() {
        long last = activity.getSharedPreferences(UPDATE_PREFS, Activity.MODE_PRIVATE)
                .getLong(LAST_CHECK, 0L);
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;
        check(true);
    }

    private void check(boolean automatic) {
        if (!automatic) {
            Toast.makeText(activity, "Проверяем обновления…", Toast.LENGTH_SHORT).show();
        }
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestMethod("GET");
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "KinozalClient-Android");
                int response = c.getResponseCode();
                if (response < 200 || response >= 300) {
                    Log.e(TAG, "GitHub release API returned HTTP " + response);
                    if (!automatic) showMessage("Не удалось проверить обновления (HTTP " + response + "). Попробуй позже.");
                    return;
                }

                JSONArray releases = new JSONArray(new String(readAll(c.getInputStream()), "UTF-8"));
                JSONObject release = null;
                int remoteCode = 0;
                String apkUrl = null;
                for (int r = 0; r < releases.length(); r++) {
                    JSONObject candidate = releases.optJSONObject(r);
                    if (candidate == null || candidate.optBoolean("draft", true)
                            || candidate.optBoolean("prerelease", false)) continue;
                    String tag = candidate.optString("tag_name", "");
                    java.util.regex.Matcher matcher = java.util.regex.Pattern
                            .compile("v?(\\\\d+)").matcher(tag);
                    if (!matcher.matches()) continue;
                    int candidateCode;
                    try { candidateCode = Integer.parseInt(matcher.group(1)); }
                    catch (Exception ignored) { continue; }
                    JSONArray candidateAssets = candidate.optJSONArray("assets");
                    String candidateApk = null;
                    if (candidateAssets != null) for (int i = 0; i < candidateAssets.length(); i++) {
                        JSONObject asset = candidateAssets.optJSONObject(i);
                        if (asset != null && "KinozalClient.apk".equals(asset.optString("name"))) {
                            candidateApk = asset.optString("browser_download_url");
                            break;
                        }
                    }
                    if (candidateApk != null && !candidateApk.isEmpty()
                            && candidateCode > remoteCode) {
                        remoteCode = candidateCode;
                        release = candidate;
                        apkUrl = candidateApk;
                    }
                }
                if (release == null || apkUrl == null) {
                    Log.e(TAG, "No published release with a valid APK was found");
                    if (!automatic) showMessage("Не найден опубликованный APK обновления.");
                    return;
                }
                activity.getSharedPreferences(UPDATE_PREFS, Activity.MODE_PRIVATE).edit()
                        .putLong(LAST_CHECK, System.currentTimeMillis()).apply();

                int localCode = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).versionCode;
                if (remoteCode <= 0) {
                    Log.e(TAG, "Invalid release tag: " + release.optString("tag_name"));
                    if (!automatic) showMessage("Не удалось определить версию обновления.");
                } else if (remoteCode <= localCode) {
                    if (!automatic) showMessage("Установлена последняя версия.");
                } else {
                    if (automatic) showMessage("Найдено обновление. Загружаем…");
                    downloadAndPrompt(apkUrl, automatic);
                }
            } catch (Exception e) {
                Log.e(TAG, "Update check failed", e);
                if (!automatic) showMessage("Не удалось проверить обновления. Проверь подключение к интернету и попробуй ещё раз.");
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    private void showMessage(String message) {
        activity.runOnUiThread(() ->
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
    }

    private void downloadAndPrompt(String apkUrl, boolean automatic) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "KinozalClient-Android");
        File apk = new File(activity.getCacheDir(), "KinozalClient-update.apk");
        try {
            int response = c.getResponseCode();
            if (response < 200 || response >= 300) {
                throw new Exception("Download returned HTTP " + response);
            }
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.getFD().sync();
            }
        } finally {
            c.disconnect();
        }
        if (!apk.isFile() || apk.length() < 100000) {
            throw new Exception("Downloaded APK is incomplete");
        }
        activity.runOnUiThread(() -> {
            try {
                if (Build.VERSION.SDK_INT >= 26
                        && !activity.getPackageManager().canRequestPackageInstalls()) {
                    Toast.makeText(activity, "Разреши установку приложений для Kinozal Client.", Toast.LENGTH_LONG).show();
                    Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(settings);
                    return;
                }
                Uri uri = Uri.parse("content://" + activity.getPackageName()
                        + ".torrentprovider/update.apk");
                Intent install = new Intent(Intent.ACTION_VIEW);
                install.setDataAndType(uri, "application/vnd.android.package-archive");
                install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(install);
            } catch (Exception e) {
                Log.e(TAG, "Cannot launch APK installer", e);
                Toast.makeText(activity, "Не удалось открыть установщик. Проверь разрешение на установку APK.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream input = in; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = input.read(b)) != -1) out.write(b, 0, n);
            return out.toByteArray();
        }
    }
}
