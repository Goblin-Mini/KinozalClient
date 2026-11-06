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

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private KinozalHttpClient client;
    private String mirror;
    private String currentPath = "browse.php";
    private String currentTitle = "Все раздачи";
    private LinearLayout root, sidebar, content, list, menuItems;
    private TextView heading, status;
    private ProgressBar progress;
    private EditText search;
    private View focusedTorrentSource;
    private final List<Torrent> torrents = new ArrayList<>();
    private int page = 0;
    private String searchSort = "date";
    private boolean searchSortDescending = true;
    private LinearLayout searchSortBar;
    private Button sortDateButton, sortSizeButton, sortSeedsButton;
    private AlertDialog settingsDialog;
    private static final String FAVORITES_KEY = "local_favorites";
    private volatile String lastHtml = "";
    private WebView sessionWebView;
    private BrowserPageCallback pendingBrowserCallback;
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
                    setCatalogStatus("Постеров", total);
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
            setCatalogStatus("Постеров", total);
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

    private void setCatalogStatus(String itemType, int count) {
        status.setText(currentTitle + " · Загружено: " + count + " " + itemType
                + "\nВерсия: " + getAppVersionName());
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
            // Poster + title only; ratings belong to the details screen.
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(238));
            list.addView(horizontal, rowParams);
        }
        if (sections.isEmpty()) addEmpty("На главной пока нет подборок.");
        animateContentTransition(list);
    }

    /** Subtle content entrance used after a catalogue or home section is rendered. */
    private void animateContentTransition(View target) {
        if (target == null) return;
        target.animate().cancel();
        target.setAlpha(0f);
        target.setTranslationY(dp(7));
        target.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(190)
                .start();
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
        LinearLayout brand = new LinearLayout(this); brand.setGravity(Gravity.CENTER);
        TextView logo = text("KINOZAL", 23, GOLD, true); brand.addView(logo);
        sidebar.addView(brand, new LinearLayout.LayoutParams(-1, dp(58)));
        ScrollView menuScroll = new ScrollView(this);
        menuScroll.setFillViewport(false);
        menuScroll.setVerticalScrollBarEnabled(false);
        menuScroll.setScrollbarFadingEnabled(true);
        menuScroll.setPadding(0, 0, 0, 0);
        menuItems = new LinearLayout(this);
        menuItems.setOrientation(LinearLayout.VERTICAL);
        menuScroll.addView(menuItems, new ScrollView.LayoutParams(-1, -2));
        sidebar.addView(menuScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        addMenu("⌂", "Главная", () -> loadInitialCatalog());
        addMenu("⇩", "Все раздачи", () -> load("browse.php", "Все раздачи"));

        Button categoriesButton = button("▦   Категории  ▾");
        categoriesButton.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        categoriesButton.setTextSize(14);
        categoriesButton.setPadding(dp(14), 0, dp(8), 0);
        LinearLayout.LayoutParams categoryButtonParams = new LinearLayout.LayoutParams(-1, dp(47));
        categoryButtonParams.rightMargin = dp(5);
        categoryButtonParams.bottomMargin = dp(3);
        menuItems.addView(categoriesButton, categoryButtonParams);

        LinearLayout categoryGroup = new LinearLayout(this);
        categoryGroup.setOrientation(LinearLayout.VERTICAL);
        categoryGroup.setVisibility(View.GONE);
        menuItems.addView(categoryGroup);

        addSubMenu(categoryGroup, "▣  Все сериалы", "browse.php?c=1001");
        addSubMenu(categoryGroup, "▣  Зарубежные сериалы", "browse.php?c=46");
        addSubMenu(categoryGroup, "▣  Российские сериалы", "browse.php?c=45");

        addSubMenu(categoryGroup, "▤  Все фильмы", "browse.php?c=1002");
        addSubMenu(categoryGroup, "▤  Советские фильмы", "browse.php?c=10");
        addSubMenu(categoryGroup, "▧  Мультфильмы", "browse.php?c=1003");
        addSubMenu(categoryGroup, "▧  Российские мульты", "browse.php?c=22");
        addSubMenu(categoryGroup, "▧  Зарубежные мульты", "browse.php?c=21");
        addSubMenu(categoryGroup, "✧  Аниме", "browse.php?c=20");
        addSubMenu(categoryGroup, "⚑  Спортивные передачи", "browse.php?c=37");
        addSubMenu(categoryGroup, "▣  Российское ТВ", "browse.php?c=49");
        addSubMenu(categoryGroup, "▣  Зарубежное ТВ", "browse.php?c=50");
        addSubMenu(categoryGroup, "♫  Концерты", "browse.php?c=48");
        addSubMenu(categoryGroup, "▸  Видеоклипы", "browse.php?c=1");
        categoriesButton.setOnClickListener(v -> {
            boolean expand = categoryGroup.getVisibility() != View.VISIBLE;
            categoryGroup.setVisibility(expand ? View.VISIBLE : View.GONE);
            categoriesButton.setText(expand ? "Категории  ▾" : "Категории  ▸");
            if (expand) categoryGroup.getChildAt(0).requestFocus();
        });

        addMenu("★", "Избранное", () -> showFavorites());
        addMenu("◷", "Недавно смотрели", () -> showRecentlyViewed());
        addMenu("⚙", "Настройки", () -> showSettings());
        status = text("Подключение…", 11, TEAL, false);
        status.setGravity(Gravity.CENTER);
        status.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, dp(44));
        statusParams.topMargin = dp(6);
        sidebar.addView(status, statusParams);

        heading = text("Главная", 25, GOLD, true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, dp(42));
        headingParams.bottomMargin = dp(4);
        main.addView(heading, headingParams);

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
        LinearLayout searchField = new LinearLayout(this);
        searchField.setGravity(Gravity.CENTER_VERTICAL);
        searchField.setOrientation(LinearLayout.HORIZONTAL);
        searchField.setBackground(shape(PANEL, TEAL));
        searchField.setPadding(dp(10), 0, dp(5), 0);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        searchParams.rightMargin = dp(8);
        search.setBackgroundColor(Color.TRANSPARENT);
        search.setPadding(dp(4), 0, dp(4), 0);
        View voiceSearch = new View(this) {
            private final android.graphics.Paint iconPaint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                iconPaint.setColor(GOLD);
                iconPaint.setStyle(android.graphics.Paint.Style.STROKE);
                iconPaint.setStrokeWidth(dp(2.1f));
                iconPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                float cx = getWidth() / 2f - dp(2);
                float cy = getHeight() / 2f - dp(2);
                float radius = dp(6);
                canvas.drawCircle(cx, cy, radius, iconPaint);
                canvas.drawLine(cx + radius * 0.72f, cy + radius * 0.72f,
                        cx + dp(10), cy + dp(10), iconPaint);
            }
        };
        voiceSearch.setContentDescription("Голосовой поиск");
        voiceSearch.setFocusable(true);
        voiceSearch.setFocusableInTouchMode(false);
        voiceSearch.setBackground(shape(PANEL, 0));
        voiceSearch.setOnFocusChangeListener((v, focused) ->
                v.setBackground(shape(focused ? Color.rgb(65, 72, 73) : PANEL, focused ? GOLD : 0)));
        voiceSearch.setNextFocusRightId(View.NO_ID);
        LinearLayout.LayoutParams voiceParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        voiceParams.rightMargin = dp(3);
        searchField.addView(voiceSearch, voiceParams);
        voiceSearch.setOnClickListener(v -> startVoiceSearch());
        searchField.addView(search, new LinearLayout.LayoutParams(0, -1, 1f));
        Button clearSearch = button("×");
        clearSearch.setTextSize(23);
        clearSearch.setContentDescription("Очистить поиск");
        clearSearch.setVisibility(View.GONE);
        searchField.addView(clearSearch, new LinearLayout.LayoutParams(dp(38), dp(40)));
        clearSearch.setOnClickListener(v -> search.setText(""));
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearSearch.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        searchBar.addView(searchField, searchParams);
        Button history = button("История");
        history.setTextSize(12);
        LinearLayout.LayoutParams historyParams = new LinearLayout.LayoutParams(dp(82), dp(48));
        historyParams.rightMargin = dp(6);
        searchBar.addView(history, historyParams);
        history.setOnClickListener(v -> showSearchHistory());
        Button find = button("Найти");
        find.setTextSize(12);
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
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(GOLD));
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(3));
        progressParams.bottomMargin = dp(5);
        main.addView(progress, progressParams);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        main.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (menuItems.getChildCount() > 0) menuItems.getChildAt(0).requestFocus();
    }

    private void addSubMenu(LinearLayout parent, String label, String path) {
        Button b = button(label);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        b.setTextSize(12);
        b.setPadding(dp(34), 0, dp(8), 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(39));
        p.rightMargin = dp(5);
        p.bottomMargin = dp(2);
        parent.addView(b, p);
        b.setOnClickListener(v -> load(path, label));
    }

    private void showSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(12));
        panel.setBackgroundColor(BG);
        Button login = button("◎   Авторизация / проверка Kinozal");
        Button mirrorButton = button("◇   Выбрать зеркало");
        Button updates = button("↻   Проверить обновления");
        Button jackett = button("⌕   Поиск Jackett");
        Button about = button("ⓘ   О приложении");
        Button close = button("Закрыть");
        for (Button action : new Button[]{login, mirrorButton, updates, jackett, about, close}) {
            action.setTextSize(15);
            action.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
            params.bottomMargin = dp(8);
            panel.addView(action, params);
        }
        login.setOnClickListener(v -> { settingsDialog.dismiss(); showLogin(); });
        mirrorButton.setOnClickListener(v -> { settingsDialog.dismiss(); chooseMirror(); });
        updates.setOnClickListener(v -> { settingsDialog.dismiss(); new UpdateManager(this).check(); });
        jackett.setOnClickListener(v -> showJackettSettings());
        about.setOnClickListener(v -> showAbout());
        close.setOnClickListener(v -> settingsDialog.dismiss());
        settingsDialog = new AlertDialog.Builder(this).setView(panel).create();
        settingsDialog.show();
        if (settingsDialog.getWindow() != null) {
            settingsDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
            settingsDialog.getWindow().setLayout(Math.min(dp(520), getResources().getDisplayMetrics().widthPixels - dp(48)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        login.requestFocus();
    }

    private void showAbout() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(12), dp(20), dp(8));
        panel.setBackgroundColor(BG);

        TextView description = text(
                "Kinozal Client — приложение для удобного просмотра каталога Kinozal на Android и Android TV.",
                15, Color.WHITE, false);
        description.setLineSpacing(dp(3), 1f);
        panel.addView(description);

        TextView github = text("GitHub: github.com/Goblin-Mini/KinozalClient",
                14, TEAL, false);
        github.setPadding(0, dp(14), 0, dp(14));
        github.setFocusable(true);
        github.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/Goblin-Mini/KinozalClient")));
            } catch (Exception e) {
                showMessage("Не удалось открыть GitHub.");
            }
        });
        panel.addView(github);

        TextView authors = text("Авторство: GoblinMini и ChatGPT", 14, GOLD, true);
        panel.addView(authors);

        AlertDialog aboutDialog = new AlertDialog.Builder(this)
                .setTitle("О приложении")
                .setView(panel)
                .setPositiveButton("Закрыть", null)
                .create();
        aboutDialog.show();
        if (aboutDialog.getWindow() != null) {
            aboutDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
            aboutDialog.getWindow().setLayout(Math.min(dp(520),
                    getResources().getDisplayMetrics().widthPixels - dp(48)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        aboutDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(GOLD);
        github.setOnFocusChangeListener((v, focused) ->
                github.setTextColor(focused ? GOLD : TEAL));
        github.requestFocus();
    }

    private static final String JACKETT_PREFS = "jackett_settings";

    private String jackettPrimary() {
        return getSharedPreferences(JACKETT_PREFS, MODE_PRIVATE)
                .getString("primary", "https://jac.red").trim();
    }

    private String jackettBackup() {
        return getSharedPreferences(JACKETT_PREFS, MODE_PRIVATE)
                .getString("backup", "https://jr.maxvol.pro").trim();
    }

    private String jackettCustom() {
        return getSharedPreferences(JACKETT_PREFS, MODE_PRIVATE)
                .getString("custom", "").trim();
    }

    private String jackettApiKey() {
        return getSharedPreferences(JACKETT_PREFS, MODE_PRIVATE)
                .getString("apikey", "").trim();
    }

    private void showJackettSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(10), dp(18), dp(8));
        panel.setBackgroundColor(BG);
        EditText primary = new EditText(this);
        EditText backup = new EditText(this);
        EditText custom = new EditText(this);
        EditText key = new EditText(this);
        EditText[] fields = {primary, backup, custom, key};
        String[] labels = {"Основной сервер", "Резервный сервер", "Свой сервер (необязательно)", "API Key"};
        String[] values = {jackettPrimary(), jackettBackup(), jackettCustom(), jackettApiKey()};
        for (int i = 0; i < fields.length; i++) {
            TextView label = text(labels[i], 13, TEAL, true);
            label.setPadding(0, dp(5), 0, dp(2));
            panel.addView(label);
            EditText field = fields[i];
            field.setSingleLine(true);
            field.setText(values[i]);
            field.setTextColor(Color.WHITE);
            field.setHintTextColor(TEAL);
            field.setTextSize(14);
            field.setPadding(dp(10), dp(7), dp(10), dp(7));
            field.setBackground(shape(PANEL, TEAL));
            if (i == 3) field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            panel.addView(field, new LinearLayout.LayoutParams(-1, dp(44)));
        }
        TextView note = text("Используется Torznab API. Для указанных публичных серверов API Key не требуется. Для своего сервера ключ можно оставить пустым или указать при необходимости.",
                12, Color.LTGRAY, false);
        note.setPadding(0, dp(8), 0, 0);
        panel.addView(note);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Поиск Jackett")
                .setView(panel)
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Отмена", null)
                .create();
        dialog.setOnShowListener(v -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(GOLD);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                String p = primary.getText().toString().trim();
                String b = backup.getText().toString().trim();
                String c = custom.getText().toString().trim();
                if (!validJackettBase(p) || !validJackettBase(b)
                        || (!c.isEmpty() && !validJackettBase(c))) {
                    showMessage("Укажите корректные адреса серверов с http:// или https://");
                    return;
                }
                getSharedPreferences(JACKETT_PREFS, MODE_PRIVATE).edit()
                        .putString("primary", p.replaceAll("/+$", ""))
                        .putString("backup", b.replaceAll("/+$", ""))
                        .putString("custom", c.replaceAll("/+$", ""))
                        .putString("apikey", key.getText().toString().trim()).apply();
                dialog.dismiss();
                showMessage("Настройки Jackett сохранены");
            });
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
            dialog.getWindow().setLayout(Math.min(dp(560),
                    getResources().getDisplayMetrics().widthPixels - dp(48)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private boolean validJackettBase(String value) {
        try {
            Uri uri = Uri.parse(value);
            return ("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (Exception ignored) { return false; }
    }

    private void searchJackett(String query, String posterUrl) {
        String searchTitle = extractJackettSearchTitle(query);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(8));
        panel.setBackgroundColor(BG);
        TextView status = text("Поиск: " + searchTitle + "\\nПодключение к Jackett…", 13, TEAL, false);
        panel.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(results);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Другие раздачи")
                .setView(panel).setNegativeButton("Закрыть", null).create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
        io.execute(() -> {
            String[] servers = !jackettCustom().isEmpty()
                    ? new String[]{jackettCustom()}
                    : new String[]{jackettPrimary(), jackettBackup()};
            List<Element> found = new ArrayList<>();
            String used = null;
            String error = "Серверы не ответили";
            for (String server : servers) {
                try {
                    String encoded = URLEncoder.encode(searchTitle, "UTF-8");
                    String apiKey = jackettApiKey();
                    String endpoint = server.replaceAll("/+$", "")
                            + "/api/v2.0/indexers/all/results/torznab/api?"
                            + (apiKey.isEmpty() ? "" : "apikey=" + URLEncoder.encode(apiKey, "UTF-8") + "&")
                            + "t=search&q=" + encoded;
                    java.net.HttpURLConnection connection =
                            (java.net.HttpURLConnection) new java.net.URL(endpoint).openConnection();
                    connection.setConnectTimeout(9000);
                    connection.setReadTimeout(18000);
                    connection.setRequestMethod("GET");
                    connection.setRequestProperty("Accept", "application/xml, text/xml");
                    int code = connection.getResponseCode();
                    if (code < 200 || code >= 300) {
                        error = "HTTP " + code + " от " + server;
                        connection.disconnect();
                        continue;
                    }
                    java.io.InputStream input = connection.getInputStream();
                    StringBuilder xml = new StringBuilder();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) xml.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
                    input.close();
                    connection.disconnect();
                    Document rss = Jsoup.parse(xml.toString(), "", org.jsoup.parser.Parser.xmlParser());
                    found = new ArrayList<>(rss.select("item"));
                    used = server;
                    break;
                } catch (Exception e) {
                    error = safe(e.getMessage());
                }
            }
            final List<Element> items = found;
            final String serverUsed = used;
            final String failure = error;
            runOnUiThread(() -> {
                results.removeAllViews();
                if (serverUsed == null) {
                    status.setText("Не удалось выполнить поиск. " + failure
                            + "\\nПроверьте доступность сервера и адрес API.");
                    return;
                }
                status.setText("Найдено: " + items.size() + " · " + serverUsed);
                if (items.isEmpty()) {
                    results.addView(text("По этому названию ничего не найдено.", 14, Color.LTGRAY, false));
                    return;
                }
                View firstResult = null;
                for (Element item : items) {
                    String name = item.selectFirst("title") == null ? "Без названия" : item.selectFirst("title").text();
                    String size = jackettAttribute(item, "size");
                    if ("—".equals(size)) {
                        Element enclosure = item.selectFirst("enclosure");
                        if (enclosure != null) size = enclosure.attr("length");
                    }
                    size = formatJackettSize(size);
                    String seeders = jackettAttribute(item, "seeders");
                    String peers = jackettAttribute(item, "peers");
                    String tracker = jackettAttribute(item, "jackettindexer");
                    if ("—".equals(tracker)) tracker = "";
                    Element link = item.selectFirst("link");
                    String target = link == null ? "" : link.text().trim();
                    if (target.isEmpty() && link != null) target = link.attr("href");
                    if (target.isEmpty()) {
                        Element enclosure = item.selectFirst("enclosure");
                        if (enclosure != null) target = enclosure.attr("url");
                    }
                    final String resultUrl = target;
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setPadding(dp(14), dp(10), dp(14), dp(10));
                    row.setBackground(shape(PANEL, 0));
                    row.setFocusable(true);
                    row.setFocusableInTouchMode(true);
                    row.setClickable(true);
                    row.setOnFocusChangeListener((view, focused) ->
                            view.setBackground(shape(focused ? Color.rgb(65, 72, 73) : PANEL, focused ? GOLD : 0)));
                    TextView titleView = text(name, 15, Color.WHITE, true);
                    row.addView(titleView);
                    TextView meta = text((tracker.isEmpty() ? "" : tracker + "  ·  ")
                            + "Размер: " + size + "  ·  Сиды: " + seeders + "  ·  Пиры: " + peers,
                            12, TEAL, false);
                    meta.setPadding(0, dp(4), 0, 0);
                    row.addView(meta);
                    if (!resultUrl.isEmpty()) row.setOnClickListener(v -> {
                        try {
                            Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(resultUrl));
                            open.putExtra("poster", posterUrl == null ? "" : posterUrl);
                            open.putExtra("title", name);
                            startActivity(open);
                        } catch (Exception e) { showMessage("Не удалось открыть ссылку результата."); }
                    });
                    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                    rowParams.bottomMargin = dp(6);
                    results.addView(row, rowParams);
                    if (firstResult == null && !resultUrl.isEmpty()) firstResult = row;
                }
                if (firstResult != null) {
                    View focusTarget = firstResult;
                    focusTarget.post(focusTarget::requestFocus);
                }
            });
        });
    }

    private String extractJackettSearchTitle(String fullTitle) {
        if (fullTitle == null) return "";
        String value = fullTitle.trim();
        int slash = value.indexOf('/');
        if (slash >= 0 && slash + 1 < value.length()) {
            String english = value.substring(slash + 1).trim();
            int nextSlash = english.indexOf('/');
            if (nextSlash >= 0) english = english.substring(0, nextSlash).trim();
            if (!english.isEmpty()) return english;
        }
        return value;
    }

    private String jackettAttribute(Element item, String name) {
        if (item == null) return "—";
        Element direct = item.selectFirst(name);
        if (direct != null && !direct.text().trim().isEmpty()) return direct.text().trim();
        for (Element element : item.getAllElements()) {
            String tag = element.tagName().toLowerCase(java.util.Locale.ROOT);
            if (("attr".equals(tag) || tag.endsWith(":attr"))
                    && name.equalsIgnoreCase(element.attr("name"))) {
                String value = element.attr("value").trim();
                if (!value.isEmpty()) return value;
            }
        }
        return "—";
    }

    private String formatJackettSize(String raw) {
        if (raw == null || raw.trim().isEmpty() || "—".equals(raw)) return "—";
        try {
            long bytes = Long.parseLong(raw.replaceAll("[^0-9]", ""));
            if (bytes <= 0) return "—";
            double gib = bytes / (1024d * 1024d * 1024d);
            return String.format(java.util.Locale.US, "%.2f ГБ", gib);
        } catch (Exception ignored) { return raw; }
    }

    private void addMenu(String icon, String label, Runnable action) {
        Button b = button(icon + "   " + label);
        if ("Недавно смотрели".equals(label)) {
            android.text.SpannableString menuText =
                    new android.text.SpannableString(icon + "   " + label);
            menuText.setSpan(new android.text.style.RelativeSizeSpan(1.2f),
                    0, icon.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setText(menuText);
        }
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
        // A thin activity indicator sits directly below the search field.
        progress.setVisibility(View.VISIBLE);
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
                if (pendingBrowserCallback != null) {
                    BrowserPageCallback cb = pendingBrowserCallback;
                    pendingBrowserCallback = null;
                    view.evaluateJavascript("(document.documentElement ? document.documentElement.outerHTML : '')", value -> {
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

    private void fetchBrowserPage(String path, String postBody,
            java.util.function.Consumer<String> callback) {
        WebView web = sessionWebView;
        if (web == null) { callback.accept(null); return; }
        // Complete any previous request before starting another one. A stale
        // WebView callback must never leave the UI waiting indefinitely.
        if (browserRequestTimeout != null) browserHandler.removeCallbacks(browserRequestTimeout);
        if (pendingBrowserCallback != null) {
            BrowserPageCallback previous = pendingBrowserCallback;
            pendingBrowserCallback = null;
            previous.onPage(null, "Предыдущий запрос отменён");
        }
        if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
        web.setVisibility(View.INVISIBLE);
        if (web.getParent() == null) root.addView(web, new LinearLayout.LayoutParams(1, 1));
        String target = path.startsWith("http") ? path
                : client.getMirror() + "/" + path.replaceFirst("^/+", "");
        pendingBrowserCallback = (html, error) -> {
            if (browserRequestTimeout != null) browserHandler.removeCallbacks(browserRequestTimeout);
            browserRequestTimeout = null;
            runOnUiThread(() -> callback.accept(error == null ? html : null));
        };
        browserRequestTimeout = () -> {
            if (pendingBrowserCallback == null) return;
            BrowserPageCallback timedOut = pendingBrowserCallback;
            pendingBrowserCallback = null;
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
        setCatalogStatus("Раздач", parsed.size());
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



    private void setSearchSort(String key) {
        if (key.equals(searchSort)) searchSortDescending = !searchSortDescending;
        else { searchSort = key; searchSortDescending = true; }
        // Keep the sort controls attached: rebuilding the whole list steals DPAD
        // focus for a frame on Android TV. Only the result rows are refreshed.
        renderTorrents(torrents);
    }

    private double sizeValue(String value) {
        if (value == null) return 0;
        String normalized = value.replace(',', '.').replaceAll("[^0-9.]", "");
        double number;
        try { number = Double.parseDouble(normalized); } catch (Exception e) { return 0; }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("tb") || lower.contains("тб")) return number * 1024d * 1024d;
        if (lower.contains("gb") || lower.contains("гб")) return number * 1024d;
        if (lower.contains("mb") || lower.contains("мб")) return number;
        return number;
    }

    private String dateValue(String value) {
        if (value == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})[.\\-/](\\d{1,2})[.\\-/](\\d{2,4})").matcher(value);
        if (m.find()) {
            String y = m.group(3).length() == 2 ? "20" + m.group(3) : m.group(3);
            return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d",
                    Integer.parseInt(y), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        }
        return value;
    }

    private void renderTorrents(List<Torrent> items) {
        if (list == null) return;
        List<Torrent> displayItems = new ArrayList<>(items);
        boolean isSearch = currentPath != null && currentPath.contains("s=");
        if (isSearch) {
            if (searchSortBar == null || searchSortBar.getParent() != list) {
                searchSortBar = new LinearLayout(this);
                searchSortBar.setGravity(Gravity.CENTER_VERTICAL);
                searchSortBar.setPadding(dp(8), dp(4), dp(8), dp(8));
                sortDateButton = button("");
                sortSizeButton = button("");
                sortSeedsButton = button("");
                for (Button b : new Button[]{sortDateButton, sortSizeButton, sortSeedsButton}) {
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(38), 1f);
                    p.rightMargin = dp(5);
                    searchSortBar.addView(b, p);
                }
                sortDateButton.setOnClickListener(v -> setSearchSort("date"));
                sortSizeButton.setOnClickListener(v -> setSearchSort("size"));
                sortSeedsButton.setOnClickListener(v -> setSearchSort("seeds"));
                list.addView(searchSortBar);
            } else {
                // Preserve the attached sort bar and its focused child.
                for (int i = list.getChildCount() - 1; i >= 0; i--) {
                    if (list.getChildAt(i) != searchSortBar) list.removeViewAt(i);
                }
            }
            sortDateButton.setText("Дата" + ("date".equals(searchSort) ? (searchSortDescending ? " ↓" : " ↑") : ""));
            sortSizeButton.setText("Размер" + ("size".equals(searchSort) ? (searchSortDescending ? " ↓" : " ↑") : ""));
            sortSeedsButton.setText("Сиды" + ("seeds".equals(searchSort) ? (searchSortDescending ? " ↓" : " ↑") : ""));
            java.util.Collections.sort(displayItems, (a, b) -> {
                int cmp;
                if ("size".equals(searchSort)) cmp = Double.compare(sizeValue(a.size), sizeValue(b.size));
                else if ("seeds".equals(searchSort)) cmp = Integer.compare(parseInt(a.seeds), parseInt(b.seeds));
                else cmp = dateValue(a.uploaded).compareTo(dateValue(b.uploaded));
                return searchSortDescending ? -cmp : cmp;
            });
        } else {
            list.removeAllViews();
            searchSortBar = null;
            sortDateButton = sortSizeButton = sortSeedsButton = null;
        }
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

        for (Torrent t : displayItems) {
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
        animateContentTransition(list);
    }

    private void changePage(int delta) {
        int next = Math.max(0, page + delta); if (next == page) return; page = next;
        String path = currentPath + (currentPath.contains("?") ? "&" : "?") + "page=" + (page + 1);
        load(path, currentTitle);
    }

    private static final String SEARCH_HISTORY_KEY = "search_history";

    private java.util.ArrayList<String> readSearchHistory() {
        java.util.ArrayList<String> history = new java.util.ArrayList<>();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(SEARCH_HISTORY_KEY, "[]");
        try {
            org.json.JSONArray array = new org.json.JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "").trim();
                if (!value.isEmpty() && !history.contains(value)) history.add(value);
            }
        } catch (Exception ignored) { }
        return history;
    }

    private void saveSearchHistory(String query) {
        java.util.ArrayList<String> history = readSearchHistory();
        history.remove(query);
        history.add(0, query);
        while (history.size() > 30) history.remove(history.size() - 1);
        org.json.JSONArray array = new org.json.JSONArray();
        for (String value : history) array.put(value);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(SEARCH_HISTORY_KEY, array.toString()).apply();
    }

    private void showSearchHistory() {
        java.util.ArrayList<String> history = readSearchHistory();
        if (history.isEmpty()) {
            showMessage("История поиска пока пуста");
            return;
        }
        String[] items = history.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("История поиска")
                .setItems(items, (dialog, which) -> {
                    search.setText(items[which]);
                    search.setSelection(search.length());
                    submitSearch();
                })
                .setNeutralButton("Очистить", (dialog, which) -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .remove(SEARCH_HISTORY_KEY).apply();
                    showMessage("История поиска очищена");
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private static final int REQUEST_VOICE_SEARCH = 7401;

    private void startVoiceSearch() {
        android.content.Intent intent = new android.content.Intent(
                android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU");
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Скажите название фильма или раздачи");
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            startActivityForResult(intent, REQUEST_VOICE_SEARCH);
        } catch (android.content.ActivityNotFoundException e) {
            showMessage("На устройстве не найдено приложение для голосового ввода.");
        } catch (Exception e) {
            showMessage("Не удалось запустить голосовой ввод: " + safe(e.getMessage()));
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_VOICE_SEARCH || resultCode != RESULT_OK || data == null) return;
        java.util.ArrayList<String> results = data.getStringArrayListExtra(
                android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (results != null && !results.isEmpty()) {
            String recognized = results.get(0);
            if (recognized != null && !recognized.trim().isEmpty()) {
                search.setText(recognized.trim());
                search.setSelection(search.length());
                submitSearch();
            }
        }
    }

    private void submitSearch() {
        String q = search.getText().toString().trim(); if (q.isEmpty()) return;
        saveSearchHistory(q);
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


    private java.util.LinkedHashMap<String, Torrent> readFavorites() {
        java.util.LinkedHashMap<String, Torrent> result = new java.util.LinkedHashMap<>();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(FAVORITES_KEY, "[]");
        try {
            org.json.JSONArray array = new org.json.JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                Torrent t = new Torrent();
                t.title = o.optString("title", "");
                t.href = o.optString("href", "");
                t.size = o.optString("size", "—");
                t.seeds = o.optString("seeds", "—");
                t.leeches = o.optString("leeches", "—");
                t.uploaded = o.optString("uploaded", "—");
                if (!t.href.isEmpty()) result.put(t.href, t);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private void writeFavorites(java.util.LinkedHashMap<String, Torrent> favorites) {
        org.json.JSONArray array = new org.json.JSONArray();
        for (Torrent t : favorites.values()) {
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                o.put("title", t.title); o.put("href", t.href); o.put("size", t.size);
                o.put("seeds", t.seeds); o.put("leeches", t.leeches); o.put("uploaded", t.uploaded);
                array.put(o);
            } catch (Exception ignored) { }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(FAVORITES_KEY, array.toString()).apply();
    }

    private boolean isFavorite(Torrent torrent) {
        return torrent != null && readFavorites().containsKey(torrent.href);
    }

    private void toggleFavorite(Torrent torrent) {
        if (torrent == null || torrent.href == null || torrent.href.isEmpty()) return;
        java.util.LinkedHashMap<String, Torrent> favorites = readFavorites();
        if (favorites.containsKey(torrent.href)) {
            favorites.remove(torrent.href);
            showMessage("Удалено из избранного");
        } else {
            favorites.put(torrent.href, torrent);
            showMessage("Добавлено в избранное");
        }
        writeFavorites(favorites);
    }

    private void showFavorites() {
        currentPath = "local:favorites";
        currentTitle = "Избранное";
        heading.setText(currentTitle);
        java.util.List<Torrent> saved = new java.util.ArrayList<>(readFavorites().values());
        list.removeAllViews();
        setCatalogStatus("Раздач в избранном", saved.size());
        if (saved.isEmpty()) addEmpty("Избранное пока пусто. Добавляйте раздачи из карточки.");
        else renderTorrents(saved);
    }


    private static final String RECENTLY_VIEWED_KEY = "recently_viewed";

    private java.util.LinkedHashMap<String, Torrent> readRecentlyViewed() {
        java.util.LinkedHashMap<String, Torrent> result = new java.util.LinkedHashMap<>();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(RECENTLY_VIEWED_KEY, "[]");
        try {
            org.json.JSONArray array = new org.json.JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                Torrent t = new Torrent();
                t.title = o.optString("title", "");
                t.href = o.optString("href", "");
                t.size = o.optString("size", "—");
                t.seeds = o.optString("seeds", "—");
                t.leeches = o.optString("leeches", "—");
                t.uploaded = o.optString("uploaded", "—");
                if (!t.href.isEmpty()) result.put(t.href, t);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private void saveRecentlyViewed(Torrent torrent) {
        if (torrent == null || torrent.href == null || torrent.href.isEmpty()) return;
        java.util.LinkedHashMap<String, Torrent> recent = readRecentlyViewed();
        recent.remove(torrent.href);
        recent.put(torrent.href, torrent);
        while (recent.size() > 30) {
            String oldest = recent.keySet().iterator().next();
            recent.remove(oldest);
        }
        org.json.JSONArray array = new org.json.JSONArray();
        java.util.List<Torrent> values = new java.util.ArrayList<>(recent.values());
        for (int i = values.size() - 1; i >= 0; i--) {
            Torrent t = values.get(i);
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                o.put("title", t.title); o.put("href", t.href); o.put("size", t.size);
                o.put("seeds", t.seeds); o.put("leeches", t.leeches); o.put("uploaded", t.uploaded);
                array.put(o);
            } catch (Exception ignored) { }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(RECENTLY_VIEWED_KEY, array.toString()).apply();
    }

    private void showRecentlyViewed() {
        currentPath = "local:recently-viewed";
        currentTitle = "Недавно смотрели";
        heading.setText(currentTitle);
        java.util.List<Torrent> saved = new java.util.ArrayList<>(readRecentlyViewed().values());
        list.removeAllViews();
        setCatalogStatus("Недавно смотрели", saved.size());
        if (saved.isEmpty()) addEmpty("Здесь появятся открытые вами раздачи.");
        else renderTorrents(saved);
    }

    private static class TorrentDetails {
        String title = "", poster = "", torrentLink = "", sourcePage = "";
        String imdbRating = "—", kinopoiskRating = "—", kinozalRating = "—", kinozalStars = "☆☆☆☆☆☆☆☆☆☆", kinozalVotes = "";
        final java.util.LinkedHashMap<String, String> fields = new java.util.LinkedHashMap<>();
    }

    private void showTorrent(Torrent t) {
        focusedTorrentSource = getCurrentFocus();
        progress.setVisibility(View.VISIBLE);
        if (sessionWebView != null) {
            fetchBrowserPage(t.href, null, html -> {
                progress.setVisibility(View.GONE);
                if (html == null || html.isEmpty()) {
                    showMessage("Не удалось загрузить карточку через браузерную сессию.");
                    return;
                }
                try {
                    Document doc = Jsoup.parse(html, t.href);
                    TorrentDetails details = parseTorrentDetails(doc, t);
                    saveRecentlyViewed(t);
                    showTorrentDetails(t, details);
                } catch (Exception e) {
                    showMessage("Не удалось разобрать карточку: " + safe(e.getMessage()));
                }
            });
            return;
        }
        io.execute(() -> {
            try {
                String html = client.fetch(t.href);
                Document doc = Jsoup.parse(html, t.href);
                TorrentDetails details = parseTorrentDetails(doc, t);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    saveRecentlyViewed(t);
                    showTorrentDetails(t, details);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    showMessage("Не удалось загрузить карточку: " + safe(e.getMessage()));
                });
            }
        });
    }

    private TorrentDetails parseTorrentDetails(Document doc, Torrent torrent) {
        TorrentDetails data = new TorrentDetails();
        data.title = torrent.title;
        data.sourcePage = torrent.href;

        // All ratings are read from the current Kinozal details HTML.
        Element imdbLink = doc.selectFirst("a[href*=imdb.com]");
        if (imdbLink != null) {
            Element score = imdbLink.selectFirst(".floatright");
            if (score != null && !score.text().trim().isEmpty()) data.imdbRating = score.text().trim();
        }
        Element kpLink = doc.selectFirst("a[href*=kinopoisk.ru]");
        if (kpLink != null) {
            Element score = kpLink.selectFirst(".floatright");
            if (score != null && !score.text().trim().isEmpty()) data.kinopoiskRating = score.text().trim();
        }
        Element kzValue = doc.selectFirst("[itemprop=ratingValue]");
        if (kzValue != null && !kzValue.text().trim().isEmpty()) data.kinozalRating = kzValue.text().trim();
        Element votes = doc.selectFirst("[itemprop=ratingCount], [itemprop=votes]");
        if (votes != null) {
            String count = votes.hasAttr("content") ? votes.attr("content") : votes.text();
            if (!count.trim().isEmpty()) data.kinozalVotes = count.trim();
        }
        Element starbar = doc.selectFirst("#starbar");
        if (starbar != null) {
            java.util.regex.Matcher width = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)px")
                    .matcher(starbar.attr("style"));
            if (width.find()) {
                try {
                    int filled = Math.max(0, Math.min(10, (int)Math.round(Double.parseDouble(width.group(1)) / 10.0)));
                    StringBuilder stars = new StringBuilder();
                    for (int i = 0; i < 10; i++) stars.append(i < filled ? "★" : "☆");
                    data.kinozalStars = stars.toString();
                } catch (Exception ignored) { }
            }
        }
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

        Element download = doc.selectFirst("a[href*=/download.php], a[href$=.torrent], a[href*=magnet:]");
        if (download != null) {
            String href = download.attr("href").trim();
            data.torrentLink = href.startsWith("magnet:") ? href : download.absUrl("href");
        }
        return data;
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

    private String cleanDetailLabel(String value) {
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
        Button favorite = button(isFavorite(torrent) ? "★ В избранном" : "☆ В избранное");
        favorite.setTextSize(13);
        favorite.setOnClickListener(v -> {
            toggleFavorite(torrent);
            favorite.setText(isFavorite(torrent) ? "★ В избранном" : "☆ В избранное");
        });
        LinearLayout.LayoutParams favoriteParams = new LinearLayout.LayoutParams(-1, dp(42));
        favoriteParams.bottomMargin = dp(10);
        posterColumn.addView(favorite, favoriteParams);

        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        poster.setBackground(shape(PANEL, 0));
        posterColumn.addView(poster, new LinearLayout.LayoutParams(-1, dp(260)));
        LinearLayout ratings = new LinearLayout(this);
        ratings.setOrientation(LinearLayout.VERTICAL);
        ratings.setGravity(Gravity.CENTER_HORIZONTAL);
        ratings.setPadding(dp(2), dp(8), dp(2), dp(2));

        TextView externalRatings = text(
                "IMDb  " + data.imdbRating + "     Кинопоиск  " + data.kinopoiskRating,
                10, Color.LTGRAY, false);
        externalRatings.setGravity(Gravity.CENTER);
        externalRatings.setSingleLine(true);
        externalRatings.setIncludeFontPadding(false);
        ratings.addView(externalRatings, new LinearLayout.LayoutParams(-1, -2));

        TextView kinozalRating = text("Kinozal  " + data.kinozalRating, 12, TEAL, true);
        kinozalRating.setGravity(Gravity.CENTER);
        kinozalRating.setPadding(0, dp(3), 0, 0);
        ratings.addView(kinozalRating, new LinearLayout.LayoutParams(-1, -2));

        TextView stars = text(data.kinozalStars, 13, GOLD, true);
        stars.setGravity(Gravity.CENTER);
        stars.setPadding(0, dp(3), 0, 0);
        ratings.addView(stars, new LinearLayout.LayoutParams(-1, -2));

        posterColumn.addView(ratings, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams posterParams = new LinearLayout.LayoutParams(dp(190), -1);
        posterParams.rightMargin = dp(12);
        mainArea.addView(posterColumn, posterParams);

        LinearLayout detailsColumn = new LinearLayout(this);
        detailsColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        Button descriptionTab = button("Описание");
        Button technicalTab = button("Техданные");
        Button jackettTab = button("Другие раздачи");
        tabs.addView(descriptionTab, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams technicalParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        technicalParams.leftMargin = dp(6);
        tabs.addView(technicalTab, technicalParams);
        LinearLayout.LayoutParams jackettParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        jackettParams.leftMargin = dp(6);
        tabs.addView(jackettTab, jackettParams);
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
        jackettTab.setOnClickListener(v -> searchJackett(torrent.title, data.poster));
        render.run();

        watch.setOnClickListener(v -> playTorrent(data));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(root)
                .create();
        dialog.show();
        dialog.setOnDismissListener(ignored -> {
            View returnFocus = focusedTorrentSource;
            focusedTorrentSource = null;
            if (returnFocus != null) {
                returnFocus.post(() -> {
                    if (returnFocus.isAttachedToWindow()
                            && returnFocus.getVisibility() == View.VISIBLE
                            && returnFocus.isFocusable()) {
                        returnFocus.requestFocus();
                    }
                });
            }
        });
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
        if (data.torrentLink.startsWith("magnet:")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(data.torrentLink));
                intent.setPackage("ru.yourok.torrserve");
                intent.putExtra("poster", data.poster == null ? "" : data.poster);
                intent.putExtra("title", data.title == null ? "" : data.title);
                startActivity(intent);
            } catch (Exception e) {
                showMessage("Не удалось открыть magnet в TorrServe: " + safe(e.getMessage()));
            }
            return;
        }

        // Download through the same WebView session that opened the details page.
        // This preserves Cloudflare/session cookies and the real details-page Referer.
        WebView web = sessionWebView;
        if (web != null) {
            // evaluateJavascript does not await a returned Promise. Use a JS bridge
            // so the response is delivered only after the authenticated fetch finishes.
            web.addJavascriptInterface(new Object() {
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
                            // Keep the native retry as a fallback, but do not silently
                            // treat the unresolved JS Promise as a successful response.
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
            web.evaluateJavascript(script, null);
        } else {
            downloadTorrentNative(data);
        }
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
