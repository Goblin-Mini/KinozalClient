package com.goblinmini.kinozalclient;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

final class UpdateManager {
    private static final String RELEASE_API =
            "https://api.github.com/repos/Goblin-Mini/KinozalClient/releases/latest";
    private final Activity activity;

    UpdateManager(Activity activity) { this.activity = activity; }

    void check() {
        Toast.makeText(activity, "Проверяем обновления…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "KinozalClient-Android");
                JSONObject release = new JSONObject(new String(readAll(c.getInputStream()), "UTF-8"));
                JSONArray assets = release.optJSONArray("assets");
                String apkUrl = null;
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if ("KinozalClient.apk".equals(asset.optString("name"))) {
                        apkUrl = asset.optString("browser_download_url");
                        break;
                    }
                }
                if (apkUrl == null || apkUrl.isEmpty()) throw new Exception("В релизе не найден APK.");
                String tag = release.optString("tag_name", "");
                int remoteCode = 0;
                try { remoteCode = Integer.parseInt(tag.replaceAll("[^0-9]", "")); }
                catch (Exception ignored) { }
                int localCode = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).versionCode;
                if (remoteCode <= localCode) {
                    activity.runOnUiThread(() -> Toast.makeText(activity,
                            "Установлена последняя версия.", Toast.LENGTH_LONG).show());
                    return;
                }
                downloadAndPrompt(apkUrl);
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Ошибка проверки обновлений" : e.getMessage();
                activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
            } finally { if (c != null) c.disconnect(); }
        }).start();
    }

    private void downloadAndPrompt(String apkUrl) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        File apk = new File(activity.getCacheDir(), "KinozalClient-update.apk");
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
            byte[] buffer = new byte[32768];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        } finally { c.disconnect(); }
        if (!apk.isFile() || apk.length() < 100000) throw new Exception("APK скачан не полностью.");
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
                Toast.makeText(activity, "Не удалось открыть установщик: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream input = in; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = input.read(b)) != -1) out.write(b, 0, n);
            return out.toByteArray();
        }
    }
}
