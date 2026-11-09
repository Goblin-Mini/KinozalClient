package com.goblinmini.kinozalclient;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Persistent, privacy-safe feed diagnostics shared by Activity and WorkManager. */
public final class FeedDiagnostics {
    private static final String TAG = "KinozalFeedDiag";
    private static final String FILE_NAME = "kinozal_feed_diagnostics.log";
    private static final int MAX_BYTES = 128 * 1024;
    private static final Object LOCK = new Object();

    private FeedDiagnostics() { }

    private static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    public static void start(Context context, String group, String mirror, String[] paths, String source) {
        synchronized (LOCK) {
            File target = file(context);
            try (FileOutputStream out = new FileOutputStream(target, false)) {
                StringBuilder header = new StringBuilder();
                header.append("Kinozal feed diagnostics\n")
                        .append("Группа: ").append(group).append('\n')
                        .append("Источник запуска: ").append(source).append('\n')
                        .append("Зеркало: ").append(mirror).append('\n')
                        .append("Время: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date())).append('\n')
                        .append("Лент: ").append(paths == null ? 0 : paths.length).append('\n');
                if (paths != null) for (int i = 0; i < paths.length; i++)
                    header.append(String.format(Locale.ROOT, "%02d. %s%n", i + 1, paths[i]));
                out.write(header.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            } catch (Exception e) {
                Log.e(TAG, "Не удалось начать файл диагностики", e);
            }
        }
        log(context, group, "START", "диагностическая сессия начата; содержимое страниц и cookies не сохраняются");
    }

    public static void log(Context context, String group, String stage, String details) {
        String line = String.format(Locale.ROOT, "[%s] [%s] %s %s%n",
                new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new Date()),
                group == null ? "?" : group, stage == null ? "EVENT" : stage,
                details == null ? "" : details);
        synchronized (LOCK) {
            File target = file(context);
            try {
                if (!target.exists()) {
                    try (FileOutputStream create = new FileOutputStream(target)) {
                        create.write("Kinozal feed diagnostics\n".getBytes(StandardCharsets.UTF_8));
                    }
                }
                if (target.length() > MAX_BYTES) {
                    byte[] old;
                    try (FileInputStream in = new FileInputStream(target)) {
                        old = new byte[(int) Math.min(target.length(), MAX_BYTES / 2)];
                        long skip = Math.max(0L, target.length() - old.length);
                        while (skip > 0) { long n = in.skip(skip); if (n <= 0) break; skip -= n; }
                        int offset = 0;
                        while (offset < old.length) { int n = in.read(old, offset, old.length - offset); if (n < 0) break; offset += n; }
                    }
                    try (FileOutputStream out = new FileOutputStream(target, false)) {
                        out.write(old);
                    }
                }
                try (FileOutputStream out = new FileOutputStream(target, true)) {
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (Exception e) {
                Log.e(TAG, "Не удалось записать диагностику", e);
            }
        }
        Log.i(TAG, line.trim());
    }

    public static String read(Context context) {
        synchronized (LOCK) {
            File target = file(context);
            if (!target.isFile()) return "";
            try (FileInputStream in = new FileInputStream(target)) {
                byte[] data = new byte[(int) Math.min(target.length(), MAX_BYTES)];
                int offset = 0;
                while (offset < data.length) { int n = in.read(data, offset, data.length - offset); if (n < 0) break; offset += n; }
                return new String(data, 0, offset, StandardCharsets.UTF_8);
            } catch (Exception e) {
                Log.e(TAG, "Не удалось прочитать диагностику", e);
                return "";
            }
        }
    }
}
