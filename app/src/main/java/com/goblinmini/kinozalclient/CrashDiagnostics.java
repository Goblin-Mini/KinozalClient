package com.goblinmini.kinozalclient;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashDiagnostics {
    private static final String FILE_NAME = "kinozal_last_crash.txt";
    private static final int MAX_BYTES = 64 * 1024;

    private CrashDiagnostics() { }

    public static void write(Context context, Thread thread, Throwable error) {
        if (context == null || error == null) return;
        File file = new File(context.getFilesDir(), FILE_NAME);
        StringBuilder report = new StringBuilder();
        String version = "неизвестна";
        try {
            android.content.pm.PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            version = packageInfo.versionName == null ? version : packageInfo.versionName;
        } catch (Exception ignored) { }
        report.append("Kinozal ТВ — отчёт о сбое\n")
                .append("Дата: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date())).append('\n')
                .append("Версия: ").append(version).append('\n')
                .append("Android: ").append(android.os.Build.VERSION.RELEASE)
                .append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
                .append("Устройство: ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append('\n')
                .append("Поток: ").append(thread == null ? "unknown" : thread.getName()).append("\n\n");
        java.io.StringWriter writer = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(writer));
        report.append(writer);
        byte[] bytes = report.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(bytes, 0, Math.min(bytes.length, MAX_BYTES));
            out.flush();
            out.getFD().sync();
        } catch (Exception ignored) { }
    }

    public static String read(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.isFile()) return "";
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int)Math.min(file.length(), MAX_BYTES)];
            int offset = 0;
            while (offset < data.length) {
                int n = in.read(data, offset, data.length - offset);
                if (n < 0) break;
                offset += n;
            }
            return new String(data, 0, offset, StandardCharsets.UTF_8);
        } catch (Exception ignored) { return ""; }
    }
}
