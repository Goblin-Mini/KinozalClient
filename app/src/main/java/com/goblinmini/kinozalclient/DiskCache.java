package com.goblinmini.kinozalclient;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Small bounded disk cache shared by HTML pages and poster images.
 * Least-recently-used files are evicted when the combined cache exceeds its limit.
 */
public final class DiskCache {
    private final File directory;
    private final long maxBytes;

    public DiskCache(Context context, long maxBytes) {
        this.directory = new File(context.getCacheDir(), "kinozal_cache");
        this.maxBytes = Math.max(1L, maxBytes);
        // Directory maintenance can scan/sort up to 200 MB of cache files.
        // Never do that synchronously from Activity.onCreate().
        if (!directory.exists()) directory.mkdirs();
    }

    private File fileFor(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte value : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return new File(directory, name.toString() + ".cache");
        } catch (Exception e) {
            return new File(directory, Integer.toHexString(key.hashCode()) + ".cache");
        }
    }

    public synchronized byte[] get(String key, long maxAgeMs) {
        File file = fileFor(key);
        if (!file.isFile()) return null;
        if (maxAgeMs > 0L && System.currentTimeMillis() - file.lastModified() > maxAgeMs) {
            file.delete();
            return null;
        }
        if (file.length() > maxBytes) {
            file.delete();
            return null;
        }
        try {
            byte[] data = new byte[(int) file.length()];
            try (FileInputStream input = new FileInputStream(file)) {
                int offset = 0;
                while (offset < data.length) {
                    int count = input.read(data, offset, data.length - offset);
                    if (count < 0) break;
                    offset += count;
                }
                if (offset != data.length) return null;
            }
            file.setLastModified(System.currentTimeMillis());
            return data;
        } catch (Exception ignored) {
            return null;
        }
    }

    public synchronized void put(String key, byte[] data) {
        if (data == null || data.length == 0 || data.length > maxBytes) return;
        if (!directory.exists() && !directory.mkdirs()) return;
        File target = fileFor(key);
        File temporary = new File(directory, target.getName() + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(data);
                output.flush();
                output.getFD().sync();
            }
            if (target.exists()) target.delete();
            if (!temporary.renameTo(target)) {
                try (FileInputStream input = new FileInputStream(temporary);
                     FileOutputStream output = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                temporary.delete();
            }
            trim();
        } catch (Exception ignored) {
            temporary.delete();
        }
    }

    public synchronized long getSizeBytes() {
        File[] files = directory.listFiles();
        if (files == null) return 0L;
        long total = 0L;
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".cache")) total += file.length();
        }
        return total;
    }

    public synchronized int getEntryCount() {
        File[] files = directory.listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".cache")) count++;
        }
        return count;
    }

    /** Remove all cached pages and images without touching user data. */
    public synchronized void clear() {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile()) file.delete();
            else if (file.isDirectory()) deleteTree(file);
        }
    }

    private void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    /** Enforce the combined 200 MB budget using least-recently-used eviction. */
    public synchronized void trim() {
        File[] files = directory.listFiles();
        if (files == null) return;
        long total = 0L;
        for (File file : files) {
            if (file.isFile()) {
                if (file.getName().endsWith(".tmp")) file.delete();
                else total += file.length();
            }
        }
        if (total <= maxBytes) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File file : files) {
            if (total <= maxBytes) break;
            if (file.isFile() && file.getName().endsWith(".cache")) {
                long length = file.length();
                if (file.delete()) total -= length;
            }
        }
    }
}
