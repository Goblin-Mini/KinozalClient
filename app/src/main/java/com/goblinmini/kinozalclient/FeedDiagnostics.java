package com.goblinmini.kinozalclient;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Stores concise feed-loading diagnostics without page bodies, cookies or credentials. */
public final class FeedDiagnostics {
    private static final String FILE_NAME = "kinozal_feed_diagnostics.txt";
    private static final int MAX_BYTES = 256 * 1024;
    private static final Object LOCK = new Object();
    private static long startedAtMs;

    private FeedDiagnostics() { }

    public static void start(Context context, String group, String mirror, String[] paths, String source) {
        if (context == null) return;
        synchronized (LOCK) {
            startedAtMs = android.os.SystemClock.elapsedRealtime();
            StringBuilder line = new StringBuilder();
            line.append("\n=== Kinozal ТВ — диагностика лент ===\n")
                    .append("Дата: ").append(now()).append('\n')
                    .append("Группа: ").append(clean(group)).append('\n')
                    .append("Источник: ").append(clean(source)).append('\n')
                    .append("Зеркало: ").append(clean(mirror)).append('\n');
            if (paths != null) {
                line.append("Ленты: ").append(paths.length).append('\n');
                for (int i = 0; i < paths.length; i++) {
                    line.append("  ").append(i + 1).append(". ").append(clean(paths[i])).append('\n');
                }
            }
            appendLocked(context, line.toString());
        }
    }

    public static void log(Context context, String group, String stage, String details) {
        if (context == null) return;
        synchronized (LOCK) {
            long elapsed = startedAtMs == 0L ? 0L
                    : android.os.SystemClock.elapsedRealtime() - startedAtMs;
            appendLocked(context, String.format(Locale.ROOT, "[+%d ms] %s %s%n",
                    elapsed, clean(stage), clean(details)));
        }
    }

    public static String read(Context context) {
        if (context == null) return "";
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (!file.isFile()) return "";
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] data = new byte[(int)Math.min(file.length(), MAX_BYTES)];
                int offset = 0;
                while (offset < data.length) {
                    int count = in.read(data, offset, data.length - offset);
                    if (count < 0) break;
                    offset += count;
                }
                return new String(data, 0, offset, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    private static void appendLocked(Context context, String value) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        try {
            byte[] incoming = value.getBytes(StandardCharsets.UTF_8);
            byte[] existing = new byte[0];
            if (file.isFile() && file.length() > 0) {
                int keep = (int)Math.min(file.length(), MAX_BYTES - incoming.length);
                if (keep > 0) {
                    try (FileInputStream in = new FileInputStream(file)) {
                        long skip = Math.max(0L, file.length() - keep);
                        while (skip > 0) {
                            long n = in.skip(skip);
                            if (n <= 0) break;
                            skip -= n;
                        }
                        existing = new byte[keep];
                        int offset = 0;
                        while (offset < keep) {
                            int n = in.read(existing, offset, keep - offset);
                            if (n < 0) break;
                            offset += n;
                        }
                        if (offset != keep) existing = java.util.Arrays.copyOf(existing, offset);
                    }
                }
            }
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write(existing);
                out.write(incoming, 0, Math.min(incoming.length, MAX_BYTES));
                out.flush();
                out.getFD().sync();
            }
        } catch (Exception ignored) { }
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
    }
}
