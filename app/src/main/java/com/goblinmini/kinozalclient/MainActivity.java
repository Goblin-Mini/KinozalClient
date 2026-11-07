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
import android.graphics.Rect;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
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
import java.net.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

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
    // Image cache reads/downloads must not block catalogue parsing or other app I/O.
    private final ExecutorService imageIo = Executors.newFixedThreadPool(3);
    // Home refresh is isolated so nine feed requests cannot starve poster loading.
    private final ExecutorService homeRefreshIo = Executors.newFixedThreadPool(4);
    private KinozalHttpClient client;
    private Socks5ProxyBridge socks5Bridge;
    private DiskCache diskCache;
    private String mirror;
    private String currentPath = "browse.php";
    private String currentTitle = "Все раздачи";
    private TextView sidebarConnectionInfo;
    private TextView sidebarCategoryInfo;
    private TextView sidebarCountryInfo;
    private TextView sidebarVersionInfo;
    private int lastLoadedTorrentCount = 0;
    private String lastKinozalConnectionStatus = "не проверено";
    private String activeTorrentDetailsHref = "";
    private String pendingDetailPrefetchHref = "";
    private final java.util.Set<String> detailPrefetchScheduled = new java.util.HashSet<>();
    private LinearLayout root, sidebar, content, list, menuItems;
    private ScrollView homeScrollView;
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
    private boolean homeCacheDisplayed = false;
    private boolean homeRefreshInProgress = false;
    private List<HomeSection> homeSectionsMemory = new ArrayList<>();
    private String homeCacheDiagnostic = "Кэш главной ещё не проверялся";

    private volatile String lastBrowserError = "Причина пока не определена";
    // Infohash is prefetched while the native details dialog is being viewed.
    // This hides the Kinozal/Cloudflare round trip behind the user's reading time.
    private final java.util.Map<String, String> infoHashCache = new java.util.HashMap<>();
    private String pendingInfoHashId;
    private final List<java.util.function.Consumer<String>> pendingInfoHashCallbacks = new ArrayList<>();

    private interface BrowserPageCallback { void onPage(String html, String error); }
    private final java.util.LinkedHashMap<String, String> pageCache =
            new java.util.LinkedHashMap<String, String>(48, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                    return size() > 48;
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
        diskCache = new DiskCache(this, 200L * 1024L * 1024L);
        buildShell();
        socks5Bridge = new Socks5ProxyBridge(this);
        String savedBrowserCookies = client.getSavedBrowserCookies();
        initializeNetwork(savedBrowserCookies);
        loadExternalCountryInfo(sidebarCountryInfo);
    }


    private void initializeNetwork(String savedBrowserCookies) {
        Socks5ProxyBridge.Settings config = socks5Bridge.getSettings();
        if (!config.enabled) {
            client.setProxy(Proxy.NO_PROXY);
            continueStartup(savedBrowserCookies);
            return;
        }
        try {
            Proxy localProxy = socks5Bridge.start();
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                socks5Bridge.stop();
                throw new IllegalStateException("Компонент WebView не поддерживает прокси. Обновите Android System WebView.");
            }
            applyWebViewProxy(true, () -> {
                client.setProxy(localProxy);
                continueStartup(savedBrowserCookies);
            });
        } catch (Exception e) {
            client.setProxy(blockedProxy());
            status.setText("SOCKS5 не запущен: " + safe(e.getMessage()));
            showMessage("Не удалось включить SOCKS5: " + safe(e.getMessage())
                    + "\\nПрямое подключение не используется.");
        }
    }

    private Proxy blockedProxy() {
        // Fail closed if an enabled proxy cannot start; never silently fall back to direct traffic.
        return new Proxy(Proxy.Type.HTTP, new java.net.InetSocketAddress("127.0.0.1", 1));
    }

    private void continueStartup(String savedBrowserCookies) {
        if (savedBrowserCookies != null) restoreBrowserSession(savedBrowserCookies);
        else markStartupCatalogReady();
    }

    private void applyWebViewProxy(boolean enabled, Runnable callback) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (enabled) throw new IllegalStateException("Эта версия WebView не поддерживает прокси");
            callback.run();
            return;
        }
        ProxyConfig.Builder builder = new ProxyConfig.Builder();
        if (enabled) builder.addProxyRule("http://127.0.0.1:" + socks5Bridge.getLocalPort());
        else builder.addDirect();
        ProxyController.getInstance().setProxyOverride(builder.build(),
                command -> runOnUiThread(command), callback);
    }

    private void showSocks5Settings() {
        Socks5ProxyBridge.Settings cfg = socks5Bridge.getSettings();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(12), dp(18), dp(8));
        panel.setBackgroundColor(BG);
        CheckBox enabled = new CheckBox(this);
        enabled.setText("Использовать SOCKS5 для приложения");
        enabled.setTextColor(GOLD);
        enabled.setButtonTintList(android.content.res.ColorStateList.valueOf(TEAL));
        enabled.setChecked(cfg.enabled);
        panel.addView(enabled);
        EditText host = proxyField("Адрес сервера", cfg.host, android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        EditText port = proxyField("Порт", String.valueOf(cfg.port), android.text.InputType.TYPE_CLASS_NUMBER);
        EditText username = proxyField("Логин (если требуется)", cfg.username,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        EditText password = proxyField("Пароль (если требуется)", cfg.password,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        addProxyField(panel, "Сервер", host);
        addProxyField(panel, "Порт", port);
        addProxyField(panel, "Логин", username);
        addProxyField(panel, "Пароль", password);
        TextView note = text("Прокси применяется к WebView, каталогу, карточкам и диагностике. Пароль хранится зашифрованным в Android Keystore.",
                12, TEAL, false);
        note.setPadding(0, dp(8), 0, dp(4));
        panel.addView(note);
        Button test = button("Проверить подключение");
        test.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams testParams = new LinearLayout.LayoutParams(-1, dp(44));
        testParams.topMargin = dp(8);
        panel.addView(test, testParams);
        TextView testResult = text("Проверка ещё не выполнялась", 12, TEAL, false);
        testResult.setPadding(0, dp(5), 0, 0);
        panel.addView(testResult);
        test.setOnClickListener(v -> {
            final String testHost = host.getText().toString().trim();
            final String testUser = username.getText().toString().trim();
            final String testPassword = password.getText().toString();
            final int testPort;
            try { testPort = Integer.parseInt(port.getText().toString().trim()); }
            catch (Exception e) { testResult.setTextColor(RED); testResult.setText("Укажите корректный порт"); return; }
            test.setEnabled(false);
            testResult.setTextColor(TEAL);
            testResult.setText("Проверка SOCKS5 и авторизации…");
            io.execute(() -> {
                try {
                    socks5Bridge.testConnection(testHost, testPort, testUser, testPassword);
                    runOnUiThread(() -> {
                        test.setEnabled(true);
                        testResult.setTextColor(GOLD);
                        testResult.setText("✓ SOCKS5 отвечает, авторизация успешна. Страна подключения отображается в настройках.");
                    });
                } catch (Exception e) {
                    String error = safe(e.getMessage());
                    runOnUiThread(() -> {
                        test.setEnabled(true);
                        testResult.setTextColor(RED);
                        testResult.setText("✕ Ошибка подключения: " + error);
                    });
                }
            });
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("SOCKS5").setView(panel)
                .setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(GOLD);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(TEAL);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                int portValue;
                try { portValue = Integer.parseInt(port.getText().toString().trim()); }
                catch (Exception e) { showMessage("Укажите корректный порт SOCKS5"); return; }
                try {
                    socks5Bridge.saveSettings(enabled.isChecked(), host.getText().toString(),
                            portValue, username.getText().toString(), password.getText().toString());
                    Proxy local;
                    if (enabled.isChecked()) local = socks5Bridge.start();
                    else { socks5Bridge.stop(); local = Proxy.NO_PROXY; }
                    if (enabled.isChecked() && !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                        socks5Bridge.stop();
                        throw new IllegalStateException("Обновите Android System WebView: нет поддержки прокси");
                    }
                    applyWebViewProxy(enabled.isChecked(), () -> {
                        client.setProxy(local);
                        dialog.dismiss();
                        showMessage(enabled.isChecked() ? "SOCKS5 включён" : "SOCKS5 отключён");
                        if (sessionWebView != null) loadInitialCatalog();
                    });
                } catch (Exception e) {
                    client.setProxy(enabled.isChecked() ? blockedProxy() : Proxy.NO_PROXY);
                    showMessage("Ошибка SOCKS5: " + safe(e.getMessage()));
                }
            });
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
            dialog.getWindow().setLayout(Math.min(dp(560),
                    getResources().getDisplayMetrics().widthPixels - dp(48)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        enabled.requestFocus();
    }

    private EditText proxyField(String hint, String value, int inputType) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(value);
        field.setHint(hint);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(TEAL);
        field.setTextSize(14);
        field.setInputType(inputType);
        field.setPadding(dp(10), dp(7), dp(10), dp(7));
        field.setBackground(shape(PANEL, TEAL));
        return field;
    }

    private void addProxyField(LinearLayout panel, String label, EditText field) {
        TextView caption = text(label, 13, TEAL, true);
        caption.setPadding(0, dp(5), 0, dp(2));
        panel.addView(caption);
        panel.addView(field, new LinearLayout.LayoutParams(-1, dp(43)));
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
        browserHandler.postDelayed(this::openConfiguredStartupSource, 350);
    }

    private void openConfiguredStartupSource() {
        String source = getSharedPreferences(PREFS, MODE_PRIVATE).getString("startup_source", "home");
        if ("browse.php".equals(source)) load("browse.php", "Все раздачи");
        else if ("browse.php?c=1002".equals(source)) load(source, "Все фильмы");
        else if ("browse.php?c=1001".equals(source)) load(source, "Все сериалы");
        else if ("browse.php?c=46".equals(source)) load(source, "Зарубежные сериалы");
        else if ("browse.php?c=45".equals(source)) load(source, "Российские сериалы");
        else loadInitialCatalog();
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

    private static final String[] HOME_PATHS = {"novinki.php", "top.php?c=0&d=0&f=0&j=&k=0&s=0&t=0",
            "top.php?c=0&d=0&f=0&j=&k=0&s=0&t=0&w=1",
            "top.php?c=0&d=0&f=0&j=&k=0&s=0&t=0&w=2",
            "top.php?j=%D0%BB%D1%83%D1%87%D1%88%D0%B8%D0%B9%20%D1%84%D0%B8%D0%BB%D1%8C%D0%BC%20%D0%9E%D1%81%D0%BA%D0%B0%D1%80",
            "top.php?j=Netflix", "top.php?j=HBO", "top.php?j=Walt%20Disney%20Pictures", "top.php?j=Marvel"};
    private static final String[] HOME_TITLES = {"Новинки", "Топ раздач", "Топ недели", "Топ месяца",
            "Топ Оскар", "Топ Netflix", "Топ HBO", "Топ Walt Disney", "Топ Marvel"};

    private void loadInitialCatalog(int startupRetry) {
        currentPath = "";
        currentTitle = "Главная";
        heading.setText("Главная");
        showPageLoading("Загрузка главной через WebView…");

        // Главная загружается только через браузерную сессию Kinozal.
        // Нативный HTTP-загрузчик и его фоновое обновление здесь не используются.
        if (sessionWebView == null) {
            restoreBrowserSession("");
        }
        browserHandler.postDelayed(() -> loadHomeSectionsInBrowser(
                HOME_PATHS, HOME_TITLES, 0, new ArrayList<>(), 0),
                sessionWebView == null ? 350 : 100);
    }

    private java.io.File homeModelFile() {
        return new java.io.File(getFilesDir(), "kinozal_home_model_v2.json");
    }

    private void saveHomeModel(List<HomeSection> sections) {
        try {
            org.json.JSONArray feeds = new org.json.JSONArray();
            for (HomeSection section : sections) {
                org.json.JSONObject feed = new org.json.JSONObject();
                feed.put("title", section.title);
                feed.put("path", section.path);
                org.json.JSONArray items = new org.json.JSONArray();
                for (HomePoster poster : section.items) {
                    org.json.JSONObject item = new org.json.JSONObject();
                    item.put("title", poster.torrent.title);
                    item.put("href", poster.torrent.href);
                    item.put("size", poster.torrent.size);
                    item.put("seeds", poster.torrent.seeds);
                    item.put("leeches", poster.torrent.leeches);
                    item.put("uploaded", poster.torrent.uploaded);
                    item.put("image", poster.imageUrl);
                    items.put(item);
                }
                feed.put("items", items);
                feeds.put(feed);
            }
            org.json.JSONObject rootJson = new org.json.JSONObject();
            rootJson.put("version", 2);
            rootJson.put("mirror", client.getMirror());
            rootJson.put("savedAt", System.currentTimeMillis());
            rootJson.put("feeds", feeds);
            java.io.File target = homeModelFile();
            java.io.File temp = new java.io.File(target.getParentFile(), target.getName() + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
                out.write(rootJson.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (target.exists() && !target.delete()) throw new java.io.IOException("Cannot replace home cache");
            if (!temp.renameTo(target)) throw new java.io.IOException("Cannot commit home cache");
        } catch (Exception ignored) { }
    }

    private List<HomeSection> readHomeModel() {
        java.io.File file = homeModelFile();
        if (!file.isFile() || file.length() <= 0 || file.length() > 8L * 1024L * 1024L) return null;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            org.json.JSONObject rootJson = new org.json.JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (rootJson.optInt("version") != 2
                    || !client.getMirror().equals(rootJson.optString("mirror"))) return null;
            if (System.currentTimeMillis() - rootJson.optLong("savedAt") > 7L * 24 * 60 * 60 * 1000) return null;
            org.json.JSONArray feeds = rootJson.optJSONArray("feeds");
            if (feeds == null) return null;
            List<HomeSection> result = new ArrayList<>();
            for (int i = 0; i < feeds.length(); i++) {
                org.json.JSONObject feed = feeds.optJSONObject(i);
                if (feed == null) continue;
                HomeSection section = new HomeSection(feed.optString("title"), feed.optString("path"));
                org.json.JSONArray items = feed.optJSONArray("items");
                if (items == null) continue;
                for (int j = 0; j < items.length(); j++) {
                    org.json.JSONObject value = items.optJSONObject(j);
                    if (value == null) continue;
                    HomePoster poster = new HomePoster();
                    poster.torrent = new Torrent();
                    poster.torrent.title = value.optString("title");
                    poster.torrent.href = value.optString("href");
                    poster.torrent.size = value.optString("size", "—");
                    poster.torrent.seeds = value.optString("seeds", "—");
                    poster.torrent.leeches = value.optString("leeches", "—");
                    poster.torrent.uploaded = value.optString("uploaded", "");
                    poster.imageUrl = value.optString("image");
                    if (!poster.torrent.title.isEmpty() && !poster.torrent.href.isEmpty()) section.items.add(poster);
                }
                if (!section.items.isEmpty()) result.add(section);
            }
            return result;
        } catch (Exception ignored) { return null; }
    }

    private void refreshHomeInBackground() {
        if (homeRefreshInProgress) return;
        homeRefreshInProgress = true;
        homeRefreshIo.execute(() -> {
            try {
                java.util.Map<String, HomeSection> merged = new java.util.LinkedHashMap<>();
                for (HomeSection old : homeSectionsMemory) merged.put(old.path, old);
                List<java.util.concurrent.Future<HomeSection>> jobs = new ArrayList<>();
                for (int i = 0; i < HOME_PATHS.length; i++) {
                    final int index = i;
                    jobs.add(homeRefreshIo.submit(() -> {
                        String html = client.fetchPublic(HOME_PATHS[index]);
                        if (html == null || html.trim().isEmpty()) return null;
                        HomeSection section = new HomeSection(HOME_TITLES[index], HOME_PATHS[index]);
                        section.items.addAll(parseHomePosters(Jsoup.parse(html, client.getMirror() + "/")));
                        if (section.items.isEmpty()) return null;
                        diskCache.put("home|" + client.getMirror() + "|" + HOME_PATHS[index],
                                html.getBytes(StandardCharsets.UTF_8));
                        return section;
                    }));
                }
                boolean changed = false;
                for (java.util.concurrent.Future<HomeSection> job : jobs) {
                    try {
                        HomeSection section = job.get();
                        if (section != null) {
                            if (!merged.containsKey(section.path) || isHomeSectionChanged(section)) changed = true;
                            merged.put(section.path, section);
                        }
                    } catch (Exception ignored) { }
                }
                List<HomeSection> refreshed = new ArrayList<>(merged.values());
                removeHomeDuplicates(refreshed);
                saveHomeModel(refreshed);
                final boolean contentChanged = changed;
                runOnUiThread(() -> {
                    homeSectionsMemory = refreshed;
                    if (contentChanged && (currentPath == null || currentPath.isEmpty())) renderHome(refreshed);
                    homeRefreshInProgress = false;
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> homeRefreshInProgress = false);
            }
        });
    }

    private boolean isHomeSectionChanged(HomeSection updated) {
        for (HomeSection old : homeSectionsMemory) {
            if (!old.path.equals(updated.path)) continue;
            if (old.items.size() != updated.items.size()) return true;
            for (int i = 0; i < old.items.size(); i++) {
                HomePoster a = old.items.get(i), b = updated.items.get(i);
                if (!a.torrent.href.equals(b.torrent.href)
                        || !a.torrent.title.equals(b.torrent.title)
                        || !a.imageUrl.equals(b.imageUrl)) return true;
            }
            return false;
        }
        return true;
    }

    private void loadInitialCatalogFromHttp(String[] homePaths, String[] homeTitles) {
        homeRefreshIo.execute(() -> {
            List<java.util.concurrent.Future<HomeSection>> jobs = new ArrayList<>();
            for (int i = 0; i < homePaths.length; i++) {
                final int index = i;
                jobs.add(homeRefreshIo.submit(() -> {
                    String html = client.fetchPublic(homePaths[index]);
                    if (html == null || html.trim().isEmpty()) return null;
                    HomeSection section = new HomeSection(homeTitles[index], homePaths[index]);
                    section.items.addAll(parseHomePosters(Jsoup.parse(html, client.getMirror() + "/")));
                    if (section.items.isEmpty()) return null;
                    diskCache.put("home|" + client.getMirror() + "|" + homePaths[index],
                            html.getBytes(StandardCharsets.UTF_8));
                    return section;
                }));
            }
            List<HomeSection> sections = new ArrayList<>();
            for (java.util.concurrent.Future<HomeSection> job : jobs) {
                try { HomeSection section = job.get(); if (section != null) sections.add(section); }
                catch (Exception ignored) { }
            }
            removeHomeDuplicates(sections);
            if (!sections.isEmpty()) saveHomeModel(sections);
            int total = countHomeCards(sections);
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                if (!sections.isEmpty()) {
                    homeSectionsMemory = new ArrayList<>(sections);
                    homeCacheDisplayed = true;
                    setCatalogStatus("Постеров", total);
                    renderHome(sections);
                    status.setText("Показано лент: " + sections.size());
                } else if (!homeCacheDisplayed) {
                    status.setText("Пробуем резервную загрузку через WebView…");
                    if (sessionWebView == null) restoreBrowserSession("");
                    browserHandler.postDelayed(() -> loadHomeSectionsInBrowser(
                            homePaths, homeTitles, 0, new ArrayList<>(), 0), 250);
                }
            });
        });
    }

    private void loadHomeSectionsInBrowser(String[] paths, String[] titles, int index,
            List<HomeSection> sections, int retry) {
        if (index >= paths.length) {
            removeHomeDuplicates(sections);
            // Re-render the final de-duplicated model. This replaces each row
            // in place, so cards discarded by final normalization disappear too.
            for (HomeSection section : sections) renderHomeSection(section);
            int total = countHomeCards(sections);
            if (!sections.isEmpty()) homeSectionsMemory = new ArrayList<>(sections);
            progress.setVisibility(View.GONE);
            if (total == 0) {
                status.setText("Нет соединения");
                addEmpty("Не удалось получить постеры Kinozal. Проверьте авторизацию и выбранное зеркало.");
            } else {
                setCatalogStatus("Постеров", total);
                status.setText("Показано лент: " + sections.size());
            }
            return;
        }

        fetchBrowserPage(paths[index], null, html -> {
            if (html == null || html.isEmpty()) {
                if (index == 0 && retry == 0) {
                    status.setText("Повторное подключение к Kinozal…");
                    browserHandler.postDelayed(() -> loadHomeSectionsInBrowser(
                            paths, titles, 0, new ArrayList<>(), 1), 1200);
                    return;
                }
                browserHandler.postDelayed(() -> loadHomeSectionsInBrowser(
                        paths, titles, index + 1, sections, 0), 150);
                return;
            }

            // Disk writes and Jsoup parsing are deliberately kept off the UI
            // thread. Each completed page is processed independently, then
            // only its small parsed model is handed back for rendering.
            io.execute(() -> {
                HomeSection section = new HomeSection(titles[index], paths[index]);
                try {
                    // Parse and validate first. Never replace a known-good cache
                    // entry with a Cloudflare challenge, login page or empty response.
                    section.items.addAll(parseHomePosters(
                            Jsoup.parse(html, client.getMirror() + "/")));
                    if (!section.items.isEmpty()) {
                        List<HomeSection> oneFeed = new ArrayList<>();
                        oneFeed.add(section);
                        removeHomeDuplicates(oneFeed);
                        if (!section.items.isEmpty()) {
                            diskCache.put("home|" + client.getMirror() + "|" + paths[index],
                                    html.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                } catch (Exception ignored) { }

                runOnUiThread(() -> {
                    if (!section.items.isEmpty()) {
                        sections.add(section);
                        renderHomeSection(section);
                    }
                    status.setText("Показано лент: " + sections.size());
                    setHomeProgress(index + 1, paths.length, sections.size());
                    browserHandler.postDelayed(() -> loadHomeSectionsInBrowser(
                            paths, titles, index + 1, sections, 0), 100);
                });
            });
        });
    }

    /**
     * Load the first page as a normal WebView navigation to establish the
     * authenticated origin, then fetch all remaining home sections concurrently.
     */
    private void fetchBrowserPages(String[] paths, java.util.function.Consumer<List<String>> callback) {
        fetchBrowserPage(paths[0], null, firstHtml -> {
            if (firstHtml == null || firstHtml.isEmpty()) {
                if (lastBrowserError == null || lastBrowserError.isEmpty())
                    lastBrowserError = "Первый запрос novinki.php не вернул HTML";
                callback.accept(null);
                return;
            }
            if (paths.length == 1) {
                List<String> only = new ArrayList<>();
                only.add(firstHtml);
                callback.accept(only);
                return;
            }
            WebView web = sessionWebView;
            if (web == null) {
                callback.accept(null);
                return;
            }

            org.json.JSONArray requested = new org.json.JSONArray();
            for (int i = 1; i < paths.length; i++) requested.put(paths[i]);

            final boolean[] completed = {false};
            final Runnable[] timeoutHolder = {null};
            web.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void onHomePagesResult(String json) {
                    runOnUiThread(() -> {
                        if (completed[0]) return;
                        completed[0] = true;
                        if (timeoutHolder[0] != null) browserHandler.removeCallbacks(timeoutHolder[0]);
                        web.removeJavascriptInterface("KinozalHomeBridge");
                        try {
                            org.json.JSONArray array = new org.json.JSONArray(json);
                            List<String> result = new ArrayList<>();
                            result.add(firstHtml);
                            for (int i = 0; i < array.length(); i++) {
                                String html = array.optString(i, "");
                                result.add(html);
                            }
                            callback.accept(result);
                        } catch (Exception e) {
                            lastBrowserError = "Ошибка разбора ответа JS-моста: " + e.getMessage();
                            callback.accept(null);
                        }
                    });
                }
            }, "KinozalHomeBridge");

            timeoutHolder[0] = () -> {
                if (completed[0]) return;
                completed[0] = true;
                web.removeJavascriptInterface("KinozalHomeBridge");
                lastBrowserError = "Тайм-аут параллельных запросов (35 секунд)";
                callback.accept(null);
            };
            browserHandler.postDelayed(timeoutHolder[0], 35000);

            String script = "(function(){var p=" + requested.toString() + ";"
                    + "Promise.all(p.map(function(u){return fetch(u,{credentials:'include',"
                    + "cache:'no-store'}).then(function(r){if(!r.ok)return '';return r.text();}"
                    + ").catch(function(){return '';});})).then(function(a){"
                    + "window.KinozalHomeBridge.onHomePagesResult(JSON.stringify(a));"
                    + "}).catch(function(e){window.KinozalHomeBridge.onHomePagesResult('[]');});})()";
            web.evaluateJavascript(script, null);
        });
    }

    private List<HomePoster> parseHomePosters(Document doc) {
        List<HomePoster> out = new ArrayList<>();
        Elements anchors = doc.select("table.t_peer td.nam > a[href*=details.php?id=]");
        if (anchors.isEmpty()) anchors = doc.select("a[href*=details.php?id=]");
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
    private int countHomeCards(List<HomeSection> sections) {
        int count = 0;
        for (HomeSection section : sections) count += section.items.size();
        return count;
    }

    private void removeHomeDuplicates(List<HomeSection> sections) {
        java.util.Set<String> globalIds = new java.util.HashSet<>();
        java.util.Set<String> globalTitles = new java.util.HashSet<>();
        java.util.Set<String> globalLinks = new java.util.HashSet<>();
        for (HomeSection section : sections) {
            List<HomePoster> unique = new ArrayList<>();
            for (HomePoster item : section.items) {
                if (item == null || item.torrent == null) continue;
                String href = item.torrent.href == null ? "" : item.torrent.href;
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("[?&]id=(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(href);
                String id = matcher.find() ? matcher.group(1) : "";
                String titleKey = normalizeHomeTitle(item.torrent.title);
                if ((!id.isEmpty() && globalIds.contains(id))
                        || (!href.isEmpty() && globalLinks.contains(href))
                        || (!titleKey.isEmpty() && globalTitles.contains(titleKey))) continue;
                if (!id.isEmpty()) globalIds.add(id);
                if (!href.isEmpty()) globalLinks.add(href);
                if (!titleKey.isEmpty()) globalTitles.add(titleKey);
                unique.add(item);
                if (unique.size() >= 15) break;
            }
            section.items.clear();
            section.items.addAll(unique);
        }
        sections.removeIf(section -> section.items.isEmpty());
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

    private void setHomeProgress(int processed, int total, int displayed) {
        status.setText("Показано лент: " + displayed);
    }

    private void setCatalogStatus(String itemType, int count) {
        status.setText(currentTitle + " · Загружено: " + count + " " + itemType
                + "\nВерсия: " + getAppVersionName());
        updateSidebarCategoryInfo(count);
    }

    private void updateSidebarCategoryInfo(int count) {
        lastLoadedTorrentCount = count;
        if (sidebarCategoryInfo != null) {
            sidebarCategoryInfo.setText(currentTitle + " — загружено " + count + " раздач");
        }
    }

    private void updateSidebarConnectionInfo() {
        if (sidebarConnectionInfo != null) {
            sidebarConnectionInfo.setText("Подключение к Kinozal: " + lastKinozalConnectionStatus);
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
        String value = title.toLowerCase(java.util.Locale.ROOT).trim();
        // The same movie often appears as "Русское название / Original title /
        // year / release". Use the leading title as the primary identity so
        // alternate encodes don't fill a feed with copies of one film.
        int slash = value.indexOf(" / ");
        if (slash > 0) value = value.substring(0, slash);
        value = value.replaceAll("\\b(2160p|1080p|720p|480p|4k|uhd|hdr10?|web[- .]?dl|webrip|blu[- .]?ray|bdrip|brrip|dvdrip|hdtv|h265|h264|x265|x264|hevc|avc|aac|dts|ac3|proper|repack|remux|extended|directors cut|theatrical|dubbed|subbed)\\b", " ");
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
        if (imageUrl == null || imageUrl.isEmpty() || target == null) return;
        // DiskCache.get() performs file I/O. Never call it while building home rows.
        imageIo.execute(() -> {
            byte[] cachedImage = diskCache.get("image|" + imageUrl, 0L);
            if (cachedImage != null && cachedImage.length > 0) {
                Bitmap cachedBitmap = BitmapFactory.decodeByteArray(cachedImage, 0, cachedImage.length);
                if (cachedBitmap != null) {
                    runOnUiThread(() -> { if (target.getParent() != null) target.setImageBitmap(cachedBitmap); });
                    return;
                }
            }
            runOnUiThread(() -> {
                if (target.getParent() == null) return;
                WebView web = sessionWebView;
                if (web == null) { loadImageWithHttp(imageUrl, target, referer); return; }
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
                        loadImageWithHttp(imageUrl, target, referer); return;
                    }
                    final String base64 = encoded;
                    imageIo.execute(() -> {
                        try {
                            byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                            diskCache.put("image|" + imageUrl, bytes);
                            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                            if (bitmap != null) runOnUiThread(() -> {
                                if (target.getParent() != null) target.setImageBitmap(bitmap);
                            });
                            else loadImageWithHttp(imageUrl, target, referer);
                        } catch (Exception ignored) { loadImageWithHttp(imageUrl, target, referer); }
                    });
                });
            });
        });
    }

    private void loadImageWithHttp(String imageUrl, ImageView target) {
        loadImageWithHttp(imageUrl, target, client.getMirror() + "/");
    }

    private void loadImageWithHttp(String imageUrl, ImageView target, String referer) {
        if (imageUrl == null || imageUrl.isEmpty() || target == null) return;
        imageIo.execute(() -> {
            try {
                byte[] bytes = diskCache.get("image|" + imageUrl, 0L);
                if (bytes == null || bytes.length == 0) {
                    bytes = client.fetchBytes(imageUrl, "image/*", referer);
                    if (bytes == null || bytes.length == 0) return;
                    diskCache.put("image|" + imageUrl, bytes);
                }
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
        for (HomeSection section : sections) renderHomeSection(section);
        if (sections.isEmpty()) addEmpty("На главной пока нет подборок.");
        scheduleVisibleHomePosterLoad();
    }

    /** Append one completed home feed without rebuilding rows already on screen. */
    private void renderHomeSection(HomeSection section) {
        if (section == null || section.items.isEmpty()) return;

        TextView sectionTitle = text(section.title, 20, GOLD, true);
        sectionTitle.setTag("home-title:" + section.path);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, dp(38));
        titleParams.topMargin = dp(10);

        android.widget.HorizontalScrollView horizontal = new android.widget.HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        horizontal.setFillViewport(false);
        horizontal.setTag("home-row:" + section.path);
        horizontal.setOnScrollChangeListener((View v, int x, int y, int oldX, int oldY) -> scheduleVisibleHomePosterLoad());
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);

        for (HomePoster item : section.items) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(5), dp(5), dp(5), dp(5));
            card.setBackground(shape(PANEL, 0));
            card.setFocusable(true);
            card.setClickable(true);
            card.setOnFocusChangeListener((v, focused) -> {
                v.setBackground(shape(focused ? Color.rgb(57, 68, 70) : PANEL, focused ? GOLD : 0));
                if (focused) loadHomePosterForCard(card);
            });
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
            card.setTag("home-card");
            if (!item.imageUrl.isEmpty()) {
                poster.setTag(new HomeImageRequest(item.imageUrl, client.getMirror() + "/" + section.path));
            }
            card.setOnClickListener(v -> showTorrent(item.torrent));
        }
        horizontal.addView(strip);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(238));

        // If this feed was already shown from cache, replace only its own pair
        // of views in place. Other feeds and DPAD focus remain untouched.
        String titleTag = "home-title:" + section.path;
        for (int i = 0; i + 1 < list.getChildCount(); i++) {
            Object tag = list.getChildAt(i).getTag();
            if (titleTag.equals(tag)) {
                list.removeViewAt(i + 1);
                list.removeViewAt(i);
                list.addView(sectionTitle, i, titleParams);
                list.addView(horizontal, i + 1, rowParams);
                horizontal.post(this::scheduleVisibleHomePosterLoad);
                return;
            }
        }
        list.addView(sectionTitle, titleParams);
        list.addView(horizontal, rowParams);
        horizontal.post(this::scheduleVisibleHomePosterLoad);
    }

    private static class HomeImageRequest {
        final String url, referer;
        boolean started;
        HomeImageRequest(String url, String referer) { this.url = url; this.referer = referer; }
    }

    private void scheduleVisibleHomePosterLoad() {
        if (list == null) return;
        list.post(() -> {
            if (list == null) return;
            for (int i = 0; i < list.getChildCount(); i++) {
                View child = list.getChildAt(i);
                if (child instanceof android.widget.HorizontalScrollView) {
                    loadVisibleHomePosters((android.widget.HorizontalScrollView) child);
                }
            }
        });
    }

    private void loadVisibleHomePosters(android.widget.HorizontalScrollView row) {
        if (row == null || row.getVisibility() != View.VISIBLE) return;
        Rect visible = new Rect();
        for (int i = 0; i < row.getChildCount(); i++) {
            View strip = row.getChildAt(i);
            if (!(strip instanceof ViewGroup)) continue;
            ViewGroup cards = (ViewGroup) strip;
            for (int j = 0; j < cards.getChildCount(); j++) {
                View card = cards.getChildAt(j);
                if (!card.getLocalVisibleRect(visible)) continue;
                loadHomePosterForCard(card);
            }
        }
    }

    private void loadHomePosterForCard(View card) {
        if (!(card instanceof ViewGroup) || ((ViewGroup) card).getChildCount() == 0) return;
        View first = ((ViewGroup) card).getChildAt(0);
        if (!(first instanceof ImageView)) return;
        ImageView poster = (ImageView) first;
        Object tag = poster.getTag();
        if (!(tag instanceof HomeImageRequest)) return;
        HomeImageRequest request = (HomeImageRequest) tag;
        if (request.started) return;
        request.started = true;
        loadImageIntoView(request.url, poster, request.referer);
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
        status = text("Показано лент: 0", 10, TEAL, false);
        status.setGravity(Gravity.CENTER);
        status.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, dp(30));
        statusParams.topMargin = dp(4);
        sidebar.addView(status, statusParams);
        sidebarConnectionInfo = text("Подключение к Kinozal: " + lastKinozalConnectionStatus, 10, TEAL, false);
        sidebarCategoryInfo = text(currentTitle + " — загружено " + lastLoadedTorrentCount + " раздач", 10, TEAL, false);
        sidebarCountryInfo = text("Страна подключения: определяется…", 10, TEAL, false);
        sidebarVersionInfo = text("Версия приложения: " + getAppVersionName(), 10, GOLD, false);
        TextView[] diagnosticLines = {sidebarConnectionInfo, sidebarCategoryInfo, sidebarCountryInfo, sidebarVersionInfo};
        for (TextView line : diagnosticLines) {
            line.setGravity(Gravity.CENTER);
            line.setMaxLines(2);
            line.setPadding(dp(2), dp(2), dp(2), dp(2));
            sidebar.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }

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
        homeScrollView = new ScrollView(this); homeScrollView.setFillViewport(true);
        homeScrollView.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        homeScrollView.addView(list);
        main.addView(homeScrollView, new LinearLayout.LayoutParams(-1, 0, 1));
        homeScrollView.setOnScrollChangeListener((View v, int x, int y, int oldX, int oldY) -> {
            if (y != oldY) scheduleVisibleHomePosterLoad();
        });
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

    private static final int REQUEST_BACKUP_EXPORT = 7510;
    private static final int REQUEST_BACKUP_IMPORT = 7511;
    private EditText torrServeAddressField;

    private void showSettings() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(12), dp(16), dp(10));
        page.setBackgroundColor(BG);

        TextView heading = text("НАСТРОЙКИ KINOZAL", 22, GOLD, true);
        page.addView(heading);
        TextView subtitle = text("Подключения, данные пользователя и поведение приложения", 12, TEAL, false);
        subtitle.setPadding(0, dp(3), 0, dp(10));
        page.addView(subtitle);

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        ScrollView leftScroll = new ScrollView(this);
        ScrollView rightScroll = new ScrollView(this);
        leftScroll.setFillViewport(false);
        rightScroll.setFillViewport(false);
        LinearLayout left = new LinearLayout(this);
        LinearLayout right = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        right.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(0, 0, dp(7), 0);
        right.setPadding(dp(7), 0, 0, 0);
        leftScroll.addView(left);
        rightScroll.addView(right);
        columns.addView(leftScroll, new LinearLayout.LayoutParams(0, -1, 1f));
        columns.addView(rightScroll, new LinearLayout.LayoutParams(0, -1, 1f));
        page.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1f));

        addSettingsSection(left, "АККАУНТ И СЕРВЕР");
        addSettingsAction(left, "Авторизация / проверка Kinozal", () -> showLogin());
        addSettingsAction(left, "Выбрать зеркало Kinozal", () -> chooseMirror());
        addSettingsAction(left, "Проверить обновления", () -> new UpdateManager(this).check());
        addSettingsAction(left, "Стартовый раздел", () -> chooseStartupSource());

        addSettingsSection(left, "РЕЗЕРВНОЕ КОПИРОВАНИЕ");
        addSettingsAction(left, "Создать резервную копию", () -> exportUserBackup());
        addSettingsAction(left, "Восстановить из копии", () -> importUserBackup());
        TextView backupNote = text("В копию входят избранное, история поиска, история просмотра и основные настройки. Пароль SOCKS5 не экспортируется: он защищён Android Keystore.", 11, TEAL, false);
        backupNote.setPadding(dp(4), dp(3), dp(4), dp(8));
        left.addView(backupNote);

        addSettingsSection(left, "ДОПОЛНИТЕЛЬНО");
        addSettingsAction(left, "Поиск Jackett", () -> showJackettSettings());
        addSettingsAction(left, "О приложении", () -> showAbout());

        addSettingsSection(right, "TORRSERVE");
        TextView tsLabel = text("Адрес внешнего TorrServe (например, http://192.168.1.10:8090)", 12, TEAL, true);
        tsLabel.setPadding(dp(4), dp(4), dp(4), dp(3));
        right.addView(tsLabel);
        torrServeAddressField = new EditText(this);
        torrServeAddressField.setSingleLine(true);
        torrServeAddressField.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString("torrserve_url", ""));
        torrServeAddressField.setHint("http://адрес:8090");
        styleSettingsEdit(torrServeAddressField);
        right.addView(torrServeAddressField, new LinearLayout.LayoutParams(-1, dp(44)));
        addSettingsAction(right, "Сохранить адрес TorrServe", () -> {
            String value = torrServeAddressField.getText().toString().trim().replaceAll("/+$", "");
            if (!value.isEmpty() && !validJackettBase(value)) {
                showMessage("Укажите адрес с http:// или https://");
                return;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("torrserve_url", value).apply();
            showMessage("Адрес TorrServe сохранён");
        });
        addSettingsAction(right, "Проверить историю и позицию TorrServe", () -> checkTorrServerViewingHistory());

        addSettingsSection(right, "СЕТЬ И ПРОКСИ");
        addSettingsAction(right, "SOCKS5: настройки и проверка", () -> showSocks5Settings());
        Socks5ProxyBridge.Settings proxy = socks5Bridge.getSettings();
        TextView proxyStatus = text(proxy.enabled
                ? "SOCKS5 включён: " + proxy.host + ":" + proxy.port
                : "SOCKS5 отключён", 12, proxy.enabled ? GOLD : TEAL, false);
        proxyStatus.setPadding(dp(4), dp(2), dp(4), dp(8));
        right.addView(proxyStatus);
        Button close = button("Закрыть");
        close.setTextColor(GOLD);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(44));
        closeParams.topMargin = dp(8);
        page.addView(close, closeParams);
        settingsDialog = new AlertDialog.Builder(this).setView(page).create();
        settingsDialog.show();
        if (settingsDialog.getWindow() != null) {
            settingsDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
            settingsDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
        close.setOnClickListener(v -> settingsDialog.dismiss());
    }

    private void checkTorrServerViewingHistory() {
        String base = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("torrserve_url", "").trim().replaceAll("/+$", "");
        if (base.isEmpty()) {
            showMessage("Сначала укажите адрес TorrServer в настройках.");
            return;
        }
        showMessage("Проверяем историю TorrServer…");
        io.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(base + "/viewed").openConnection();
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(10000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", "application/json");
                byte[] payload = "{\"action\":\"list\"}".getBytes(StandardCharsets.UTF_8);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(payload); }
                int code = connection.getResponseCode();
                java.io.InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                StringBuilder body = new StringBuilder();
                if (stream != null) {
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) body.append(line);
                    }
                }
                if (code < 200 || code >= 300)
                    throw new java.io.IOException("/viewed: HTTP " + code + " "
                            + body.toString().substring(0, Math.min(180, body.length())));
                org.json.JSONArray entries = new org.json.JSONArray(body.toString());
                final String report = "API /viewed: доступен\n"
                        + "Записей в истории TorrServer: " + entries.length()
                        + "\n\nПроверяется только список истории. Временные позиции воспроизведения "
                        + "не используются.";
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("История TorrServer").setMessage(report)
                        .setPositiveButton("Закрыть", null).show());
            } catch (Exception e) {
                final String report = "Не удалось прочитать историю TorrServer: " + safe(e.getMessage())
                        + "\n\nПроверьте адрес, доступность сервера и авторизацию API.";
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("История TorrServer").setMessage(report)
                        .setPositiveButton("Закрыть", null).show());
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void addSettingsSection(LinearLayout parent, String title) {
        TextView label = text(title, 13, TEAL, true);
        label.setPadding(dp(4), dp(12), dp(4), dp(5));
        parent.addView(label);
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(65, 70, 74));
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void addSettingsAction(LinearLayout parent, String title, Runnable action) {
        Button button = button(title);
        button.setTextSize(13);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        button.setPadding(dp(10), 0, dp(8), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(44));
        params.topMargin = dp(5);
        parent.addView(button, params);
        button.setOnClickListener(v -> action.run());
    }

    private void styleSettingsEdit(EditText field) {
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(TEAL);
        field.setTextSize(13);
        field.setPadding(dp(10), dp(5), dp(10), dp(5));
        field.setBackground(shape(PANEL, TEAL));
    }

    private void chooseStartupSource() {
        String[] labels = {"Главная (Новинки и топы)", "Все раздачи", "Все фильмы",
                "Все сериалы", "Зарубежные сериалы", "Российские сериалы"};
        String[] values = {"home", "browse.php", "browse.php?c=1002", "browse.php?c=1001",
                "browse.php?c=46", "browse.php?c=45"};
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString("startup_source", "home");
        int selected = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(saved)) selected = i;
        new AlertDialog.Builder(this).setTitle("Открывать при запуске")
                .setSingleChoiceItems(labels, selected, (d, which) -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("startup_source", values[which]).apply();
                    d.dismiss();
                    showMessage("Стартовый раздел сохранён");
                }).setNegativeButton("Отмена", null).show();
    }

    private org.json.JSONObject buildUserBackup() throws Exception {
        org.json.JSONObject root = new org.json.JSONObject();
        root.put("format", "kinozal-client-backup");
        root.put("version", 1);
        root.put("created", System.currentTimeMillis());
        org.json.JSONObject app = new org.json.JSONObject();
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String[] keys = {FAVORITES_KEY, RECENTLY_VIEWED_KEY, SEARCH_HISTORY_KEY,
                "mirror", "startup_source", "torrserve_url"};
        for (String key : keys) if (prefs.contains(key)) app.put(key, prefs.getAll().get(key));
        root.put("app", app);
        org.json.JSONObject jackett = new org.json.JSONObject();
        for (java.util.Map.Entry<String, ?> entry :
                getSharedPreferences(JACKETT_PREFS, MODE_PRIVATE).getAll().entrySet())
            jackett.put(entry.getKey(), entry.getValue());
        root.put("jackett", jackett);
        Socks5ProxyBridge.Settings proxy = socks5Bridge.getSettings();
        org.json.JSONObject socks = new org.json.JSONObject();
        socks.put("enabled", proxy.enabled);
        socks.put("host", proxy.host);
        socks.put("port", proxy.port);
        socks.put("username", proxy.username);
        root.put("socks5", socks);
        return root;
    }

    private void exportUserBackup() {
        try {
            String json = buildUserBackup().toString(2);
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "kinozal-client-backup.json");
            pendingBackupJson = json;
            startActivityForResult(intent, REQUEST_BACKUP_EXPORT);
        } catch (Exception e) { showMessage("Не удалось подготовить копию: " + safe(e.getMessage())); }
    }

    private String pendingBackupJson;

    private void importUserBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_BACKUP_IMPORT);
    }

    private void writeBackupToUri(Uri uri) {
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new java.io.IOException("Не удалось открыть файл");
            out.write((pendingBackupJson == null ? "" : pendingBackupJson).getBytes(StandardCharsets.UTF_8));
            out.flush();
            pendingBackupJson = null;
            showMessage("Резервная копия сохранена");
        } catch (Exception e) { showMessage("Ошибка сохранения копии: " + safe(e.getMessage())); }
    }

    private void restoreBackupFromUri(Uri uri) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            if (in == null) throw new java.io.IOException("Не удалось открыть файл");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            org.json.JSONObject root = new org.json.JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            if (!"kinozal-client-backup".equals(root.optString("format"))
                    || root.optInt("version") != 1) throw new IllegalArgumentException("Неподдерживаемый формат копии");
            org.json.JSONObject app = root.optJSONObject("app");
            if (app != null) {
                android.content.SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
                String[] keys = {FAVORITES_KEY, RECENTLY_VIEWED_KEY, SEARCH_HISTORY_KEY,
                        "mirror", "startup_source", "torrserve_url"};
                for (String key : keys) if (app.has(key)) {
                    Object value = app.get(key);
                    if (value instanceof String) editor.putString(key, (String)value);
                }
                editor.commit();
                mirror = getSharedPreferences(PREFS, MODE_PRIVATE).getString("mirror", MIRRORS[0]);
                client.setMirror(mirror);
            }
            org.json.JSONObject jackett = root.optJSONObject("jackett");
            if (jackett != null) {
                android.content.SharedPreferences.Editor editor = getSharedPreferences(JACKETT_PREFS, MODE_PRIVATE).edit();
                java.util.Iterator<String> keys = jackett.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = jackett.get(key);
                    if (value instanceof String) editor.putString(key, (String)value);
                }
                editor.apply();
            }
            org.json.JSONObject socks = root.optJSONObject("socks5");
            if (socks != null) {
                // The SOCKS password is intentionally not exported. Restore the
                // endpoint disabled; the user re-enters the password before enabling.
                String host = socks.optString("host", "");
                int port = socks.optInt("port", 1080);
                String user = socks.optString("username", "");
                socks5Bridge.saveSettings(false, host, port, user, "");
                showMessage("Данные восстановлены. Введите пароль SOCKS5 и включите прокси в настройках.");
            } else showMessage("Резервная копия восстановлена");
        } catch (Exception e) { showMessage("Не удалось восстановить копию: " + safe(e.getMessage())); }
    }

    private void showDiagnostics() {
        long used = diskCache.getSizeBytes();
        long limit = diskCache.getMaxBytes();
        double usedMb = used / (1024d * 1024d);
        double limitMb = limit / (1024d * 1024d);
        double percent = limit > 0 ? used * 100d / limit : 0d;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(14), dp(20), dp(12));
        panel.setBackgroundColor(BG);

        TextView networkInfo = text("Внешний IP и страна: проверка…", 14, GOLD, false);
        networkInfo.setLineSpacing(dp(4), 1f);
        panel.addView(networkInfo);
        TextView cacheInfo = text(
                "Дисковый кэш Kinozal\\n"
                + String.format(java.util.Locale.ROOT, "Занято: %.2f МБ из %.0f МБ (%.1f%%)\\n",
                        usedMb, limitMb, percent)
                + String.format(java.util.Locale.ROOT, "Файлов в кэше: %d\\n", diskCache.getEntryCount())
                + "Расположение: cache/kinozal_cache\\n"
                + homeCacheDiagnostic,
                15, Color.WHITE, false);
        cacheInfo.setLineSpacing(dp(5), 1f);
        LinearLayout.LayoutParams cacheParams = new LinearLayout.LayoutParams(-1, -2);
        cacheParams.topMargin = dp(12);
        panel.addView(cacheInfo, cacheParams);

        Button refresh = button("Обновить данные");
        refresh.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(-1, dp(44));
        refreshParams.topMargin = dp(14);
        panel.addView(refresh, refreshParams);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Диагностика")
                .setView(panel)
                .setPositiveButton("Закрыть", null)
                .create();
        refresh.setOnClickListener(v -> {
            long current = diskCache.getSizeBytes();
            cacheInfo.setText("Дисковый кэш Kinozal\\n"
                    + String.format(java.util.Locale.ROOT, "Занято: %.2f МБ из %.0f МБ (%.1f%%)\\n",
                            current / (1024d * 1024d), limitMb, limit > 0 ? current * 100d / limit : 0d)
                    + String.format(java.util.Locale.ROOT, "Файлов в кэше: %d\\n", diskCache.getEntryCount())
                    + "Расположение: cache/kinozal_cache\\n"
                    + homeCacheDiagnostic);
            loadExternalCountryInfo(networkInfo);
        });;
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
            dialog.getWindow().setLayout(Math.min(dp(520),
                    getResources().getDisplayMetrics().widthPixels - dp(48)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(GOLD);
        loadExternalCountryInfo(networkInfo);
        refresh.requestFocus();
    }

    private void loadExternalCountryInfo(TextView target) {
        target.setText("Страна подключения: определяется…");
        io.execute(() -> {
            HttpURLConnection connection = null;
            try {
                Socks5ProxyBridge.Settings cfg = socks5Bridge.getSettings();
                Proxy route;
                if (cfg.enabled) {
                    if (!socks5Bridge.isRunning()) throw new IllegalStateException("SOCKS5 включён, но туннель не запущен");
                    route = socks5Bridge.localHttpProxy();
                } else route = Proxy.NO_PROXY;
                connection = (HttpURLConnection) new URL("https://ipwho.is/").openConnection(route);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "KinozalClient/0.5.13");
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
                StringBuilder body = new StringBuilder();
                try (java.io.InputStream in = connection.getInputStream();
                     java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    body.append(new String(out.toByteArray(), StandardCharsets.UTF_8));
                }
                org.json.JSONObject json = new org.json.JSONObject(body.toString());
                if (!json.optBoolean("success", false)) throw new java.io.IOException(json.optString("message", "Не удалось определить страну"));
                String country = json.optString("country", "не определена");
                runOnUiThread(() -> target.setText("Страна подключения: " + country));
            } catch (Exception e) {
                String message = "Страна подключения: не определена";
                runOnUiThread(() -> target.setText(message));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
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
        TextView mainRelease = text(query == null ? "" : query.trim(), 16, Color.WHITE, true);
        mainRelease.setMaxLines(2);
        mainRelease.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mainRelease.setPadding(dp(4), dp(2), dp(4), dp(5));
        panel.addView(mainRelease);
        TextView status = text("Поиск по: " + searchTitle + "\nПодключение к Jackett…", 13, TEAL, false);
        status.setPadding(dp(4), 0, dp(4), dp(8));
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
                            + "\nПроверьте доступность сервера и адрес API.");
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
        if (value.isEmpty()) return "";

        java.util.regex.Matcher yearMatcher =
                java.util.regex.Pattern.compile("(?<!\\d)(?:19|20)\\d{2}(?!\\d)").matcher(value);
        String year = yearMatcher.find() ? yearMatcher.group() : "";

        // Kinozal titles often use slash-separated fields:
        // Russian title / original title / year / audio / source / codec.
        // Only the first two title fields are candidates. Never turn release
        // metadata (WEB-DLRip, HEVC, 1080p, etc.) into the search query.
        String[] parts = value.split("/");
        String primaryTitle = parts.length > 0 ? parts[0].trim() : value;
        String title = primaryTitle;

        for (int i = 1; i < Math.min(parts.length, 3); i++) {
            String candidate = parts[i].trim().replaceAll("\\s+", " ");
            if (candidate.isEmpty()) continue;
            if (!candidate.matches("(?s).*[A-Za-zА-Яа-яЁё]{2,}.*")) continue;
            if (isJackettReleaseMetadata(candidate)) continue;
            title = candidate;
            break;
        }

        if (title.isEmpty()) title = primaryTitle.isEmpty() ? value : primaryTitle;
        if (!year.isEmpty() && !title.matches("(?s).*\\b" + year + "\\b.*")) {
            title = title + " " + year;
        }
        return title.replaceAll("\\s+", " ").trim();
    }

    private boolean isJackettReleaseMetadata(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
        if (normalized.isEmpty()) return true;
        return normalized.matches(".*\\b(hevc|h264|h265|x264|x265|av1|avc|"
                + "web dl|webdl|web rip|webrip|web d lrip|webdlrip|"
                + "bdrip|brrip|bd rip|hdrip|dvdrip|remux|"
                + "1080p|2160p|720p|480p|4k|8k|"
                + "hdr|sdr|imax|proper|repack|"
                + "aac|ac3|dts|ddp|truehd|atmos|"
                + "dub|sub|rus|eng|multi|"
                + "webrip|hdtv|pdtv|dsr|"
                + "ts|telesync|cam|telecine)\\b.*")
                || normalized.matches("^(ru|rus|eng|ukr|multi|dub|sub|db|ct)$");
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

        // Browse/category pages change frequently. Always request the current
        // page from Kinozal; only Home feeds, posters and torrent details use
        // their dedicated caches.
        showPageLoading("Загрузка каталога…");
        if (sessionWebView != null) {
            fetchBrowserPage(path, postBody, html -> {
                if (html != null && !html.isEmpty()) {
                    lastKinozalConnectionStatus = "установлено";
                    updateSidebarConnectionInfo();
                    showLoadedPage(html);
                } else {
                    lastKinozalConnectionStatus = "нет соединения";
                    if (sidebarConnectionInfo != null) sidebarConnectionInfo.setText("Подключение к Kinozal: нет соединения");
                    progress.setVisibility(View.GONE);
                    status.setText("Нет соединения");
                    addEmpty("Не удалось загрузить каталог через браузерную сессию.");
                }
            });
        } else {
            io.execute(() -> {
                try {
                    String html = postBody == null ? client.fetch(path) : client.fetchPost(path, postBody);
                    runOnUiThread(() -> {
                        lastKinozalConnectionStatus = "установлено";
                        updateSidebarConnectionInfo();
                        showLoadedPage(html);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        lastKinozalConnectionStatus = "нет соединения";
                        if (sidebarConnectionInfo != null) sidebarConnectionInfo.setText("Подключение к Kinozal: нет соединения");
                        progress.setVisibility(View.GONE); status.setText("Нет соединения");
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
        final boolean noCookies = remaining[0] == 0;
        final boolean[] started = {false};
        android.webkit.ValueCallback<Boolean> callback = accepted -> {
            remaining[0]--;
            if (remaining[0] <= 0 && !started[0]) {
                started[0] = true;
                manager.flush();
                markStartupCatalogReady();
            }
        };
        if (!noCookies) {
            for (String pair : pairs) {
                String item = pair.trim();
                if (!item.contains("=")) continue;
                manager.setCookie(client.getMirror(), item + "; Path=/; Secure; SameSite=Lax", callback);
            }
        }
        web.setWebViewClient(new WebViewClient() {
            private boolean isMirror(Uri uri) {
                return uri != null && uri.getHost() != null
                        && uri.getHost().equalsIgnoreCase(Uri.parse(client.getMirror()).getHost());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request.isForMainFrame() && !isMirror(request.getUrl());
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                    android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame() && pendingBrowserCallback != null) {
                    lastBrowserError = "Ошибка WebView: " + error.getErrorCode() + " "
                            + error.getDescription() + "; URL: " + request.getUrl();
                    BrowserPageCallback cb = pendingBrowserCallback;
                    pendingBrowserCallback = null;
                    cb.onPage(null, lastBrowserError);
                }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request,
                    android.webkit.WebResourceResponse response) {
                super.onReceivedHttpError(view, request, response);
                if (request.isForMainFrame() && pendingBrowserCallback != null) {
                    lastBrowserError = "HTTP " + response.getStatusCode() + " "
                            + response.getReasonPhrase() + "; URL: " + request.getUrl();
                    BrowserPageCallback cb = pendingBrowserCallback;
                    pendingBrowserCallback = null;
                    cb.onPage(null, lastBrowserError);
                }
            }
            @Override public void onReceivedSslError(WebView view,
                    android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
                handler.cancel();
                if (pendingBrowserCallback != null) {
                    lastBrowserError = "Ошибка SSL: " + error.toString();
                    BrowserPageCallback cb = pendingBrowserCallback;
                    pendingBrowserCallback = null;
                    cb.onPage(null, lastBrowserError);
                }
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
                            lastBrowserError = "WebView завершил загрузку, но DOM пустой. URL: " + url;
                            cb.onPage(null, lastBrowserError);
                            return;
                        }
                        Document loaded = Jsoup.parse(html);
                        boolean loginForm = !loaded.select("form:has(input[type=password])").isEmpty();
                        String pageUrl = view.getUrl() == null ? "" : view.getUrl().toLowerCase(java.util.Locale.ROOT);
                        if (loginForm || pageUrl.contains("login.php") || pageUrl.contains("takelogin.php")) {
                            lastBrowserError = "Сайт перенаправил на авторизацию. URL: " + view.getUrl();
                            cb.onPage(null, lastBrowserError);
                            return;
                        }
                        lastBrowserError = "Первичная страница загружена: " + url + ", HTML=" + html.length() + " символов";
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
        if (noCookies) markStartupCatalogReady();
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
            row.setFocusable(true);
            final Runnable[] prefetchTask = {null};
            row.setOnFocusChangeListener((v, f) -> {
                v.setBackground(shape(f ? Color.rgb(57, 68, 70) : PANEL, f ? GOLD : 0));
                if (prefetchTask[0] != null) browserHandler.removeCallbacks(prefetchTask[0]);
                if (f) {
                    prefetchTask[0] = () -> prefetchTorrentDetails(t);
                    browserHandler.postDelayed(prefetchTask[0], 320);
                }
            });
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
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_BACKUP_EXPORT) {
            writeBackupToUri(data.getData());
            return;
        }
        if (requestCode == REQUEST_BACKUP_IMPORT) {
            new AlertDialog.Builder(this).setTitle("Восстановить резервную копию?")
                    .setMessage("Избранное, история и сохранённые настройки будут заменены данными из файла. Текущие данные рекомендуется предварительно сохранить.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Восстановить", (dialog, which) -> restoreBackupFromUri(data.getData()))
                    .show();
            return;
        }
        if (requestCode != REQUEST_VOICE_SEARCH) return;
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
        panel.setPadding(dp(8), dp(4), dp(8), dp(4));

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
        web.setMinimumHeight(dp(300));
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

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

        String[] saved = client.getSavedCredentials();
        EditText loginField = new EditText(this);
        EditText passwordField = new EditText(this);
        styleCompactCredentialField(loginField, "Логин");
        styleCompactCredentialField(passwordField, "Пароль");
        passwordField.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (saved != null) {
            loginField.setText(saved[0]);
            passwordField.setText(saved[1]);
        }

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
                    boolean isUserGesture, android.os.Message resultMsg) { return false; }
        });
        web.setWebViewClient(new WebViewClient() {
            private boolean allowed(Uri uri) {
                String host = uri == null ? null : uri.getHost();
                return host != null && host.equalsIgnoreCase(allowedHost);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (allowed(Uri.parse(url))) return false;
                showMessage("Переход на посторонний сайт заблокирован.");
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                if (allowed(request.getUrl())) return false;
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
                view.evaluateJavascript("(function(){"
                        + "if(!window.__kinozalInputHook){window.__kinozalInputHook=true;"
                        + "document.addEventListener('focusin',function(ev){var e=ev.target;if(!e)return;"
                        + "var tag=(e.tagName||'').toLowerCase(),type=(e.type||'text').toLowerCase();"
                        + "if((tag==='input'&&['text','password','email','search','tel','url','number'].indexOf(type)>=0)"
                        + "||tag==='textarea'||e.isContentEditable){window.__kinozalTarget=e;"
                        + "if(window.AndroidTV&&window.AndroidTV.onTextFieldFocused)"
                        + "window.AndroidTV.onTextFieldFocused(type==='password'?'password':'text');}},true);"
                        + "document.addEventListener('submit',function(ev){var f=ev.target,p=f.querySelector('input[type=password]');"
                        + "if(!p||!window.AndroidTV)return;var u=[].slice.call(f.querySelectorAll('input')).find(function(e){"
                        + "var t=(e.type||'text').toLowerCase(),n=((e.name||'')+' '+(e.id||'')+' '+(e.placeholder||'')).toLowerCase();"
                        + "return e!==p&&!e.disabled&&t!=='search'&&['text','email'].indexOf(t)>=0&&(/login|user|name|nick|email/.test(n)||!n);});"
                        + "window.AndroidTV.onLoginSubmit(u?u.value:'',p.value);},true);},true);}"
                        + "var c=document.querySelector('input[type=checkbox]');if(c){c.focus();}"
                        + "var f=document.querySelector('iframe[title*=Widget],iframe[title*=challenge],#challenge-form iframe');"
                        + "if(f){f.setAttribute('tabindex','0');f.focus();}"
                        + "})();", null);
                String cookie = browserCookies.getCookie(base);
                if (cookie != null && !cookie.isEmpty()) {
                    try {
                        client.importBrowserCookies(cookie);
                        client.saveBrowserCookies(cookie);
                    } catch (Exception ignored) { }
                }
            }
        });

        // Username and password are visible together at the top for quick TV input.
        LinearLayout credentials = new LinearLayout(this);
        credentials.setGravity(Gravity.CENTER_VERTICAL);
        credentials.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        credentials.addView(loginField, fieldParams);
        LinearLayout.LayoutParams passParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        passParams.leftMargin = dp(6);
        credentials.addView(passwordField, passParams);

        Button insert = button("Вставить");
        Button signIn = button("Вход");
        Button done = button("Готово");
        LinearLayout.LayoutParams smallAction = new LinearLayout.LayoutParams(dp(112), dp(44));
        smallAction.leftMargin = dp(6);
        credentials.addView(insert, smallAction);
        LinearLayout.LayoutParams signParams = new LinearLayout.LayoutParams(dp(100), dp(44));
        signParams.leftMargin = dp(6);
        credentials.addView(signIn, signParams);
        LinearLayout.LayoutParams doneTopParams = new LinearLayout.LayoutParams(dp(112), dp(44));
        doneTopParams.leftMargin = dp(6);
        credentials.addView(done, doneTopParams);
        panel.addView(credentials, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(6), 0, dp(6));
        Button refresh = button("Обновить");
        Button close = button("Закрыть");
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        actions.addView(refresh, actionParams);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        closeParams.leftMargin = dp(6);
        actions.addView(close, closeParams);
        panel.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(panel)
                .create();

        Runnable detachWeb = () -> {
            web.stopLoading();
            if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
            web.setVisibility(View.INVISIBLE);
            if (web.getParent() == null) root.addView(web, new LinearLayout.LayoutParams(1, 1));
        };
        close.setOnClickListener(v -> { detachWeb.run(); dialog.dismiss(); });
        refresh.setOnClickListener(v -> {
            web.stopLoading();
            web.reload();
            status.setText("Обновление проверки Kinozal…");
        });
        insert.setOnClickListener(v -> {
            String username = loginField.getText().toString();
            String password = passwordField.getText().toString();
            if (username.trim().isEmpty() || password.isEmpty()) {
                showMessage("Заполните логин и пароль.");
                return;
            }
            String u = org.json.JSONObject.quote(username);
            String p = org.json.JSONObject.quote(password);
            web.evaluateJavascript("(function(){"
                    + "var p=document.querySelector('input[type=password]');if(!p)return 'NO_FIELDS';"
                    + "var f=p.closest('form');var scope=f||p.parentElement&&p.parentElement.parentElement||document;"
                    + "var all=[].slice.call(scope.querySelectorAll('input'));"
                    + "var u=all.find(function(e){var t=(e.type||'text').toLowerCase();"
                    + "var n=((e.name||'')+' '+(e.id||'')+' '+(e.placeholder||'')+' '+(e.getAttribute('autocomplete')||'')).toLowerCase();"
                    + "return e!==p&&!e.disabled&&t!=='search'&&['text','email'].indexOf(t)>=0&&(/login|user|name|nick|email/.test(n)||!n);});"
                    + "if(!u)return 'NO_FIELDS';"
                    + "function put(e,v){var setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;"
                    + "setter.call(e,v);e.dispatchEvent(new Event('input',{bubbles:true}));"
                    + "e.dispatchEvent(new Event('change',{bubbles:true}));}"
                    + "put(u," + u + ");put(p," + p + ");return 'OK';})()", result -> {
                if (result == null || result.contains("NO_FIELDS")) showMessage("Не найдены поля логина и пароля на странице.");
                else {
                    submittedLogin[0] = username;
                    submittedPassword[0] = password;
                    try { client.saveCredentials(username, password); }
                    catch (Exception e) { showMessage("Не удалось сохранить данные входа: " + safe(e.getMessage())); }
                }
            });
        });
        signIn.setOnClickListener(v -> {
            String username = loginField.getText().toString();
            String password = passwordField.getText().toString();
            if (username.trim().isEmpty() || password.isEmpty()) {
                showMessage("Заполните логин и пароль.");
                return;
            }
            String u = org.json.JSONObject.quote(username);
            String p = org.json.JSONObject.quote(password);
            web.evaluateJavascript("(function(){var p=document.querySelector('input[type=password]');"
                    + "if(!p)return 'NO_FIELDS';var f=p.closest('form');"
                    + "var scope=f||p.parentElement&&p.parentElement.parentElement||document;"
                    + "var all=[].slice.call(scope.querySelectorAll('input'));"
                    + "var u=all.find(function(e){var t=(e.type||'text').toLowerCase();"
                    + "var n=((e.name||'')+' '+(e.id||'')+' '+(e.placeholder||'')+' '+(e.getAttribute('autocomplete')||'')).toLowerCase();"
                    + "return e!==p&&!e.disabled&&t!=='search'&&['text','email'].indexOf(t)>=0&&(/login|user|name|nick|email/.test(n)||!n);});"
                    + "if(!u)return 'NO_FIELDS';"
                    + "function put(e,v){var setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;"
                    + "setter.call(e,v);e.dispatchEvent(new Event('input',{bubbles:true}));"
                    + "e.dispatchEvent(new Event('change',{bubbles:true}));}"
                    + "put(u," + u + ");put(p," + p + ");"
                    + "if(f){if(f.requestSubmit)f.requestSubmit();else f.submit();return 'SUBMITTED';}"
                    + "var submit=scope.querySelector('input[type=submit],button[type=submit],input[value*=Войти],button');"
                    + "if(submit){submit.click();return 'CLICKED';}return 'NO_SUBMIT';})()", result -> {
                if (result == null || result.contains("NO_FIELDS") || result.contains("NO_SUBMIT")) {
                    showMessage("Не удалось определить поля или кнопку входа на странице.");
                    return;
                }
                try { client.saveCredentials(username, password); }
                catch (Exception e) { showMessage("Не удалось сохранить данные входа: " + safe(e.getMessage())); }
            });
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
            detachWeb.run();
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
                (int)(getResources().getDisplayMetrics().widthPixels * 0.90f),
                (int)(getResources().getDisplayMetrics().heightPixels * 0.90f));
        web.post(() -> {
            web.requestLayout();
            web.requestFocus();
            web.loadUrl(base + "/login.php?m=5");
        });
    }

    private void styleCompactCredentialField(EditText field, String hint) {
        field.setSingleLine(true);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(TEAL);
        field.setHint(hint);
        field.setTextSize(14);
        field.setSelectAllOnFocus(true);
        field.setFocusable(true);
        field.setFocusableInTouchMode(true);
        field.setPadding(dp(10), dp(5), dp(10), dp(5));
        field.setBackground(shape(PANEL, TEAL));
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

    // Warm the next likely details page while the user navigates the list.
    // The click handler reuses this in-flight request instead of starting a
    // second WebView navigation and cancelling the first one.
    private void prefetchTorrentDetails(Torrent torrent) {
        if (torrent == null || torrent.href == null || torrent.href.trim().isEmpty()
                || sessionWebView == null || !pendingDetailPrefetchHref.isEmpty()
                || pendingBrowserCallback != null) return;
        final String href = torrent.href;
        final String key = "details|" + client.getMirror() + "|" + href;
        synchronized (pageCache) {
            if (pageCache.containsKey(key)) return;
        }
        if (diskCache.get(key, 30L * 60L * 1000L) != null) return;
        pendingDetailPrefetchHref = href;
        fetchBrowserPage(href, null, html -> {
            if (href.equals(pendingDetailPrefetchHref)) pendingDetailPrefetchHref = "";
            if (html == null || html.trim().isEmpty()) return;
            try {
                Document doc = Jsoup.parse(html, href);
                TorrentDetails parsed = parseTorrentDetails(doc, torrent);
                boolean valid = !parsed.title.trim().isEmpty()
                        && (doc.selectFirst("#main .content") != null
                        || doc.selectFirst("a[href*=download.php]") != null);
                if (!valid) return;
                synchronized (pageCache) { pageCache.put(key, html); }
                diskCache.put(key, html.getBytes(StandardCharsets.UTF_8));
                if (href.equals(activeTorrentDetailsHref)) {
                    progress.setVisibility(View.GONE);
                    saveRecentlyViewed(torrent);
                    showTorrentDetails(torrent, parsed);
                }
            } catch (Exception ignored) { }
        });
    }

    private void showTorrent(Torrent t) {
        focusedTorrentSource = getCurrentFocus();
        if (t == null || t.href == null || t.href.trim().isEmpty()) {
            showMessage("Не удалось определить адрес раздачи.");
            return;
        }

        final String href = t.href;
        final String cacheKey = "details|" + client.getMirror() + "|" + href;
        activeTorrentDetailsHref = href;

        // Render a previously validated detail page immediately. The network
        // refresh below updates ratings, peer counts and other changing fields.
        String cachedHtml = null;
        synchronized (pageCache) { cachedHtml = pageCache.get(cacheKey); }
        if (cachedHtml == null) {
            byte[] saved = diskCache.get(cacheKey, 30L * 60L * 1000L);
            if (saved != null) {
                cachedHtml = new String(saved, StandardCharsets.UTF_8);
                synchronized (pageCache) { pageCache.put(cacheKey, cachedHtml); }
            }
        }

        if (cachedHtml != null) {
            try {
                Document cachedDoc = Jsoup.parse(cachedHtml, href);
                TorrentDetails cachedDetails = parseTorrentDetails(cachedDoc, t);
                saveRecentlyViewed(t);
                showTorrentDetails(t, cachedDetails);
            } catch (Exception ignored) {
                cachedHtml = null;
            }
        }

        final boolean hasCachedDetails = cachedHtml != null;
        if (!hasCachedDetails) {
            progress.setVisibility(View.VISIBLE);
            // A focused-row prefetch is already loading this exact page.
            // Wait for it rather than cancelling it with a duplicate request.
            if (href.equals(pendingDetailPrefetchHref)) return;
        }

        java.util.function.Consumer<String> onFreshHtml = html -> runOnUiThread(() -> {
            if (html == null || html.trim().isEmpty()) {
                if (!hasCachedDetails && href.equals(activeTorrentDetailsHref)) {
                    progress.setVisibility(View.GONE);
                    showMessage("Не удалось загрузить карточку через браузерную сессию.");
                }
                return;
            }
            try {
                Document doc = Jsoup.parse(html, href);
                TorrentDetails details = parseTorrentDetails(doc, t);
                // Do not let a challenge/login/empty document replace a good entry.
                boolean validDetails = !details.title.trim().isEmpty()
                        && (doc.selectFirst("#main .content") != null
                        || doc.selectFirst("a[href*=download.php]") != null);
                if (!validDetails) {
                    if (!hasCachedDetails && href.equals(activeTorrentDetailsHref)) {
                        progress.setVisibility(View.GONE);
                        showMessage("Сайт вернул неполную страницу раздачи.");
                    }
                    return;
                }
                synchronized (pageCache) { pageCache.put(cacheKey, html); }
                diskCache.put(cacheKey, html.getBytes(StandardCharsets.UTF_8));
                saveRecentlyViewed(t);
                if (href.equals(activeTorrentDetailsHref)) {
                    progress.setVisibility(View.GONE);
                    showTorrentDetails(t, details);
                }
            } catch (Exception e) {
                if (!hasCachedDetails && href.equals(activeTorrentDetailsHref)) {
                    progress.setVisibility(View.GONE);
                    showMessage("Не удалось разобрать карточку: " + safe(e.getMessage()));
                }
            }
        });

        if (sessionWebView != null) {
            // A single authenticated WebView request avoids the known HTTP 403
            // attempt followed by a second browser navigation.
            fetchBrowserPage(href, null, onFreshHtml);
        } else {
            io.execute(() -> {
                try {
                    onFreshHtml.accept(client.fetch(href));
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        if (!hasCachedDetails && href.equals(activeTorrentDetailsHref)) {
                            progress.setVisibility(View.GONE);
                            showMessage("Не удалось загрузить карточку: " + safe(e.getMessage()));
                        }
                    });
                }
            });
        }
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
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);

        Button watch = button("Просмотр");
        watch.setTextSize(15);
        watch.setTextColor(Color.WHITE);
        watch.setBackground(shape(RED, 0));
        watch.setOnFocusChangeListener((v, focused) ->
                v.setBackground(shape(focused ? Color.rgb(125, 48, 25) : RED, focused ? GOLD : 0)));
        LinearLayout.LayoutParams watchParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        watchParams.rightMargin = dp(8);
        actionRow.addView(watch, watchParams);

        Button favorite = button(isFavorite(torrent) ? "★" : "☆");
        favorite.setTextSize(25);
        favorite.setTextColor(GOLD);
        favorite.setGravity(Gravity.CENTER);
        favorite.setContentDescription(isFavorite(torrent) ? "Убрать из избранного" : "Добавить в избранное");
        favorite.setBackground(shape(PANEL, 0));
        favorite.setOnFocusChangeListener((v, focused) ->
                v.setBackground(shape(focused ? Color.rgb(65, 72, 73) : PANEL, focused ? GOLD : 0)));
        favorite.setOnClickListener(v -> {
            toggleFavorite(torrent);
            boolean saved = isFavorite(torrent);
            favorite.setText(saved ? "★" : "☆");
            favorite.setContentDescription(saved ? "Убрать из избранного" : "Добавить в избранное");
        });
        actionRow.addView(favorite, new LinearLayout.LayoutParams(dp(48), dp(48)));
        posterColumn.addView(actionRow);

        Button downloadTorrent = button("↓  Скачать торрент");
        downloadTorrent.setTextSize(14);
        downloadTorrent.setTextColor(GOLD);
        downloadTorrent.setBackground(shape(PANEL, 0));
        downloadTorrent.setOnFocusChangeListener((v, focused) ->
                v.setBackground(shape(focused ? Color.rgb(65, 72, 73) : PANEL, focused ? GOLD : 0)));
        LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(-1, dp(42));
        downloadParams.topMargin = dp(8);
        downloadParams.bottomMargin = dp(10);
        posterColumn.addView(downloadTorrent, downloadParams);

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
        downloadTorrent.setOnClickListener(v -> downloadTorrentFile(data));
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
        if (data.poster != null && !data.poster.isEmpty()) loadImageIntoView(data.poster, poster);
        watch.requestFocus();
        prefetchInfoHash(data);
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

    private String getReleaseId(TorrentDetails data) {
        String page = data.sourcePage == null ? "" : data.sourcePage.trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:[?&]id=)([0-9]+)").matcher(page);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String parseInfoHash(String response) {
        if (response == null || response.isEmpty()) return null;
        java.util.regex.Matcher itemMatcher = java.util.regex.Pattern
                .compile("(?is)<li>[^<]*?:\\s*([0-9a-f]{40})\\s*</li>").matcher(response);
        if (itemMatcher.find()) return itemMatcher.group(1).toLowerCase(java.util.Locale.ROOT);
        java.util.regex.Matcher hashMatcher = java.util.regex.Pattern
                .compile("(?i)\\b[0-9a-f]{40}\\b").matcher(response);
        return hashMatcher.find() ? hashMatcher.group().toLowerCase(java.util.Locale.ROOT) : null;
    }

    private void prefetchInfoHash(TorrentDetails data) {
        requestInfoHash(data, null);
    }

    private void requestInfoHash(TorrentDetails data,
            java.util.function.Consumer<String> callback) {
        if (sessionWebView == null) {
            if (callback != null) callback.accept(null);
            return;
        }
        String releaseId = getReleaseId(data);
        if (releaseId == null) {
            if (callback != null) callback.accept(null);
            return;
        }

        String cached;
        synchronized (infoHashCache) { cached = infoHashCache.get(releaseId); }
        if (cached != null) {
            if (callback != null) callback.accept(cached);
            return;
        }

        if (releaseId.equals(pendingInfoHashId)) {
            if (callback != null) pendingInfoHashCallbacks.add(callback);
            return;
        }

        // Only one top-level WebView navigation can be active. If the user
        // switches to another release, finish the old waiters gracefully.
        if (pendingInfoHashId != null) {
            List<java.util.function.Consumer<String>> old = new ArrayList<>(pendingInfoHashCallbacks);
            pendingInfoHashCallbacks.clear();
            pendingInfoHashId = null;
            for (java.util.function.Consumer<String> waiter : old) waiter.accept(null);
        }
        pendingInfoHashId = releaseId;
        pendingInfoHashCallbacks.clear();
        if (callback != null) pendingInfoHashCallbacks.add(callback);

        String endpoint = Uri.parse(data.sourcePage).buildUpon()
                .path("/get_srv_details.php")
                .clearQuery()
                .appendQueryParameter("id", releaseId)
                .appendQueryParameter("action", "2")
                .build().toString();

        fetchBrowserPage(endpoint, null, response -> {
            if (!releaseId.equals(pendingInfoHashId)) return;
            String hash = parseInfoHash(response);
            if (hash != null) {
                synchronized (infoHashCache) { infoHashCache.put(releaseId, hash); }
            }
            List<java.util.function.Consumer<String>> waiters =
                    new ArrayList<>(pendingInfoHashCallbacks);
            pendingInfoHashCallbacks.clear();
            pendingInfoHashId = null;
            for (java.util.function.Consumer<String> waiter : waiters) waiter.accept(hash);
        });
    }

    private void playTorrent(TorrentDetails data) {
        requestInfoHash(data, hash -> {
            if (hash == null) {
                showMessage("Не удалось получить ссылку для воспроизведения. Проверьте авторизацию Kinozal и повторите.");
                return;
            }

            String magnet = "magnet:?xt=urn:btih:" + hash
                    + "&dn=" + Uri.encode(data.title == null ? "" : data.title);
            String remote = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("torrserve_url", "").trim().replaceAll("/+$", "");
            String remoteHost = remote.isEmpty() ? "" : Uri.parse(remote).getHost();
            boolean localTorrServer = "127.0.0.1".equals(remoteHost)
                    || "localhost".equalsIgnoreCase(remoteHost)
                    || "::1".equals(remoteHost);
            // On the same Android device, hand the magnet to the installed TorrServe app.
            // The REST /torrents add endpoint only queues a torrent and does not start playback.
            if (!remote.isEmpty() && !localTorrServer) {
                addMagnetToExternalTorrServer(remote, magnet, data);
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(magnet));
            intent.setPackage("ru.yourok.torrserve");
            intent.putExtra("poster", data.poster == null ? "" : data.poster);
            intent.putExtra("title", data.title == null ? "" : data.title);
            try {
                startActivity(intent);
            } catch (Exception e) {
                showMessage("Не удалось открыть TorrServe. Проверьте, что он установлен.");
            }
        });
    }


    private void addMagnetToExternalTorrServer(String baseUrl, String magnet, TorrentDetails data) {
        // Match TorrServe MatriX "Играть все": add the magnet through the server API,
        // wait until metadata is ready, then open the server-generated M3U playlist.
        // Do not select a single file or build a /stream URL on the client.
        io.execute(() -> {
            try {
                org.json.JSONObject addRequest = new org.json.JSONObject();
                addRequest.put("action", "add");
                addRequest.put("link", magnet);
                addRequest.put("title", data.title == null ? "" : data.title);
                addRequest.put("poster", data.poster == null ? "" : data.poster);
                addRequest.put("category", "");
                addRequest.put("data", "");
                addRequest.put("save_to_db", true);

                org.json.JSONObject torrent = postTorrServerJson(baseUrl, addRequest);
                String hash = torrent.optString("hash", "");
                if (hash.isEmpty()) {
                    throw new java.io.IOException("Удалённый TorrServer не вернул hash добавленной раздачи.");
                }

                org.json.JSONArray files = torrent.optJSONArray("file_stats");
                for (int attempt = 0; (files == null || files.length() == 0) && attempt < 60; attempt++) {
                    Thread.sleep(1000);
                    org.json.JSONObject getRequest = new org.json.JSONObject();
                    getRequest.put("action", "get");
                    getRequest.put("hash", hash);
                    torrent = postTorrServerJson(baseUrl, getRequest);
                    files = torrent.optJSONArray("file_stats");
                }
                if (files == null || files.length() == 0) {
                    throw new java.io.IOException("Удалённый TorrServer не подготовил список файлов за 60 секунд.");
                }

                String torrentName = torrent.optString("name", "");
                if (torrentName.trim().isEmpty()) {
                    torrentName = data.title == null || data.title.trim().isEmpty()
                            ? hash : data.title;
                }

                // This is the same endpoint used by TorrServe MatriX's own
                // "Играть все" button: /playlist/{torrent-name}.m3u?hash=...
                Uri playlistUri = Uri.parse(baseUrl + "/playlist/" + Uri.encode(torrentName) + ".m3u")
                        .buildUpon()
                        .appendQueryParameter("hash", hash)
                        .build();
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(playlistUri, "video/*");
                intent.putExtra("title", data.title == null ? "" : data.title);
                intent.putExtra("poster", data.poster == null ? "" : data.poster);
                runOnUiThread(() -> {
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        showMessage("Не найден плеер, поддерживающий плейлисты TorrServer. Установите VLC или другой совместимый плеер.");
                    }
                });
            } catch (Exception e) {
                String error = safe(e.getMessage());
                runOnUiThread(() -> showMessage("Не удалось запустить «Играть все» на удалённом TorrServer: " + error));
            }
        });
    }

    private org.json.JSONObject postTorrServerJson(String baseUrl, org.json.JSONObject request) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + "/torrents").openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(45000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }

            int code = connection.getResponseCode();
            java.io.InputStream input = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder body = new StringBuilder();
            if (input != null) {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(input, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
            }
            if (code < 200 || code >= 300) {
                String detail = body.toString();
                throw new java.io.IOException("TorrServer HTTP " + code
                        + (detail.isEmpty() ? "" : ": " + detail.substring(0, Math.min(180, detail.length()))));
            }
            return new org.json.JSONObject(body.toString());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void startMagnetWebViewFetch(TorrentDetails data, WebView web, int retry) {
        final boolean[] completed = {false};
        final Runnable fallback = () -> {
            if (completed[0]) return;
            completed[0] = true;
            downloadTorrentNativeForPlayback(data);
        };
        final Runnable timeout = fallback;
        browserHandler.postDelayed(timeout, 20000);

        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onMagnetTorrentResult(int statusCode, String base64, String error) {
                runOnUiThread(() -> {
                    if (completed[0]) return;
                    if (statusCode >= 200 && statusCode < 300
                            && base64 != null && !base64.isEmpty()) {
                        try {
                            byte[] bytes = android.util.Base64.decode(
                                    base64, android.util.Base64.DEFAULT);
                            if (bytes.length >= 20 && bytes[0] == 'd') {
                                completed[0] = true;
                                browserHandler.removeCallbacks(timeout);
                                deliverMagnetFromTorrentBytes(data, bytes);
                                return;
                            }
                        } catch (Exception ignored) { }
                    }
                    browserHandler.removeCallbacks(timeout);
                    if (retry == 0) {
                        // Retry once after a transient first-request/session race.
                        startMagnetWebViewFetch(data, web, 1);
                    } else {
                        fallback.run();
                    }
                });
            }
        }, "KinozalMagnetBridge");

        String url = org.json.JSONObject.quote(data.torrentLink);
        String script = "(function(){try{fetch(" + url
                + ",{credentials:'include',redirect:'follow',cache:'no-store'})"
                + ".then(function(r){if(!r.ok){window.KinozalMagnetBridge.onMagnetTorrentResult(r.status,'','HTTP '+r.status);return;}"
                + "return r.blob().then(function(blob){var reader=new FileReader();"
                + "reader.onloadend=function(){var value=String(reader.result||'');"
                + "window.KinozalMagnetBridge.onMagnetTorrentResult(r.status,value.substring(value.indexOf(',')+1),'');};"
                + "reader.onerror=function(){window.KinozalMagnetBridge.onMagnetTorrentResult(0,'','FileReader error');};"
                + "reader.readAsDataURL(blob);});})"
                + ".catch(function(e){window.KinozalMagnetBridge.onMagnetTorrentResult(0,'',String(e));});"
                + "}catch(e){window.KinozalMagnetBridge.onMagnetTorrentResult(0,'',String(e));}})()";
        web.evaluateJavascript(script, null);
    }

    private void downloadTorrentFile(TorrentDetails data) {
        if (data.torrentLink == null || data.torrentLink.isEmpty()) {
            showMessage("Не удалось найти download.php на странице раздачи.");
            return;
        }
        if (data.torrentLink.startsWith("magnet:")) {
            showMessage("На странице пока доступна только magnet-ссылка. Обработку magnet продолжим отдельно.");
            return;
        }

        WebView web = sessionWebView;
        if (web == null) {
            downloadTorrentNative(data);
            return;
        }

        // The hidden WebView may still be on the home/catalogue page on the
        // first visit to a details card. Load the actual details page first:
        // this establishes the correct same-origin context and Referer before
        // requesting download.php. fetchBrowserPage waits for onPageFinished.
        String detailsPage = data.sourcePage;
        if (detailsPage == null || detailsPage.trim().isEmpty()) {
            detailsPage = data.torrentLink;
        }
        fetchBrowserPage(detailsPage, null, html -> {
            if (html == null || html.isEmpty()) {
                downloadTorrentNative(data);
                return;
            }
            startTorrentWebViewFetch(data, web, 0);
        });
    }

    private void startTorrentWebViewFetch(TorrentDetails data, WebView web, int retry) {
        final boolean[] completed = {false};
        final Runnable fallback = () -> {
            if (completed[0]) return;
            completed[0] = true;
            downloadTorrentNative(data);
        };
        final Runnable timeout = () -> fallback.run();
        browserHandler.postDelayed(timeout, 20000);

        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onTorrentDownloadResult(int statusCode, String base64, String error) {
                runOnUiThread(() -> {
                    if (completed[0]) return;
                    if (statusCode >= 200 && statusCode < 300 && base64 != null && !base64.isEmpty()) {
                        try {
                            byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                            if (bytes.length >= 20 && bytes[0] == 'd') {
                                completed[0] = true;
                                browserHandler.removeCallbacks(timeout);
                                deliverTorrentBytes(data, bytes);
                                return;
                            }
                        } catch (Exception ignored) { }
                    }
                    browserHandler.removeCallbacks(timeout);
                    if (retry == 0) {
                        // One fresh request handles transient first-load/session races.
                        startTorrentWebViewFetch(data, web, 1);
                    } else {
                        fallback.run();
                    }
                });
            }
        }, "KinozalDownloadBridge");

        String url = org.json.JSONObject.quote(data.torrentLink);
        String script = "(function(){try{"
                + "fetch(" + url + ",{credentials:'include',redirect:'follow',cache:'no-store'})"
                + ".then(function(r){if(!r.ok){window.KinozalDownloadBridge.onTorrentDownloadResult(r.status,'','HTTP '+r.status);return;}"
                + "return r.blob().then(function(blob){var reader=new FileReader();"
                + "reader.onloadend=function(){var value=String(reader.result||'');"
                + "window.KinozalDownloadBridge.onTorrentDownloadResult(r.status,value.substring(value.indexOf(',')+1),'');};"
                + "reader.onerror=function(){window.KinozalDownloadBridge.onTorrentDownloadResult(0,'','FileReader error');};"
                + "reader.readAsDataURL(blob);});})"
                + ".catch(function(e){window.KinozalDownloadBridge.onTorrentDownloadResult(0,'',String(e));});"
                + "}catch(e){window.KinozalDownloadBridge.onTorrentDownloadResult(0,'',String(e));}})()";
        web.evaluateJavascript(script, null);
    }

    private void downloadTorrentNativeForPlayback(TorrentDetails data) {
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
                deliverMagnetFromTorrentBytes(data, bytes);
            } catch (Exception e) {
                runOnUiThread(() -> showMessage("Не удалось получить magnet: " + safe(e.getMessage())));
            }
        });
    }

    private void deliverMagnetFromTorrentBytes(TorrentDetails data, byte[] bytes) throws Exception {
        String magnet = TorrentMagnetBuilder.build(bytes, data.title);
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(magnet));
        intent.setPackage("ru.yourok.torrserve");
        intent.putExtra("poster", data.poster == null ? "" : data.poster);
        intent.putExtra("title", data.title == null ? "" : data.title);
        runOnUiThread(() -> {
            try {
                startActivity(intent);
            } catch (Exception e) {
                showMessage("Не удалось открыть magnet в TorrServe: " + safe(e.getMessage()));
            }
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
    @Override protected void onDestroy() { if (socks5Bridge != null) socks5Bridge.stop(); io.shutdownNow(); imageIo.shutdownNow(); homeRefreshIo.shutdownNow(); super.onDestroy(); }
}
