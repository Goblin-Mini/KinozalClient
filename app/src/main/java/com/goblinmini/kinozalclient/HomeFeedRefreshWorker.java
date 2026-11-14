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
        boolean bulk = getInputData().getBoolean(HomeFeedScheduler.KEY_BULK, false);
        int total = getInputData().getInt(HomeFeedScheduler.KEY_TOTAL, 1);
        if (group == null || path == null || title == null || index < 0) return Result.failure();

        Context context = getApplicationContext();
        String mirror = context.getSharedPreferences("kinozal_settings", Context.MODE_PRIVATE)
                .getString("mirror", KinozalHttpClient.DEFAULT_MIRROR);
        Socks5ProxyBridge bridge = Socks5ProxyBridge.getInstance(context);
        KinozalHttpClient client = new KinozalHttpClient(context, mirror);
        long workerStarted = android.os.SystemClock.elapsedRealtime();
        String requestUrl = client.getMirror() + "/" + path.replaceFirst("^/+", "");
        FeedDiagnostics.log(context, group, "WORKER_START", "feed=" + (index + 1) + "/" + total
                + ", title=" + title + ", path=" + path + ", url=" + requestUrl
                + ", bulk=" + bulk + ", cacheModelExists="
                + new File(context.getFilesDir(), "collections".equals(group)
                        ? "kinozal_collections_model_v1.json" : "kinozal_home_model_v2.json").isFile());
        sendRefreshState(context, group, index, path, title, bulk, total, "running", null);
        try {
            // A headless WebView runs on Android's main Looper even when the
            // Activity is closed. This lets Cloudflare execute its JS challenge.
            Socks5ProxyBridge.Settings proxySettings = bridge.getSettings();
            Integer webViewProxyPort = null;
            if (proxySettings.enabled) {
                bridge.ensureStarted();
                webViewProxyPort = bridge.getLocalPort();
            }
            FeedDiagnostics.log(context, group, "REQUEST_START", "url=" + requestUrl
                    + ", transport=authenticated-headless-WebView, proxy=" + (webViewProxyPort == null ? "off" : "on"));
            BrowserFeedFetcher.Result browserResult = BrowserFeedFetcher.fetch(
                    context, requestUrl, client.getUserAgent(), webViewProxyPort, 75000L);
            String html = browserResult.html;
            FeedDiagnostics.log(context, group, "RESPONSE", "requestedUrl=" + requestUrl
                    + ", finalUrl=" + browserResult.finalUrl + ", httpStatus="
                    + (browserResult.httpStatus < 0 ? "not-reported" : browserResult.httpStatus)
                    + ", durationMs=" + browserResult.durationMs + ", htmlChars="
                    + (html == null ? 0 : html.length()));
            if (browserResult.cookies != null && !browserResult.cookies.trim().isEmpty()) {
                client.saveBrowserCookies(browserResult.cookies);
                client.importBrowserCookies(browserResult.cookies);
            }
            if (html == null || html.trim().isEmpty()) throw new java.io.IOException("Пустой ответ Kinozal");
            Document diagnosticDoc = Jsoup.parse(html, client.getMirror() + "/");
            Elements diagnosticAnchors = diagnosticDoc.select("a[href*=details.php?id=]");
            StringBuilder anchorSamples = new StringBuilder();
            for (int sample = 0; sample < Math.min(3, diagnosticAnchors.size()); sample++) {
                Element anchor = diagnosticAnchors.get(sample);
                Element parent = anchor.parent();
                String parentHtml = parent == null ? "" : parent.outerHtml();
                if (parentHtml.length() > 900) parentHtml = parentHtml.substring(0, 900);
                if (anchorSamples.length() > 0) anchorSamples.append(" || ");
                anchorSamples.append("a=").append(anchor.outerHtml().replaceAll("\\s+", " "))
                        .append("; parent=").append(parentHtml.replaceAll("\\s+", " "));
            }
            FeedDiagnostics.log(context, group, "HTML_SHAPE", "path=" + path
                    + ", pageTitle=" + diagnosticDoc.title()
                    + ", detailsAnchors=" + diagnosticAnchors.size()
                    + ", tableRows=" + diagnosticDoc.select("tr").size()
                    + ", tableCells=" + diagnosticDoc.select("tr td").size()
                    + ", bodyChars=" + (diagnosticDoc.body() == null ? 0 : diagnosticDoc.body().text().length())
                    + ", anchorSamples=" + anchorSamples
                    + ", finalUrl=" + browserResult.finalUrl
                    + ", httpStatus=" + browserResult.httpStatus);
            JSONArray items = parseItems(html, client.getMirror());
            FeedDiagnostics.log(context, group, "PARSE", "path=" + path + ", parsedCards=" + items.length());
            for (int itemIndex = 0; itemIndex < items.length(); itemIndex++) {
                JSONObject item = items.optJSONObject(itemIndex);
                if (item != null) FeedDiagnostics.log(context, group, "ITEM",
                        "feed=" + title + ", n=" + (itemIndex + 1) + ", title=" + item.optString("title")
                                + ", href=" + item.optString("href"));
            }
            if (items.length() == 0) throw new EmptyFeedException(
                    "В ответе нет раздач: кэш оставлен без изменений");

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
            FeedDiagnostics.log(context, group, "CACHE_WRITE", "updated feed=" + title
                    + ", cards=" + items.length() + ", model=committed");
            if ("home".equals(group)) {
                // Refresh launcher rows in this WorkManager process even when the Activity is stopped.
                TvHomeChannels.syncFromWorker(context,
                        new File(context.getFilesDir(), "kinozal_tv_channels_v1.json"));
            }
            FeedDiagnostics.log(context, group, "WORKER_DONE", "elapsedMs="
                    + (android.os.SystemClock.elapsedRealtime() - workerStarted));
            Log.i(TAG, "Updated " + group + " feed " + index + ": " + title + ", cards=" + items.length());
            android.content.Intent changed = new android.content.Intent("com.goblinmini.kinozalclient.FEED_CACHE_UPDATED");
            changed.setPackage(context.getPackageName());
            changed.putExtra("feed_group", group);
            changed.putExtra("feed_index", index);
            changed.putExtra("feed_path", path);
            context.sendBroadcast(changed);
            sendRefreshState(context, group, index, path, title, bulk, total, "success", null);
            HomeFeedScheduler.scheduleNext(context, group, index, path, title);
            return Result.success();
        } catch (EmptyFeedException e) {
            // Avoid retrying an identical empty/changed page response and spamming the UI.
            Log.w(TAG, "Feed returned no parseable releases; stopping this refresh: " + path, e);
            FeedDiagnostics.log(context, group, "WORKER_EMPTY_FEED", "path=" + path
                    + ", reason=" + e.getMessage() + ", previousCachePreserved=true, retry=false");
            notifyFailure(context, group, index, path, title, bulk, total, e.getMessage());
            return Result.failure();
        } catch (SecurityException e) {
            Log.w(TAG, "Feed refresh requires a fresh Kinozal login: " + path, e);
            FeedDiagnostics.log(context, group, "WORKER_AUTH_ERROR", "path=" + path + ", reason=" + e.getMessage());
            notifyFailure(context, group, index, path, title, bulk, total, "нужен повторный вход на Kinozal");
            return Result.retry();
        } catch (Exception e) {
            Log.w(TAG, "Feed refresh failed; previous cache is preserved: " + path, e);
            String reason = e.getMessage();
            FeedDiagnostics.log(context, group, "WORKER_ERROR", "path=" + path + ", type="
                    + e.getClass().getSimpleName() + ", reason=" + (reason == null ? "unknown" : reason)
                    + ", elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - workerStarted)
                    + ", previousCachePreserved=true");
            notifyFailure(context, group, index, path, title, bulk, total,
                    reason == null || reason.trim().isEmpty() ? "ошибка сети или ответа сайта" : reason);
            return Result.retry();
        } finally {
            // The bridge is shared with MainActivity and must remain alive while
            // WebView's process-wide proxy override points to its loopback port.
        }
    }

    private static final class EmptyFeedException extends java.io.IOException {
        EmptyFeedException(String message) { super(message); }
    }

    private static void notifyFailure(Context context, String group, int index, String path,
            String title, boolean bulk, int total, String message) {
        sendRefreshState(context, group, index, path, title, bulk, total, "failure", message);
    }

    private static void sendRefreshState(Context context, String group, int index, String path,
            String title, boolean bulk, int total, String state, String message) {
        android.content.Intent event = new android.content.Intent(
                "com.goblinmini.kinozalclient.FEED_REFRESH_STATE");
        event.setPackage(context.getPackageName());
        event.putExtra("feed_group", group);
        event.putExtra("feed_index", index);
        event.putExtra("feed_path", path);
        event.putExtra("feed_title", title);
        event.putExtra("feed_bulk", bulk);
        event.putExtra("feed_total", total);
        event.putExtra("refresh_state", state);
        if (message != null) event.putExtra("refresh_error", message);
        context.sendBroadcast(event);
    }

    private static JSONArray parseItems(String html, String mirror) throws Exception {
        Document doc = Jsoup.parse(html, mirror + "/");
        String text = doc.text().toLowerCase(java.util.Locale.ROOT);
        if (text.contains("checking your browser") || text.contains("just a moment")
                || text.contains("подтвердите, что вы не робот")) {
            throw new java.io.IOException("Kinozal вернул проверку Cloudflare");
        }
        Elements anchors = doc.select("a[href*=details.php?id=]");
        JSONArray result = new JSONArray();
        Set<String> seen = new HashSet<>();
        Set<String> seenIds = new HashSet<>();
        java.util.regex.Pattern idPattern = java.util.regex.Pattern.compile("[?&]id=(\\d+)",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        for (Element a : anchors) {
            String href = a.absUrl("href");
            if (href == null || !href.startsWith("http"))
                href = mirror + "/" + a.attr("href").replaceFirst("^/+", "");
            String lowerHref = href.toLowerCase(java.util.Locale.ROOT);
            if (!lowerHref.contains("/details.php?id=") || lowerHref.contains("userdetails")
                    || !seen.add(href)) continue;

            java.util.regex.Matcher idMatcher = idPattern.matcher(href);
            String torrentId = idMatcher.find() ? idMatcher.group(1) : "";
            if (!torrentId.isEmpty() && seenIds.contains(torrentId)) continue;

            // Kinozal sometimes renders the feed as cards/divs instead of table rows.
            // Prefer the classic row, otherwise walk up to the nearest useful container.
            Element container = a.closest("tr");
            if (container == null) {
                Element candidate = a.parent();
                while (candidate != null && !candidate.tagName().equals("body")) {
                    if (candidate.select("a[href*=details.php?id=]").size() <= 3
                            && (candidate.select("img").size() > 0
                                || candidate.text().length() > a.text().length() + 8)) {
                        container = candidate;
                        break;
                    }
                    candidate = candidate.parent();
                }
            }
            Element img = a.selectFirst("img");
            if (img == null && container != null) img = container.selectFirst("img");
            if (img == null && a.parent() != null) img = a.parent().selectFirst("img");

            String title = a.attr("title").trim();
            if (title.isEmpty() && img != null) title = img.attr("alt").trim();
            if (title.isEmpty()) title = a.text().replaceAll("\\s+", " ").trim();
            if (title.isEmpty() && img != null) title = img.attr("title").trim();
            if (title.isEmpty()) continue;

            // Torrent ID / href is the identity. Do not collapse distinct releases
            // merely because they share a title or poster image.
            if (!torrentId.isEmpty()) seenIds.add(torrentId);

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
            Elements cells = container == null ? new Elements() : container.select("td");
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

    private static String normalizeFeedTitle(String title) {
        if (title == null) return "";
        String value = title.toLowerCase(java.util.Locale.ROOT).trim();
        int slash = value.indexOf(" / ");
        if (slash > 0) value = value.substring(0, slash);
        value = value.replaceAll("\\b(2160p|1080p|720p|480p|4k|uhd|hdr10?|web[- .]?dl|webrip|blu[- .]?ray|bdrip|brrip|dvdrip|hdtv|h265|h264|x265|x264|hevc|avc|aac|dts|ac3|proper|repack|remux|extended|directors cut|theatrical|dubbed|subbed)\\b", " ");
        return value.replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ").trim();
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
