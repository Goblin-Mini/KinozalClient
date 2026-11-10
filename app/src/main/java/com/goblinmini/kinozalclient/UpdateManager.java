package com.goblinmini.kinozalclient;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
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
            "https://api.github.com/repos/Goblin-Mini/KinozalClient/releases/latest";
    private static final String UPDATE_PREFS = "kinozal_update_prefs";
    private static final String LAST_AUTO_CHECK = "last_automatic_check";
    private static final String AUTO_CHECK = "automatic_check_enabled";
    private static final long CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private final Activity activity;
    private ProgressDialog progressDialog;
    private boolean downloadInProgress;

    UpdateManager(Activity activity) { this.activity = activity; }

    static boolean isAutomaticCheckEnabled(Activity activity) {
        return activity.getSharedPreferences(UPDATE_PREFS, Activity.MODE_PRIVATE)
                .getBoolean(AUTO_CHECK, true);
    }

    static void setAutomaticCheckEnabled(Activity activity, boolean enabled) {
        activity.getSharedPreferences(UPDATE_PREFS, Activity.MODE_PRIVATE)
                .edit().putBoolean(AUTO_CHECK, enabled).apply();
    }

    void check() { check(false); }

    void checkAutomatically() {
        if (!isAutomaticCheckEnabled(activity)) return;
        long last = activity.getSharedPreferences(UPDATE_PREFS, Activity.MODE_PRIVATE)
                .getLong(LAST_AUTO_CHECK, 0L);
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;
        check(true);
    }

    private void check(boolean automatic) {
        if (!automatic) Toast.makeText(activity, "Проверяем обновления…", Toast.LENGTH_SHORT).show();
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
                    if (!automatic) showMessage("Не удалось проверить обновления (HTTP " + response + ").");
                    return;
                }
                JSONObject release = new JSONObject(new String(readAll(c.getInputStream()), "UTF-8"));
                // Manual checks must not postpone the next automatic startup check.
                if (automatic) {
                    activity.getSharedPreferences(UPDATE_PREFS, Activity.MODE_PRIVATE).edit()
                            .putLong(LAST_AUTO_CHECK, System.currentTimeMillis()).apply();
                }
                JSONArray assets = release.optJSONArray("assets");
                String apkUrl = null;
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if ("KinozalClient.apk".equals(asset.optString("name"))) {
                        apkUrl = asset.optString("browser_download_url");
                        break;
                    }
                }
                if (apkUrl == null || apkUrl.isEmpty()) {
                    if (!automatic) showMessage("В последнем релизе не найден установочный APK.");
                    return;
                }
                String remoteVersion = extractSemanticVersion(
                        release.optString("name", "") + " " + release.optString("tag_name", ""));
                String localVersion = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).versionName;
                if (remoteVersion == null || localVersion == null
                        || compareSemanticVersions(remoteVersion, localVersion) <= 0) {
                    if (!automatic) showMessage("Установлена последняя версия.");
                    return;
                }
                showUpdateAvailable(remoteVersion, apkUrl);
            } catch (Exception e) {
                Log.e(TAG, "Update check failed", e);
                if (!automatic) showMessage("Не удалось проверить обновления. Проверь подключение к интернету.");
            } finally {
                if (c != null) c.disconnect();
            }
        }, "Kinozal-update-check").start();
    }

    private static String extractSemanticVersion(String value) {
        if (value == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?<!\\d)(\\d+)\\.(\\d+)\\.(\\d+)(?!\\d)")
                .matcher(value);
        return matcher.find() ? matcher.group() : null;
    }

    private static int compareSemanticVersions(String first, String second) {
        String[] a = first.split("\\.");
        String[] b = second.split("\\.");
        for (int i = 0; i < 3; i++) {
            int result = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (result != 0) return result;
        }
        return 0;
    }

    private void showUpdateAvailable(String version, String apkUrl) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            new AlertDialog.Builder(activity)
                    .setTitle("Доступно обновление")
                    .setMessage("Найдена новая версия Кинозал ТВ: " + version
                            + "\nХотите загрузить и установить её?")
                    .setPositiveButton("Загрузить", (dialog, which) -> download(apkUrl))
                    .setNegativeButton("Отмена", null)
                    .show();
        });
    }

    private void download(String apkUrl) {
        if (downloadInProgress) return;
        downloadInProgress = true;
        activity.runOnUiThread(() -> {
            progressDialog = new ProgressDialog(activity);
            progressDialog.setTitle("Загрузка обновления");
            progressDialog.setMessage("Подготовка…");
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setIndeterminate(true);
            progressDialog.setProgress(0);
            progressDialog.setCancelable(false);
            progressDialog.setButton(ProgressDialog.BUTTON_NEGATIVE, "Отмена", (d, w) -> {
                // Closing the dialog does not interrupt a partially downloaded APK.
                progressDialog = null;
            });
            progressDialog.show();
        });
        new Thread(() -> {
            HttpURLConnection c = null;
            File apk = new File(activity.getCacheDir(), "KinozalClient-update.apk");
            try {
                c = (HttpURLConnection) new URL(apkUrl).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", "KinozalClient-Android");
                int response = c.getResponseCode();
                if (response < 200 || response >= 300) throw new Exception("HTTP " + response);
                int total = c.getContentLength();
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[32768];
                    int n, downloaded = 0;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                        downloaded += n;
                        if (total > 0) {
                            int percent = (int) (downloaded * 100L / total);
                            activity.runOnUiThread(() -> {
                                if (progressDialog != null) {
                                    progressDialog.setIndeterminate(false);
                                    progressDialog.setProgress(percent);
                                    progressDialog.setMessage("Загружено " + percent + "%");
                                }
                            });
                        }
                    }
                    out.getFD().sync();
                }
                if (!apk.isFile() || apk.length() < 100000) throw new Exception("Загруженный APK неполный");
                activity.runOnUiThread(() -> {
                    closeProgress();
                    downloadInProgress = false;
                    launchInstaller();
                });
            } catch (Exception e) {
                Log.e(TAG, "Update download failed", e);
                activity.runOnUiThread(() -> {
                    closeProgress();
                    downloadInProgress = false;
                    Toast.makeText(activity, "Ошибка загрузки обновления: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            } finally {
                if (c != null) c.disconnect();
            }
        }, "Kinozal-update-download").start();
    }

    private void closeProgress() {
        if (progressDialog != null) {
            try { progressDialog.dismiss(); } catch (Exception ignored) { }
            progressDialog = null;
        }
    }

    private void launchInstaller() {
        try {
            if (Build.VERSION.SDK_INT >= 26
                    && !activity.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(activity, "Разреши установку приложений для Kinozal ТВ.", Toast.LENGTH_LONG).show();
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(settings);
                return;
            }
            Uri uri = Uri.parse("content://" + activity.getPackageName() + ".torrentprovider/update.apk");
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(install);
        } catch (Exception e) {
            Log.e(TAG, "Cannot launch APK installer", e);
            Toast.makeText(activity, "Не удалось открыть системный установщик APK.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showMessage(String message) {
        activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
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
