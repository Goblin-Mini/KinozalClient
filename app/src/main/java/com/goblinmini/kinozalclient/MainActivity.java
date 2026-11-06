package com.goblinmini.kinozalclient;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 19, 23);
    private static final int PANEL = Color.rgb(29, 34, 39);
    private static final int GOLD = Color.rgb(251, 226, 173);
    private static final int RED = Color.rgb(174, 55, 18);
    private static final int TEAL = Color.rgb(115, 164, 170);
    private static final String PREFS = "kinozal_settings";
    private static final String[] MIRRORS = {
            "https://kinozal.cloudns.nz", "https://kinozal.guru",
            "https://kinozal.tv", "https://kinozal.me"
    };

    private static final String MAGNET_DIAG_FILE = "magnet-diagnostics.log";

    private synchronized void magnetDiag(String stage, String detail) {
        String line = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(new java.util.Date()) + " [" + stage + "] " + (detail == null ? "" : detail) + "\n";
        android.util.Log.i("KinozalMagnet", line.trim());
        try (java.io.FileOutputStream out = openFileOutput(MAGNET_DIAG_FILE, MODE_APPEND)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            android.util.Log.e("KinozalMagnet", "Не удалось записать журнал", e);
        }
    }

    private String readMagnetDiagnostics() {
        try (java.io.FileInputStream in = openFileInput(MAGNET_DIAG_FILE)) {
            byte[] bytes = new byte[(int) Math.min(in.available(), 128 * 1024)];
            int count = in.read(bytes);
            return count > 0 ? new String(bytes, 0, count, StandardCharsets.UTF_8) : "Журнал пуст.";
        } catch (java.io.FileNotFoundException e) {
            return "Журнал пока пуст. Открой карточку раздачи, чтобы выполнить проверку.";
        } catch (Exception e) {
            return "Не удалось прочитать журнал: " + safe(e.getMessage());
        }
    }

    private void showMagnetDiagnostics() {
        String log = readMagnetDiagnostics();
        new AlertDialog.Builder(this)
                .setTitle("Диагностика получения magnet")
                .setMessage(log)
                .setPositiveButton("Копировать", (d, w) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("Kinozal magnet diagnostics", log));
                    showMessage("Журнал скопирован");
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private KinozalHttpClient client;
    private String mirror;
    private String currentPath = "browse.php";
    private String currentTitle = "Все раздачи";
    private LinearLayout root, sidebar, content, list, menuItems;
    private TextView heading, status;
    private ProgressBar progress;
    private EditText search;
    private final List<Torrent> torrents = new ArrayList<>();
    private int page = 0;
    private volatile String lastHtml = "";
    private WebView sessionWebView;
    private BrowserPageCallback pendingBrowserCallback;
    private String pendingBrowserTarget = "";
    private long browserRequestSerial = 0;
    private volatile long torrentRequestSerial = 0;
    private final android.os.Handler browserHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable browserRequestTimeout;
    private boolean startupCatalogReady = false;
    private boolean startupCatalogStarted = false;
    private boolean automaticUpdateCheckScheduled = false;
    private interface BrowserPageCallback { void onPage(String html, String error); }
    private final java.util.LinkedHashMap<String, String> pageCache =
            new java.util.LinkedHashMap<String, String>(12, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                    return size() > 12;
                }
            };

    private static class Category { String title, href, method = "GET", body = ""; }

    private static class Torrent {
        String title, href, size, seeds, leeches, uploaded;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        mirror = getSharedPreferences(PREFS, MODE_PRIVATE).getString("mirror", MIRRORS[0]);
        client = new KinozalHttpClient(this, mirror);
        buildShell();
        String savedBrowserCookies = client.getSavedBrowserCookies();
        if (savedBrowserCookies != null) {
            restoreBrowserSession(savedBrowserCookies);
        } else {
            // Wait until the TV window is actually focused before the first request.
            markStartupCatalogReady();
        }
    }


    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            startInitialCatalogWhenReady();
            scheduleAutomaticUpdateCheck();
        }
    }

    private void scheduleAutomaticUpdateCheck() {
        if (automaticUpdateCheckScheduled) return;
        automaticUpdateCheckScheduled = true;
        // Let the home screen settle before making a background network request.
        browserHandler.postDelayed(() -> new UpdateManager(this).checkAutomatically(), 15000);
    }

    private void markStartupCatalogReady() {
        startupCatalogReady = true;
        startInitialCatalogWhenReady();
    }

    private void startInitialCatalogWhenReady() {
        if (!startupCatalogReady || startupCatalogStarted || !hasWindowFocus()
                || root == null || !root.isAttachedToWindow()) return;
        startupCatalogStarted = true;
        browserHandler.postDelayed(this::loadInitialCatalog, 350);
    }

    private static class HomeSection {
        String title, path;
        final List<HomePoster> items = new ArrayList<>();
        HomeSection(String title, String path) { this.title = title; this.path = path; }
    }

    private static class HomePoster {
        Torrent torrent;
        String imageUrl;
    }

    private void loadInitialCatalog() {
        loadInitialCatalog(0);
    }

    private void loadInitialCatalog(int startupRetry) {
        currentPath = "";
        currentTitle = "Главная";
        heading.setText("Главная");
        showPageLoading("Подключение к Kinozal…");
        if (sessionWebView != null) {
            loadHomeSectionsInBrowser(new String[]{"novinki.php", "top.php",
                    "top.php?t=0&d=0&f=0&k=0&j=&s=0&w=1",
                    "top.php?t=0&d=0&f=0&k=0&j=&s=0&w=2"},
                    new String[]{"Новинки", "Топ раздач", "Топ недели", "Топ месяца"}, 0,
                    new ArrayList<>(), 0);
            return;
        }
        io.execute(() -> {
            try {
                String[] paths = {
                        "novinki.php",
                        "top.php",
                        "top.php?t=0&d=0&f=0&k=0&j=&s=0&w=1",
                        "top.php?t=0&d=0&f=0&k=0&j=&s=0&w=2"
                };
                String[] titles = {"Новинки", "Топ раздач", "Топ недели", "Топ месяца"};
                List<HomeSection> sections = new ArrayList<>();
                for (int i = 0; i < paths.length; i++) {
                    String html = client.fetch(paths[i]);
                    HomeSection section = new HomeSection(titles[i], paths[i]);
                    section.items.addAll(parseHomePosters(Jsoup.parse(html, client.getMirror() + "/")));
                    sections.add(section);
                }
                removeHomeDuplicates(sections);
                int count = 0;
                for (HomeSection section : sections) count += section.items.size();
                final int total = count;
                if (total == 0 && startupRetry == 0) {
                    runOnUiThread(() -> {
                        status.setText("Повторное подключение к Kinozal…");
                        browserHandler.postDelayed(() -> loadInitialCatalog(1), 1500);
                    });
                    return;
                }
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("Главная · Постеров: " + total + "\nВерсия: " + getAppVersionName());
                    renderHome(sections);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("Нет соединения");
                    addEmpty("Не удалось загрузить главную страницу. " + safe(e.getMessage()));
                });
            }
        });
    }

    private void loadHomeSectionsInBrowser(String[] paths, String[] titles, int index,
            List<HomeSection> sections, int retry) {
        if (index >= paths.length) {
            removeHomeDuplicates(sections);
            int total = 0;
            for (HomeSection section : sections) total += section.items.size();
            // The first WebView pass can receive a Cloudflare/session warm-up page
            // instead of the catalogue. Retry the complete home sequence once
            // automatically so the user does not need to press Главная twice.
            if (total == 0 && retry == 0) {
                status.setText("Повторное подключение к Kinozal…");
                browserHandler.postDelayed(() -> loadHomeSectionsInBrowser(
                        paths, titles, 0, new ArrayList<>(), 1), 1500);
                return;
            }
            progress.setVisibility(View.GONE);
            status.setText("Главная · Постеров: " + total + "\nВерсия: " + getAppVersionName());
            renderHome(sections);
            return;
        }
        fetchBrowserPage(paths[index], null, html -> {
            if (html == null || html.isEmpty()) {
                progress.setVisibility(View.GONE);
                status.setText("Нет соединения");
                addEmpty("Не удалось загрузить каталог через сохранённую браузерную сессию.");
                return;
            }
            HomeSection section = new HomeSection(titles[index], paths[index]);
            section.items.addAll(parseHomePosters(Jsoup.parse(html, client.getMirror() + "/")));
            sections.add(section);
            loadHomeSectionsInBrowser(paths, titles, index + 1, sections, retry);
        });
    }

    private List<HomePoster> parseHomePosters(Document doc) {
        List<HomePoster> out = new ArrayList<>();
        Elements anchors = doc.select("a[href*=details.php?id=]");
        for (Element a : anchors) {
            String linkPath = a.attr("href").toLowerCase(java.util.Locale.ROOT);
            if (linkPath.contains("userdetails.php") || linkPath.contains("userdetails")) continue;
            String href = mirrorUrl(a.absUrl("href"), a.attr("href"));
            if (href.isEmpty()) continue;
            Element img = a.selectFirst("img");
            if (img == null) {
                Element row = a.closest("tr");
                if (row != null) img = row.selectFirst("img");
            }
            if (img == null && a.parent() != null) img = a.parent().selectFirst("img");
            if (img == null) {
                Element ancestor = a;
                for (int depth = 0; depth < 4 && ancestor != null && img == null; depth++) {
                    ancestor = ancestor.parent();
                    if (ancestor != null) img = ancestor.selectFirst("img");
                }
            }
            String title = a.attr("title").trim();
            if (title.isEmpty() && img != null) title = img.attr("alt").trim();
            if (title.isEmpty()) title = a.text().replaceAll("\\s+", " ").trim();
            if (title.isEmpty()) continue;
            String image = "";
            if (img != null) {
                String raw = firstImageSource(img);
                // Use the real lazy-load attribute, not a placeholder data: URL from src.
                image = mirrorUrl("", raw);
                if (image.isEmpty()) image = resolveImageUrl(raw);
            }
            HomePoster item = new HomePoster();
            item.torrent = new Torrent();
            item.torrent.title = title;
            item.torrent.href = href;
            Element statsRow = a.closest("tr");
            if (statsRow != null) {
                Elements cells = statsRow.select("td");
                item.torrent.size = cells.size() > 3 ? cells.get(3).text().trim() : "—";
                item.torrent.seeds = cells.size() > 4 ? cells.get(4).text().trim() : "—";
                item.torrent.leeches = cells.size() > 5 ? cells.get(5).text().trim() : "—";
            } else {
                item.torrent.size = item.torrent.seeds = item.torrent.leeches = "—";
            }
            item.imageUrl = image;
            boolean duplicate = false;
            for (HomePoster old : out) {
                if (old.torrent.href.equals(href)) { duplicate = true; break; }
            }
            if (!duplicate) out.add(item);
        }
        return out;
    }

    /**
     * Collapse alternate encodes of the same release inside each home section.
     * Keep the torrent with the most seeders; leechers break ties. Other sections
     * remain independent so a title can appear in multiple home collections.
     */
    private void removeHomeDuplicates(List<HomeSection> sections) {
        for (HomeSection section : sections) {
            java.util.LinkedHashMap<String, HomePoster> bestByTitle = new java.util.LinkedHashMap<>();
            java.util.Map<String, String> posterGroups = new java.util.HashMap<>();
            java.util.Set<String> seenLinks = new java.util.HashSet<>();
            List<HomePoster> unique = new ArrayList<>();
            for (HomePoster item : section.items) {
                String href = item.torrent.href == null ? "" : item.torrent.href;
                String id = "";
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("[?&]id=(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(href);
                if (matcher.find()) id = matcher.group(1);
                if ((!id.isEmpty() && seenLinks.contains("id:" + id))
                        || (!href.isEmpty() && seenLinks.contains("url:" + href))) continue;
                if (!id.isEmpty()) seenLinks.add("id:" + id);
                if (!href.isEmpty()) seenLinks.add("url:" + href);

                String titleKey = normalizeHomeTitle(item.torrent.title);
                String posterKey = normalizeHomePosterImage(item.imageUrl);
                // Kinozal can expose the same movie under slightly different title
                // strings. The poster URL is a second identity signal that catches
                // those aliases without merging unrelated seasons by title alone.
                String groupKey = titleKey;
                if (!posterKey.isEmpty()) {
                    String knownGroup = posterGroups.get(posterKey);
                    if (knownGroup != null) groupKey = knownGroup;
                    else if (groupKey.isEmpty()) groupKey = "poster:" + posterKey;
                    posterGroups.put(posterKey, groupKey);
                }
                if (groupKey.isEmpty()) {
                    unique.add(item);
                    continue;
                }
                HomePoster current = bestByTitle.get(groupKey);
                if (current == null) {
                    bestByTitle.put(groupKey, item);
                } else if (homeTorrentScore(item) > homeTorrentScore(current)) {
                    bestByTitle.put(groupKey, item);
                }
            }
            unique.addAll(bestByTitle.values());
            section.items.clear();
            section.items.addAll(unique.subList(0, Math.min(15, unique.size())));
        }
    }

    private String normalizeHomePosterImage(String imageUrl) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) return "";
        try {
            java.net.URI uri = java.net.URI.create(imageUrl.trim());
            String path = uri.getPath();
            if (path == null || path.isEmpty()) return "";
            return path.replaceAll("/+$", "").toLowerCase(java.util.Locale.ROOT);
        } catch (Exception ignored) {
            return imageUrl.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private String getAppVersionName() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "неизвестна" : info.versionName;
        } catch (Exception ignored) {
            return "неизвестна";
        }
    }

    private int homeTorrentScore(HomePoster item) {
        return parseInt(item.torrent.seeds) * 1000000 + parseInt(item.torrent.leeches);
    }

    private String normalizeHomeTitle(String title) {
        if (title == null) return "";
        String value = title.toLowerCase(java.util.Locale.ROOT);
        value = value.replaceAll("\\b(2160p|1080p|720p|480p|4k|uhd|hdr10?|web[- .]?dl|webrip|blu[- .]?ray|bdrip|brrip|dvdrip|hdtv|h265|h264|x265|x264|hevc|avc|aac|dts|ac3|proper|repack|remux)\\b", " ");
        return value.replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private String firstImageSource(Element img) {
        String[] attributes = {"data-src", "data-original", "data-lazy-src", "data-original-src", "src"};
        for (String attribute : attributes) {
            String value = img.attr(attribute).trim();
            if (!value.isEmpty() && !value.startsWith("data:image/")) return value;
        }
        String srcset = img.attr("data-srcset");
        if (srcset.isEmpty()) srcset = img.attr("srcset");
        if (!srcset.isEmpty()) return srcset.split(",")[0].trim().split("\\s+")[0];
        return "";
    }

    private String resolveImageUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        try {
            java.net.URI uri = java.net.URI.create(raw.trim());
            return uri.isAbsolute() ? uri.toString()
                    : java.net.URI.create(client.getMirror() + "/").resolve(uri).toString();
        } catch (Exception ignored) { return raw.trim(); }
    }

    /**
     * Some Kinozal home layouts keep the poster separate from the title link.
     * Resolve it from the torrent page og:image, also used by the details view.
     */
    private void loadHomePosterFromDetails(HomePoster item, ImageView target) {
        io.execute(() -> {
            try {
                String html = client.fetch(item.torrent.href);
                Document doc = Jsoup.parse(html, item.torrent.href);
                Element image = doc.selectFirst("meta[property=og:image]");
                String imageUrl = "";
                if (image != null) {
                    String raw = image.attr("content").trim();
                    if (!raw.isEmpty()) imageUrl = doc.baseUri().isEmpty()
                            ? resolveImageUrl(raw) : java.net.URI.create(doc.baseUri()).resolve(raw).toString();
                }
                if (imageUrl.isEmpty()) {
                    Element poster = doc.selectFirst("img[src*=/poster/], img[data-src*=/poster/]");
                    if (poster != null) {
                        String raw = firstImageSource(poster);
                        imageUrl = poster.absUrl("src");
                        if (imageUrl.isEmpty()) imageUrl = resolveImageUrl(raw);
                    }
                }
                if (imageUrl.isEmpty()) return;
                byte[] bytes = client.fetchBytes(imageUrl, "image/*", item.torrent.href);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) runOnUiThread(() -> {
                    if (target.getParent() != null) target.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) { }
        });
    }

    private void loadImageIntoView(String imageUrl, ImageView target) {
        loadImageIntoView(imageUrl, target, client.getMirror() + "/");
    }

    private void loadImageIntoView(String imageUrl, ImageView target, String referer) {
        if (imageUrl == null || imageUrl.isEmpty()) return;
        WebView web = sessionWebView;
        if (web == null) {
            loadImageWithHttp(imageUrl, target, referer);
            return;
        }
        String quotedUrl = org.json.JSONObject.quote(imageUrl);
        String script = "(function(){return fetch(" + quotedUrl + ",{credentials:'include'})"
                + ".then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.blob();})"
                + ".then(function(b){return new Promise(function(resolve,reject){var f=new FileReader();"
                + "f.onloadend=function(){var v=String(f.result||'');resolve(v.substring(v.indexOf(',')+1));};"
                + "f.onerror=reject;f.readAsDataURL(b);});});})()";
        web.evaluateJavascript(script, result -> {
            String encoded = null;
            try { encoded = new org.json.JSONArray("[" + result + "]").getString(0); }
            catch (Exception ignored) { }
            if (encoded == null || encoded.isEmpty() || "null".equals(encoded)) {
                loadImageWithHttp(imageUrl, target, referer);
                return;
            }
            final String base64 = encoded;
            io.execute(() -> {
                try {
                    byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap != null) runOnUiThread(() -> {
                        if (target.getParent() != null) target.setImageBitmap(bitmap);
                    });
                    else loadImageWithHttp(imageUrl, target, referer);
                } catch (Exception ignored) { loadImageWithHttp(imageUrl, target, referer); }
            });
        });
    }

    private void loadImageWithHttp(String imageUrl, ImageView target) {
        loadImageWithHttp(imageUrl, target, client.getMirror() + "/");
    }

    private void loadImageWithHttp(String imageUrl, ImageView target, String referer) {
        io.execute(() -> {
            try {
                byte[] bytes = client.fetchBytes(imageUrl, "image/*", referer);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) runOnUiThread(() -> {
                    if (target.getParent() != null) target.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) { }
        });
    }

    private String mirrorUrl(String absolute, String raw) {
        String value = absolute == null || absolute.isEmpty() ? raw : absolute;
        if (value == null || value.trim().isEmpty()) return "";
        try {
            java.net.URI uri = java.net.URI.create(value.trim());
            java.net.URI base = java.net.URI.create(client.getMirror());
            if (!uri.isAbsolute()) return base.resolve(uri).toString();

            // Only translate URLs that point to one of Kinozal's known site mirrors.
            // Poster/CDN hosts are separate from the selected mirror and must stay intact.
            String host = uri.getHost();
            if (host != null) {
                for (String knownMirror : MIRRORS) {
                    String mirrorHost = java.net.URI.create(knownMirror).getHost();
                    if (mirrorHost != null && host.equalsIgnoreCase(mirrorHost)) {
                        return new java.net.URI(base.getScheme(), null, base.getHost(), base.getPort(),
                                uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
                    }
                }
            }
            return uri.toString();
        } catch (Exception ignored) { return value; }
    }

    private void renderHome(List<HomeSection> sections) {
        list.removeAllViews();
        for (HomeSection section : sections) {
            TextView sectionTitle = text(section.title, 20, GOLD, true);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, dp(38));
            titleParams.topMargin = dp(10);
            list.addView(sectionTitle, titleParams);
            android.widget.HorizontalScrollView horizontal = new android.widget.HorizontalScrollView(this);
            horizontal.setHorizontalScrollBarEnabled(false);
            horizontal.setFillViewport(false);
            LinearLayout strip = new LinearLayout(this);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            for (HomePoster item : section.items) {
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(5), dp(5), dp(5), dp(5));
                card.setBackground(shape(PANEL, 0));
                card.setFocusable(true);
                card.setClickable(true);
                card.setOnFocusChangeListener((v, focused) ->
                        v.setBackground(shape(focused ? Color.rgb(57, 68, 70) : PANEL, focused ? GOLD : 0)));
                ImageView poster = new ImageView(this);
                poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
                poster.setBackground(shape(Color.rgb(40, 45, 49), 0));
                card.addView(poster, new LinearLayout.LayoutParams(dp(126), dp(178)));
                TextView name = text(item.torrent.title, 12, Color.WHITE, true);
                name.setMaxLines(2);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setPadding(dp(2), dp(6), dp(2), dp(2));
                card.addView(name, new LinearLayout.LayoutParams(dp(126), dp(42)));
                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(138), -2);
                cardParams.rightMargin = dp(8);
                strip.addView(card, cardParams);
                card.setOnClickListener(v -> showTorrent(item.torrent));
                if (!item.imageUrl.isEmpty()) {
                    loadImageIntoView(item.imageUrl, poster, client.getMirror() + "/" + section.path);
                } else {
                    loadHomePosterFromDetails(item, poster);
                }
            }
            horizontal.addView(strip);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(238));
            list.addView(horizontal, rowParams);
        }
        if (sections.isEmpty()) addEmpty("На главной пока нет подборок.");
    }

    private int dp(float n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }

    private GradientDrawable shape(int color, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(9));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size);
        t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(GOLD);
        b.setTextSize(14);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setIncludeFontPadding(false);
        b.setFocusable(true);
        b.setFocusableInTouchMode(false);
        b.setStateListAnimator(null);
        b.setBackground(shape(PANEL, 0));
        // Keep the focus outline inside the button bounds. Scaling caused the
        // highlight to overlap neighbouring rows and made the sidebar look oversized.
        b.setOnFocusChangeListener((v, focus) -> {
            v.setBackground(shape(focus ? Color.rgb(65, 72, 73) : PANEL,
                    focus ? GOLD : 0));
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.setElevation(0f);
        });
        return b;
    }

    private void buildShell() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG); root.setPadding(dp(12), dp(12), dp(12), dp(12));
        sidebar = new LinearLayout(this); sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(8), dp(8), dp(8), dp(8)); sidebar.setBackground(shape(PANEL, 0));
        root.addView(sidebar, new LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout main = new LinearLayout(this); main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(16), dp(8), dp(8), dp(8));
        root.addView(main, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout brand = new LinearLayout(this); brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = text("KINOZAL", 23, GOLD, true); brand.addView(logo);
        TextView sub = text("  NATIVE TV", 11, TEAL, true); brand.addView(sub);
        sidebar.addView(brand, new LinearLayout.LayoutParams(-1, dp(58)));
        ScrollView menuScroll = new ScrollView(this);
        menuScroll.setFillViewport(false);
        menuScroll.setVerticalScrollBarEnabled(true);
        menuScroll.setScrollbarFadingEnabled(false);
        // Reserve a narrow gutter for the scrollbar instead of drawing it
        // over the right edge of focused menu buttons.
        menuScroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        menuScroll.setPadding(0, 0, dp(22), 0);
        menuItems = new LinearLayout(this);
        menuItems.setOrientation(LinearLayout.VERTICAL);
        menuScroll.addView(menuItems, new ScrollView.LayoutParams(-1, -2));
        sidebar.addView(menuScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        addMenu("Главная", () -> loadInitialCatalog());
        addMenu("Все раздачи", () -> load("browse.php", "Все раздачи"));
        addMenu("Все сериалы", () -> load("browse.php?c=1001", "Все сериалы"));
        addMenu("Зарубежные сериалы", () -> load("browse.php?c=46", "Зарубежные сериалы"));
        addMenu("Российские сериалы", () -> load("browse.php?c=45", "Российские сериалы"));
        addMenu("Все фильмы", () -> load("browse.php?c=1002", "Все фильмы"));
        addMenu("Советские фильмы", () -> load("browse.php?c=10", "Советские фильмы"));
        addMenu("Мультфильмы", () -> load("browse.php?c=1003", "Мультфильмы"));
        addMenu("Российские мульты", () -> load("browse.php?c=22", "Российские мульты"));
        addMenu("Зарубежные мульты", () -> load("browse.php?c=21", "Зарубежные мульты"));
        addMenu("Аниме", () -> load("browse.php?c=20", "Аниме"));
        addMenu("Спортивные передачи", () -> load("browse.php?c=37", "Спортивные передачи"));
        addMenu("Российское ТВ", () -> load("browse.php?c=49", "Российское ТВ"));
        addMenu("Зарубежное ТВ", () -> load("browse.php?c=50", "Зарубежное ТВ"));
        addMenu("Концерты", () -> load("browse.php?c=48", "Концерты"));
        addMenu("Видеоклипы", () -> load("browse.php?c=1", "Видеоклипы"));
        addMenu("Избранное", () -> showFavorites());
        addMenu("Авторизация", () -> showLogin());
        addMenu("Зеркало", () -> chooseMirror());
        addMenu("Проверить обновления", () -> new UpdateManager(this).check());
        status = text("Подключение…", 11, TEAL, false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, dp(28));
        statusParams.topMargin = dp(4);
        sidebar.addView(status, statusParams);

        LinearLayout searchBar = new LinearLayout(this); searchBar.setGravity(Gravity.CENTER_VERTICAL);
        search = new EditText(this);
        search.setSingleLine(true);
        search.setMinWidth(0);
        search.setMinEms(0);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(TEAL);
        search.setHint("Название фильма или раздачи");
        search.setTextSize(16);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setBackground(shape(PANEL, TEAL));
        search.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        searchParams.rightMargin = dp(8);
        searchBar.addView(search, searchParams);
        Button find = button("Найти");
        find.setMinWidth(0);
        LinearLayout.LayoutParams findParams = new LinearLayout.LayoutParams(dp(104), dp(48));
        searchBar.addView(find, findParams);
        find.setOnClickListener(v -> submitSearch());
        search.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { submitSearch(); return true; }
            return false;
        });
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(-1, dp(52)); sbp.bottomMargin = dp(8);
        main.addView(searchBar, sbp);
        heading = text("Главная", 25, GOLD, true);
        main.addView(heading, new LinearLayout.LayoutParams(-1, dp(42)));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        main.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (menuItems.getChildCount() > 0) menuItems.getChildAt(0).requestFocus();
    }

    private void addMenu(String label, Runnable action) {
        Button b = button(label);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        b.setTextSize(14);
        b.setPadding(dp(14), 0, dp(8), 0);
        b.setMaxLines(1);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(47));
        // Make the focused button frame 5dp narrower on the scrollbar side.
        p.rightMargin = dp(5);
        p.bottomMargin = dp(3);
        menuItems.addView(b, p); b.setOnClickListener(v -> action.run());
    }

    private void focusSearch() { search.requestFocus(); }
    private void showMessage(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    private void showPageLoading(String message) {
        // No in-page spinner: keep the content area clean while the request runs.
        progress.setVisibility(View.GONE);
        list.removeAllViews();
        status.setText(message);
    }

    private void load(String path, String title) { load(path, title, null); }

    private void load(String path, String title, String postBody) {
        currentPath = path; currentTitle = title; heading.setText(title);
        String cacheKey = (postBody == null ? "GET|" + path : "POST|" + path + "|" + postBody);
        String cachedPage;
        synchronized (pageCache) { cachedPage = pageCache.get(cacheKey); }
        if (cachedPage != null) { showLoadedPage(cachedPage); return; }
        showPageLoading("Загрузка каталога…");
        if (sessionWebView != null) {
            fetchBrowserPage(path, postBody, html -> {
                if (html != null && !html.isEmpty()) {
                    synchronized (pageCache) { pageCache.put(cacheKey, html); }
                    showLoadedPage(html);
                } else {
                    progress.setVisibility(View.GONE);
                    status.setText("Нет соединения");
                    addEmpty("Не удалось загрузить каталог через браузерную сессию.");
                }
            });
        } else {
            io.execute(() -> {
                try {
                    String html = postBody == null ? client.fetch(path) : client.fetchPost(path, postBody);
                    synchronized (pageCache) { pageCache.put(cacheKey, html); }
                    runOnUiThread(() -> showLoadedPage(html));
                } catch (Exception e) {
                    runOnUiThread(() -> { progress.setVisibility(View.GONE); status.setText("Нет соединения");
                        addEmpty("Не удалось загрузить каталог. " + safe(e.getMessage())); });
                }
            });
        }
    }

    private void restoreBrowserSession(String cookieHeader) {
        // Restore the same authenticated browser session into the native HTTP
        // cookie jar as well. The WebView and HttpURLConnection keep separate jars.
        try {
            client.importBrowserCookies(cookieHeader);
        } catch (Exception e) {
            showMessage("Не удалось восстановить HTTP-сессию Kinozal: " + safe(e.getMessage()));
        }
        WebView web = new WebView(this);
        sessionWebView = web;
        web.setVisibility(View.INVISIBLE);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setLoadsImagesAutomatically(false);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager manager = CookieManager.getInstance();
        manager.setAcceptCookie(true);
        manager.setAcceptThirdPartyCookies(web, true);
        String[] pairs = cookieHeader.split(";");
        final int[] remaining = {0};
        for (String pair : pairs) if (pair.trim().contains("=")) remaining[0]++;
        if (remaining[0] == 0) {
            markStartupCatalogReady();
            return;
        }
        final boolean[] started = {false};
        android.webkit.ValueCallback<Boolean> callback = accepted -> {
            remaining[0]--;
            if (remaining[0] <= 0 && !started[0]) {
                started[0] = true;
                manager.flush();
                markStartupCatalogReady();
            }
        };
        for (String pair : pairs) {
            String item = pair.trim();
            if (!item.contains("=")) continue;
            manager.setCookie(client.getMirror(), item + "; Path=/; Secure; SameSite=Lax", callback);
        }
        web.setWebViewClient(new WebViewClient() {
            private boolean isMirror(Uri uri) {
                return uri != null && uri.getHost() != null
                        && uri.getHost().equalsIgnoreCase(Uri.parse(client.getMirror()).getHost());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request.isForMainFrame() && !isMirror(request.getUrl());
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // A WebView can finish an older navigation after a newer request
                // has installed its callback. Never let that stale page consume it.
                if (pendingBrowserCallback != null
                        && isExpectedBrowserPage(url, pendingBrowserTarget)) {
                    final BrowserPageCallback cb = pendingBrowserCallback;
                    final long completedSerial = browserRequestSerial;
                    final String completedTarget = pendingBrowserTarget;
                    pendingBrowserCallback = null;
                    pendingBrowserTarget = "";
                    view.evaluateJavascript("(document.documentElement ? document.documentElement.outerHTML : '')", value -> {
                        // evaluateJavascript is asynchronous. A second navigation can
                        // replace the document before this callback returns; discard it.
                        if (completedSerial != browserRequestSerial
                                || !isExpectedBrowserPage(view.getUrl(), completedTarget)) return;
                        String html = "";
                        try { html = new org.json.JSONArray("[" + value + "]").getString(0); }
                        catch (Exception ignored) { }
                        if (html.isEmpty()) {
                            cb.onPage(null, "Пустая страница браузера");
                            return;
                        }
                        Document loaded = Jsoup.parse(html);
                        boolean loginForm = !loaded.select("form:has(input[type=password])").isEmpty();
                        String pageUrl = view.getUrl() == null ? "" : view.getUrl().toLowerCase(java.util.Locale.ROOT);
                        if (loginForm || pageUrl.contains("login.php") || pageUrl.contains("takelogin.php")) {
                            cb.onPage(null, "Сессия Kinozal не активна: сайт снова показывает форму входа. Откройте «Авторизация» и войдите.");
                            return;
                        }
                        cb.onPage(html, null);
                    });
                }
                String currentCookies = manager.getCookie(client.getMirror());
                if (currentCookies != null && !currentCookies.isEmpty()) {
                    try {
                        client.saveBrowserCookies(currentCookies);
                        client.importBrowserCookies(currentCookies);
                    } catch (Exception ignored) { }
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient());
    }

    private boolean isExpectedBrowserPage(String finishedUrl, String targetUrl) {
        if (finishedUrl == null || finishedUrl.isEmpty()
                || targetUrl == null || targetUrl.isEmpty()) return false;
        try {
            Uri finished = Uri.parse(finishedUrl);
            Uri expected = Uri.parse(targetUrl);
            String finishedHost = finished.getHost() == null ? "" : finished.getHost();
            String expectedHost = expected.getHost() == null ? "" : expected.getHost();
            if (!finishedHost.equalsIgnoreCase(expectedHost)) return false;

            String finishedPath = finished.getPath() == null ? "/" : finished.getPath();
            String expectedPath = expected.getPath() == null ? "/" : expected.getPath();
            String lowerPath = finishedPath.toLowerCase(java.util.Locale.ROOT);
            if (lowerPath.contains("login.php") || lowerPath.contains("takelogin.php")) return true;
            if (!finishedPath.equals(expectedPath)) return false;

            // Details pages are sometimes redirected with extra tracking/session
            // parameters. Match the torrent ID, not the entire query-string order.
            if (expectedPath.toLowerCase(java.util.Locale.ROOT).endsWith("/details.php")
                    || expectedPath.equalsIgnoreCase("/details.php")) {
                String expectedId = expected.getQueryParameter("id");
                String finishedId = finished.getQueryParameter("id");
                return expectedId != null && expectedId.equals(finishedId);
            }
            return java.util.Objects.equals(finished.getEncodedQuery(), expected.getEncodedQuery());
        } catch (Exception ignored) {
            return finishedUrl.equals(targetUrl);
        }
    }

    private void fetchBrowserPage(String path, String postBody,
            java.util.function.Consumer<String> callback) {
        WebView web = sessionWebView;
        if (web == null) { callback.accept(null); return; }
        // Complete any previous request before starting another one. A stale
        // WebView callback must never leave the UI waiting indefinitely.
        if (browserRequestTimeout != null) browserHandler.removeCallbacks(browserRequestTimeout);
        final long requestSerial = ++browserRequestSerial;
        // Superseded requests are silently discarded. Their callers validate their
        // own request IDs and must not replace the UI with an obsolete error/result.
        pendingBrowserCallback = null;
        pendingBrowserTarget = "";
        // Stop the previous navigation before assigning the next request.
        web.stopLoading();
        if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
        web.setVisibility(View.INVISIBLE);
        if (web.getParent() == null) root.addView(web, new LinearLayout.LayoutParams(1, 1));
        String target = path.startsWith("http") ? path
                : client.getMirror() + "/" + path.replaceFirst("^/+", "");
        pendingBrowserTarget = target;
        pendingBrowserCallback = (html, error) -> {
            if (browserRequestTimeout != null) browserHandler.removeCallbacks(browserRequestTimeout);
            browserRequestTimeout = null;
            pendingBrowserTarget = "";
            runOnUiThread(() -> callback.accept(error == null ? html : null));
        };
        browserRequestTimeout = () -> {
            if (requestSerial != browserRequestSerial || pendingBrowserCallback == null) return;
            BrowserPageCallback timedOut = pendingBrowserCallback;
            pendingBrowserCallback = null;
            pendingBrowserTarget = "";
            browserRequestTimeout = null;
            web.stopLoading();
            timedOut.onPage(null, "Тайм-аут ожидания ответа Kinozal (30 секунд). Проверьте соединение или авторизацию.");
        };
        browserHandler.postDelayed(browserRequestTimeout, 30000);
        if (postBody == null) {
            web.loadUrl(target);
        } else {
            web.postUrl(target, postBody.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void showLoadedPage(String html) {
        lastHtml = html;
        List<Torrent> parsed = parseTorrents(Jsoup.parse(html, client.getMirror()));
        progress.setVisibility(View.GONE);
        torrents.clear(); torrents.addAll(parsed);
        status.setText("Загружено: " + parsed.size());
        renderTorrents(parsed);
        if (parsed.isEmpty()) addEmpty("Страница загружена, но раздачи не найдены. Возможно, фильтр не применился.");
    }
    private List<Torrent> parseTorrents(Document doc) {
        List<Torrent> out = new ArrayList<>();

        // On Kinozal, uploader profiles use userdetails.php?id=...
        // A broad "details.php?id=" selector also matched those links,
        // causing usernames to appear as separate torrent cards.
        // Torrent titles live specifically in the "nam" table cell.
        Elements anchors = doc.select("table.t_peer td.nam > a[href*=details.php?id=]");
        for (Element a : anchors) {
            String name = a.text().trim();
            if (name.length() < 3) continue;

            Element row = a.closest("tr");
            String href = a.absUrl("href");
            if (href.isEmpty()) href = a.attr("href");

            Torrent t = new Torrent();
            t.title = name;
            t.href = href;

            if (row != null) {
                Elements cells = row.select("td");
                // Kinozal columns: icon, title, comments, size, seeds, peers, uploaded, uploader.
                t.size = cells.size() > 3 ? cells.get(3).text().trim() : "—";
                t.seeds = cells.size() > 4 ? cells.get(4).text().trim() : "—";
                t.leeches = cells.size() > 5 ? cells.get(5).text().trim() : "—";
                t.uploaded = cells.size() > 6 ? cells.get(6).text().trim() : "—";
            } else {
                t.size = "—";
                t.seeds = "—";
                t.leeches = "—";
                t.uploaded = "—";
            }

            boolean duplicate = false;
            for (Torrent old : out) {
                if (old.href.equals(t.href)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) out.add(t);
        }
        return out;
    }

    private void renderTorrents(List<Torrent> items) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(8), dp(14), dp(8));
        TextView titleHeader = text("НАЗВАНИЕ РАЗДАЧИ", 11, TEAL, true);
        header.addView(titleHeader, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView sizeHeader = text("РАЗМЕР", 11, TEAL, true);
        sizeHeader.setGravity(Gravity.CENTER);
        header.addView(sizeHeader, new LinearLayout.LayoutParams(dp(85), -2));
        TextView dateHeader = text("ДАТА", 11, TEAL, true);
        dateHeader.setGravity(Gravity.CENTER);
        header.addView(dateHeader, new LinearLayout.LayoutParams(dp(96), -2));
        TextView seedsHeader = text("СИДЫ", 11, TEAL, true);
        seedsHeader.setGravity(Gravity.CENTER);
        header.addView(seedsHeader, new LinearLayout.LayoutParams(dp(55), -2));
        TextView leechesHeader = text("ЛИЧИ", 11, TEAL, true);
        leechesHeader.setGravity(Gravity.CENTER);
        header.addView(leechesHeader, new LinearLayout.LayoutParams(dp(55), -2));
        list.addView(header);

        for (Torrent t : items) {
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(8), dp(14), dp(8)); row.setBackground(shape(PANEL, 0));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(66)); rp.bottomMargin = dp(5);
            TextView name = text(t.title, 16, Color.WHITE, true); name.setMaxLines(2);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView size = text(t.size, 13, GOLD, false);
            size.setGravity(Gravity.CENTER);
            row.addView(size, new LinearLayout.LayoutParams(dp(85), -2));
            TextView uploaded = text(t.uploaded == null ? "—" : t.uploaded, 12, Color.rgb(170, 205, 180), false);
            uploaded.setGravity(Gravity.CENTER);
            uploaded.setMaxLines(2);
            row.addView(uploaded, new LinearLayout.LayoutParams(dp(96), -2));
            TextView seeds = text(t.seeds, 13, TEAL, true);
            seeds.setGravity(Gravity.CENTER);
            row.addView(seeds, new LinearLayout.LayoutParams(dp(55), -2));
            TextView leeches = text(t.leeches, 13, RED, true);
            leeches.setGravity(Gravity.CENTER);
            row.addView(leeches, new LinearLayout.LayoutParams(dp(55), -2));
            row.setFocusable(true); row.setOnFocusChangeListener((v, f) -> v.setBackground(shape(f ? Color.rgb(57, 68, 70) : PANEL, f ? GOLD : 0)));
            row.setOnClickListener(v -> showTorrent(t)); list.addView(row, rp);
        }
        LinearLayout pages = new LinearLayout(this); pages.setGravity(Gravity.RIGHT);
        Button prev = button("← Назад"); Button next = button("Далее →");
        pages.addView(prev); pages.addView(next);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(52)); pp.topMargin = dp(10); list.addView(pages, pp);
        prev.setOnClickListener(v -> changePage(-1)); next.setOnClickListener(v -> changePage(1));
    }

    private void changePage(int delta) {
        int next = Math.max(0, page + delta); if (next == page) return; page = next;
        String path = currentPath + (currentPath.contains("?") ? "&" : "?") + "page=" + (page + 1);
        load(path, currentTitle);
    }

    private void submitSearch() {
        String q = search.getText().toString().trim(); if (q.isEmpty()) return;
        try { load("browse.php?s=" + URLEncoder.encode(q, "UTF-8"), "Поиск: " + q); }
        catch (Exception e) { showMessage("Ошибка поиска"); }
    }

    private void categories() { categories(null); }

    private void categories(String preferredTitle) {
        // Reuse the catalogue response instead of requesting browse.php again.
        // This avoids unnecessary duplicate traffic and reduces HTTP 429 responses.
        String cached = lastHtml;
        if (cached == null || cached.isEmpty()) {
            showMessage("Сначала загрузите каталог, затем откройте категории.");
            return;
        }
        progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                Document d = Jsoup.parse(cached, client.getMirror() + "/");
                List<Category> found = new ArrayList<>();
                java.util.LinkedHashMap<String, Category> unique = new java.util.LinkedHashMap<>();

                // Kinozal exposes category navigation as ordinary links on some mirrors
                // and as query-parameter links on others. Keep the original href intact.
                for (Element a : d.select("a[href]")) {
                    String label = a.text().replaceAll("\\s+", " ").trim();
                    String href = a.attr("href").trim();
                    if (label.isEmpty() || label.length() > 90 || href.isEmpty()
                            || href.startsWith("#") || href.toLowerCase(java.util.Locale.ROOT).startsWith("javascript:")) continue;
                    String lower = href.toLowerCase(java.util.Locale.ROOT);
                    boolean categoryLink = lower.matches(".*(?:[?&](?:cat|category|browse|section|c)=|/browse\\.php\\?).*");
                    if (!categoryLink) continue;
                    String absolute = a.absUrl("href");
                    if (absolute.isEmpty()) absolute = java.net.URI.create(client.getMirror() + "/").resolve(href).toString();
                    try {
                        java.net.URI base = java.net.URI.create(client.getMirror());
                        java.net.URI target = java.net.URI.create(absolute);
                        if (!base.getHost().equalsIgnoreCase(target.getHost())) continue;
                    } catch (Exception ignored) { continue; }
                    if (lower.contains("page=") || lower.contains("sort=") || lower.contains("s=")) continue;
                    Category item = new Category(); item.title = label; item.href = absolute;
                    unique.putIfAbsent(absolute, item);
                }

                // Also support mirrors that render the section list as a select menu.
                for (Element select : d.select("select")) {
                    String field = select.attr("name").trim();
                    if (field.isEmpty()) continue;
                    for (Element option : select.select("option[value]")) {
                        String value = option.attr("value").trim();
                        String label = option.text().replaceAll("\\s+", " ").trim();
                        if (value.isEmpty() || label.isEmpty() || label.length() > 90
                                || value.equals("0") || value.equals("-1")) continue;
                        Element form = select.closest("form");
                        String action = form == null || form.attr("action").isEmpty()
                                ? client.getMirror() + "/browse.php" : form.absUrl("action");
                        if (action.isEmpty()) action = client.getMirror() + "/browse.php";
                        java.util.LinkedHashMap<String, String> params = new java.util.LinkedHashMap<>();
                        if (form != null) {
                            for (Element input : form.select("input[name]")) {
                                String type = input.attr("type").toLowerCase(java.util.Locale.ROOT);
                                if (!type.equals("submit") && !type.equals("button") && !type.equals("file")) {
                                    params.put(input.attr("name"), input.attr("value"));
                                }
                            }
                            for (Element otherSelect : form.select("select[name]")) {
                                String otherName = otherSelect.attr("name");
                                Element selectedOption = otherSelect.selectFirst("option[selected]");
                                if (otherSelect == select) params.put(otherName, value);
                                else if (selectedOption != null) params.put(otherName, selectedOption.attr("value"));
                            }
                        } else {
                            params.put(field, value);
                        }
                        StringBuilder encoded = new StringBuilder();
                        for (java.util.Map.Entry<String, String> entry : params.entrySet()) {
                            if (encoded.length() > 0) encoded.append('&');
                            encoded.append(java.net.URLEncoder.encode(entry.getKey(), "UTF-8"))
                                    .append('=').append(java.net.URLEncoder.encode(entry.getValue(), "UTF-8"));
                        }
                        Category item = new Category(); item.title = label;
                        item.method = form != null && form.attr("method").equalsIgnoreCase("post") ? "POST" : "GET";
                        if ("POST".equals(item.method)) {
                            item.href = action; item.body = encoded.toString();
                        } else {
                            item.href = action + (action.contains("?") ? "&" : "?") + encoded;
                        }
                        unique.putIfAbsent(item.href + "|" + item.body, item);
                    }
                }

                found.addAll(unique.values());
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    if (found.isEmpty()) {
                        showMessage("На странице зеркала не найдены ссылки разделов Kinozal. Проверьте выбранное зеркало и авторизацию.");
                        return;
                    }
                    if (preferredTitle != null && !preferredTitle.trim().isEmpty()) {
                        String wanted = preferredTitle.trim().toLowerCase(java.util.Locale.ROOT);
                        Category match = null;
                        for (Category candidate : found) {
                            String actual = candidate.title.trim().toLowerCase(java.util.Locale.ROOT);
                            String hrefText = candidate.href.toLowerCase(java.util.Locale.ROOT);
                            boolean exact = actual.equals(wanted);
                            boolean tv = wanted.contains("телевид") && (actual.contains("телевид") || actual.contains("телеперед") || actual.equals("тв") || hrefText.contains("television") || hrefText.contains("tv="));
                            boolean sport = wanted.contains("спорт") && (actual.contains("спорт") || actual.contains("футбол") || actual.contains("хоккей") || hrefText.contains("sport"));
                            if (exact || tv || sport) { match = candidate; break; }
                        }
                        if (match != null) {
                            if ("POST".equals(match.method)) load(match.href, match.title, match.body);
                            else load(match.href, match.title);
                            return;
                        }
                        showMessage("Раздел «" + preferredTitle + "» не найден в меню выбранного зеркала.");
                        return;
                    }
                    String[] labels = new String[found.size()];
                    for (int i = 0; i < found.size(); i++) labels[i] = found.get(i).title;
                    new AlertDialog.Builder(this).setTitle("Категории Kinozal")
                            .setItems(labels, (dialog, which) -> {
                                Category selected = found.get(which);
                                if ("POST".equals(selected.method)) load(selected.href, selected.title, selected.body);
                                else load(selected.href, selected.title);
                            }).setNegativeButton("Закрыть", null).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    showMessage("Не удалось получить категории: " + safe(e.getMessage()));
                });
            }
        });
    }

    private void showLogin() {
        // Never fire a background login attempt just by opening the authorization screen.
        // The user submits exactly one request sequence from the visible native form.
        showNativeLoginForm(client.getSavedCredentials());
    }

    /**
     * Opens a restricted browser only for the selected Kinozal mirror.
     * Popups and top-level redirects to unrelated/ad domains are blocked.
     * Cloudflare scripts and challenge frames remain enabled.
     */
    private void openCloudflareWindow() {
        final String base = client.getMirror();
        final String allowedHost;
        try { allowedHost = java.net.URI.create(base).getHost().toLowerCase(java.util.Locale.ROOT); }
        catch (Exception e) { showMessage("Некорректный адрес зеркала."); return; }

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(BG);
        panel.setMinimumHeight((int)(getResources().getDisplayMetrics().heightPixels * 0.72f));
        panel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WebView existingWeb = sessionWebView;
        if (existingWeb == null) {
            existingWeb = new WebView(this);
            sessionWebView = existingWeb;
        } else if (existingWeb.getParent() instanceof ViewGroup) {
            ((ViewGroup) existingWeb.getParent()).removeView(existingWeb);
        }
        final WebView web = existingWeb;
        web.setBackgroundColor(Color.WHITE);
        web.setVisibility(View.VISIBLE);
        web.setMinimumHeight(dp(360));
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        // Let WebView handle DPAD/ENTER itself so focused HTML inputs can open Android TV IME.
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager browserCookies = CookieManager.getInstance();
        browserCookies.setAcceptCookie(true);
        CookieManager.setAcceptFileSchemeCookies(false);
        browserCookies.setAcceptThirdPartyCookies(web, true);
        final boolean[] textEntryOpen = {false};
        final String[] submittedLogin = {null};
        final String[] submittedPassword = {null};
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onTextFieldFocused(String fieldType) {
                runOnUiThread(() -> {
                    if (textEntryOpen[0] || web.getParent() == null) return;
                    textEntryOpen[0] = true;
                    showWebTextEntry(web, fieldType, () -> textEntryOpen[0] = false);
                });
            }
            @JavascriptInterface
            public void onLoginSubmit(String username, String password) {
                submittedLogin[0] = username;
                submittedPassword[0] = password;
            }
        }, "AndroidTV");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean isDialog,
                    boolean isUserGesture, android.os.Message resultMsg) {
                return false;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            private boolean allowed(Uri uri) {
                String host = uri == null ? null : uri.getHost();
                if (host == null) return false;
                host = host.toLowerCase(java.util.Locale.ROOT);
                return host.equals(allowedHost);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if (allowed(uri)) return false;
                showMessage("Переход на посторонний сайт заблокирован.");
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                if (allowed(uri)) return false;
                showMessage("Рекламный переход заблокирован.");
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (pendingBrowserCallback != null) {
                    BrowserPageCallback callback = pendingBrowserCallback;
                    pendingBrowserCallback = null;
                    view.evaluateJavascript("(document.documentElement ? document.documentElement.outerHTML : '')", value -> {
                        String html = "";
                        try { html = new org.json.JSONArray("[" + value + "]").getString(0); }
                        catch (Exception ignored) { }
                        callback.onPage(html, html.isEmpty() ? "Пустая страница браузера" : null);
                    });
                }
                view.evaluateJavascript("(function(){if(window.__kinozalInputHook)return;window.__kinozalInputHook=true;"
                        + "document.addEventListener('focusin',function(ev){var e=ev.target;if(!e)return;"
                        + "var tag=(e.tagName||'').toLowerCase(),type=(e.type||'text').toLowerCase();"
                        + "if((tag==='input'&&['text','password','email','search','tel','url','number'].indexOf(type)>=0)"
                        + "||tag==='textarea'||e.isContentEditable){window.__kinozalTarget=e;"
                        + "if(window.AndroidTV&&window.AndroidTV.onTextFieldFocused)"
                        + "window.AndroidTV.onTextFieldFocused(type==='password'?'password':'text');}},true);"
                        + "document.addEventListener('submit',function(ev){var f=ev.target,p=f.querySelector('input[type=password]');"
                        + "if(!p||!window.AndroidTV)return;var u=f.querySelector('input[type=text],input[type=email],input:not([type])');"
                        + "window.AndroidTV.onLoginSubmit(u?u.value:'',p.value);},true);})();", null);
                String cookie = browserCookies.getCookie(base);
                if (cookie != null && !cookie.isEmpty()) {
                    try {
                        client.importBrowserCookies(cookie);
                        client.saveBrowserCookies(cookie);
                    } catch (Exception ignored) { }
                }
            }
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(8), dp(6), dp(8), dp(6));
        Button close = button("Закрыть");
        Button done = button("Готово");
        actions.addView(close, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        doneParams.leftMargin = dp(8);
        actions.addView(done, doneParams);
        TextView hint = text("Пройдите проверку на сайте. Посторонние рекламные переходы и всплывающие окна блокируются.", 13, TEAL, false);
        hint.setPadding(dp(12), dp(8), dp(12), dp(8));
        panel.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        // Match the supplied screenshot: hint, then action buttons, then the site page.
        panel.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        panel.addView(web, webParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Проверка Kinozal")
                .setView(panel)
                .create();
        close.setOnClickListener(v -> {
            web.stopLoading();
            if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
            web.setVisibility(View.INVISIBLE);
            root.addView(web, new LinearLayout.LayoutParams(1, 1));
            dialog.dismiss();
        });
        done.setOnClickListener(v -> {
            browserCookies.flush();
            String cookie = browserCookies.getCookie(base);
            if (cookie != null && !cookie.isEmpty()) {
                try {
                    client.importBrowserCookies(cookie);
                    client.saveBrowserCookies(cookie);
                    if (submittedLogin[0] != null && !submittedLogin[0].trim().isEmpty()
                            && submittedPassword[0] != null && !submittedPassword[0].isEmpty()) {
                        client.saveCredentials(submittedLogin[0], submittedPassword[0]);
                    }
                } catch (Exception e) {
                    showMessage("Не удалось сохранить сессию: " + safe(e.getMessage()));
                    return;
                }
            }
            web.stopLoading();
            if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
            web.setVisibility(View.INVISIBLE);
            root.addView(web, new LinearLayout.LayoutParams(1, 1));
            dialog.dismiss();
            showMessage("Браузерная сессия сохранена. Загружаю каталог через WebView.");
            status.setText("Браузерная сессия активна");
            load("browse.php", "Все раздачи");
        });
        dialog.setOnDismissListener(d -> {
            if (web.getParent() == panel) {
                panel.removeView(web);
                web.setVisibility(View.INVISIBLE);
                if (web.getParent() == null) root.addView(web, new LinearLayout.LayoutParams(1, 1));
            }
        });
        dialog.show();
        dialog.getWindow().setLayout(
                (int)(getResources().getDisplayMetrics().widthPixels * 0.88f),
                (int)(getResources().getDisplayMetrics().heightPixels * 0.88f));
        web.post(() -> {
            web.requestLayout();
            web.requestFocus();
            web.loadUrl(base + "/login.php?m=5");
        });
    }

    /**
     * Android TV WebViews often do not summon the system IME for HTML inputs.
     * Use a native EditText (which can invoke the TV keyboard), then insert the
     * entered text into the currently focused HTML input.
     */
    private void showWebTextEntry(WebView web, String fieldType, Runnable onClosed) {
        boolean password = "password".equalsIgnoreCase(fieldType);
        EditText editor = new EditText(this);
        editor.setSingleLine(true);
        editor.setTextColor(Color.WHITE);
        editor.setHintTextColor(TEAL);
        editor.setHint(password ? "Введите пароль" : "Введите логин");
        editor.setInputType(password
                ? android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                : android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setSelectAllOnFocus(true);
        editor.setFocusable(true);
        editor.setFocusableInTouchMode(true);
        editor.setPadding(dp(14), dp(8), dp(14), dp(8));
        editor.setBackground(shape(PANEL, TEAL));

        AlertDialog inputDialog = new AlertDialog.Builder(this)
                .setTitle(password ? "Пароль Kinozal" : "Ввод на Kinozal")
                .setView(editor)
                .setPositiveButton("Вставить", null)
                .setNegativeButton("Отмена", null)
                .create();
        inputDialog.setOnDismissListener(d -> onClosed.run());
        inputDialog.setOnShowListener(ignored -> {
            inputDialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            editor.requestFocus();
            editor.postDelayed(() -> {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(editor,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }, 180);
            inputDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = editor.getText().toString();
                String quoted = org.json.JSONObject.quote(value);
                String script = "(function(){var e=window.__kinozalTarget;"
                        + "if(!e||!e.isConnected)return 'NO_FIELD';e.focus();"
                        + "e.value=" + quoted + ";"
                        + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                        + "e.dispatchEvent(new Event('change',{bubbles:true}));return 'OK';})()";
                web.evaluateJavascript(script, result -> {
                    if (result == null || result.contains("NO_FIELD")) {
                        showMessage("Поле сайта потеряло фокус. Выберите его ещё раз.");
                    }
                });
                inputDialog.dismiss();
            });
        });
        inputDialog.show();
        inputDialog.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void showNativeLoginForm(String[] saved) {
        // Authentication is performed on the mirror's own login page inside WebView.
        openCloudflareWindow();
    }

    private void chooseMirror() {
        String[] labels = {"Kinozal CloudNS", "Kinozal Guru", "Kinozal TV", "Kinozal Me"};
        new AlertDialog.Builder(this).setTitle("Зеркало").setItems(labels, (d, which) -> {
            mirror = MIRRORS[which]; getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("mirror", mirror).apply();
            client.setMirror(mirror); load("browse.php", "Все раздачи");
        }).setNegativeButton("Отмена", null).show();
    }

    private void showFavorites() { addEmpty("Избранное пока пусто. Добавление раздач будет подключено вместе с карточкой раздачи."); }

    private static class TorrentDetails {
        String title = "", poster = "", torrentLink = "", sourcePage = "";
        final java.util.LinkedHashMap<String, String> fields = new java.util.LinkedHashMap<>();
    }

    private void showTorrent(Torrent t) {
        // Snapshot the selected row. The catalogue can be refreshed while a
        // details request is in flight; never let a mutable row reference
        // change the identity of the request.
        final Torrent selected = new Torrent();
        selected.title = t.title;
        selected.href = t.href;
        selected.size = t.size;
        selected.seeds = t.seeds;
        selected.leeches = t.leeches;
        selected.uploaded = t.uploaded;

        final long requestId = ++torrentRequestSerial;
        progress.setVisibility(View.VISIBLE);

        // Details pages are fetched through the authenticated native HTTP
        // client first. It serializes requests and returns HTML tied to this
        // exact URL, avoiding races in the single shared WebView document.
        io.execute(() -> {
            TorrentDetails nativeDetails = null;
            String nativeError = null;
            try {
                String html = client.fetch(selected.href);
                Document doc = Jsoup.parse(html, selected.href);
                nativeDetails = parseTorrentDetails(doc, selected);\n                magnetDiag("HTTP_DETAILS", "id=" + torrentId(selected.href) + ", htmlBytes=" + html.length() + ", magnet=" + nativeDetails.torrentLink.startsWith("magnet:") + ", magnetAnchors=" + doc.select("a[href^=magnet:]").size() + ", title=" + safeDiag(nativeDetails.title));
                boolean hasDetails = !nativeDetails.fields.isEmpty()
                        || !nativeDetails.poster.isEmpty()
                        || !nativeDetails.torrentLink.isEmpty()
                        || (doc.selectFirst("h1") != null);
                if (!hasDetails) nativeDetails = null;
            } catch (Exception e) {
                nativeError = safe(e.getMessage());\n                magnetDiag("HTTP_ERROR", "id=" + torrentId(selected.href) + ", error=" + safeDiag(nativeError));
            }

            if (nativeDetails != null) {
                if (!nativeDetails.torrentLink.startsWith("magnet:")) {
                    try {
                        magnetDiag("SERVER_FALLBACK_START", "id=" + torrentId(selected.href));\n                        String magnet = fetchMagnetFromServer(selected);\n                        magnetDiag("SERVER_FALLBACK_RESULT", "id=" + torrentId(selected.href) + ", found=" + !magnet.isEmpty());
                        if (!magnet.isEmpty()) nativeDetails.torrentLink = magnet;
                    } catch (Exception ignored) { }
                }
                final TorrentDetails result = nativeDetails;
                if (requestId != torrentRequestSerial) return;
                runOnUiThread(() -> {
                    if (requestId != torrentRequestSerial || isFinishing()) return;
                    progress.setVisibility(View.GONE);
                    showTorrentDetails(selected, result);
                });
                return;
            }

            // Some mirrors expose the details only through the logged-in
            // browser session (Cloudflare or browser-only cookies). Use the
            // WebView strictly as a fallback, never as the primary source.
            final String failure = nativeError;
            runOnUiThread(() -> {
                if (requestId != torrentRequestSerial || isFinishing()) return;
                if (sessionWebView == null) {
                    progress.setVisibility(View.GONE);
                    showMessage("Не удалось загрузить карточку: "
                            + (failure == null ? "страница раздачи пуста" : failure));
                    return;
                }
                magnetDiag("WEBVIEW_FALLBACK_START", "id=" + torrentId(selected.href));\n                fetchBrowserPage(selected.href, null, browserHtml -> {
                    if (requestId != torrentRequestSerial || isFinishing()) return;
                    if (browserHtml == null || browserHtml.isEmpty()) {
                        progress.setVisibility(View.GONE);
                        showMessage("Не удалось загрузить карточку: "
                                + (failure == null ? "браузерная сессия не вернула страницу" : failure));
                        return;
                    }
                    final TorrentDetails browserDetails;
                    try {
                        browserDetails = parseTorrentDetails(
                                Jsoup.parse(browserHtml, selected.href), selected);
                    } catch (Exception e) {
                        progress.setVisibility(View.GONE);
                        showMessage("Не удалось разобрать карточку: " + safe(e.getMessage()));
                        return;
                    }
                    if (!browserDetails.torrentLink.startsWith("magnet:")) {
                        io.execute(() -> {
                            TorrentDetails result = browserDetails;
                            try {
                                String magnet = fetchMagnetFromServer(selected);
                                if (!magnet.isEmpty()) result.torrentLink = magnet;
                            } catch (Exception ignored) { }
                            if (requestId != torrentRequestSerial) return;
                            runOnUiThread(() -> {
                                if (requestId != torrentRequestSerial || isFinishing()) return;
                                progress.setVisibility(View.GONE);
                                showTorrentDetails(selected, result);
                            });
                        });
                    } else {
                        progress.setVisibility(View.GONE);
                        showTorrentDetails(selected, browserDetails);
                    }
                });
            });
        });
    }

    private TorrentDetails parseTorrentDetails(Document doc, Torrent torrent) {
        TorrentDetails data = new TorrentDetails();
        data.title = torrent.title;
        Element pageTitle = doc.selectFirst("h1");
        if (pageTitle != null && !pageTitle.text().trim().isEmpty()) {
            data.title = pageTitle.text().replaceAll("\\s+", " ").trim();
        }
        data.sourcePage = torrent.href;
        Element info = doc.selectFirst(".bx1.justify h2");
        if (info != null) extractBoldFields(info, data.fields);

        Element description = doc.selectFirst(".bx1.justify p");
        if (description != null) {
            String value = description.text().replaceFirst("(?i)^\\s*О фильме\\s*:\\s*", "").trim();
            if (!value.isEmpty()) data.fields.put("О фильме", value);
        }

        Element technical = doc.selectFirst("#tabs");
        if (technical != null) extractBoldFields(technical, data.fields);
        data.fields.putIfAbsent("Размер", torrent.size);

        Element ogImage = doc.selectFirst("meta[property=og:image]");
        if (ogImage != null) {
            String src = ogImage.attr("content").trim();
            if (!src.isEmpty()) data.poster = src.startsWith("http") ? src
                    : doc.baseUri().isEmpty() ? src
                    : java.net.URI.create(doc.baseUri()).resolve(src).toString();
        }
        if (data.poster.isEmpty()) {
            Element poster = doc.selectFirst("img[src*=/poster/]");
            if (poster != null) data.poster = poster.absUrl("src");
        }

        // Magnet must have priority: Kinozal commonly places download.php
        // before the TSA_magnet anchor in the same table cell.
        Elements magnetAnchors = doc.select("a[href^=magnet:]");\n        magnetDiag("PARSE_MAGNET", "id=" + torrentId(torrent.href) + ", anchors=" + magnetAnchors.size() + ", tsa=" + doc.select("a.TSA_magnet").size());\n        Element magnet = doc.selectFirst("a.TSA_magnet[href^=magnet:], a[href^=magnet:]");
        if (magnet != null) {
            data.torrentLink = magnet.attr("href").trim();
        } else {
            Element download = doc.selectFirst("a[href*=/download.php], a[href$=.torrent]");
            if (download != null) data.torrentLink = download.absUrl("href");
        }
        return data;
    }

    /**
     * Kinozal's details page can expose the magnet hash through its server-side
     * details endpoint instead of a direct TSA_magnet anchor. The endpoint
     * returns the info hash in the first list item (action=2, id=<torrent id>).
     */
    private String fetchMagnetFromServer(Torrent torrent) throws Exception {
        if (torrent == null || torrent.href == null) return "";
        java.util.regex.Matcher idMatcher = java.util.regex.Pattern
                .compile("[?&]id=(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(torrent.href);
        if (!idMatcher.find()) return "";

        String endpoint = client.getMirror() + "/get_srv_details.php?action=2&id="
                + idMatcher.group(1);
        String html = client.fetch(endpoint);\n        magnetDiag("SERVER_RESPONSE", "id=" + idMatcher.group(1) + ", chars=" + html.length() + ", hasMagnet=" + html.contains("magnet:") + ", hasHash=" + java.util.regex.Pattern.compile("(?i)[a-f0-9]{40}").matcher(html).find());
        Document response = Jsoup.parse(html, endpoint);

        Element directMagnet = response.selectFirst("a[href^=magnet:]");
        if (directMagnet != null) return directMagnet.attr("href").trim();

        Element firstItem = response.selectFirst("li:first-child");
        String hashText = firstItem == null ? response.text() : firstItem.text();
        java.util.regex.Matcher hashMatcher = java.util.regex.Pattern
                .compile("(?i)[a-f0-9]{40}").matcher(hashText);
        boolean hashFound = hashMatcher.find();
        if (!hashFound) {
            hashMatcher = java.util.regex.Pattern.compile("(?i)[a-f0-9]{40}")
                    .matcher(response.text());
            hashFound = hashMatcher.find();
        }
        if (!hashFound) return "";

        String magnet = "magnet:?xt=urn:btih:" + hashMatcher.group();
        String title = torrent.title == null ? "" : torrent.title.trim();
        if (!title.isEmpty()) {
            magnet += "&dn=" + URLEncoder.encode(title, "UTF-8");
        }
        return magnet;
    }

    private void extractBoldFields(Element block, java.util.LinkedHashMap<String, String> fields) {
        for (Element bold : block.select("b")) {
            String label = bold.text().replaceAll("[:：]+$", "").trim();
            if (label.isEmpty()) continue;
            StringBuilder value = new StringBuilder();
            for (org.jsoup.nodes.Node node = bold.nextSibling(); node != null; node = node.nextSibling()) {
                if (node instanceof Element && ((Element) node).tagName().equalsIgnoreCase("b")) break;
                String part = org.jsoup.Jsoup.parse(node.toString()).text().trim();
                if (!part.isEmpty()) {
                    if (value.length() > 0) value.append(' ');
                    value.append(part);
                }
            }
            String text = value.toString().replaceAll("\\s+", " ").trim();
            if (!text.isEmpty()) fields.put(label, text);
        }
    }

    private String torrentId(String href) {\n        if (href == null) return "unknown";\n        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]id=(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(href);\n        return m.find() ? m.group(1) : "unknown";\n    }\n\n    private String safeDiag(String value) {\n        if (value == null) return "";\n        return value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();\n    }\n\n    private String cleanDetailLabel(String value) {
        return value == null ? "" : value.replaceAll("[:：*]+$", "").trim();
    }

    private int parseInt(String value) {
        try { return Integer.parseInt(value.replaceAll("[^0-9]", "")); }
        catch (Exception ignored) { return 0; }
    }

    private void showTorrentDetails(Torrent torrent, TorrentDetails data) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(10));
        root.setBackgroundColor(BG);

        TextView title = text(data.title, 20, GOLD, true);
        title.setMaxLines(2);
        title.setPadding(dp(4), dp(2), dp(4), dp(8));
        root.addView(title);

        LinearLayout mainArea = new LinearLayout(this);
        mainArea.setOrientation(LinearLayout.HORIZONTAL);
        mainArea.setGravity(Gravity.TOP);

        LinearLayout posterColumn = new LinearLayout(this);
        posterColumn.setOrientation(LinearLayout.VERTICAL);
        posterColumn.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        Button watch = button("Просмотр");
        watch.setTextSize(15);
        watch.setTextColor(Color.WHITE);
        watch.setBackground(shape(RED, 0));
        watch.setOnFocusChangeListener((v, focused) ->
                v.setBackground(shape(focused ? Color.rgb(125, 48, 25) : RED, focused ? GOLD : 0)));
        LinearLayout.LayoutParams watchParams = new LinearLayout.LayoutParams(-1, dp(48));
        watchParams.bottomMargin = dp(10);
        posterColumn.addView(watch, watchParams);

        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        poster.setBackground(shape(PANEL, 0));
        posterColumn.addView(poster, new LinearLayout.LayoutParams(-1, dp(260)));
        LinearLayout.LayoutParams posterParams = new LinearLayout.LayoutParams(dp(190), -1);
        posterParams.rightMargin = dp(12);
        mainArea.addView(posterColumn, posterParams);

        LinearLayout detailsColumn = new LinearLayout(this);
        detailsColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        Button descriptionTab = button("Описание");
        Button technicalTab = button("Техданные");
        tabs.addView(descriptionTab, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams technicalParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        technicalParams.leftMargin = dp(6);
        tabs.addView(technicalTab, technicalParams);
        detailsColumn.addView(tabs);

        ScrollView detailsScroll = new ScrollView(this);
        detailsScroll.setFillViewport(true);
        LinearLayout detailContent = new LinearLayout(this);
        detailContent.setOrientation(LinearLayout.VERTICAL);
        detailContent.setPadding(dp(8), dp(10), dp(8), dp(12));
        detailsScroll.addView(detailContent);
        detailsColumn.addView(detailsScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        mainArea.addView(detailsColumn, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(mainArea, new LinearLayout.LayoutParams(-1, 0, 1));

        String[][] groups = {
                {"Год выпуска", "Жанр", "Выпущено", "Режиссер", "В ролях", "О фильме"},
                {"Качество", "Видео", "Аудио", "Субтитры", "Продолжительность", "Перевод", "Размер", "Язык"}
        };
        final int[] active = {0};
        Runnable render = () -> {
            detailContent.removeAllViews();
            String[] keys = groups[active[0]];
            boolean any = false;
            int index = 0;
            while (index < keys.length) {
                String key = keys[index];
                String value = data.fields.get(key);
                if (value == null || value.trim().isEmpty()) { index++; continue; }
                any = true;
                boolean fullWidth = active[0] == 0
                        && ("В ролях".equals(key) || "О фильме".equals(key));
                if (fullWidth) {
                    addDetailField(detailContent, key, value, -1);
                    index++;
                    continue;
                }
                LinearLayout pair = new LinearLayout(this);
                pair.setOrientation(LinearLayout.HORIZONTAL);
                pair.setGravity(Gravity.TOP);
                addDetailField(pair, key, value, 0);
                index++;
                if (index < keys.length) {
                    String nextKey = keys[index];
                    String nextValue = data.fields.get(nextKey);
                    boolean nextFullWidth = active[0] == 0
                            && ("В ролях".equals(nextKey) || "О фильме".equals(nextKey));
                    if (!nextFullWidth && nextValue != null && !nextValue.trim().isEmpty()) {
                        addDetailField(pair, nextKey, nextValue, 1);
                        index++;
                    }
                }
                detailContent.addView(pair, new LinearLayout.LayoutParams(-1, -2));
                View divider = new View(this);
                divider.setBackgroundColor(Color.rgb(65, 70, 74));
                detailContent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
            }
            if (!any) detailContent.addView(text("Сведения на странице не найдены.", 15, Color.LTGRAY, false));
            // Tab selection controls content only. Visual highlighting follows
            // actual DPAD focus, so the unfocused Description tab is not lit
            // while the default focus is on Просмотр.
            descriptionTab.setBackground(shape(descriptionTab.hasFocus() ? Color.rgb(65, 72, 73) : PANEL, descriptionTab.hasFocus() ? GOLD : 0));
            technicalTab.setBackground(shape(technicalTab.hasFocus() ? Color.rgb(65, 72, 73) : PANEL, technicalTab.hasFocus() ? GOLD : 0));
            detailsScroll.scrollTo(0, 0);
        };
        descriptionTab.setOnFocusChangeListener((v, focused) ->
                v.setBackground(shape(focused ? Color.rgb(65, 72, 73) : PANEL, focused ? GOLD : 0)));
        technicalTab.setOnFocusChangeListener((v, focused) ->
                v.setBackground(shape(focused ? Color.rgb(65, 72, 73) : PANEL, focused ? GOLD : 0)));
        descriptionTab.setOnClickListener(v -> { active[0] = 0; render.run(); });
        technicalTab.setOnClickListener(v -> { active[0] = 1; render.run(); });
        render.run();

        watch.setOnClickListener(v -> playTorrent(data));\n        Button diagnostics = button("Диагностика magnet");\n        diagnostics.setTextSize(12);\n        diagnostics.setOnClickListener(v -> showMagnetDiagnostics());\n        LinearLayout.LayoutParams diagParams = new LinearLayout.LayoutParams(-1, dp(38));\n        diagParams.topMargin = dp(8);\n        root.addView(diagnostics, diagParams);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(root)
                .create();
        dialog.show();
        if (dialog.getWindow() != null) {
            android.view.Window window = dialog.getWindow();
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
            window.getDecorView().setBackgroundColor(BG);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams windowParams = window.getAttributes();
            windowParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            windowParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            windowParams.dimAmount = 0.72f;
            window.setAttributes(windowParams);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        if (!data.poster.isEmpty()) loadImageIntoView(data.poster, poster);
        watch.requestFocus();
    }

    private void addDetailField(LinearLayout parent, String key, String value, int column) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(8), dp(7), dp(10), dp(9));
        TextView label = text(key, 12, TEAL, true);
        TextView valueView = text(value, 14, Color.WHITE, false);
        valueView.setLineSpacing(dp(1), 1f);
        block.addView(label);
        block.addView(valueView);
        if (column < 0) {
            parent.addView(block, new LinearLayout.LayoutParams(-1, -2));
        } else {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
            if (column == 1) params.leftMargin = dp(8);
            parent.addView(block, params);
        }
    }

    private void playTorrent(TorrentDetails data) {
        if (data.torrentLink == null || data.torrentLink.isEmpty()) {
            showMessage("Не удалось найти ссылку на торрент на странице раздачи.");
            return;
        }
        magnetDiag("PLAY_REQUEST", "magnet=" + data.torrentLink.startsWith("magnet:") + ", title=" + safeDiag(data.title));\n        if (data.torrentLink.startsWith("magnet:")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(data.torrentLink));
                // Use Android resolver; do not hardcode a specific TorrServe package.
                intent.putExtra("poster", data.poster == null ? "" : data.poster);
                intent.putExtra("title", data.title == null ? "" : data.title);
                startActivity(intent);
            } catch (Exception e) {
                showMessage("Не удалось открыть magnet в TorrServe: " + safe(e.getMessage()));
            }
            return;
        }

        WebView web = sessionWebView;
        if (web == null || data.sourcePage == null || data.sourcePage.isEmpty()) {
            downloadTorrentNative(data);
            return;
        }

        // Details are now parsed by the native client, so the shared WebView is
        // not necessarily displaying this torrent. Navigate it to the exact
        // details page first: Kinozal may validate the download Referer as well
        // as the authenticated cookies.
        fetchBrowserPage(data.sourcePage, null, pageHtml -> {
            if (pageHtml == null || pageHtml.isEmpty()) {
                downloadTorrentNative(data);
                return;
            }
            WebView activeWeb = sessionWebView;
            if (activeWeb == null) {
                downloadTorrentNative(data);
                return;
            }
            activeWeb.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void onTorrentResult(int statusCode, String base64, String error) {
                    runOnUiThread(() -> {
                        if (statusCode >= 200 && statusCode < 300 && base64 != null && !base64.isEmpty()) {
                            try {
                                byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                                deliverTorrentBytes(data, bytes);
                            } catch (Exception e) {
                                showMessage("Не удалось прочитать торрент: " + safe(e.getMessage()));
                            }
                        } else {
                            downloadTorrentNative(data);
                        }
                    });
                }
            }, "KinozalTorrentBridge");
            String url = org.json.JSONObject.quote(data.torrentLink);
            String script = "(function(){fetch(" + url
                    + ",{credentials:'include',redirect:'follow',cache:'no-store'})"
                    + ".then(function(r){if(!r.ok){window.KinozalTorrentBridge.onTorrentResult(r.status,'','HTTP '+r.status);return;}"
                    + "return r.blob().then(function(blob){var reader=new FileReader();"
                    + "reader.onloadend=function(){var value=String(reader.result||'');"
                    + "window.KinozalTorrentBridge.onTorrentResult(r.status,value.substring(value.indexOf(',')+1),'');};"
                    + "reader.onerror=function(){window.KinozalTorrentBridge.onTorrentResult(0,'','FileReader error');};"
                    + "reader.readAsDataURL(blob);});})"
                    + ".catch(function(e){window.KinozalTorrentBridge.onTorrentResult(0,'',String(e));});})()";
            activeWeb.evaluateJavascript(script, null);
        });
    }

    private void downloadTorrentNative(TorrentDetails data) {
        io.execute(() -> {
            try {
                String browserCookies = CookieManager.getInstance().getCookie(client.getMirror());
                if (browserCookies != null && !browserCookies.isEmpty()) {
                    client.importBrowserCookies(browserCookies);
                }
                byte[] bytes = client.fetchBytes(data.torrentLink,
                        "application/x-bittorrent,application/octet-stream,*/*",
                        data.sourcePage == null || data.sourcePage.isEmpty()
                                ? client.getMirror() + "/" : data.sourcePage);
                deliverTorrentBytes(data, bytes);
            } catch (Exception e) {
                runOnUiThread(() -> showMessage("Не удалось передать торрент: " + safe(e.getMessage())));
            }
        });
    }

    private void deliverTorrentBytes(TorrentDetails data, byte[] bytes) {
        try {
            if (bytes == null || bytes.length < 20 || bytes[0] != 'd') {
                throw new java.io.IOException("Сайт вернул не .torrent-файл. Возможно, сессия истекла или зеркало отклонило запрос.");
            }
            java.io.File target = new java.io.File(getCacheDir(), "kinozal-transfer.torrent");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(target)) { out.write(bytes); }
            Uri fileUri = Uri.parse("content://" + getPackageName() + ".torrentprovider/torrent");
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setPackage("ru.yourok.torrserve");
            send.setType("application/x-bittorrent");
            send.putExtra(Intent.EXTRA_STREAM, fileUri);
            send.putExtra("poster", data.poster == null ? "" : data.poster);
            send.putExtra("title", data.title == null ? "" : data.title);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(android.content.ClipData.newUri(getContentResolver(), "torrent", fileUri));
            runOnUiThread(() -> {
                try { startActivity(send); }
                catch (Exception e) { showMessage("TorrServe MatriX не установлен или не принимает файл."); }
            });
        } catch (Exception e) {
            runOnUiThread(() -> showMessage("Не удалось подготовить торрент: " + safe(e.getMessage())));
        }
    }

    private void addEmpty(String message) {
        list.removeAllViews(); TextView empty = text(message, 17, Color.LTGRAY, false);
        empty.setPadding(dp(22), dp(24), dp(22), dp(24)); list.addView(empty);
    }

    private String safe(String s) { return s == null ? "Ошибка сети" : s; }

    @Override public void onBackPressed() { finish(); }
    @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }
}
