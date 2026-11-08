package com.goblinmini.kinozalclient;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Refreshes exactly one Kinozal feed without depending on an Activity or WebView. */
public final class HomeFeedRefreshWorker extends Worker {
    private static final String TAG = "KinozalFeedWorker";
    private static final Object MODEL_LOCK = new Object();

    public HomeFeedRefreshWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        String group = getInputData().getString(HomeFeedScheduler.KEY_GROUP);
        int index = getInputData().getInt(HomeFeedScheduler.KEY_INDEX, -1);
        String path = getInputData().getString(HomeFeedScheduler.KEY_PATH);
        String title = getInputData().getString(HomeFeedScheduler.KEY_TITLE);
        if (group == null || path == null || title == null || index < 0) return Result.failure();

        Context context = getApplicationContext();
        String mirror = context.getSharedPreferences("kinozal_settings", Context.MODE_PRIVATE)
                .getString("mirror", KinozalHttpClient.DEFAULT_MIRROR);
        Socks5ProxyBridge bridge = new Socks5ProxyBridge(context);
        KinozalHttpClient client = new KinozalHttpClient(context, mirror);
        boolean proxyStarted = false;
        try {
            Socks5ProxyBridge.Settings proxySettings = bridge.getSettings();
            if (proxySettings.enabled) {
                Proxy proxy = bridge.start();
                proxyStarted = true;
                client.setProxy(proxy);
            }
            String html = client.fetch(path);
            if (html == null || html.trim().isEmpty()) throw new java.io.IOException("Пустой ответ Kinozal");
            JSONArray items = parseItems(html, client.getMirror());
            if (items.length() == 0) throw new java.io.IOException("В ответе нет раздач: кэш оставлен без изменений");

            synchronized (MODEL_LOCK) {
                if ("home".equals(group)) {
                    mergeModel(new File(context.getFilesDir(), "kinozal_home_model_v2.json"),
                            2, client.getMirror(), title, path, items);
                    mergeModel(new File(context.getFilesDir(), "kinozal_tv_channels_v1.json"),
                            1, client.getMirror(), title, path, items);
                } else if ("collections".equals(group)) {
                    mergeModel(new File(context.getFilesDir(), "kinozal_collections_model_v1.json"),
                            1, client.getMirror(), title, path, items);
                } else {
                    return Result.failure();
                }
            }
            Log.i(TAG, "Updated " + group + " feed " + index + ": " + title + ", cards=" + items.length());
            android.content.Intent changed = new android.content.Intent("com.goblinmini.kinozalclient.FEED_CACHE_UPDATED");
            changed.setPackage(context.getPackageName());
            changed.putExtra("feed_group", group);
            changed.putExtra("feed_index", index);
            changed.putExtra("feed_path", path);
            context.sendBroadcast(changed);
            HomeFeedScheduler.scheduleNext(context, group, index, path, title);
            return Result.success();
        } catch (SecurityException e) {
            Log.w(TAG, "Feed refresh requires a fresh Kinozal login: " + path, e);
            notifyFailure(context, group, path, "нужен повторный вход на Kinozal");
            return Result.retry();
        } catch (Exception e) {
            Log.w(TAG, "Feed refresh failed; previous cache is preserved: " + path, e);
            String reason = e.getMessage();
            notifyFailure(context, group, path,
                    reason == null || reason.trim().isEmpty() ? "ошибка сети или ответа сайта" : reason);
            return Result.retry();
        } finally {
            if (proxyStarted) bridge.stop();
        }
    }

    private static void notifyFailure(Context context, String group, String path, String message) {
        android.content.Intent failed = new android.content.Intent(
                "com.goblinmini.kinozalclient.FEED_CACHE_UPDATED");
        failed.setPackage(context.getPackageName());
        failed.putExtra("feed_group", group);
        failed.putExtra("feed_path", path);
        failed.putExtra("refresh_error", message);
        context.sendBroadcast(failed);
    }

    private static JSONArray parseItems(String html, String mirror) throws Exception {
        Document doc = Jsoup.parse(html, mirror + "/");
        String text = doc.text().toLowerCase(java.util.Locale.ROOT);
        if (text.contains("checking your browser") || text.contains("just a moment")
                || text.contains("подтвердите, что вы не робот")) {
            throw new java.io.IOException("Kinozal вернул проверку Cloudflare");
        }
        Elements anchors = doc.select("table.t_peer td.nam > a[href*=details.php?id=]");
        if (anchors.isEmpty()) anchors = doc.select("a[href*=details.php?id=]");
        JSONArray result = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (Element a : anchors) {
            String href = a.absUrl("href");
            if (href == null || !href.startsWith("http")) href = mirror + "/" + a.attr("href").replaceFirst("^/+", "");
            if (!href.contains("details.php?id=") || !seen.add(href)) continue;
            Element img = a.selectFirst("img");
            if (img == null) {
                Element row = a.closest("tr");
                if (row != null) img = row.selectFirst("img");
            }
            if (img == null && a.parent() != null) img = a.parent().selectFirst("img");
            String title = a.attr("title").trim();
            if (title.isEmpty() && img != null) title = img.attr("alt").trim();
            if (title.isEmpty()) title = a.text().replaceAll("\\s+", " ").trim();
            if (title.isEmpty()) continue;
            String image = "";
            if (img != null) {
                String raw = img.hasAttr("data-src") ? img.attr("data-src")
                        : img.hasAttr("data-original") ? img.attr("data-original")
                        : img.hasAttr("src") ? img.attr("src") : "";
                if (raw.startsWith("//")) raw = "https:" + raw;
                else if (raw.startsWith("/")) raw = mirror + raw;
                else if (!raw.isEmpty() && !raw.startsWith("http")) raw = mirror + "/" + raw;
                if (raw.startsWith("http://") || raw.startsWith("https://")) image = raw;
            }
            Element row = a.closest("tr");
            Elements cells = row == null ? new Elements() : row.select("td");
            JSONObject item = new JSONObject();
            item.put("title", title);
            item.put("href", href);
            item.put("size", cells.size() > 3 ? cells.get(3).text().trim() : "—");
            item.put("seeds", cells.size() > 4 ? cells.get(4).text().trim() : "—");
            item.put("leeches", cells.size() > 5 ? cells.get(5).text().trim() : "—");
            item.put("uploaded", "");
            item.put("image", image);
            result.put(item);
            if (result.length() >= 15) break;
        }
        return result;
    }

    private static void mergeModel(File target, int version, String mirror, String title,
            String path, JSONArray newItems) throws Exception {
        JSONObject root = new JSONObject();
        JSONArray feeds = new JSONArray();
        if (target.isFile() && target.length() > 0 && target.length() < 8L * 1024L * 1024L) {
            try {
                JSONObject old = new JSONObject(new String(
                        java.nio.file.Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
                if (old.optInt("version") == version && mirror.equals(old.optString("mirror"))) {
                    root = old;
                    JSONArray oldFeeds = old.optJSONArray("feeds");
                    if (oldFeeds != null) feeds = oldFeeds;
                }
            } catch (Exception ignored) { }
        }
        JSONArray updated = new JSONArray();
        boolean replaced = false;
        for (int i = 0; i < feeds.length(); i++) {
            JSONObject feed = feeds.optJSONObject(i);
            if (feed == null) continue;
            if (path.equals(feed.optString("path"))) {
                JSONObject fresh = new JSONObject();
                fresh.put("title", title);
                fresh.put("path", path);
                fresh.put("items", newItems);
                updated.put(fresh);
                replaced = true;
            } else updated.put(feed);
        }
        if (!replaced) {
            JSONObject fresh = new JSONObject();
            fresh.put("title", title);
            fresh.put("path", path);
            fresh.put("items", newItems);
            updated.put(fresh);
        }
        root.put("version", version);
        root.put("mirror", mirror);
        root.put("savedAt", System.currentTimeMillis());
        root.put("feeds", updated);
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (target.exists() && !target.delete()) throw new java.io.IOException("Не удалось заменить кэш");
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new java.io.IOException("Не удалось сохранить кэш");
        }
    }
}
