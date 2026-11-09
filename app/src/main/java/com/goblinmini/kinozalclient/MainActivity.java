package com.goblinmini.kinozalclient;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.util.Log;
import android.view.Choreographer;
import android.widget.ImageView;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.animation.ObjectAnimator;
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
import android.widget.FrameLayout;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 19, 23);
    private static final int PANEL = Color.rgb(29, 34, 39);
    private int GOLD = Color.rgb(251, 226, 173);
    private static final int RED = Color.rgb(174, 55, 18);
    private static final int TEAL = Color.rgb(115, 164, 170);
    private static final java.util.regex.Pattern LOGIN_PASSWORD_TYPE =
            java.util.regex.Pattern.compile("\\btype\\s*=\\s*(['\\\"]?)password\\1");
    private static final String PREFS = "kinozal_settings";
    private static final String FONT_SIZE_OFFSET_KEY = "font_size_offset_sp";
    private int fontOffsetSp = 0;
    private static final String[] MIRRORS = {
            "https://kinozal.me", "https://kinozal.guru",
            "https://kinozal.jumpingcrab.com"
    };

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    // Image cache reads/downloads must not block catalogue parsing or other app I/O.
    private final ExecutorService imageIo = Executors.newFixedThreadPool(3);
    // Home refresh is isolated so nine feed requests cannot starve poster loading.
    private final ExecutorService homeRefreshIo = Executors.newFixedThreadPool(4);

    // WebView callbacks may arrive after Activity.onDestroy() has shut down
    // the executors. Ignore those late tasks instead of crashing the UI thread.
    private void executeSafely(ExecutorService executor, Runnable task) {
        if (executor == null || task == null || executor.isShutdown()
                || isFinishing() || isDestroyed()) return;
        try {
            executor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // The executor may be shut down between the state check and execute().
        }
    }
    private KinozalHttpClient client;
    private Socks5ProxyBridge socks5Bridge;
    private DiskCache diskCache;
    private LruCache<String, Bitmap> decodedImageCache;
    private String mirror;
    private String currentPath = "browse.php";
    private String currentTitle = "Все раздачи";
    private TextView sidebarConnectionInfo;
    private TextView sidebarCategoryInfo;
    private TextView sidebarCountryInfo;
    private TextView sidebarVersionInfo;
    private TextView sidebarLoginInfo;
    private int lastLoadedTorrentCount = 0;
    private String lastKinozalConnectionStatus = "не проверено";
    private String activeTorrentDetailsHref = "";
    private String pendingDetailPrefetchHref = "";
    private final java.util.Set<String> detailPrefetchScheduled = new java.util.HashSet<>();
    private LinearLayout root, sidebar, content, list, menuItems;
    private ScrollView homeScrollView;
    private ScrollView collectionsScrollView;
    private ScrollView browseScrollView;
    private LinearLayout browseList;
    private LinearLayout homeList, collectionsList;
    private ViewGroup feedScreenParent;
    private ScrollView activeFeedScrollView;
    private String activeFeedScreen = "home";
    private boolean homeLoadedOnce = false;
    private List<HomeSection> homeDiskCacheSnapshot = new ArrayList<>();
    private boolean collectionsLoadedOnce = false;
    private android.content.BroadcastReceiver feedCacheReceiver;
    private final Map<String, TextView> feedRefreshStatusViews = new HashMap<>();
    private final Map<String, ProgressBar> feedRefreshProgressViews = new HashMap<>();
    private final Map<String, LinearLayout> feedRefreshPanels = new HashMap<>();
    private final Map<String, Button> feedRefreshButtons = new HashMap<>();
    private final Map<String, ObjectAnimator> feedRefreshAnimations = new HashMap<>();
    private List<HomeSection> collectionsSectionsMemory = new ArrayList<>();
    private TextView heading, status;
    private ProgressBar progress;
    private EditText search;
    private View focusedTorrentSource;
    private final List<Torrent> torrents = new ArrayList<>();
    private int page = 0;
    private String searchSort = "date";
    private boolean searchSortDescending = true;
    private String localSort = "recent";
    private boolean localSortDescending = true;
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
    private String pendingTvTorrentHref = "";
    private String pendingTvTorrentTitle = "";
    private String pendingExternalSearchQuery = "";
    private boolean startupCatalogStarted = false;
    private boolean automaticUpdateCheckScheduled = false;
    private boolean homeCacheDisplayed = false;
    private boolean homeCacheLoadFinished = false;
    private boolean initialHomeFetchPending = false;
    private boolean homeRefreshInProgress = false;
    // Home/collection and Android TV feed refreshes share one WebView. Queue
    // interactive navigation until the background sequence releases it.
    private boolean browserFeedLoading = false;
    private Runnable pendingBrowserNavigation;
    private List<HomeSection> homeSectionsMemory = new ArrayList<>();
    private String homeCacheDiagnostic = "Кэш главной ещё не проверялся";
    // Temporary feed diagnostics: timings and response metadata only; never store cookies or page bodies.
    private final StringBuilder feedLoadDiagnostics = new StringBuilder();
    private final Object feedDiagnosticsLock = new Object();
    private long feedDiagnosticStartedAtMs = 0L;
    private String feedDiagnosticGroup = "";
    private void resetFeedDiagnostics(String group, String[] paths) {
        synchronized (feedDiagnosticsLock) {
            feedLoadDiagnostics.setLength(0);
            feedDiagnosticStartedAtMs = android.os.SystemClock.elapsedRealtime();
            feedDiagnosticGroup = group;
        }
        FeedDiagnostics.start(this, group, client == null ? "?" : client.getMirror(), paths, "WebView");
        appendFeedDiagnostic("START", "начата загрузка группы");
    }
    private void appendFeedDiagnostic(String stage, String details) {
        long elapsed = feedDiagnosticStartedAtMs == 0L ? 0L
                : android.os.SystemClock.elapsedRealtime() - feedDiagnosticStartedAtMs;
        String line = String.format(java.util.Locale.ROOT, "[+%d ms] %s %s%n", elapsed, stage,
                details == null ? "" : details);
        synchronized (feedDiagnosticsLock) {
            feedLoadDiagnostics.append(line);
            if (feedLoadDiagnostics.length() > 48000)
                feedLoadDiagnostics.delete(0, feedLoadDiagnostics.length() - 48000);
        }
        FeedDiagnostics.log(this, feedDiagnosticGroup, stage, details);
    }
    private String getFeedDiagnosticsText() {
        String persistent = FeedDiagnostics.read(this);
        if (!persistent.trim().isEmpty()) return persistent;
        synchronized (feedDiagnosticsLock) { return feedLoadDiagnostics.toString(); }
    }

    private int homeRenderGeneration = 0;
    // Lightweight startup diagnostics: frame gaps and expensive home-row renders.
    private boolean homePerfMonitoring = false;
    private long homePerfLastFrameNanos = 0L;
    private long homePerfStartedAtMs = 0L;
    private int homePerfSlowFrames = 0;
    private final Choreographer.FrameCallback homePerfFrameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!homePerfMonitoring) return;
            if (homePerfLastFrameNanos != 0L) {
                long frameGapMs = (frameTimeNanos - homePerfLastFrameNanos) / 1_000_000L;
                if (frameGapMs >= 32L) {
                    homePerfSlowFrames++;
                    Log.w("KinozalPerf", "slow frame gap=" + frameGapMs
                            + "ms, homeLoading=true, renderedSections=" + homeRenderGeneration);
                }
            }
            homePerfLastFrameNanos = frameTimeNanos;
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void startHomePerformanceMonitor() { }

    private void stopHomePerformanceMonitor() { }

    // Per-category timing diagnostics. These measurements do not cache pages or alter request behavior.
    private long browsePerfStartedAtMs = 0L;
    private String browsePerfPath = "";

    private void logBrowsePerf(String stage, long durationMs, String details) { }

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

    // Parsed detail models are kept separately from HTML so reopening a release
    // does not repeatedly parse a large document on the UI thread.
    private final java.util.LinkedHashMap<String, TorrentDetails> detailDataCache =
            new java.util.LinkedHashMap<String, TorrentDetails>(32, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, TorrentDetails> eldest) {
                    return size() > 32;
                }
            };
    private TorrentDetails getCachedDetailData(String key) {
        synchronized (detailDataCache) { return detailDataCache.get(key); }
    }
    private void putCachedDetailData(String key, TorrentDetails data) {
        if (key == null || data == null) return;
        synchronized (detailDataCache) { detailDataCache.put(key, data); }
    }

    private static class Category { String title, href, method = "GET", body = ""; }

    private static class Torrent {
        String title, href, size, seeds, leeches, uploaded, poster;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        GOLD = getAccentColor(getSharedPreferences(PREFS, MODE_PRIVATE).getString("accent_color", "gold"));
        fontOffsetSp = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(FONT_SIZE_OFFSET_KEY, 0);
        readTvLaunchIntent(getIntent());
        mirror = getSharedPreferences(PREFS, MODE_PRIVATE).getString("mirror", MIRRORS[0]);
        if ("https://kinozal.tv".equalsIgnoreCase(mirror)) {
            mirror = MIRRORS[0];
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("mirror", mirror).apply();
        }
        client = new KinozalHttpClient(this, mirror);
        diskCache = new DiskCache(this, 200L * 1024L * 1024L);
        int bitmapCacheBytes = (int) Math.min(16L * 1024L * 1024L,
                Math.max(2L * 1024L * 1024L, Runtime.getRuntime().maxMemory() / 8L));
        decodedImageCache = new LruCache<String, Bitmap>(bitmapCacheBytes) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value == null ? 0 : value.getAllocationByteCount();
            }
        };
        buildShell();
        // Read the last successful home snapshot off the UI thread. This runs
        // alongside proxy/session initialization so a returning user sees
        // useful content before the sequential WebView refresh begins.
        preloadHomeCache();
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

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readTvLaunchIntent(intent);
        if (startupCatalogReady) browserHandler.postDelayed(this::openConfiguredStartupSource, 150);
    }

    private void readTvLaunchIntent(android.content.Intent intent) {
        if (intent == null) return;
        Uri incomingData = intent.getData();
        if (incomingData != null && "kinozal".equals(incomingData.getScheme())) {
            if ("torrent".equals(incomingData.getHost())) {
                String href = incomingData.getQueryParameter("href");
                if (href != null && !href.trim().isEmpty()) {
                    pendingTvTorrentHref = href.trim();
                    pendingTvTorrentTitle = incomingData.getQueryParameter("title");
                }
            } else if ("search".equals(incomingData.getHost())) {
                String query = incomingData.getQueryParameter("q");
                if (query != null && !query.trim().isEmpty()) pendingExternalSearchQuery = query.trim();
            }
        }
        String action = intent.getAction();
        if (Intent.ACTION_SEARCH.equals(action) || "android.intent.action.WEB_SEARCH".equals(action)) {
            String query = intent.getStringExtra(android.app.SearchManager.QUERY);
            if (query == null || query.trim().isEmpty()) query = intent.getStringExtra("query");
            if (query != null && !query.trim().isEmpty()) pendingExternalSearchQuery = query.trim();
        }
        String href = intent.getStringExtra("tv_torrent_href");
        if (href != null && !href.trim().isEmpty()) {
            pendingTvTorrentHref = href.trim();
            pendingTvTorrentTitle = intent.getStringExtra("tv_torrent_title");
        }
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
            executeSafely(io, () -> {
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
        field.setTextSize(fontSize(14));
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
        if (!pendingExternalSearchQuery.isEmpty()) {
            String query = pendingExternalSearchQuery;
            pendingExternalSearchQuery = "";
            search.setText(query);
            submitSearch();
            Log.i("KinozalSearch", "Opened external voice search query");
            return;
        }
        if (!pendingTvTorrentHref.isEmpty()) {
            Torrent selected = new Torrent();
            selected.href = pendingTvTorrentHref;
            selected.title = pendingTvTorrentTitle == null || pendingTvTorrentTitle.trim().isEmpty()
                    ? "Раздача Kinozal" : pendingTvTorrentTitle;
            selected.size = selected.seeds = selected.leeches = "—";
            pendingTvTorrentHref = "";
            pendingTvTorrentTitle = "";
            showTorrent(selected);
            return;
        }
        String source = getSharedPreferences(PREFS, MODE_PRIVATE).getString("startup_source", "home");
        if ("collections".equals(source)) loadCollections();
        else if ("browse.php".equals(source)) load("browse.php", "Все раздачи");
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
        // Prepared away from the UI thread before the card enters the view tree.
        List<String> preparedBadges;
    }

    private void loadInitialCatalog() {
        if (deferBrowserNavigationIfBusy(this::loadInitialCatalog)) return;
        loadInitialCatalog(0);
    }

    private static final String[] HOME_PATHS = {
            "novinki.php",
            "top.php?j=&t=0&d=0&k=0&f=0&w=2&s=0",
            "top.php?j=&t=31&d=0&k=0&f=0&w=2&s=0",
            "top.php?j=&t=32&d=0&k=0&f=0&w=2&s=0",
            "top.php?j=&t=1&d=0&k=0&f=0&w=2&s=0",
            "top.php?t=2&d=0&f=0&k=0&j=&s=0&w=2",
            "top.php?j=&t=4&d=0&k=0&f=0&w=2&s=0",
            "top.php?j=&t=7&d=0&k=0&f=0&w=2&s=0"
    };
    private static final String[] HOME_TITLES = {
            "Новинки кино",
            "Топ раздач месяца",
            "Топ русские сериалы",
            "Топ иностранные сериалы",
            "Топ фильмы",
            "Топ мульты",
            "Топ музыка",
            "Топ игр"
    };

    private static final String[] COLLECTION_PATHS = {
            "top.php?j=Netflix",
            "top.php?j=HBO",
            "top.php?j=Marvel",
            "top.php?j=Walt%20Disney%20Pictures",
            "top.php?j=Pixar",
            "top.php?j=%D0%9F%D1%81%D0%B8%D1%85%D0%BE%D0%BB%D0%BE%D0%B3%D0%B8%D1%87%D0%B5%D1%81%D0%BA%D0%B8%D0%B9%20%D1%82%D1%80%D0%B8%D0%BB%D0%BB%D0%B5%D1%80",
            "top.php?j=%D0%9D%D0%BE%D0%B2%D0%BE%D0%B3%D0%BE%D0%B4%D0%BD%D0%B8%D0%B9",
            "top.php?j=%D0%A0%D0%B5%D0%B0%D0%BB%D1%8C%D0%BD%D1%8B%D0%B5%20%D1%81%D0%BE%D0%B1%D1%8B%D1%82%D0%B8%D1%8F",
            "top.php?j=%D0%BB%D1%83%D1%87%D1%88%D0%B8%D0%B9%20%D1%84%D0%B8%D0%BB%D1%8C%D0%BC%20%D0%9E%D1%81%D0%BA%D0%B0%D1%80",
            "top.php?j=%D1%8D%D0%BA%D1%80%D0%B0%D0%BD%D0%B8%D0%B7%D0%B0%D1%86%D0%B8%D1%8F",
            "top.php?j=%D1%84%D0%B8%D0%BB%D1%8C%D0%BC%D1%8B%20%D0%BE%20%D1%81%D0%BF%D0%BE%D1%80%D1%82%D0%B5"
    };
    private static final String[] COLLECTION_TITLES = {
            "Netflix", "HBO", "Marvel", "Walt Disney", "Pixar",
            "Триллер", "Новый год", "Реальные события", "Оскар",
            "Экранизации", "Спорт"
    };

    private boolean deferBrowserNavigationIfBusy(Runnable navigation) {
        if (!browserFeedLoading && !tvChannelsRefreshInProgress) return false;
        // Keep the latest explicit user choice; it will open as soon as the
        // background WebView sequence finishes instead of cancelling it.
        pendingBrowserNavigation = navigation;
        progress.setVisibility(View.VISIBLE);
        status.setText("Завершаем обновление каталога…");
        return true;
    }

    private void runPendingBrowserNavigation() {
        if (browserFeedLoading || tvChannelsRefreshInProgress || pendingBrowserNavigation == null) return;
        Runnable navigation = pendingBrowserNavigation;
        pendingBrowserNavigation = null;
        browserHandler.post(navigation);
    }

    private void loadCollections() {
        if (deferBrowserNavigationIfBusy(this::loadCollections)) return;
        activateFeedScreen("collections");
        currentPath = "collections";
        currentTitle = "Подборки";
        heading.setText("Подборки");
        synchronized (feedDiagnosticsLock) {
            feedLoadDiagnostics.setLength(0);
            feedDiagnosticStartedAtMs = android.os.SystemClock.elapsedRealtime();
            feedDiagnosticGroup = "Подборки";
        }
        FeedDiagnostics.start(this, "Подборки", client == null ? "?" : client.getMirror(),
                COLLECTION_PATHS, "вход на экран подборок");
        if (!collectionsSectionsMemory.isEmpty()) {
            collectionsLoadedOnce = true;
            appendFeedDiagnostic("CACHE_MEMORY", "экран показан из памяти Activity; секций="
                    + collectionsSectionsMemory.size() + ", карточек=" + countHomeCards(collectionsSectionsMemory));
            appendFeedDiagnostic("REFRESH_DECISION", "кэш памяти показан сразу; проверяем фоновое обновление по сроку давности");
            HomeFeedScheduler.refreshCachedIfStale(getApplicationContext(), "collections");
            status.setText("Показано лент: " + collectionsSectionsMemory.size());
            return;
        }
        List<HomeSection> cached = readCollectionsModel();
        if (cached != null && !cached.isEmpty()) {
            collectionsSectionsMemory = new ArrayList<>(cached);
            collectionsLoadedOnce = true;
            java.io.File cacheFile = collectionsModelFile();
            long cacheAgeMs = cacheFile.isFile() ? Math.max(0L, System.currentTimeMillis() - cacheFile.lastModified()) : -1L;
            appendFeedDiagnostic("CACHE_DISK", "экран показан из файла kinozal_collections_model_v1.json; секций="
                    + cached.size() + ", карточек=" + countHomeCards(cached)
                    + ", fileBytes=" + (cacheFile.isFile() ? cacheFile.length() : 0L)
                    + ", fileAgeMs=" + cacheAgeMs);
            renderHome(cached);
            status.setText("Подборки из кэша");
            appendFeedDiagnostic("REFRESH_SCHEDULE", "кэш показан сразу; запрашиваем фоновое обновление, если прошло 30 минут");
            HomeFeedScheduler.refreshCachedIfStale(getApplicationContext(), "collections");
            return;
        }
        appendFeedDiagnostic("CACHE_MISS", "валидный дисковый кеш не найден; запускается WebView-загрузка");
        if (collectionsLoadedOnce) {
            appendFeedDiagnostic("REFRESH_DECISION", "collectionsLoadedOnce=true; ранний выход без сетевого запроса");
            return;
        }
        browserFeedLoading = true;
        showPageLoading("Загрузка подборок через WebView…");
        if (sessionWebView == null) restoreBrowserSession("");
        browserHandler.postDelayed(() -> loadHomeSectionsInBrowser(
                COLLECTION_PATHS, COLLECTION_TITLES, 0, new ArrayList<>(), 0),
                sessionWebView == null ? 350 : 100);
    }

    private void preloadHomeCache() {
        if (!pendingTvTorrentHref.isEmpty()) {
            homeCacheLoadFinished = true;
            return;
        }
        executeSafely(io, () -> {
            List<HomeSection> cached = readHomeModel();
            runOnUiThread(() -> {
                homeCacheLoadFinished = true;
                homeDiskCacheSnapshot = cached == null ? new ArrayList<>() : new ArrayList<>(cached);
                if (!homeDiskCacheSnapshot.isEmpty()) {
                    homeSectionsMemory = new ArrayList<>(homeDiskCacheSnapshot);
                    homeCacheDisplayed = true;
                    homeLoadedOnce = true;
                    // Only attach Home when it is the currently selected startup page.
                    if ((currentPath == null || currentPath.isEmpty())
                            && "home".equals(getSharedPreferences(PREFS, MODE_PRIVATE)
                                    .getString("startup_source", "home"))) {
                        renderCachedHomeIfNeeded();
                        status.setText("Главная · восстановлена из кэша");
                        HomeFeedScheduler.scheduleInitial(getApplicationContext(), "home");
                    }
                }
                if (initialHomeFetchPending) {
                    initialHomeFetchPending = false;
                    if (!homeLoadedOnce && (currentPath == null || currentPath.isEmpty())) {
                        startInitialHomeBrowserLoad();
                    }
                }
            });
        });
    }

    private void renderCachedHomeIfNeeded() {
        if (homeList == null || homeSectionsMemory.isEmpty() || homeList.getChildCount() > 0) return;
        LinearLayout previous = list;
        list = homeList;
        renderHome(homeSectionsMemory);
        list = previous;
    }

    private void loadInitialCatalog(int startupRetry) {
        activateFeedScreen("home");
        currentPath = "";
        currentTitle = "Главная";
        heading.setText("Главная");
        if (homeLoadedOnce) {
            renderCachedHomeIfNeeded();
            status.setText("Главная · сохранённый экран");
            return;
        }
        if (!homeCacheLoadFinished) {
            initialHomeFetchPending = true;
            progress.setVisibility(View.VISIBLE);
            status.setText("Подготавливаем главную…");
            return;
        }
        startInitialHomeBrowserLoad();
    }

    private void startInitialHomeBrowserLoad() {
        activateFeedScreen("home");
        if (homeLoadedOnce || browserFeedLoading) return;
        browserFeedLoading = true;
        currentPath = "";
        currentTitle = "Главная";
        heading.setText("Главная");
        if (homeCacheDisplayed) {
            // Keep cached rows visible while fresh feeds arrive one by one.
            progress.setVisibility(View.VISIBLE);
            status.setText("Обновляем главную…");
        } else {
            showPageLoading("Загрузка главной через WebView…");
        }

        // Keep the authenticated browser session as the source of fresh feeds.
        // The local snapshot only improves first paint; it does not replace auth.
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
            // TV channels are refreshed separately from authenticated channel feeds.
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
                String cachedPath = feed.optString("path");
                // Drop legacy "new arrivals" sections saved by older builds.
                if (cachedPath.isEmpty() || "?page=1".equals(cachedPath)) continue;
                HomeSection section = new HomeSection(feed.optString("title"), cachedPath);
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
            int beforeDedup = countHomeCards(result);
            removeHomeDuplicates(result);
            if (countHomeCards(result) != beforeDedup) saveHomeModel(result);
            return result;
        } catch (Exception ignored) { return null; }
    }

    private void refreshHomeInBackground() {
        if (homeRefreshInProgress) return;
        homeRefreshInProgress = true;
        executeSafely(homeRefreshIo, () -> {
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
        executeSafely(homeRefreshIo, () -> {
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
        // Home and Collections are navigated sequentially; each rendered page is
        // extracted directly from its authenticated WebView DOM using JavaScript.
        if (index != 0) return;
        String group = paths == HOME_PATHS ? "Главная" : "Подборки";
        resetFeedDiagnostics(group, paths);
        if (paths == HOME_PATHS) startHomePerformanceMonitor();

        long fetchStarted = android.os.SystemClock.elapsedRealtime();
        long parseStarted = fetchStarted;
        fetchAndParseHomeFeedsSequentially(paths, titles, 0, new ArrayList<>(),
                fetchStarted, parseStarted, loaded -> {
                    if (loaded.isEmpty() && retry == 0) {
                        appendFeedDiagnostic("NAVIGATION", "не получены карточки; error=" + safe(lastBrowserError));
                        status.setText("Повторное подключение к Kinozal…");
                        browserHandler.postDelayed(() -> loadHomeSectionsInBrowser(
                                paths, titles, 0, new ArrayList<>(), 1), 1200);
                        return;
                    }
                    removeHomeDuplicates(loaded);
                    for (HomeSection section : loaded) {
                        if (section == null || section.items == null) continue;
                        for (HomePoster poster : section.items) {
                            if (poster == null || poster.torrent == null) continue;
                            appendFeedDiagnostic("CARD", "section=" + safe(section.title)
                                    + ", title=" + safe(poster.torrent.title)
                                    + ", href=" + safe(poster.torrent.href));
                        }
                    }
                    appendFeedDiagnostic("PARSE_DONE", "mode=webview-js, секций=" + loaded.size()
                            + ", карточек=" + countHomeCards(loaded)
                            + ", processingMs=" + (android.os.SystemClock.elapsedRealtime() - parseStarted));
                    if (!loaded.isEmpty()) {
                        if (paths == HOME_PATHS) saveHomeModel(loaded);
                        else if (paths == COLLECTION_PATHS) saveCollectionsModel(loaded);
                    }
                    finishHomeBrowserLoad(paths, loaded);
                });
    }

    private void finishHomeBrowserLoad(String[] paths, List<HomeSection> sections) {
        appendFeedDiagnostic("RENDER_START", "секций=" + sections.size() + ", карточек=" + countHomeCards(sections));
        // Preserve the existing screen completion behavior and keep Android TV
        // channel loading on its separate, unchanged pipeline.
        ensureFeedActions();
        renderHomeSectionsIncrementally(sections);
        int total = countHomeCards(sections);
        if (!sections.isEmpty() && paths == HOME_PATHS) {
            homeSectionsMemory = new ArrayList<>(sections);
            homeLoadedOnce = true;
            HomeFeedScheduler.scheduleInitial(getApplicationContext(), "home");
        } else if (!sections.isEmpty() && paths == COLLECTION_PATHS) {
            collectionsSectionsMemory = new ArrayList<>(sections);
            collectionsLoadedOnce = true;
            HomeFeedScheduler.scheduleInitial(getApplicationContext(), "collections");
        }
        progress.setVisibility(View.GONE);
        browserFeedLoading = false;
        if (paths == HOME_PATHS && pendingBrowserNavigation == null) {
            browserHandler.postDelayed(() -> {
                if (currentPath == null || currentPath.isEmpty()) loadTvChannelFeedsInBrowser();
            }, 350);
        } else {
            runPendingBrowserNavigation();
        }
        appendFeedDiagnostic("FINISH", "секций=" + sections.size() + ", карточек=" + total
                + ", totalMs=" + (android.os.SystemClock.elapsedRealtime() - feedDiagnosticStartedAtMs));
        if (total == 0) {
            status.setText("Нет соединения");
            addEmpty("Не удалось получить постеры Kinozal. Проверьте авторизацию и выбранное зеркало.");
        } else {
            setCatalogStatus("Постеров", total);
            status.setText("Показано лент: " + sections.size());
        }
    }

    private void mergeNewArrivalsHomePages(List<HomeSection> sections) {
        HomeSection combined = new HomeSection("Новые поступления", "");
        for (int i = sections.size() - 1; i >= 0; i--) {
            HomeSection section = sections.get(i);
            if ("".equals(section.path) || "?page=1".equals(section.path)) {
                combined.items.addAll(0, section.items);
                sections.remove(i);
            }
        }
        if (!combined.items.isEmpty()) sections.add(0, combined);
    }

    private static final String[] TV_CHANNEL_PATHS = HOME_PATHS;
    private static final String[] TV_CHANNEL_TITLES = HOME_TITLES;
    private boolean tvChannelsRefreshInProgress;

    private java.io.File tvChannelModelFile() {
        return new java.io.File(getFilesDir(), "kinozal_tv_channels_v1.json");
    }

    private void loadTvChannelFeedsInBrowser() {
        if (tvChannelsRefreshInProgress || sessionWebView == null || isFinishing()) return;
        tvChannelsRefreshInProgress = true;
        List<HomeSection> sections = new ArrayList<>();
        final String generalTopPath = "top.php?c=0&d=0&f=0&j=&k=0&s=0&t=0";
        for (HomeSection existing : homeSectionsMemory) {
            if (generalTopPath.equals(existing.path) && !existing.items.isEmpty()) {
                HomeSection general = new HomeSection("Общий топ Kinozal", generalTopPath);
                general.items.addAll(existing.items);
                sections.add(general);
                break;
            }
        }
        loadNextTvChannelFeed(0, sections);
    }

    private void loadNextTvChannelFeed(int index, List<HomeSection> sections) {
        if (index >= TV_CHANNEL_PATHS.length) {
            // Keep each TV channel independent: the same release may legitimately
            // appear in more than one feed. parseHomePosters already removes duplicates
            // within an individual feed.
            executeSafely(io, () -> {
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
                    rootJson.put("version", 1);
                    rootJson.put("mirror", client.getMirror());
                    rootJson.put("savedAt", System.currentTimeMillis());
                    rootJson.put("feeds", feeds);
                    java.io.File target = tvChannelModelFile();
                    java.io.File temp = new java.io.File(target.getParentFile(), target.getName() + ".tmp");
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
                        out.write(rootJson.toString().getBytes(StandardCharsets.UTF_8));
                        out.getFD().sync();
                    }
                    if (target.exists() && !target.delete()) throw new java.io.IOException("Cannot replace TV channel cache");
                    if (!temp.renameTo(target)) throw new java.io.IOException("Cannot commit TV channel cache");
                    runOnUiThread(() -> {
                        tvChannelsRefreshInProgress = false;
                        TvHomeChannels.sync(this, target);
                        runPendingBrowserNavigation();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        tvChannelsRefreshInProgress = false;
                        runPendingBrowserNavigation();
                    });
                }
            });
            return;
        }
        final String path = TV_CHANNEL_PATHS[index];
        final String title = TV_CHANNEL_TITLES[index];
        fetchBrowserPage(path, null, html -> {
            if (html == null || html.trim().isEmpty()) {
                browserHandler.postDelayed(() -> loadNextTvChannelFeed(index + 1, sections), 180);
                return;
            }
            executeSafely(io, () -> {
                HomeSection section = new HomeSection(title, path);
                try {
                    section.items.addAll(parseHomePosters(Jsoup.parse(html, client.getMirror() + "/")));
                    // Apply the same title/id/link de-duplication used by the home rows,
                    // but scope it to this feed so separate TV collections stay independent.
                    List<HomeSection> oneFeed = new ArrayList<>();
                    oneFeed.add(section);
                    removeHomeDuplicates(oneFeed);
                } catch (Exception ignored) { }
                runOnUiThread(() -> {
                    if (!section.items.isEmpty()) sections.add(section);
                    browserHandler.postDelayed(() -> loadNextTvChannelFeed(index + 1, sections), 180);
                });
            });
        });
    }

    /**
     * Navigate feeds sequentially and extract their already-rendered DOM in WebView.
     * Jsoup is retained as a background fallback if JavaScript extraction fails.
     */
    private void fetchAndParseHomeFeedsSequentially(String[] paths, String[] titles, int index,
            List<HomeSection> loaded, long fetchStarted, long parseStarted,
            java.util.function.Consumer<List<HomeSection>> callback) {
        if (index >= paths.length) {
            appendFeedDiagnostic("FETCH_DONE", "parallelBatchSize=2, totalMs="
                    + (android.os.SystemClock.elapsedRealtime() - fetchStarted));
            callback.accept(loaded);
            return;
        }

        final int feedIndex = index;
        final String path = paths[index];
        final String title = titles[index];
        long requestStarted = android.os.SystemClock.elapsedRealtime();
        appendFeedDiagnostic(index == 0 ? "NAVIGATION_START" : "BATCH_NAVIGATION",
                path + " index=" + (index + 1) + "/" + paths.length);

        // One real WebView navigation establishes/refreshes the authenticated origin.
        // The next two same-origin feeds are fetched concurrently with credentials.
        fetchBrowserPage(path, null, html -> {
            long requestDuration = android.os.SystemClock.elapsedRealtime() - requestStarted;
            int htmlLength = html == null ? 0 : html.length();
            appendFeedDiagnostic("NAVIGATION_DONE", path + " durationMs=" + requestDuration
                    + ", htmlChars=" + htmlLength
                    + (htmlLength == 0 ? ", error=" + safe(lastBrowserError) : ""));
            if (htmlLength == 0) {
                fetchAndParseHomeFeedsSequentialFallback(paths, titles, feedIndex + 1, loaded,
                        fetchStarted, parseStarted, callback, "navigation failed");
                return;
            }

            WebView web = sessionWebView;
            if (web == null) {
                parseHomeFeedFallback(html, title, path, feedIndex, loaded, paths, titles,
                        fetchStarted, parseStarted, callback, "WebView отсутствует");
                return;
            }

            final String currentHtml = html;
            final int batchEnd = Math.min(paths.length, feedIndex + 3);
            org.json.JSONArray batchPaths = new org.json.JSONArray();
            for (int i = feedIndex + 1; i < batchEnd; i++) {
                org.json.JSONObject entry = new org.json.JSONObject();
                try {
                    entry.put("path", paths[i]);
                    entry.put("title", titles[i]);
                    entry.put("index", i);
                    batchPaths.put(entry);
                } catch (Exception ignored) { }
            }

            String script = buildHomeParallelExtractScript(batchPaths.toString(), feedIndex,
                    org.json.JSONObject.quote(currentHtml));
            long batchStarted = android.os.SystemClock.elapsedRealtime();
            evaluateHomeParallelBatch(web, script, value -> {
                try {
                    String json = new org.json.JSONArray("[" + value + "]").getString(0);
                    org.json.JSONArray results = new org.json.JSONArray(json);
                    boolean batchOk = results.length() == batchEnd - feedIndex;
                    for (int i = 0; i < results.length(); i++) {
                        org.json.JSONObject result = results.optJSONObject(i);
                        if (result == null || !result.optBoolean("ok", false)) {
                            batchOk = false;
                            continue;
                        }
                        int resultIndex = result.optInt("index", -1);
                        if (resultIndex < feedIndex || resultIndex >= batchEnd) {
                            batchOk = false;
                            continue;
                        }
                        String resultPath = paths[resultIndex];
                        String resultHtml = result.optString("html", "");
                        org.json.JSONArray extractedItems = result.optJSONArray("items");
                        appendHomeExtractorDiagnostics(extractedItems, resultPath);
                        HomeSection section = new HomeSection(titles[resultIndex], resultPath);
                        section.items.addAll(parseHomePostersFromJsonArray(extractedItems));
                        if (!section.items.isEmpty()) {
                            loaded.add(section);
                            diskCache.put("home|" + client.getMirror() + "|" + resultPath,
                                    resultHtml.getBytes(StandardCharsets.UTF_8));
                        }
                        appendFeedDiagnostic(resultIndex == feedIndex ? "PARSE_JS" : "PARSE_JS_PARALLEL",
                                resultPath + " durationMs="
                                + (android.os.SystemClock.elapsedRealtime() - batchStarted)
                                + ", cards=" + section.items.size()
                                + ", htmlChars=" + resultHtml.length());
                    }
                    if (batchOk) {
                        appendFeedDiagnostic("PARALLEL_BATCH", "feeds=" + (batchEnd - feedIndex - 1)
                                + ", durationMs=" + (android.os.SystemClock.elapsedRealtime() - batchStarted));
                        fetchAndParseHomeFeedsSequentially(paths, titles, batchEnd, loaded,
                                fetchStarted, parseStarted, callback);
                    } else {
                        appendFeedDiagnostic("PARALLEL_FALLBACK", "batch incomplete; continue sequentially from "
                                + (feedIndex + 1));
                        fetchAndParseHomeFeedsSequentialFallback(paths, titles, feedIndex + 1, loaded,
                                fetchStarted, parseStarted, callback, "parallel batch incomplete");
                    }
                } catch (Exception e) {
                    appendFeedDiagnostic("PARALLEL_FALLBACK", path + " " + safe(e.getMessage()));
                    // The current navigated page is retained; remaining feeds use the proven path.
                    fetchAndParseHomeFeedsSequentialFallback(paths, titles, feedIndex + 1, loaded,
                            fetchStarted, parseStarted, callback, "JS batch error");
                }
            });
        });
    }

    private String buildHomeParallelExtractScript(String pathsJson, int currentIndex, String currentHtmlJson) {
        return "(function(){"
                + "function extract(doc,base){try{"
                + "var links=doc.querySelectorAll('a[href*=\"details.php?id=\"]');"
                + "var out=[],seen=Object.create(null),stats={anchors:links.length,noRow:0,shortRow:0,noTitle:0,accepted:0};"
                + "function txt(e){return e?(e.innerText||e.textContent||'').replace(/\\\\s+/g,' ').trim():'';}"
                + "function imgSrc(img){if(!img)return '';var a=['data-src','data-original','data-lazy-src','data-original-src','src'];"
                + "for(var i=0;i<a.length;i++){var v=(img.getAttribute(a[i])||'').trim();"
                + "if(v&&v.indexOf('data:image/')!==0)return v;}"
                + "var ss=img.getAttribute('data-srcset')||img.getAttribute('srcset')||'';"
                + "return ss?ss.split(',')[0].trim().split(/\\\\s+/)[0]:'';}"
                + "for(var i=0;i<links.length;i++){var a=links[i],raw=a.getAttribute('href')||'';"
                + "if(raw.indexOf('details.php?id=')<0||/userdetails/i.test(raw))continue;"
                + "var href='';try{href=new URL(raw,base).href;}catch(e){continue;}if(seen[href])continue;"
                + "var row=a.closest('tr');if(!row)stats.noRow++;else if(row.querySelectorAll('td').length<6)stats.shortRow++;seen[href]=1;"
                + "var img=a.querySelector('img');if(!img&&row)img=row.querySelector('img');"
                + "if(!img&&a.parentElement)img=a.parentElement.querySelector('img');"
                + "var p=a;for(var d=0;d<4&&!img&&p;d++){p=p.parentElement;if(p)img=p.querySelector('img');}"
                + "var title=(a.getAttribute('title')||'').trim()||(img?(img.getAttribute('alt')||'').trim():'')||txt(a);"
                + "if(!title){stats.noTitle++;continue;}var image=imgSrc(img);try{if(image)image=new URL(image,base).href;}catch(e){}"
                + "var cells=row?row.querySelectorAll('td'):[];function cell(n){return cells.length>n?txt(cells[n]):'—';}"
                + "out.push({title:title,href:href,image:image,size:cell(3),seeds:cell(4),leeches:cell(5),uploaded:cell(6)});stats.accepted++;}out.push({_diag:stats});"
                + "return out;}catch(e){return [];}}"
                + "var requests=" + pathsJson + ";"
                + "var currentHtml=" + currentHtmlJson + ",currentIndex=" + currentIndex + ";"
                + "window.__kinozalParallelResult=null;"
                + "Promise.all(requests.map(function(r){"
                + "var url=new URL(r.path,location.href).href;"
                + "return fetch(url,{credentials:'include',cache:'no-store'}).then(function(resp){"
                + "if(!resp.ok)throw new Error('HTTP '+resp.status+' '+r.path);"
                + "return resp.arrayBuffer().then(function(buf){"
                + "var ct=resp.headers.get('content-type')||'',match=ct.match(/charset\\s*=\\s*['\\\"]?([^;\\s'\\\"]+)/i);"
                + "var probe=new TextDecoder('windows-1252').decode(buf.slice(0,4096));"
                + "var meta=probe.match(/<meta[^>]+charset\\s*=\\s*['\\\"]?([^\\s'\\\"/>;]+)/i)"
                + "||probe.match(/<meta[^>]+content=[^>]*charset=([^\\s'\\\";]+)/i);"
                + "var enc=match?match[1].toLowerCase():(meta?meta[1].toLowerCase():'utf-8'),html;"
                + "try{html=new TextDecoder(enc).decode(buf);}catch(decodeError){html=new TextDecoder('utf-8').decode(buf);}"
                + "var doc=new DOMParser().parseFromString(html,'text/html');"
                + "return {ok:true,index:r.index,html:html,items:extract(doc,url)};});"
                + "});})).then(function(rows){rows.unshift({ok:true,index:currentIndex,html:currentHtml,"
                + "items:extract(document,document.baseURI)});window.__kinozalParallelResult=JSON.stringify(rows);})"
                + ".catch(function(e){window.__kinozalParallelResult=JSON.stringify([{ok:true,index:currentIndex,html:currentHtml,"
                + "items:extract(document,document.baseURI)},{ok:false,error:String(e)}]);});"
                + "return 'started';})()";
    }

    private void evaluateHomeParallelBatch(WebView web, String script,
            java.util.function.Consumer<String> callback) {
        final int[] attempts = {0};
        web.evaluateJavascript("window.__kinozalParallelResult=null;" + script, ignored -> {
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                if (web != sessionWebView || isFinishing()) {
                    callback.accept(null);
                    return;
                }
                web.evaluateJavascript("window.__kinozalParallelResult", value -> {
                    if (value != null && !"null".equals(value) && value.length() > 2) {
                        callback.accept(value);
                    } else if (++attempts[0] >= 150) {
                        appendFeedDiagnostic("PARALLEL_TIMEOUT", "fetch batch exceeded 15 seconds");
                        callback.accept(null);
                    } else {
                        browserHandler.postDelayed(poll[0], 100);
                    }
                });
            };
            browserHandler.postDelayed(poll[0], 100);
        });
    }

    private void appendHomeExtractorDiagnostics(org.json.JSONArray array, String path) {
        if (array == null || array.length() == 0) return;
        org.json.JSONObject last = array.optJSONObject(array.length() - 1);
        org.json.JSONObject stats = last == null ? null : last.optJSONObject("_diag");
        if (stats == null) return;
        appendFeedDiagnostic("JS_EXTRACT_STATS", path + " anchors=" + stats.optInt("anchors")
                + ", noRow=" + stats.optInt("noRow") + ", shortRow=" + stats.optInt("shortRow")
                + ", noTitle=" + stats.optInt("noTitle") + ", accepted=" + stats.optInt("accepted"));
    }

    private List<HomePoster> parseHomePostersFromJsonArray(org.json.JSONArray array) {
        List<HomePoster> out = new ArrayList<>();
        if (array == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            org.json.JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String title = obj.optString("title", "").trim();
            String href = obj.optString("href", "").trim();
            if (title.isEmpty() || href.isEmpty() || !seen.add(href)) continue;
            HomePoster item = new HomePoster();
            item.torrent = new Torrent();
            item.torrent.title = title;
            item.torrent.href = href;
            item.torrent.size = obj.optString("size", "—");
            item.torrent.seeds = obj.optString("seeds", "—");
            item.torrent.leeches = obj.optString("leeches", "—");
            item.torrent.uploaded = obj.optString("uploaded", "—");
            String image = obj.optString("image", "").trim();
            if (!image.startsWith("http://") && !image.startsWith("https://")) image = "";
            item.imageUrl = image;
            item.torrent.poster = image;
            out.add(item);
        }
        return out;
    }

    private void fetchAndParseHomeFeedsSequentialFallback(String[] paths, String[] titles, int index,
            List<HomeSection> loaded, long fetchStarted, long parseStarted,
            java.util.function.Consumer<List<HomeSection>> callback, String reason) {
        appendFeedDiagnostic("SEQUENTIAL_FALLBACK", reason + ", fromIndex=" + index);
        fetchAndParseHomeFeedsSequentiallyLegacy(paths, titles, index, loaded,
                fetchStarted, parseStarted, callback);
    }

    private void fetchAndParseHomeFeedsSequentiallyLegacy(String[] paths, String[] titles, int index,
            List<HomeSection> loaded, long fetchStarted, long parseStarted,
            java.util.function.Consumer<List<HomeSection>> callback) {
        if (index >= paths.length) {
            appendFeedDiagnostic("FETCH_DONE", "sequentialFallback=true, totalMs="
                    + (android.os.SystemClock.elapsedRealtime() - fetchStarted));
            callback.accept(loaded);
            return;
        }
        final int feedIndex = index;
        final String path = paths[index];
        fetchBrowserPage(path, null, html -> {
            if (html == null || html.trim().isEmpty()) {
                fetchAndParseHomeFeedsSequentiallyLegacy(paths, titles, feedIndex + 1, loaded,
                        fetchStarted, parseStarted, callback);
                return;
            }
            WebView web = sessionWebView;
            if (web == null) {
                parseHomeFeedFallback(html, titles[feedIndex], path, feedIndex, loaded, paths, titles,
                        fetchStarted, parseStarted, callback, "WebView отсутствует during fallback");
                return;
            }
            // Reuse the DOM extraction logic with the current live document.
            String currentScript = "(function(){try{var links=document.querySelectorAll('a[href*=\"details.php?id=\"]');var out=[],seen={};"
                    + "function t(e){return e?(e.innerText||e.textContent||'').replace(/\\\\s+/g,' ').trim():'';}"
                    + "function im(x){if(!x)return '';var z=['data-src','data-original','data-lazy-src','data-original-src','src'];"
                    + "for(var j=0;j<z.length;j++){var v=x.getAttribute(z[j])||'';if(v&&v.indexOf('data:image/')!==0)return v;}"
                    + "return '';}for(var i=0;i<links.length;i++){var a=links[i],h=a.getAttribute('href')||'';"
                    + "if(h.indexOf('details.php?id=')<0||/userdetails/i.test(h))continue;var u=a.href||h;if(seen[u])continue;"
                    + "var r=a.closest('tr');seen[u]=1;var x=a.querySelector('img');if(!x&&r)x=r.querySelector('img');"
                    + "if(!x&&a.parentElement)x=a.parentElement.querySelector('img');var p=a;"
                    + "for(var d=0;d<4&&!x&&p;d++){p=p.parentElement;if(p)x=p.querySelector('img');}"
                    + "var n=(a.getAttribute('title')||'').trim()||(x?(x.getAttribute('alt')||'').trim():'')||t(a);if(!n)continue;"
                    + "var q=im(x);try{if(q)q=new URL(q,document.baseURI).href;}catch(e){}var c=r?r.querySelectorAll('td'):[];"
                    + "function cell(k){return c.length>k?t(c[k]):'—';}out.push({title:n,href:u,image:q,size:cell(3),seeds:cell(4),leeches:cell(5),uploaded:cell(6)});}"
                    + "return JSON.stringify(out);}catch(e){return '[]';}})()";
            web.evaluateJavascript(currentScript, value -> {
                List<HomePoster> posters = parseHomePostersJson(value);
                HomeSection section = new HomeSection(titles[feedIndex], path);
                section.items.addAll(posters);
                if (!section.items.isEmpty()) {
                    loaded.add(section);
                    diskCache.put("home|" + client.getMirror() + "|" + path,
                            html.getBytes(StandardCharsets.UTF_8));
                }
                appendFeedDiagnostic("PARSE_JS_FALLBACK", path + " cards=" + posters.size());
                fetchAndParseHomeFeedsSequentiallyLegacy(paths, titles, feedIndex + 1, loaded,
                        fetchStarted, parseStarted, callback);
            });
        });
    }

    private List<HomePoster> parseHomePostersJson(String javascriptValue) {
        List<HomePoster> out = new ArrayList<>();
        try {
            String json = new org.json.JSONArray("[" + javascriptValue + "]").getString(0);
            org.json.JSONArray array = new org.json.JSONArray(json);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                String title = obj.optString("title", "").trim();
                String href = obj.optString("href", "").trim();
                if (title.isEmpty() || href.isEmpty() || !seen.add(href)) continue;
                HomePoster item = new HomePoster();
                item.torrent = new Torrent();
                item.torrent.title = title;
                item.torrent.href = href;
                item.torrent.size = obj.optString("size", "—");
                item.torrent.seeds = obj.optString("seeds", "—");
                item.torrent.leeches = obj.optString("leeches", "—");
                item.torrent.uploaded = obj.optString("uploaded", "—");
                String image = obj.optString("image", "").trim();
                if (!image.startsWith("http://") && !image.startsWith("https://")) image = "";
                item.imageUrl = image;
                item.torrent.poster = image;
                out.add(item);
            }
        } catch (Exception e) {
            Log.w("KinozalFeedDiag", "Home JS JSON parse failed", e);
        }
        return out;
    }

    private void parseHomeFeedFallback(String html, String title, String path, int feedIndex,
            List<HomeSection> loaded, String[] paths, String[] titles, long fetchStarted,
            long parseStarted, java.util.function.Consumer<List<HomeSection>> callback,
            String reason) {
        appendFeedDiagnostic("PARSE_JS_FALLBACK", path + " " + reason);
        executeSafely(io, () -> {
            long fallbackStarted = android.os.SystemClock.elapsedRealtime();
            HomeSection section = new HomeSection(title, path);
            try {
                section.items.addAll(parseHomePosters(Jsoup.parse(html, client.getMirror() + "/")));
            } catch (Exception e) {
                appendFeedDiagnostic("PARSE_ERROR", path + " " + safe(e.getMessage()));
            }
            long fallbackDuration = android.os.SystemClock.elapsedRealtime() - fallbackStarted;
            runOnUiThread(() -> {
                if (!section.items.isEmpty()) {
                    loaded.add(section);
                    diskCache.put("home|" + client.getMirror() + "|" + path,
                            html.getBytes(StandardCharsets.UTF_8));
                }
                appendFeedDiagnostic("PARSE_JS_FALLBACK_DONE", path + " durationMs="
                        + fallbackDuration + ", cards=" + section.items.size());
                fetchAndParseHomeFeedsSequentially(paths, titles, feedIndex + 1, loaded,
                        fetchStarted, parseStarted, callback);
            });
        });
    }

    /**
     * Load home feeds one by one through the authenticated WebView session.
     * Sequential navigations avoid stalled parallel fetch() requests on Kinozal.
     */
    private void fetchBrowserPages(String[] paths, java.util.function.Consumer<List<String>> callback) {
        long started = android.os.SystemClock.elapsedRealtime();
        List<String> result = new ArrayList<>();
        fetchBrowserPagesSequentially(paths, 0, result, started, callback);
    }

    private void fetchBrowserPagesSequentially(String[] paths, int index, List<String> result,
            long started, java.util.function.Consumer<List<String>> callback) {
        if (index >= paths.length) {
            appendFeedDiagnostic("FETCH_DONE", "sequentialRequests=" + result.size()
                    + ", totalMs=" + (android.os.SystemClock.elapsedRealtime() - started));
            callback.accept(result);
            return;
        }

        String path = paths[index];
        long requestStarted = android.os.SystemClock.elapsedRealtime();
        appendFeedDiagnostic(index == 0 ? "NAVIGATION_START" : "HTTP_START",
                path + " index=" + (index + 1) + "/" + paths.length);

        fetchBrowserPage(path, null, html -> {
            long duration = android.os.SystemClock.elapsedRealtime() - requestStarted;
            int length = html == null ? 0 : html.length();
            appendFeedDiagnostic(index == 0 ? "NAVIGATION_DONE" : "HTTP",
                    path + " durationMs=" + duration + ", htmlChars=" + length
                            + (length == 0 ? ", error=" + safe(lastBrowserError) : ""));

            if (length == 0) {
                // Keep already loaded sections; a single failed feed must not
                // discard successful responses from the same group.
                result.add("");
            } else {
                result.add(html);
            }
            fetchBrowserPagesSequentially(paths, index + 1, result, started, callback);
        });
    }

    private List<HomePoster> parseHomePosters(Document doc) {
        List<HomePoster> out = new ArrayList<>();
        java.util.Set<String> seenLinks = new java.util.HashSet<>();
        Elements anchors = doc.select("a[href*=details.php?id=]");
        for (Element a : anchors) {
            String linkPath = a.attr("href").toLowerCase(java.util.Locale.ROOT);
            if (linkPath.contains("userdetails") || !linkPath.contains("details.php?id=")) continue;
            String href = mirrorUrl(a.absUrl("href"), a.attr("href"));
            if (href.isEmpty() || !seenLinks.add(href)) continue;
            Element row = a.closest("tr");
            // Keep release links even when a mirror no longer uses the legacy six-cell table.
            // Row statistics are optional; poster/title extraction does not depend on them.

            // Resolve the table row once and reuse it for poster and statistics lookup.
            Element img = a.selectFirst("img");
            if (img == null && row != null) img = row.selectFirst("img");
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
            if (row != null) {
                Elements cells = row.select("td");
                item.torrent.size = cells.size() > 3 ? cells.get(3).text().trim() : "—";
                item.torrent.seeds = cells.size() > 4 ? cells.get(4).text().trim() : "—";
                item.torrent.leeches = cells.size() > 5 ? cells.get(5).text().trim() : "—";
            } else {
                item.torrent.size = item.torrent.seeds = item.torrent.leeches = "—";
            }
            // Keep releases even when Kinozal has no poster or lazy loading has not
            // produced a usable URL. The supplied Kinozal logo is shown as fallback.
            if (image == null || !(image.startsWith("http://") || image.startsWith("https://"))) image = "";
            item.imageUrl = image;
            item.torrent.poster = image;
            out.add(item);
        }
        return out;
    }

    /**
     * Collapse duplicate releases independently inside each home section.
     * A title may legitimately appear in several different feeds, so no
     * de-duplication key is shared between sections.
     */
    private int countHomeCards(List<HomeSection> sections) {
        int count = 0;
        for (HomeSection section : sections) count += section.items.size();
        return count;
    }

    private void removeHomeDuplicates(List<HomeSection> sections) {
        java.util.regex.Pattern idPattern = java.util.regex.Pattern.compile(
                "[?&]id=(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE);
        for (HomeSection section : sections) {
            java.util.Set<String> ids = new java.util.HashSet<>();
            java.util.Set<String> titles = new java.util.HashSet<>();
            java.util.Set<String> links = new java.util.HashSet<>();
            java.util.Set<String> posterKeys = new java.util.HashSet<>();
            List<HomePoster> unique = new ArrayList<>();
            int duplicateIds = 0;
            int duplicateLinks = 0;
            int duplicateTitles = 0;
            int invalid = 0;

            for (HomePoster item : section.items) {
                if (item == null || item.torrent == null) {
                    invalid++;
                    continue;
                }

                String href = item.torrent.href == null ? "" : item.torrent.href.trim();
                java.util.regex.Matcher matcher = idPattern.matcher(href);
                String id = matcher.find() ? matcher.group(1) : "";
                String titleKey = normalizeHomeTitle(item.torrent.title);
                String posterKey = normalizeHomePosterImage(item.imageUrl);

                if (!id.isEmpty() && ids.contains(id)) {
                    duplicateIds++;
                    continue;
                }
                if (!href.isEmpty() && links.contains(href)) {
                    duplicateLinks++;
                    continue;
                }
                if (!titleKey.isEmpty() && titles.contains(titleKey)) {
                    duplicateTitles++;
                    continue;
                }
                if (!posterKey.isEmpty() && posterKeys.contains(posterKey)) {
                    duplicateTitles++;
                    continue;
                }

                if (!id.isEmpty()) ids.add(id);
                if (!href.isEmpty()) links.add(href);
                if (!titleKey.isEmpty()) titles.add(titleKey);
                if (!posterKey.isEmpty()) posterKeys.add(posterKey);
                unique.add(item);
                if (unique.size() >= 15) break;
            }

            int before = section.items.size();
            section.items.clear();
            section.items.addAll(unique);
            appendFeedDiagnostic("DEDUP", section.title
                    + " before=" + before + ", after=" + unique.size()
                    + ", duplicateIds=" + duplicateIds
                    + ", duplicateLinks=" + duplicateLinks
                    + ", duplicateTitles=" + duplicateTitles
                    + ", invalid=" + invalid
                    + ", limit=15");
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

    private void updateSidebarLoginStatus(boolean loggedIn) {
        if (sidebarLoginInfo == null) return;
        sidebarLoginInfo.setText(loggedIn ? "Вход: выполнен" : "Вход: не выполнен");
        sidebarLoginInfo.setTextColor(loggedIn ? Color.rgb(115, 190, 135) : Color.rgb(230, 105, 90));
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
        executeSafely(io, () -> {
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

    private Bitmap decodeImageForView(byte[] bytes, ImageView target) {
        if (bytes == null || bytes.length == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int targetWidth = target == null ? 0 : target.getWidth();
        int targetHeight = target == null ? 0 : target.getHeight();
        if (targetWidth <= 0 && target != null && target.getLayoutParams() != null)
            targetWidth = target.getLayoutParams().width;
        if (targetHeight <= 0 && target != null && target.getLayoutParams() != null)
            targetHeight = target.getLayoutParams().height;
        if (targetWidth <= 0) targetWidth = dp(240);
        if (targetHeight <= 0) targetHeight = dp(360);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= targetWidth
                && bounds.outHeight / (options.inSampleSize * 2) >= targetHeight)
            options.inSampleSize *= 2;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private Bitmap getCachedImageBitmap(String imageUrl) {
        return decodedImageCache == null ? null : decodedImageCache.get(imageUrl);
    }

    private void cacheImageBitmap(String imageUrl, Bitmap bitmap) {
        if (decodedImageCache != null && imageUrl != null && bitmap != null && !bitmap.isRecycled())
            decodedImageCache.put(imageUrl, bitmap);
    }

    private void loadImageIntoView(String imageUrl, ImageView target) {
        loadImageIntoView(imageUrl, target, client.getMirror() + "/");
    }

    private void loadImageIntoView(String imageUrl, ImageView target, String referer) {
        if (imageUrl == null || imageUrl.isEmpty() || target == null) return;
        Bitmap memoryBitmap = getCachedImageBitmap(imageUrl);
        if (memoryBitmap != null) {
            target.setImageBitmap(memoryBitmap);
            return;
        }
        executeSafely(imageIo, () -> {
            Bitmap ready = getCachedImageBitmap(imageUrl);
            if (ready != null) {
                runOnUiThread(() -> { if (target.getParent() != null) target.setImageBitmap(ready); });
                return;
            }
            byte[] cachedImage = diskCache.get("image|" + imageUrl, 0L);
            if (cachedImage != null && cachedImage.length > 0) {
                Bitmap cachedBitmap = decodeImageForView(cachedImage, target);
                if (cachedBitmap != null) {
                    cacheImageBitmap(imageUrl, cachedBitmap);
                    runOnUiThread(() -> { if (target.getParent() != null) target.setImageBitmap(cachedBitmap); });
                    return;
                }
            }
            // Poster traffic must not share the authenticated WebView's main-thread
        // JavaScript queue with catalogue navigation. Fetch and decode on imageIo.
        loadImageWithHttp(imageUrl, target, referer);
        });
    }

    private void loadImageWithHttp(String imageUrl, ImageView target) {
        loadImageWithHttp(imageUrl, target, client.getMirror() + "/");
    }

    private void loadImageWithHttp(String imageUrl, ImageView target, String referer) {
        if (imageUrl == null || imageUrl.isEmpty() || target == null) return;
        executeSafely(imageIo, () -> {
            try {
                byte[] bytes = diskCache.get("image|" + imageUrl, 0L);
                if (bytes == null || bytes.length == 0) {
                    bytes = client.fetchBytes(imageUrl, "image/*", referer);
                    if (bytes == null || bytes.length == 0) return;
                    diskCache.put("image|" + imageUrl, bytes);
                }
                Bitmap bitmap = decodeImageForView(bytes, target);
                cacheImageBitmap(imageUrl, bitmap);
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

    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.regex.Pattern>
            HOME_BADGE_PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.regex.Pattern HOME_MUSIC_HINT =
            java.util.regex.Pattern.compile("\\b(FLAC|MP3|WAV|ALAC|APE|AAC|DSD)\\b");
    private static final java.util.regex.Pattern HOME_GAME_HINT =
            java.util.regex.Pattern.compile("\\b(PC|PS[345]|XBOX|NINTENDO|SWITCH|REPACK|PORTABLE)\\b");
    private static final java.util.regex.Pattern HOME_GAME_VERSION =
            java.util.regex.Pattern.compile("(?i)\\b(?:v(?:ersion)?\\s*)?([0-9]+\\.[0-9]+(?:\\.[0-9]+){0,2})\\b");

    private static java.util.regex.Pattern homeBadgePattern(String alternatives) {
        return HOME_BADGE_PATTERNS.computeIfAbsent(alternatives, key ->
                java.util.regex.Pattern.compile("(?i)(?<![A-Z0-9])(?:" + key + ")(?![A-Z0-9])"));
    }

    private List<String> extractHomeBadges(String title, String sectionTitle) {
        List<String> badges = new ArrayList<>();
        if (title == null) return badges;
        String value = title.toUpperCase(java.util.Locale.ROOT);
        String section = sectionTitle == null ? "" : sectionTitle.toLowerCase(java.util.Locale.ROOT);
        boolean music = section.contains("музык") || section.contains("music")
                || HOME_MUSIC_HINT.matcher(value).find();
        boolean game = section.contains("игр") || section.contains("game")
                || HOME_GAME_HINT.matcher(value).find();

        if (music) {
            addBadge(badges, value, "FLAC|ALAC|APE|WAV|DSD|MP3|AAC|OGG|OPUS");
            addBadge(badges, value, "24\\s*BIT|24BIT|16\\s*BIT|16BIT");
            addBadge(badges, value, "192\\s*KHZ|96\\s*KHZ|48\\s*KHZ|44[,.]1\\s*KHZ");
            addBadge(badges, value, "LOSSLESS|HI-RES|HIRES");
        } else if (game) {
            addBadge(badges, value, "PC|WINDOWS|STEAM|GOG|PS5|PS4|PS3|XBOX|SWITCH");
            addBadge(badges, value, "REPACK|PORTABLE|ISO|RIP");
            java.util.regex.Matcher version = HOME_GAME_VERSION.matcher(title);
            if (version.find()) badges.add("v" + version.group(1));
            addBadge(badges, value, "RUS|РУССКИЙ|MULTI[0-9]*|ENG|АНГЛ");
        } else {
            addBadge(badges, value, "4320P|2160P|4K|1080P|720P|576P|480P");
            addBadge(badges, value, "BLU[- .]?RAY REMUX|BDREMUX|REMUX|BLU[- .]?RAY|BDRIP|BRRIP|WEB[- .]?DL[- .]?RIP|WEB[- .]?DL|WEBRIP|HDRIP|HDTV|DVDRIP|CAM|TS");
            addBadge(badges, value, "HEVC|H\\.?265|X265|AV1|AVC|H\\.?264|X264");
            addBadge(badges, value, "DOLBY VISION|DV|HDR10\\+?|HDR");
            addBadge(badges, value, "3D");
        }
        if (badges.size() > 3) return new ArrayList<>(badges.subList(0, 3));
        return badges;
    }

    private void addBadge(List<String> badges, String title, String alternatives) {
        if (badges.size() >= 3) return;
        java.util.regex.Matcher matcher = homeBadgePattern(alternatives).matcher(title);
        if (matcher.find()) {
            String badge = matcher.group().replaceAll("\\s+", " ").replaceAll("[- ]+", " ").trim();
            if ("WEB DL RIP".equals(badge)) badge = "WEB-DLRip";
            if (!badges.contains(badge)) badges.add(badge);
        }
    }

    private void ensureFeedActions() {
        if (list.getChildCount() > 0 && "feed-actions".equals(list.getChildAt(0).getTag())) return;
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setTag("feed-actions");

        String screenKey = "collections".equals(activeFeedScreen) ? "collections" : "home";
        LinearLayout feedRefreshPanel = new LinearLayout(this);
        feedRefreshPanel.setOrientation(LinearLayout.VERTICAL);
        feedRefreshPanel.setPadding(dp(2), dp(3), dp(2), dp(8));
        feedRefreshPanel.setVisibility(View.GONE);
        TextView feedRefreshStatus = text("", 12, TEAL, false);
        feedRefreshStatus.setSingleLine(false);
        feedRefreshPanel.addView(feedRefreshStatus, new LinearLayout.LayoutParams(-1, -2));
        ProgressBar feedRefreshProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        feedRefreshProgress.setMax(100);
        feedRefreshProgress.setProgress(0);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(4));
        progressParams.topMargin = dp(5);
        feedRefreshPanel.addView(feedRefreshProgress, progressParams);
        feedRefreshPanels.put(screenKey, feedRefreshPanel);
        feedRefreshStatusViews.put(screenKey, feedRefreshStatus);
        feedRefreshProgressViews.put(screenKey, feedRefreshProgress);
        actions.addView(feedRefreshPanel, new LinearLayout.LayoutParams(-1, -2));
        list.addView(actions, 0, new LinearLayout.LayoutParams(-1, -2));
    }

    private String[] feedPaths(String group) {
        return "collections".equals(group) ? COLLECTION_PATHS : HOME_PATHS;
    }

    private String[] feedTitles(String group) {
        return "collections".equals(group) ? COLLECTION_TITLES : HOME_TITLES;
    }

    /** Refresh through the already-cleared WebView session so Cloudflare JavaScript runs. */
    private void refreshFeedInBrowser(String group, int index, String path, String title,
            boolean bulk, int total) {
        appendFeedDiagnostic("REFRESH_REQUEST", "mode=" + (bulk ? "bulk-item" : "single")
                + ", group=" + group + ", path=" + path + ", title=" + title
                + ", modelSections=" + ("collections".equals(group)
                    ? collectionsSectionsMemory.size() : homeSectionsMemory.size()));
        if (sessionWebView == null) {
            status.setText("Браузерная сессия ещё не готова. Открой Kinozal и повтори обновление.");
            return;
        }
        if (browserFeedLoading || pendingBrowserCallback != null) {
            status.setText("Сейчас выполняется другой запрос к Kinozal. Повтори обновление чуть позже.");
            return;
        }
        Intent running = new Intent();
        running.putExtra("feed_group", group);
        running.putExtra("feed_path", path);
        running.putExtra("feed_title", title);
        running.putExtra("feed_index", index);
        running.putExtra("feed_total", total);
        running.putExtra("feed_bulk", bulk);
        running.putExtra("refresh_state", "running");
        handleFeedRefreshState(running);

        fetchBrowserPage(path, null, html -> {
            if (html == null || html.trim().isEmpty()) {
                Intent failed = new Intent();
                failed.putExtra("feed_group", group);
                failed.putExtra("feed_path", path);
                failed.putExtra("feed_title", title);
                failed.putExtra("feed_index", index);
                failed.putExtra("feed_total", total);
                failed.putExtra("feed_bulk", bulk);
                failed.putExtra("refresh_state", "failure");
                failed.putExtra("refresh_error", lastBrowserError == null
                        ? "WebView не получил ответ Kinozal" : lastBrowserError);
                handleFeedRefreshState(failed);
                return;
            }
            executeSafely(io, () -> {
                HomeSection refreshed = new HomeSection(title, path);
                String cloudflareText = Jsoup.parse(html).text().toLowerCase(java.util.Locale.ROOT);
                String failure = null;
                if (cloudflareText.contains("just a moment")
                        || cloudflareText.contains("enable javascript and cookies")
                        || cloudflareText.contains("checking your browser")
                        || cloudflareText.contains("attention required")) {
                    failure = "Cloudflare ещё не завершил проверку. Открой сайт в браузере и повтори попытку.";
                } else {
                    try {
                        refreshed.items.addAll(parseHomePosters(Jsoup.parse(html, client.getMirror() + "/")));
                        // Keep single-feed refresh consistent with initial and bulk loading:
                        // collapse alternate encodes of the same movie before saving/rendering.
                        List<HomeSection> singleFeed = new ArrayList<>();
                        singleFeed.add(refreshed);
                        removeHomeDuplicates(singleFeed);
                    } catch (Exception e) {
                        failure = "Не удалось разобрать ленту: " + e.getMessage();
                    }
                    if (failure == null && refreshed.items.isEmpty()) {
                        failure = "В ответе нет раздач; прежний кэш сохранён.";
                    }
                }
                final String error = failure;
                runOnUiThread(() -> {
                    if (error != null) {
                        Intent failed = new Intent();
                        failed.putExtra("feed_group", group);
                        failed.putExtra("feed_path", path);
                        failed.putExtra("feed_title", title);
                        failed.putExtra("feed_index", index);
                        failed.putExtra("feed_total", total);
                        failed.putExtra("feed_bulk", bulk);
                        failed.putExtra("refresh_state", "failure");
                        failed.putExtra("refresh_error", error);
                        handleFeedRefreshState(failed);
                        return;
                    }
                    List<HomeSection> model = "collections".equals(group)
                            ? new ArrayList<>(collectionsSectionsMemory)
                            : new ArrayList<>(homeSectionsMemory);
                    int oldCardCount = 0;
                    for (HomeSection old : model) {
                        if (path.equals(old.path)) { oldCardCount = old.items.size(); break; }
                    }
                    appendFeedDiagnostic("REFRESH_MODEL_BEFORE",
                            "mode=" + (bulk ? "bulk-item" : "single") + ", path=" + path
                            + ", oldCards=" + oldCardCount + ", incomingCards=" + refreshed.items.size()
                            + ", modelSections=" + model.size());
                    int existing = -1;
                    for (int i = 0; i < model.size(); i++) {
                        if (path.equals(model.get(i).path)) { existing = i; break; }
                    }
                    if (existing >= 0) model.set(existing, refreshed);
                    else model.add(refreshed);
                    appendFeedDiagnostic("REFRESH_MODEL_AFTER",
                            "path=" + path + ", action=" + (existing >= 0 ? "replace" : "append")
                            + ", replacedIndex=" + existing + ", newCards=" + refreshed.items.size()
                            + ", modelSections=" + model.size());
                    if ("collections".equals(group)) {
                        collectionsSectionsMemory = model;
                        saveCollectionsModel(model);
                    } else {
                        homeSectionsMemory = model;
                        saveHomeModel(model);
                    }
                    if (group.equals(activeFeedScreen)) {
                        renderHomeSection(refreshed, homeRenderGeneration, true);
                    }
                    Intent success = new Intent();
                    success.putExtra("feed_group", group);
                    success.putExtra("feed_path", path);
                    success.putExtra("feed_title", title);
                    success.putExtra("feed_index", index);
                    success.putExtra("feed_total", total);
                    success.putExtra("feed_bulk", bulk);
                    success.putExtra("refresh_state", "success");
                    handleFeedRefreshState(success);
                });
            });
        });
    }

    private boolean bulkFeedRefreshInProgress = false;

    /**
     * Refresh the first page in the authenticated WebView, then fetch every
     * remaining feed concurrently from that same origin/session. The shared
     * WebView is navigated only once; no five-minute per-feed queue.
     */
    private void refreshAllFeedsInBrowser(String group) {
        String[] paths = feedPaths(group);
        String[] titles = feedTitles(group);
        if (bulkFeedRefreshInProgress) {
            status.setText("Обновление всех лент уже выполняется.");
            return;
        }
        if (sessionWebView == null || browserFeedLoading || pendingBrowserCallback != null) {
            status.setText("Сейчас занят браузер Kinozal. Повтори обновление чуть позже.");
            return;
        }
        bulkFeedRefreshInProgress = true;
        showFeedRefreshQueued(group, "Все ленты", paths.length, true);
        TextView label = feedRefreshStatusViews.get(group);
        ProgressBar bar = feedRefreshProgressViews.get(group);
        if (label != null) label.setText("Одновременное обновление " + paths.length + " лент…");
        if (bar != null) bar.setIndeterminate(true);

        for (int i = 0; i < paths.length; i++) {
            Intent running = new Intent();
            running.putExtra("feed_group", group);
            running.putExtra("feed_path", paths[i]);
            running.putExtra("feed_title", titles[i]);
            running.putExtra("feed_index", i);
            running.putExtra("feed_total", paths.length);
            running.putExtra("feed_bulk", true);
            running.putExtra("refresh_state", "running");
            handleFeedRefreshState(running);
        }
        if (label != null) label.setText("Одновременно загружаются все " + paths.length + " лент…");
        if (bar != null) bar.setIndeterminate(true);

        fetchBrowserPage(paths[0], null, html -> {
            if (isFinishing()) {
                bulkFeedRefreshInProgress = false;
                return;
            }
            if (html == null || html.trim().isEmpty() || sessionWebView == null) {
                finishBulkFeedRefresh(group, paths, titles, null,
                        lastBrowserError == null ? "Не удалось открыть первую ленту" : lastBrowserError);
                return;
            }

            org.json.JSONArray requests = new org.json.JSONArray();
            try {
                for (int i = 1; i < paths.length; i++) {
                    org.json.JSONObject request = new org.json.JSONObject();
                    request.put("path", paths[i]);
                    request.put("title", titles[i]);
                    request.put("index", i);
                    requests.put(request);
                }
            } catch (Exception e) {
                finishBulkFeedRefresh(group, paths, titles, null, safe(e.getMessage()));
                return;
            }

            String script = buildHomeParallelExtractScript(requests.toString(), 0,
                    org.json.JSONObject.quote(html));
            evaluateHomeParallelBatch(sessionWebView, script, value -> {
                List<HomeSection> refreshed = new ArrayList<>();
                String failure = null;
                try {
                    if (value == null || "null".equals(value)) {
                        throw new java.io.IOException("Истекло время ожидания ответов лент");
                    }
                    String json = new org.json.JSONArray("[" + value + "]").getString(0);
                    org.json.JSONArray results = new org.json.JSONArray(json);
                    boolean[] received = new boolean[paths.length];
                    for (int i = 0; i < results.length(); i++) {
                        org.json.JSONObject result = results.optJSONObject(i);
                        if (result == null || !result.optBoolean("ok", false)) continue;
                        int index = result.optInt("index", -1);
                        if (index < 0 || index >= paths.length || received[index]) continue;
                        received[index] = true;
                        HomeSection section = new HomeSection(titles[index], paths[index]);
                        section.items.addAll(parseHomePostersFromJsonArray(result.optJSONArray("items")));
                        if (!section.items.isEmpty()) {
                            List<HomeSection> oneFeed = new ArrayList<>();
                            oneFeed.add(section);
                            removeHomeDuplicates(oneFeed);
                            if (!section.items.isEmpty()) {
                                refreshed.add(section);
                                String pageHtml = result.optString("html", "");
                                if (!pageHtml.isEmpty()) {
                                    diskCache.put("home|" + client.getMirror() + "|" + paths[index],
                                            pageHtml.getBytes(StandardCharsets.UTF_8));
                                }
                            }
                        }
                    }
                    if (refreshed.isEmpty()) {
                        throw new java.io.IOException("Не получено ни одной ленты");
                    }
                    appendFeedDiagnostic("BULK_REFRESH_PARALLEL",
                            "group=" + group + ", received=" + refreshed.size() + "/" + paths.length);
                } catch (Exception e) {
                    failure = safe(e.getMessage());
                }
                finishBulkFeedRefresh(group, paths, titles, refreshed, failure);
            });
        });
    }

    private void finishBulkFeedRefresh(String group, String[] paths, String[] titles,
            List<HomeSection> refreshed, String failure) {
        bulkFeedRefreshInProgress = false;
        if (isFinishing()) return;
        if (refreshed == null || refreshed.isEmpty()) {
            for (int i = 0; i < paths.length; i++) {
                Intent failed = new Intent();
                failed.putExtra("feed_group", group);
                failed.putExtra("feed_path", paths[i]);
                failed.putExtra("feed_title", titles[i]);
                failed.putExtra("feed_index", i);
                failed.putExtra("feed_total", paths.length);
                failed.putExtra("feed_bulk", true);
                failed.putExtra("refresh_state", "failure");
                failed.putExtra("refresh_error", failure == null ? "Нет данных от Kinozal" : failure);
                handleFeedRefreshState(failed);
            }
            return;
        }

        List<HomeSection> model = "collections".equals(group)
                ? new ArrayList<>(collectionsSectionsMemory)
                : new ArrayList<>(homeSectionsMemory);
        appendFeedDiagnostic("BULK_MODEL_BEFORE",
                "group=" + group + ", modelSections=" + model.size()
                + ", successfulFeeds=" + refreshed.size() + "/" + paths.length);
        int successCount = 0;
        for (HomeSection section : refreshed) {
            int existing = -1;
            for (int i = 0; i < model.size(); i++) {
                if (section.path.equals(model.get(i).path)) { existing = i; break; }
            }
            int oldCards = existing >= 0 ? model.get(existing).items.size() : 0;
            if (existing >= 0) model.set(existing, section);
            else model.add(section);
            appendFeedDiagnostic("BULK_MODEL_ITEM",
                    "path=" + section.path + ", action=" + (existing >= 0 ? "replace" : "append")
                    + ", oldCards=" + oldCards + ", incomingCards=" + section.items.size());
            successCount++;
        }
        appendFeedDiagnostic("BULK_MODEL_AFTER",
                "group=" + group + ", modelSections=" + model.size()
                + ", successfulFeeds=" + successCount);
        if ("collections".equals(group)) {
            collectionsSectionsMemory = model;
            saveCollectionsModel(model);
        } else {
            homeSectionsMemory = model;
            saveHomeModel(model);
        }

        for (int i = 0; i < paths.length; i++) {
            HomeSection updated = null;
            for (HomeSection section : refreshed) if (paths[i].equals(section.path)) { updated = section; break; }
            Intent result = new Intent();
            result.putExtra("feed_group", group);
            result.putExtra("feed_path", paths[i]);
            result.putExtra("feed_title", titles[i]);
            result.putExtra("feed_index", i);
            result.putExtra("feed_total", paths.length);
            result.putExtra("feed_bulk", true);
            result.putExtra("refresh_state", updated == null ? "failure" : "success");
            if (updated == null) result.putExtra("refresh_error", "Эта лента не вернула раздачи; прежний кэш сохранён.");
            handleFeedRefreshState(result);
            if (updated != null && group.equals(activeFeedScreen)) {
                renderHomeSection(updated, homeRenderGeneration, true);
            }
        }

        TextView label = feedRefreshStatusViews.get(group);
        ProgressBar bar = feedRefreshProgressViews.get(group);
        if (label != null) {
            label.setText(successCount == paths.length
                    ? "Все " + paths.length + " лент обновлены одновременно"
                    : "Обновлено " + successCount + " из " + paths.length + " лент");
        }
        if (bar != null) {
            bar.setIndeterminate(false);
            bar.setMax(paths.length);
            bar.setProgress(successCount);
        }
        status.setText("Обновлено лент: " + successCount + " из " + paths.length);

        // Hide the completed bulk-refresh message automatically, but only if
        // it has not been replaced by a newer refresh status in the meantime.
        if (label != null) {
            final String completedMessage = label.getText().toString();
            browserHandler.postDelayed(() -> {
                TextView currentLabel = feedRefreshStatusViews.get(group);
                LinearLayout currentPanel = feedRefreshPanels.get(group);
                if (!bulkFeedRefreshInProgress && currentLabel != null
                        && currentPanel != null
                        && completedMessage.contentEquals(currentLabel.getText())) {
                    currentPanel.setVisibility(View.GONE);
                }
            }, 3500L);
        }
    }

    private String feedButtonKey(String group, String path) { return group + "|" + path; }

    private void showFeedRefreshQueued(String group, String title, int total, boolean bulk) {
        LinearLayout panel = feedRefreshPanels.get(group);
        TextView label = feedRefreshStatusViews.get(group);
        ProgressBar bar = feedRefreshProgressViews.get(group);
        if (panel == null || label == null || bar == null) return;
        panel.setVisibility(View.VISIBLE);
        label.setText(bulk
                ? "В очереди " + total + " лент. Ожидаем запуск первой…"
                : "Лента «" + title + "» поставлена в очередь…");
        bar.setIndeterminate(true);
    }

    private void stopFeedRefreshAnimation(String key) {
        ObjectAnimator animator = feedRefreshAnimations.remove(key);
        if (animator != null) {
            animator.cancel();
            Button target = feedRefreshButtons.get(key);
            if (target != null) target.setRotation(0f);
        }
    }

    private void startFeedRefreshAnimation(String key) {
        Button target = feedRefreshButtons.get(key);
        if (target == null || feedRefreshAnimations.containsKey(key)) return;
        ObjectAnimator animator = ObjectAnimator.ofFloat(target, View.ROTATION, 0f, 360f);
        animator.setDuration(900L);
        animator.setRepeatCount(ObjectAnimator.INFINITE);
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        feedRefreshAnimations.put(key, animator);
        animator.start();
    }

    private void handleFeedRefreshState(Intent intent) {
        String group = intent.getStringExtra("feed_group");
        String path = intent.getStringExtra("feed_path");
        String title = intent.getStringExtra("feed_title");
        String state = intent.getStringExtra("refresh_state");
        if (group == null || path == null || state == null) return;
        String key = feedButtonKey(group, path);
        boolean visibleGroup = group.equals(activeFeedScreen);
        int index = intent.getIntExtra("feed_index", 0);
        int total = Math.max(1, intent.getIntExtra("feed_total", 1));
        boolean bulk = intent.getBooleanExtra("feed_bulk", false);
        LinearLayout panel = feedRefreshPanels.get(group);
        TextView label = feedRefreshStatusViews.get(group);
        ProgressBar bar = feedRefreshProgressViews.get(group);
        if ("running".equals(state)) {
            if (visibleGroup && panel != null && label != null && bar != null) {
                panel.setVisibility(View.VISIBLE);
                label.setText(bulk
                        ? "Обновление лент: " + (index + 1) + " из " + total + " — " + title
                        : "Обновляется лента: " + title);
                bar.setIndeterminate(false);
                bar.setMax(total);
                bar.setProgress(Math.min(total, index + 1));
            }
            if (visibleGroup) startFeedRefreshAnimation(key);
        } else if ("success".equals(state)) {
            stopFeedRefreshAnimation(key);
            if (visibleGroup && panel != null && label != null && bar != null) {
                panel.setVisibility(View.VISIBLE);
                bar.setIndeterminate(false);
                bar.setMax(total);
                bar.setProgress(Math.min(total, index + 1));
                if (bulk && index + 1 >= total) {
                    label.setText("Все " + total + " лент обновлены");
                } else if (bulk) {
                    label.setText("Готово " + (index + 1) + " из " + total
                            + ". Следующая лента будет обновляться по очереди");
                } else {
                    label.setText("Лента обновлена: " + title);
                    final String completedMessage = label.getText().toString();
                    browserHandler.postDelayed(() -> {
                        TextView currentLabel = feedRefreshStatusViews.get(group);
                        LinearLayout currentPanel = feedRefreshPanels.get(group);
                        if (!bulkFeedRefreshInProgress && currentLabel != null
                                && currentPanel != null
                                && completedMessage.contentEquals(currentLabel.getText())) {
                            currentPanel.setVisibility(View.GONE);
                        }
                    }, 3500L);
                }
            }
        } else if ("failure".equals(state)) {
            stopFeedRefreshAnimation(key);
            if (visibleGroup && panel != null && label != null && bar != null) {
                panel.setVisibility(View.VISIBLE);
                bar.setIndeterminate(false);
                bar.setProgress(0);
                label.setText("Ошибка обновления «" + title + "»: "
                        + intent.getStringExtra("refresh_error"));
            }
        }
    }

    private int homePreparationGeneration = 0;

    private void renderHome(List<HomeSection> sections) {
        if (list == null || isFinishing()) return;
        final int preparation = ++homePreparationGeneration;
        final List<HomeSection> snapshot = new ArrayList<>(sections);
        // Regex matching and badge classification are pure data work. Do it on
        // the I/O executor so the first few cards do not stall DPAD handling.
        executeSafely(io, () -> {
            for (HomeSection section : snapshot) {
                if (section == null || section.items == null) continue;
                for (HomePoster poster : section.items) {
                    if (poster == null || poster.torrent == null) continue;
                    poster.preparedBadges = extractHomeBadges(
                            poster.torrent.title, section.title);
                }
            }
            runOnUiThread(() -> {
                if (preparation != homePreparationGeneration || isFinishing() || list == null) return;
                list.removeAllViews();
                ensureFeedActions();
                if (snapshot.isEmpty()) {
                    addEmpty("Подборки пока не загружены.");
                    return;
                }
                renderHomeSectionsIncrementally(snapshot);
            });
        });
    }

    /**
     * Build poster rows across separate UI frames instead of creating every
     * card in one long main-thread pass. This is especially important on
     * low-memory Android TV boxes.
     */
    private final java.util.ArrayDeque<Runnable> homeCardBuildQueue = new java.util.ArrayDeque<>();
    private boolean homeCardBuildScheduled = false;
    private boolean homeRowsEnqueued = false;

    private void renderHomeSectionsIncrementally(List<HomeSection> sections) {
        final int generation = ++homeRenderGeneration;
        homeCardBuildQueue.clear();
        homeCardBuildScheduled = false;
        homeRowsEnqueued = false;
        final List<HomeSection> snapshot = new ArrayList<>(sections);
        final int[] index = {0};
        Runnable[] next = new Runnable[1];
        next[0] = () -> {
            if (generation != homeRenderGeneration || isFinishing()) return;
            if (index[0] >= snapshot.size()) {
                homeRowsEnqueued = true;
                scheduleVisibleHomePosterLoad();
                scheduleNextHomeCardBuild(generation);
                return;
            }
            HomeSection section = snapshot.get(index[0]++);
            renderHomeSection(section, generation);
            browserHandler.postDelayed(next[0], 24);
        };
        browserHandler.post(next[0]);
    }

    private void scheduleNextHomeCardBuild(int generation) {
        if (generation != homeRenderGeneration || isFinishing() || homeCardBuildScheduled) return;
        homeCardBuildScheduled = true;
        browserHandler.post(() -> {
            homeCardBuildScheduled = false;
            if (generation != homeRenderGeneration || isFinishing()) return;
            Runnable task = homeCardBuildQueue.pollFirst();
            if (task == null) {
                if (homeRowsEnqueued) {
                    scheduleVisibleHomePosterLoad();
                    stopHomePerformanceMonitor();
                } else {
                    browserHandler.postDelayed(() -> scheduleNextHomeCardBuild(generation), 24);
                }
                return;
            }
            task.run();
            // Yield several display frames between card constructions. The
            // handler runs on the UI thread; an 8 ms cadence starves DPAD and
            // click handling on slower Android TV hardware while rows are built.
            if (!homeCardBuildQueue.isEmpty()) {
                browserHandler.postDelayed(() -> scheduleNextHomeCardBuild(generation), 48L);
            } else {
                browserHandler.postDelayed(() -> scheduleNextHomeCardBuild(generation), 48L);
            }
        });
    }

    /** Append a feed shell immediately, then construct poster cards over separate UI frames. */
    private void renderHomeSection(HomeSection section) {
        renderHomeSection(section, homeRenderGeneration, false);
    }

    private void renderHomeSection(HomeSection section, int generation) {
        renderHomeSection(section, generation, false);
    }

    /** Build a refreshed row independently of the shared initial-screen card queue. */
    private void renderHomeSection(HomeSection section, int generation, boolean immediate) {
        if (section == null || section.items.isEmpty()) return;

        int oldRowCards = 0;
        String expectedTitleTag = "home-title:" + section.path;
        for (int i = 0; i + 1 < list.getChildCount(); i++) {
            if (expectedTitleTag.equals(list.getChildAt(i).getTag())
                    && list.getChildAt(i + 1) instanceof android.widget.HorizontalScrollView) {
                View oldRow = list.getChildAt(i + 1);
                if (oldRow instanceof android.widget.HorizontalScrollView) {
                    View oldStrip = ((android.widget.HorizontalScrollView) oldRow).getChildAt(0);
                    if (oldStrip instanceof ViewGroup) oldRowCards = ((ViewGroup) oldStrip).getChildCount();
                }
                break;
            }
        }
        appendFeedDiagnostic("FEED_UI_APPLY_START",
                "path=" + section.path + ", title=" + section.title
                + ", mode=" + (immediate ? "refresh-immediate" : "initial-incremental")
                + ", oldRenderedCards=" + oldRowCards + ", incomingCards=" + section.items.size()
                + ", listChildrenBefore=" + list.getChildCount());

        LinearLayout sectionHeader = new LinearLayout(this);
        sectionHeader.setGravity(Gravity.CENTER_VERTICAL);
        sectionHeader.setTag("home-title:" + section.path);
        final String feedGroup = getFeedIndex("collections", section.path) >= 0
                ? "collections" : "home";
        Button refreshFeed = button("⟳");
        // Icon-only refresh control: no button plate, only the glyph changes color on focus.
        refreshFeed.setTextSize(fontSize(20));
        refreshFeed.setTextColor(Color.rgb(192, 198, 202)); // Серебристый
        refreshFeed.setMinWidth(0);
        refreshFeed.setMinimumWidth(0);
        refreshFeed.setMinHeight(0);
        refreshFeed.setMinimumHeight(0);
        refreshFeed.setGravity(Gravity.CENTER);
        refreshFeed.setIncludeFontPadding(false);
        refreshFeed.setPadding(0, 0, 0, 0);
        refreshFeed.setBackgroundColor(Color.TRANSPARENT);
        refreshFeed.setTranslationY(-dp(4));
        refreshFeed.setOnFocusChangeListener((v, focused) -> {
            refreshFeed.setTextColor(focused ? GOLD : Color.rgb(192, 198, 202));
            refreshFeed.setBackgroundColor(Color.TRANSPARENT);
        });
        refreshFeed.setContentDescription("Обновить ленту " + section.title);
        refreshFeed.setOnClickListener(v -> {
            int feedIndex = getFeedIndex(feedGroup, section.path);
            if (feedIndex < 0) {
                status.setText("Не найдена лента для обновления: " + section.title);
                return;
            }
            refreshFeedInBrowser(feedGroup, feedIndex, section.path, section.title, false, 1);
        });
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        refreshParams.rightMargin = dp(10);
        sectionHeader.addView(refreshFeed, refreshParams);
        String refreshButtonKey = feedButtonKey(feedGroup, section.path);
        stopFeedRefreshAnimation(refreshButtonKey);
        feedRefreshButtons.put(refreshButtonKey, refreshFeed);
        TextView sectionTitle = text(section.title, 20, GOLD, true);
        sectionHeader.addView(sectionTitle, new LinearLayout.LayoutParams(0, dp(38), 1f));
        String[] firstFeedPaths = feedPaths(feedGroup);
        if (firstFeedPaths.length > 0 && firstFeedPaths[0].equals(section.path)) {
            Button refreshAll = button("Обновить всё");
            refreshAll.setTextSize(fontSize(12));
            refreshAll.setTranslationY(dp(3));
            LinearLayout.LayoutParams allParams = new LinearLayout.LayoutParams(-2, dp(36));
            allParams.leftMargin = dp(8);
            sectionHeader.addView(refreshAll, allParams);
            refreshAll.setOnClickListener(v -> refreshAllFeedsInBrowser(feedGroup));
        }
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, dp(38));
        titleParams.topMargin = dp(10);

        android.widget.HorizontalScrollView horizontal = new android.widget.HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        horizontal.setFillViewport(false);
        horizontal.setTag("home-row:" + section.path);
        horizontal.setOnScrollChangeListener((View v, int x, int y, int oldX, int oldY) -> scheduleVisibleHomePosterLoad());
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        horizontal.addView(strip);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(260));

        // Replace only this feed's existing views, preserving other rows and DPAD focus.
        String titleTag = "home-title:" + section.path;
        boolean replaced = false;
        for (int i = 0; i + 1 < list.getChildCount(); i++) {
            if (titleTag.equals(list.getChildAt(i).getTag())) {
                list.removeViewAt(i + 1);
                list.removeViewAt(i);
                list.addView(sectionHeader, i, titleParams);
                list.addView(horizontal, i + 1, rowParams);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            list.addView(sectionHeader, titleParams);
            list.addView(horizontal, rowParams);
        }
        horizontal.post(this::scheduleVisibleHomePosterLoad);

        final boolean rowWasReplaced = replaced;
        if (immediate) {
            // Replace and build only this feed. Other rows and their pending work remain intact.
            for (HomePoster item : section.items) {
                strip.addView(createHomePosterCard(item, section));
            }
            appendFeedDiagnostic("FEED_UI_APPLY_DONE",
                    "path=" + section.path + ", action=" + (replaced ? "replace-row" : "append-row")
                    + ", oldRenderedCards=" + oldRowCards + ", renderedCards=" + strip.getChildCount()
                    + ", incomingCards=" + section.items.size()
                    + ", listChildrenAfter=" + list.getChildCount());
            horizontal.post(this::scheduleVisibleHomePosterLoad);
        } else {
            final long[] startedAt = {0L};
            final int[] cardIndex = {0};
            final int cardCount = section.items.size();
            for (HomePoster item : section.items) {
                homeCardBuildQueue.addLast(() -> {
                    if (generation != homeRenderGeneration || isFinishing()) return;
                    long cardStartedAt = android.os.SystemClock.elapsedRealtime();
                    if (startedAt[0] == 0L) startedAt[0] = cardStartedAt;
                    strip.addView(createHomePosterCard(item, section));
                    long cardDuration = android.os.SystemClock.elapsedRealtime() - cardStartedAt;
                    if (cardDuration >= 32L) {
                        Log.w("KinozalPerf", "home card build=" + cardDuration
                                + "ms, title=" + section.title);
                    }
                    cardIndex[0]++;
                    if (cardIndex[0] == cardCount) {
                        appendFeedDiagnostic("FEED_UI_APPLY_DONE",
                                "path=" + section.path + ", action=" + (rowWasReplaced ? "replace-row" : "append-row")
                                + ", mode=initial-incremental, renderedCards=" + strip.getChildCount()
                                + ", incomingCards=" + cardCount);
                        long duration = android.os.SystemClock.elapsedRealtime() - startedAt[0];
                        Log.i("KinozalPerf", "home row complete=" + duration
                                + "ms, title=" + section.title + ", cards=" + cardCount);
                        scheduleVisibleHomePosterLoad();
                    }
                });
            }
            scheduleNextHomeCardBuild(generation);
        }
    }

    private int getFeedIndex(String group, String path) {
        String[] paths = "collections".equals(group) ? COLLECTION_PATHS : HOME_PATHS;
        for (int i = 0; i < paths.length; i++) if (paths[i].equals(path)) return i;
        return -1;
    }

    private View createHomePosterCard(HomePoster item, HomeSection section) {
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

        FrameLayout posterFrame = new FrameLayout(this);
        posterFrame.setBackground(shape(Color.rgb(40, 45, 49), 0));
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(shape(Color.rgb(40, 45, 49), 0));
        poster.setImageResource(R.drawable.kinozal_poster_placeholder);
        posterFrame.addView(poster, new FrameLayout.LayoutParams(-1, -1));
        card.addView(posterFrame, new LinearLayout.LayoutParams(dp(126), dp(178)));

        List<String> badges = item.preparedBadges != null
                ? item.preparedBadges : extractHomeBadges(item.torrent.title, section.title);
        LinearLayout badgeRow = new LinearLayout(this);
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.setGravity(Gravity.CENTER_VERTICAL);
        badgeRow.setPadding(0, dp(2), 0, dp(2));
        for (String badge : badges) {
            TextView chip = text(badge, 10, GOLD, true);
            chip.setSingleLine(true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(8), dp(2), dp(8), dp(2));
            android.graphics.drawable.GradientDrawable chipBackground =
                    new android.graphics.drawable.GradientDrawable();
            chipBackground.setColor(Color.rgb(39, 57, 61));
            chipBackground.setCornerRadius(dp(4));
            chipBackground.setStroke(dp(1), TEAL);
            chip.setBackground(chipBackground);
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, dp(20));
            chipParams.rightMargin = dp(3);
            badgeRow.addView(chip, chipParams);
        }
        badgeRow.setTranslationY(dp(3));
        card.addView(badgeRow, new LinearLayout.LayoutParams(dp(126), dp(25)));

        TextView name = text(item.torrent.title, 12, Color.WHITE, true);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(dp(2), dp(6), dp(2), dp(2));
        card.addView(name, new LinearLayout.LayoutParams(dp(126), dp(42)));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(138), -2);
        cardParams.rightMargin = dp(8);
        card.setTag("home-card");
        if (!item.imageUrl.isEmpty()) {
            poster.setTag(new HomeImageRequest(item.imageUrl, client.getMirror() + "/" + section.path));
        }
        card.setOnClickListener(v -> showTorrent(item.torrent));
        card.setLayoutParams(cardParams);
        return card;
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
        ImageView poster;
        if (first instanceof ImageView) poster = (ImageView) first;
        else if (first instanceof ViewGroup && ((ViewGroup) first).getChildCount() > 0
                && ((ViewGroup) first).getChildAt(0) instanceof ImageView) {
            poster = (ImageView) ((ViewGroup) first).getChildAt(0);
        } else return;
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
        TextView t = new TextView(this); t.setText(value); t.setTextSize(fontSize(size));
        t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(GOLD);
        b.setTextSize(fontSize(14));
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
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        brand.setPadding(dp(10), 0, dp(4), 0);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.kinozal_sidebar_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        logo.setAdjustViewBounds(true);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(158), dp(58)));
        sidebar.addView(brand, new LinearLayout.LayoutParams(-1, dp(58)));
        ScrollView menuScroll = new ScrollView(this);
        menuScroll.setFillViewport(false);
        menuScroll.setVerticalScrollBarEnabled(false);
        menuScroll.setScrollbarFadingEnabled(true);
        menuScroll.setPadding(0, dp(5), 0, 0);
        menuItems = new LinearLayout(this);
        menuItems.setOrientation(LinearLayout.VERTICAL);
        menuScroll.addView(menuItems, new ScrollView.LayoutParams(-1, -2));
        sidebar.addView(menuScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        addMenu("⌂", "Главная", () -> loadInitialCatalog());
        addMenu("▤", "Подборки", () -> loadCollections());
        addMenu("⇩", "Все раздачи", () -> load("browse.php", "Все раздачи"));

        Button categoriesButton = button("▦   Категории  ▾");
        categoriesButton.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        categoriesButton.setTextSize(fontSize(14));
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

        Button moviesButton = button("▤  Фильмы  ▾");
        moviesButton.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        moviesButton.setTextSize(fontSize(12));
        moviesButton.setPadding(dp(34), 0, dp(8), 0);
        LinearLayout.LayoutParams moviesButtonParams = new LinearLayout.LayoutParams(-1, dp(39));
        moviesButtonParams.rightMargin = dp(5);
        moviesButtonParams.bottomMargin = dp(2);
        categoryGroup.addView(moviesButton, moviesButtonParams);

        LinearLayout moviesGroup = new LinearLayout(this);
        moviesGroup.setOrientation(LinearLayout.VERTICAL);
        moviesGroup.setVisibility(View.GONE);
        categoryGroup.addView(moviesGroup);

        addSubMenu(moviesGroup, "▣  Все фильмы", "browse.php?c=1002");
        addSubMenu(moviesGroup, "●  Кино - Комедия", "browse.php?c=8");
        addSubMenu(moviesGroup, "⚑  Кино - Боевик / Военный", "browse.php?c=6");
        addSubMenu(moviesGroup, "◈  Кино - Триллер / Детектив", "browse.php?c=15");
        addSubMenu(moviesGroup, "▰  Кино - Драма", "browse.php?c=17");
        addSubMenu(moviesGroup, "♥  Кино - Мелодрама", "browse.php?c=35");
        addSubMenu(moviesGroup, "✿  Кино - Индийское", "browse.php?c=39");
        addSubMenu(moviesGroup, "✧  Кино - Фантастика", "browse.php?c=13");
        addSubMenu(moviesGroup, "♢  Кино - Фэнтези", "browse.php?c=14");
        addSubMenu(moviesGroup, "☾  Кино - Ужас / Мистика", "browse.php?c=24");
        addSubMenu(moviesGroup, "➤  Кино - Приключения", "browse.php?c=11");
        addSubMenu(moviesGroup, "⌂  Кино - Наше Кино", "browse.php?c=10");
        addSubMenu(moviesGroup, "◷  Кино - Исторический", "browse.php?c=9");
        addSubMenu(moviesGroup, "✿  Кино - Азиатский", "browse.php?c=47");
        addSubMenu(moviesGroup, "▤  Кино - Документальный", "browse.php?c=18");
        addSubMenu(moviesGroup, "⚽  Кино - Спорт", "browse.php?c=37");
        addSubMenu(moviesGroup, "★  Кино - Детский / Семейный", "browse.php?c=12");
        addSubMenu(moviesGroup, "▥  Кино - Классика", "browse.php?c=7");
        addSubMenu(moviesGroup, "♫  Кино - Концерт", "browse.php?c=48");
        addSubMenu(moviesGroup, "▣  Кино - Передачи / ТВ-шоу", "browse.php?c=49");
        addSubMenu(moviesGroup, "▣  Кино - ТВ-шоу Мир", "browse.php?c=50");
        addSubMenu(moviesGroup, "♬  Кино - Театр, Опера, Балет", "browse.php?c=38");
        addSubMenu(moviesGroup, "♥  Кино - Эротика", "browse.php?c=16");

        moviesButton.setOnClickListener(v -> {
            boolean expand = moviesGroup.getVisibility() != View.VISIBLE;
            moviesGroup.setVisibility(expand ? View.VISIBLE : View.GONE);
            moviesButton.setText(expand ? "▤  Фильмы  ▾" : "▤  Фильмы  ▸");
            if (expand && moviesGroup.getChildCount() > 0) moviesGroup.getChildAt(0).requestFocus();
        });
        addSubMenu(categoryGroup, "▧  Мультфильмы", "browse.php?c=1003");
        addSubMenu(categoryGroup, "▧  Российские мульты", "browse.php?c=22");
        addSubMenu(categoryGroup, "▧  Зарубежные мульты", "browse.php?c=21");
        addSubMenu(categoryGroup, "✧  Аниме", "browse.php?c=20");
        categoriesButton.setOnClickListener(v -> {
            boolean expand = categoryGroup.getVisibility() != View.VISIBLE;
            categoryGroup.setVisibility(expand ? View.VISIBLE : View.GONE);
            categoriesButton.setText(expand ? "Категории  ▾" : "Категории  ▸");
            if (expand) categoryGroup.getChildAt(0).requestFocus();
        });

        addMenu("★", "Избранное", () -> showFavorites());
        addMenu("◷", "Недавно смотрели", () -> showRecentlyViewed());
        addMenu("⚙", "Настройки", () -> showSettings());
        // Keep status available for internal progress updates, but do not show
        // diagnostic counters or connection state in the sidebar.
        status = text("Показано лент: 0", 10, TEAL, false);
        status.setVisibility(View.GONE);
        sidebarConnectionInfo = null;
        sidebarCategoryInfo = null;
        sidebarCountryInfo = text("Страна подключения: определяется…", 10, TEAL, false);
        sidebarLoginInfo = text("Вход: не проверен", 11, TEAL, true);
        sidebarVersionInfo = text("Версия: " + getAppVersionName(), 10, GOLD, false);
        TextView[] diagnosticLines = {sidebarCountryInfo, sidebarLoginInfo, sidebarVersionInfo};
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
        search.setTextSize(fontSize(16));
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
                float cx = getWidth() / 2f;
                canvas.save();
                canvas.translate(0, -dp(2));
                canvas.scale(0.72f, 0.72f, cx, getHeight() / 2f);
                float top = getHeight() / 2f - dp(10);
                float bottom = getHeight() / 2f + dp(2);
                float micWidth = dp(6);
                canvas.drawRoundRect(cx - micWidth, top, cx + micWidth, bottom,
                        micWidth, micWidth, iconPaint);
                canvas.drawArc(cx - dp(10), top + dp(3), cx + dp(10), bottom + dp(9),
                        0, 180, false, iconPaint);
                canvas.drawLine(cx, bottom + dp(9), cx, bottom + dp(14), iconPaint);
                canvas.drawLine(cx - dp(6), bottom + dp(14), cx + dp(6), bottom + dp(14), iconPaint);
                canvas.restore();
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
        clearSearch.setTextSize(fontSize(23));
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
        history.setTextSize(fontSize(12));
        history.setMinWidth(0);
        history.setMinimumWidth(0);
        history.setGravity(Gravity.CENTER);
        history.setIncludeFontPadding(false);
        LinearLayout.LayoutParams historyParams = new LinearLayout.LayoutParams(dp(92), dp(52));
        historyParams.rightMargin = dp(10);
        searchBar.addView(history, historyParams);
        history.setOnClickListener(v -> showSearchHistory());
        Button find = button("Найти");
        find.setTextSize(fontSize(12));
        find.setMinWidth(0);
        find.setMinimumWidth(0);
        find.setGravity(Gravity.CENTER);
        find.setIncludeFontPadding(false);
        LinearLayout.LayoutParams findParams = new LinearLayout.LayoutParams(dp(92), dp(52));
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
        homeList = list;
        homeScrollView.addView(homeList);
        main.addView(homeScrollView, new LinearLayout.LayoutParams(-1, 0, 1));
        feedScreenParent = main;
        activeFeedScrollView = homeScrollView;
        homeScrollView.setOnScrollChangeListener((View v, int x, int y, int oldX, int oldY) -> {
            if (y != oldY) scheduleVisibleHomePosterLoad();
        });
        setContentView(root);
        if (menuItems.getChildCount() > 0) menuItems.getChildAt(0).requestFocus();
    }

    /** Keep Home and Collections as two distinct live view trees. */
    /** Browse/category pages use their own temporary host and cannot erase feed screens. */
    private void activateBrowseScreen() {
        if (feedScreenParent == null) return;
        if (browseScrollView == null) {
            browseList = new LinearLayout(this);
            browseList.setOrientation(LinearLayout.VERTICAL);
            browseScrollView = new ScrollView(this);
            browseScrollView.setFillViewport(true);
            browseScrollView.setVerticalScrollBarEnabled(false);
            browseScrollView.addView(browseList);
        }
        if (activeFeedScrollView == browseScrollView) {
            list = browseList;
            activeFeedScreen = "browse";
            return;
        }
        View current = activeFeedScrollView;
        ViewGroup.LayoutParams params = current == null ? null : current.getLayoutParams();
        if (current != null && current.getParent() == feedScreenParent) feedScreenParent.removeView(current);
        if (params == null) params = new LinearLayout.LayoutParams(-1, 0, 1);
        if (browseScrollView.getParent() instanceof ViewGroup)
            ((ViewGroup) browseScrollView.getParent()).removeView(browseScrollView);
        feedScreenParent.addView(browseScrollView, params);
        activeFeedScrollView = browseScrollView;
        activeFeedScreen = "browse";
        list = browseList;
    }

    private void activateFeedScreen(String screen) {
        if (feedScreenParent == null || homeScrollView == null) return;
        boolean collections = "collections".equals(screen);
        if (collections && collectionsScrollView == null) {
            collectionsList = new LinearLayout(this);
            collectionsList.setOrientation(LinearLayout.VERTICAL);
            collectionsScrollView = new ScrollView(this);
            collectionsScrollView.setFillViewport(true);
            collectionsScrollView.setVerticalScrollBarEnabled(false);
            collectionsScrollView.addView(collectionsList);
            collectionsScrollView.setOnScrollChangeListener((View v, int x, int y, int oldX, int oldY) -> {
                if (y != oldY) scheduleVisibleHomePosterLoad();
            });
        }
        ScrollView target = collections ? collectionsScrollView : homeScrollView;
        if (target == null) return;
        View current = activeFeedScrollView;
        if (current == target) {
            list = collections ? collectionsList : homeList;
            activeFeedScreen = screen;
            return;
        }
        ViewGroup.LayoutParams params = current == null ? null : current.getLayoutParams();
        if (current != null && current.getParent() == feedScreenParent) feedScreenParent.removeView(current);
        if (params == null) params = new LinearLayout.LayoutParams(-1, 0, 1);
        if (target.getParent() instanceof ViewGroup) ((ViewGroup) target.getParent()).removeView(target);
        feedScreenParent.addView(target, params);
        list = collections ? collectionsList : homeList;
        activeFeedScrollView = target;
        activeFeedScreen = screen;
        scheduleVisibleHomePosterLoad();
    }

    private java.io.File collectionsModelFile() {
        return new java.io.File(getFilesDir(), "kinozal_collections_model_v1.json");
    }

    private void saveCollectionsModel(List<HomeSection> sections) {
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
            rootJson.put("version", 1);
            rootJson.put("mirror", client.getMirror());
            rootJson.put("savedAt", System.currentTimeMillis());
            rootJson.put("feeds", feeds);
            writeModelAtomically(collectionsModelFile(), rootJson.toString());
        } catch (Exception ignored) { }
    }

    private List<HomeSection> readCollectionsModel() {
        java.io.File file = collectionsModelFile();
        if (!file.isFile() || file.length() <= 0 || file.length() > 8L * 1024L * 1024L) return null;
        try {
            org.json.JSONObject rootJson = new org.json.JSONObject(new String(
                    java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (rootJson.optInt("version") != 1 || !client.getMirror().equals(rootJson.optString("mirror"))
                    || System.currentTimeMillis() - rootJson.optLong("savedAt") > 7L * 24 * 60 * 60 * 1000) return null;
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
            int beforeDedup = countHomeCards(result);
            removeHomeDuplicates(result);
            if (countHomeCards(result) != beforeDedup) saveCollectionsModel(result);
            return result;
        } catch (Exception ignored) { return null; }
    }

    private void writeModelAtomically(java.io.File target, String value) throws Exception {
        java.io.File temp = new java.io.File(target.getParentFile(), target.getName() + ".tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        java.io.File backup = new java.io.File(target.getParentFile(), target.getName() + ".bak");
        if (target.exists() && !target.renameTo(backup)) throw new java.io.IOException("Cannot backup model");
        if (!temp.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target);
            throw new java.io.IOException("Cannot commit model");
        }
        if (backup.exists()) backup.delete();
    }

    private String categoryIcon(String label) {
        String value = label == null ? "" : label.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("сериал")) return "▣";
        if (value.contains("все фильмы") || value.contains("наше кино")) return "▰";
        if (value.contains("комедия")) return "☻";
        if (value.contains("боевик") || value.contains("военный")) return "⚔";
        if (value.contains("триллер") || value.contains("детектив")) return "⌕";
        if (value.contains("драма")) return "◈";
        if (value.contains("мелодрама")) return "♡";
        if (value.contains("индий")) return "✿";
        if (value.contains("фантаст")) return "✧";
        if (value.contains("фэнтези")) return "✦";
        if (value.contains("ужас") || value.contains("мистик")) return "☾";
        if (value.contains("приключ")) return "➤";
        if (value.contains("истор")) return "⌛";
        if (value.contains("азиат")) return "❖";
        if (value.contains("документ")) return "▤";
        if (value.contains("спорт")) return "⚑";
        if (value.contains("детский") || value.contains("семейн")) return "☆";
        if (value.contains("классик")) return "▥";
        if (value.contains("концерт")) return "♫";
        if (value.contains("передач") || value.contains("тв-шоу")) return "▣";
        if (value.contains("театр") || value.contains("опера") || value.contains("балет")) return "♟";
        if (value.contains("эрот")) return "♡";
        if (value.contains("мульт")) return "▧";
        if (value.contains("аниме")) return "✧";
        return "◆";
    }

    private void addSubMenu(LinearLayout parent, String label, String path) {
        String displayLabel = label;
        int existingIconEnd = label.indexOf("  ");
        if (existingIconEnd <= 0 || existingIconEnd > 3) {
            displayLabel = categoryIcon(label) + "  " + label;
        }
        Button b = button(displayLabel);
        // One uniform icon size and weight across every category row.
        int iconEnd = displayLabel.indexOf("  ");
        if (iconEnd > 0 && iconEnd <= 3) {
            android.text.SpannableString menuLabel = new android.text.SpannableString(displayLabel);
            menuLabel.setSpan(new android.text.style.RelativeSizeSpan(1.0f),
                    0, iconEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            menuLabel.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    0, iconEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setText(menuLabel);
        }
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        b.setTextSize(fontSize(12));
        b.setPadding(dp(34), 0, dp(8), 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(39));
        p.rightMargin = dp(5);
        p.bottomMargin = dp(2);
        parent.addView(b, p);
        b.setOnClickListener(v -> load(path, label));
    }

    private static final int REQUEST_BACKUP_EXPORT = 7510;
    private static final int REQUEST_BACKUP_IMPORT = 7511;
    private static final int REQUEST_FEED_DIAGNOSTICS_EXPORT = 7512;
    private String pendingFeedDiagnosticsExport;
    private EditText torrServeAddressField;

    private int getAccentColor(String key) {
        if ("silver".equals(key)) return Color.rgb(214, 219, 224);
        if ("bronze".equals(key)) return Color.rgb(205, 145, 96);
        return Color.rgb(251, 226, 173);
    }

    private float fontSize(float baseSp) {
        return Math.max(10f, Math.min(30f, baseSp + fontOffsetSp));
    }

    private void applyFontDelta(View view, int deltaSp) {
        if (view == null || deltaSp == 0) return;
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
            float currentSp = textView.getTextSize() / scaledDensity;
            float nextSp = Math.max(10f, Math.min(30f, currentSp + deltaSp));
            textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, nextSp);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                applyFontDelta(group.getChildAt(i), deltaSp);
        }
    }

    private void changeFontSize(int deltaSp, TextView valueLabel) {
        int next = Math.max(-6, Math.min(6, fontOffsetSp + deltaSp));
        if (next == fontOffsetSp) return;
        int appliedDelta = next - fontOffsetSp;
        fontOffsetSp = next;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(FONT_SIZE_OFFSET_KEY, fontOffsetSp).apply();
        applyFontDelta(getWindow() == null ? null : getWindow().getDecorView(), appliedDelta);
        if (valueLabel != null) applyFontDelta(valueLabel.getRootView(), appliedDelta);
        if (settingsDialog != null && settingsDialog.isShowing()
                && settingsDialog.getWindow() != null)
            applyFontDelta(settingsDialog.getWindow().getDecorView(), appliedDelta);
        if (valueLabel != null) valueLabel.setText(fontOffsetSp == 0
                ? "Размер по умолчанию"
                : (fontOffsetSp > 0 ? "+" : "") + fontOffsetSp + " sp");
    }

    private void showFontSizeSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(12));
        panel.setBackgroundColor(BG);
        TextView value = text(fontOffsetSp == 0 ? "Размер по умолчанию"
                : (fontOffsetSp > 0 ? "+" : "") + fontOffsetSp + " sp", 16, GOLD, true);
        value.setGravity(Gravity.CENTER);
        value.setPadding(0, dp(4), 0, dp(14));
        panel.addView(value, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        Button smaller = button("А−");
        smaller.setTextSize(fontSize(20));
        Button larger = button("А+");
        larger.setTextSize(fontSize(20));
        LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        controlParams.rightMargin = dp(8);
        controls.addView(smaller, controlParams);
        controls.addView(larger, new LinearLayout.LayoutParams(0, dp(52), 1f));
        panel.addView(controls);
        TextView hint = text("Каждое нажатие меняет размер на 2 пункта.", 12, TEAL, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(12), 0, 0);
        panel.addView(hint);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Размер шрифта")
                .setView(panel)
                .setPositiveButton("Готово", null)
                .setNeutralButton("Сбросить", null)
                .create();
        smaller.setOnClickListener(v -> changeFontSize(-2, value));
        larger.setOnClickListener(v -> changeFontSize(2, value));
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(GOLD);
            Button reset = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            reset.setTextColor(GOLD);
            reset.setOnClickListener(v -> changeFontSize(-fontOffsetSp, value));
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
            dialog.getWindow().setLayout(Math.min(dp(440),
                    getResources().getDisplayMetrics().widthPixels - dp(48)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showAccentPicker() {
        String[] labels = {"Золотистый", "Серебряный", "Бронзовый"};
        String[] keys = {"gold", "silver", "bronze"};
        String current = getSharedPreferences(PREFS, MODE_PRIVATE).getString("accent_color", "gold");
        int selected = 0;
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(current)) selected = i;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Цвет акцента")
                .setSingleChoiceItems(labels, selected, (d, which) -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("accent_color", keys[which]).apply();
                    d.dismiss();
                    recreate();
                })
                .setNegativeButton("Отмена", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(GOLD);
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(GOLD);
    }

    private void showSettings() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(16), dp(18), dp(14));
        page.setBackgroundColor(BG);

        TextView heading = text("Настройки", 24, GOLD, true);
        heading.setPadding(dp(4), 0, 0, dp(12));
        page.addView(heading);

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = new LinearLayout(this);
        LinearLayout right = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        right.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(0, 0, dp(9), 0);
        right.setPadding(dp(9), 0, 0, 0);
        columns.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        columns.addView(right, new LinearLayout.LayoutParams(0, -2, 1f));

        addSettingsAction(left, "Авторизация", () -> showLogin());
        addSettingsAction(left, "Начальный раздел", () -> chooseStartupSource());
        addSettingsAction(left, "Размер шрифта", () -> showFontSizeSettings());
        addSettingsAction(left, "Поиск Jackett", () -> showJackettSettings());
        addSettingsAction(left, "Прокси", () -> showSocks5Settings());
        addSettingsAction(left, "Очистить кэш", () -> confirmClearCache());

        addSettingsAction(right, "Зеркало", () -> chooseMirror());
        addSettingsAction(right, "Цвет акцента", () -> showAccentPicker());
        addSettingsAction(right, "TorrServe", () -> showTorrServeSettings());
        addSettingsAction(right, "Резервная копия", () -> new AlertDialog.Builder(this)
                .setItems(new String[]{"Создать резервную копию", "Восстановить из копии"},
                        (dialog, which) -> {
                            if (which == 0) exportUserBackup();
                            else importUserBackup();
                        }).show());
        addSettingsAction(right, "Обновления", () -> new UpdateManager(this).check());
        addSettingsAction(right, "Диагностика", () -> showCrashDiagnostics());

        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setPadding(0, dp(8), 0, 0);
        Button channelsButton = settingsPillButton("Каналы Android TV");
        boolean channelsOn = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("tv_channels_enabled", true);
        channelsButton.setText("Каналы Android TV: " + (channelsOn ? "Вкл." : "Выкл."));
        bottomRow.addView(channelsButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button aboutButton = settingsPillButton("О приложении");
        LinearLayout.LayoutParams aboutParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        aboutParams.leftMargin = dp(10);
        bottomRow.addView(aboutButton, aboutParams);
        page.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1f));
        page.addView(bottomRow, new LinearLayout.LayoutParams(-1, dp(56)));

        channelsButton.setOnClickListener(v -> {
            boolean enabled = !getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean("tv_channels_enabled", true);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("tv_channels_enabled", enabled).apply();
            channelsButton.setText("Каналы Android TV: " + (enabled ? "Вкл." : "Выкл."));
            if (enabled) {
                browserHandler.postDelayed(this::loadTvChannelFeedsInBrowser, 150);
                showMessage("Каналы Android TV включены");
            } else {
                TvHomeChannels.removeAll(this);
                showMessage("Каналы Android TV отключены");
            }
        });
        aboutButton.setOnClickListener(v -> showAbout());

        settingsDialog = new AlertDialog.Builder(this).setView(page).create();
        settingsDialog.show();
        if (settingsDialog.getWindow() != null) {
            settingsDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
            settingsDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    private Button settingsPillButton(String title) {
        Button b = button(title);
        b.setTextSize(fontSize(13));
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        b.setPadding(dp(12), 0, dp(8), 0);
        return b;
    }

    private void showTorrServeSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(12), dp(18), dp(10));
        panel.setBackgroundColor(BG);
        torrServeAddressField = new EditText(this);
        torrServeAddressField.setSingleLine(true);
        torrServeAddressField.setText(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("torrserve_url", "http://localhost:8090"));
        torrServeAddressField.setHint("http://адрес:8090");
        styleSettingsEdit(torrServeAddressField);
        panel.addView(torrServeAddressField, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button save = settingsPillButton("Сохранить");
        Button test = settingsPillButton("Проверить соединение");
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams testParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        testParams.leftMargin = dp(8);
        actions.addView(test, testParams);
        panel.addView(actions);

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("TorrServe")
                .setView(panel).create();
        save.setOnClickListener(v -> {
            String value = torrServeAddressField.getText().toString().trim().replaceAll("/+$", "");
            if (!value.isEmpty() && !validJackettBase(value)) {
                showMessage("Укажите адрес с http:// или https://");
                return;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("torrserve_url", value).apply();
            showMessage("Адрес TorrServe сохранён");
        });
        test.setOnClickListener(v -> checkTorrServeConnection());
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
            dialog.getWindow().setLayout(Math.min(dp(680),
                    getResources().getDisplayMetrics().widthPixels - dp(48)), -2);
        }
    }

    private void checkTorrServeConnection() {
        String base = torrServeAddressField == null ? "" : torrServeAddressField.getText().toString()
                .trim().replaceAll("/+$", "");
        if (base.isEmpty() || !validJackettBase(base)) {
            showMessage("Укажите корректный адрес TorrServe");
            return;
        }
        showMessage("Проверяем соединение с TorrServe…");
        executeSafely(io, () -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(base + "/echo").openConnection();
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(10000);
                connection.setRequestMethod("GET");
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
                runOnUiThread(() -> showMessage("Соединение с TorrServe установлено"));
            } catch (Exception e) {
                String error = safe(e.getMessage());
                runOnUiThread(() -> showMessage("Не удалось подключиться к TorrServe: " + error));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void confirmClearCache() {
        new AlertDialog.Builder(this)
                .setTitle("Очистить кэш?")
                .setMessage("Будут удалены сохранённые главная и подборки, кэш страниц и изображений. Авторизация, избранное, история и настройки останутся без изменений.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Очистить", (dialog, which) -> {
                    showMessage("Очищаем кэш…");
                    executeSafely(io, () -> {
                        if (diskCache != null) diskCache.clear();
                        boolean homeModelDeleted = homeModelFile().delete();
                        boolean collectionsModelDeleted = collectionsModelFile().delete();
                        FeedDiagnostics.log(getApplicationContext(), "all", "CACHE_CLEAR",
                                "diskCache=cleared, homeModelDeleted=" + homeModelDeleted
                                        + ", collectionsModelDeleted=" + collectionsModelDeleted);
                        runOnUiThread(() -> {
                            pageCache.clear();
                            if (decodedImageCache != null) decodedImageCache.evictAll();
                            homeSectionsMemory.clear();
                            collectionsSectionsMemory.clear();
                            homeCacheDisplayed = false;
                            homeCacheLoadFinished = true;
                            initialHomeFetchPending = false;
                            collectionsLoadedOnce = false;
                            if (sessionWebView != null) sessionWebView.clearCache(true);
                            showMessage("Кэш главной и подборок очищен. Следующее открытие загрузит их заново.");
                        });
                    });
                })
                .show();
    }

    private void checkTorrServerViewingHistory() {
        String base = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("torrserve_url", "http://localhost:8090").trim().replaceAll("/+$", "");
        if (base.isEmpty()) {
            showMessage("Сначала укажите адрес TorrServer в настройках.");
            return;
        }
        showMessage("Проверяем историю TorrServer…");
        executeSafely(io, () -> {
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
        button.setTextSize(fontSize(13));
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
        field.setTextSize(fontSize(13));
        field.setPadding(dp(10), dp(5), dp(10), dp(5));
        field.setBackground(shape(PANEL, TEAL));
    }

    private void chooseStartupSource() {
        String[] labels = {"Главная (Новинки и топы)", "Подборки", "Все раздачи", "Все фильмы",
                "Все сериалы", "Зарубежные сериалы", "Российские сериалы"};
        String[] values = {"home", "collections", "browse.php", "browse.php?c=1002", "browse.php?c=1001",
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

    private void showCrashDiagnostics() {
        String report = CrashDiagnostics.read(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(10));
        panel.setBackgroundColor(BG);
        TextView info = text(report.isEmpty()
                ? "Сбоев приложения не зафиксировано."
                : "Последний сбой приложения сохранён. Отчёт не содержит пароли или cookies.",
                14, Color.WHITE, false);
        info.setLineSpacing(dp(4), 1f);
        panel.addView(info);

        Button feedsButton = settingsPillButton("Диагностика лент");
        LinearLayout.LayoutParams feedsParams = new LinearLayout.LayoutParams(-1, dp(46));
        feedsParams.topMargin = dp(10);
        panel.addView(feedsButton, feedsParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Диагностика")
                .setView(panel)
                .setPositiveButton("Закрыть", null)
                .setNeutralButton("Отправить отчёт", null)
                .create();
        dialog.setOnShowListener(d -> {
            feedsButton.setOnClickListener(v -> {
                dialog.dismiss();
                showFeedDiagnostics();
            });
            Button send = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            send.setEnabled(!report.isEmpty());
            send.setTextColor(GOLD);
            send.setOnClickListener(v -> {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_SUBJECT, "Kinozal ТВ — отчёт о сбое");
                share.putExtra(Intent.EXTRA_TEXT, report);
                startActivity(Intent.createChooser(share, "Отправить отчёт о сбое"));
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(GOLD);
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
            dialog.getWindow().setLayout(Math.min(dp(520),
                    getResources().getDisplayMetrics().widthPixels - dp(48)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void exportFeedDiagnostics() {
        try {
            pendingFeedDiagnosticsExport = FeedDiagnostics.read(this);
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, "kinozal_feed_diagnostics.txt");
            startActivityForResult(intent, REQUEST_FEED_DIAGNOSTICS_EXPORT);
        } catch (Exception e) {
            showMessage("Не удалось сохранить диагностику: " + safe(e.getMessage()));
        }
    }

    private void writeFeedDiagnosticsToUri(Uri uri) {
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new java.io.IOException("Не удалось открыть файл");
            out.write((pendingFeedDiagnosticsExport == null ? "" : pendingFeedDiagnosticsExport)
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            showMessage("Диагностика сохранена в файл");
        } catch (Exception e) {
            showMessage("Не удалось сохранить диагностику: " + safe(e.getMessage()));
        } finally {
            pendingFeedDiagnosticsExport = null;
        }
    }

    private void showFeedDiagnostics() {
        String report = FeedDiagnostics.read(this);
        ScrollView scroll = new ScrollView(this);
        TextView content = text(report.isEmpty()
                ? "Диагностика лент пока пуста. Откройте Главную или Подборки и дождитесь завершения загрузки."
                : report, 12, Color.WHITE, false);
        content.setTextIsSelectable(true);
        content.setTypeface(android.graphics.Typeface.MONOSPACE);
        content.setPadding(dp(12), dp(10), dp(12), dp(10));
        scroll.addView(content);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Диагностика лент")
                .setView(scroll)
                .setPositiveButton("Закрыть", null)
                .setNeutralButton("Сохранить в файл", null)
                .create();
        dialog.setOnShowListener(d -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            save.setEnabled(!report.isEmpty());
            save.setTextColor(GOLD);
            save.setOnClickListener(v -> exportFeedDiagnostics());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(GOLD);
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
            dialog.getWindow().setLayout(Math.min(dp(760),
                    getResources().getDisplayMetrics().widthPixels - dp(48)),
                    Math.min(dp(620), getResources().getDisplayMetrics().heightPixels - dp(100)));
        }
    }

    private void loadExternalCountryInfo(TextView target) {
        target.setText("Страна подключения: определяется…");
        executeSafely(io, () -> {
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
            field.setTextSize(fontSize(14));
            field.setPadding(dp(10), dp(7), dp(10), dp(7));
            field.setBackground(shape(PANEL, TEAL));
            if (i == 3) field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            if (i == 2) {
                LinearLayout customRow = new LinearLayout(this);
                customRow.setOrientation(LinearLayout.HORIZONTAL);
                customRow.setGravity(Gravity.CENTER_VERTICAL);
                customRow.addView(field, new LinearLayout.LayoutParams(0, dp(44), 1f));
                Button check = settingsPillButton("Проверить");
                LinearLayout.LayoutParams checkParams =
                        new LinearLayout.LayoutParams(dp(112), dp(44));
                checkParams.leftMargin = dp(8);
                customRow.addView(check, checkParams);
                TextView result = text("—", 23, Color.LTGRAY, true);
                result.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams resultParams =
                        new LinearLayout.LayoutParams(dp(34), dp(44));
                resultParams.leftMargin = dp(5);
                customRow.addView(result, resultParams);
                check.setOnClickListener(v -> checkJackettCustomServer(
                        field, key, check, result));
                panel.addView(customRow, new LinearLayout.LayoutParams(-1, dp(46)));
            } else {
                panel.addView(field, new LinearLayout.LayoutParams(-1, dp(44)));
            }
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
            dialog.getWindow().setLayout(Math.min(dp(760),
                    getResources().getDisplayMetrics().widthPixels - dp(48)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void checkJackettCustomServer(EditText addressField, EditText keyField,
                                           Button checkButton, TextView resultView) {
        String base = addressField.getText().toString().trim().replaceAll("/+$", "");
        if (!validJackettBase(base)) {
            resultView.setText("✕");
            resultView.setTextColor(Color.RED);
            showMessage("Укажите корректный адрес своего сервера");
            return;
        }
        checkButton.setEnabled(false);
        resultView.setText("…");
        resultView.setTextColor(TEAL);
        String apiKey = keyField.getText().toString().trim();
        executeSafely(io, () -> {
            HttpURLConnection connection = null;
            boolean success = false;
            String error = "";
            try {
                String endpoint = base + "/api/v2.0/indexers/all/results/torznab/api?t=caps"
                        + (apiKey.isEmpty() ? "" : "&apikey=" + URLEncoder.encode(apiKey, "UTF-8"));
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(10000);
                connection.setRequestMethod("GET");
                int code = connection.getResponseCode();
                if (code >= 200 && code < 300) {
                    try (java.io.InputStream input = connection.getInputStream();
                         java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                        byte[] buffer = new byte[2048];
                        int read;
                        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                        String body = output.toString("UTF-8");
                        success = body.contains("<caps") || body.contains(":caps");
                        if (!success) error = "Ответ не похож на Torznab API";
                    }
                } else {
                    error = "HTTP " + code;
                }
            } catch (Exception e) {
                error = safe(e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
            final boolean passed = success;
            final String failure = error;
            runOnUiThread(() -> {
                checkButton.setEnabled(true);
                resultView.setText(passed ? "✓" : "✕");
                resultView.setTextColor(passed ? Color.GREEN : Color.RED);
                if (!passed) showMessage("Проверка Jackett не пройдена: " + failure);
                else showMessage("Свой сервер Jackett доступен");
            });
        });
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
        TextView dialogTitle = text("Другие раздачи", 20, GOLD, true);
        dialogTitle.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        dialogTitle.setPadding(dp(4), dp(6), dp(4), dp(10));
        panel.addView(dialogTitle, 0);
        // No bottom Close button: Android TV Back dismisses this dialog.
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(panel).create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
        executeSafely(io, () -> {
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
                    String metaValue = (tracker.isEmpty() ? "" : tracker + "  ·  ")
                            + "Размер: " + size + "  ·  Сиды: " + seeders + "  ·  Пиры: " + peers;
                    android.text.SpannableString metaText = new android.text.SpannableString(metaValue);
                    int trackerColor = Color.rgb(231, 186, 134); // #E7BA86 tracker accent
                    int trackerStart = 0;
                    if (!tracker.isEmpty()) {
                        metaText.setSpan(new android.text.style.ForegroundColorSpan(trackerColor),
                                trackerStart, tracker.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    int seedsStart = metaValue.indexOf("Сиды:");
                    int seedsEnd = metaValue.indexOf("  ·  Пиры:");
                    if (seedsStart >= 0 && seedsEnd > seedsStart) {
                        metaText.setSpan(new android.text.style.ForegroundColorSpan(TEAL),
                                seedsStart, seedsEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    int peersStart = metaValue.indexOf("Пиры:");
                    if (peersStart >= 0) {
                        metaText.setSpan(new android.text.style.ForegroundColorSpan(RED),
                                peersStart, metaValue.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    TextView meta = text("", 12, TEAL, false);
                    meta.setText(metaText);
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
        b.setTextSize(fontSize(14));
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
        if (deferBrowserNavigationIfBusy(() -> load(path, title, postBody))) return;
        activateBrowseScreen();
        currentPath = path; currentTitle = title; heading.setText(title);
        browsePerfPath = path;
        browsePerfStartedAtMs = android.os.SystemClock.elapsedRealtime();
        Log.i("KinozalPerf", "browse start path=" + path + " transport="
                + (sessionWebView != null ? "WebView" : "native-http"));

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
            executeSafely(io, () -> {
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
        // Cloudflare clearance is bound to the browser User-Agent. Persist the exact
        // WebView UA so WorkManager does not present a different native fingerprint.
        client.setUserAgent(web.getSettings().getUserAgentString());
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
                    long pageFinishedAt = android.os.SystemClock.elapsedRealtime();
                    logBrowsePerf("webview-page-finished",
                            browsePerfStartedAtMs > 0 ? pageFinishedAt - browsePerfStartedAtMs : -1,
                            "url=" + url);
                    BrowserPageCallback cb = pendingBrowserCallback;
                    pendingBrowserCallback = null;
                    final long evaluateStartedAt = android.os.SystemClock.elapsedRealtime();
                    view.evaluateJavascript("(document.documentElement ? document.documentElement.outerHTML : '')", value -> {
                        long evaluateDuration = android.os.SystemClock.elapsedRealtime() - evaluateStartedAt;
                        String html = "";
                        try { html = new org.json.JSONArray("[" + value + "]").getString(0); }
                        catch (Exception ignored) { }
                        if (html.isEmpty()) {
                            lastBrowserError = "WebView завершил загрузку, но DOM пустой. URL: " + url;
                            cb.onPage(null, lastBrowserError);
                            return;
                        }
                        String pageUrl = view.getUrl() == null ? "" : view.getUrl().toLowerCase(java.util.Locale.ROOT);
                        final String loadedHtml = html;
                        final String loadedUrl = pageUrl;
                        logBrowsePerf("dom-extract", evaluateDuration,
                                "htmlChars=" + loadedHtml.length());
                        // DOM parsing on the WebView/UI thread caused visible
                        // stalls for large Kinozal pages. Validate the login
                        // redirect on the background I/O executor instead.
                        executeSafely(io, () -> {
                            long loginCheckStartedAt = android.os.SystemClock.elapsedRealtime();
                            boolean loginForm = containsLoginPasswordInput(loadedHtml);
                            logBrowsePerf("login-check",
                                    android.os.SystemClock.elapsedRealtime() - loginCheckStartedAt,
                                    "htmlChars=" + loadedHtml.length());
                            final boolean redirectedToLogin = loginForm
                                    || loadedUrl.contains("login.php")
                                    || loadedUrl.contains("takelogin.php");
                            runOnUiThread(() -> {
                                updateSidebarLoginStatus(!redirectedToLogin);
                                if (redirectedToLogin) {
                                    lastBrowserError = "Сайт перенаправил на авторизацию. URL: " + view.getUrl();
                                    cb.onPage(null, lastBrowserError);
                                    return;
                                }
                                lastBrowserError = "Первичная страница загружена: " + url
                                        + ", HTML=" + loadedHtml.length() + " символов";
                                cb.onPage(loadedHtml, null);
                            });
                        });
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

    // Fast equivalent of form:has(input[type=password]) for login detection.
    // Avoid building a second Jsoup Document for every catalogue response.
    private boolean containsLoginPasswordInput(String html) {
        if (html == null || html.isEmpty()) return false;
        String lower = html.toLowerCase(java.util.Locale.ROOT);
        int formStart = 0;
        while ((formStart = lower.indexOf("<form", formStart)) >= 0) {
            int formEnd = lower.indexOf("</form", formStart);
            if (formEnd < 0) formEnd = lower.length();
            int inputStart = formStart;
            while ((inputStart = lower.indexOf("<input", inputStart)) >= 0
                    && inputStart < formEnd) {
                int tagEnd = lower.indexOf(">", inputStart);
                if (tagEnd < 0 || tagEnd > formEnd) break;
                String inputTag = lower.substring(inputStart, tagEnd + 1);
                if (LOGIN_PASSWORD_TYPE.matcher(inputTag).find()) return true;
                inputStart = tagEnd + 1;
            }
            formStart = formEnd + 6;
        }
        return false;
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
        if (html == null || html.isEmpty()) return;
        final String requestedPath = currentPath;
        final long parseStartedAt = android.os.SystemClock.elapsedRealtime();

        // The authenticated WebView already owns the live category DOM. Extract
        // the 50 visible rows there instead of rebuilding the same DOM with Jsoup.
        WebView web = sessionWebView;
        if (web != null && "browse".equals(activeFeedScreen)) {
            parseCatalogInWebView(web, html, requestedPath, parseStartedAt);
            return;
        }
        parseCatalogWithJsoup(html, requestedPath, parseStartedAt);
    }

    private void parseCatalogInWebView(WebView web, String html, String requestedPath,
            long parseStartedAt) {
        final boolean[] completed = {false};
        final Runnable[] timeoutHolder = {null};
        timeoutHolder[0] = () -> {
            if (completed[0]) return;
            completed[0] = true;
            Log.w("KinozalPerf", "catalog JS extraction timed out; using Jsoup fallback");
            parseCatalogWithJsoup(html, requestedPath, parseStartedAt);
        };
        browserHandler.postDelayed(timeoutHolder[0], 4000);

        String script = "(function(){try{"
                + "var rows=document.querySelectorAll('table.t_peer tr'),out=[],seen=Object.create(null);"
                + "for(var i=0;i<rows.length;i++){var row=rows[i],cells=row.children,nameCell=null;"
                + "for(var c=0;c<cells.length;c++){var cell=cells[c];"
                + "if(cell.tagName==='TD'&&cell.classList.contains('nam')){nameCell=cell;break;}}"
                + "if(!nameCell)continue;var links=nameCell.children;"
                + "for(var a=0;a<links.length;a++){var link=links[a];"
                + "if(link.tagName!=='A'||(link.getAttribute('href')||'').indexOf('details.php?id=')<0)continue;"
                + "var title=(link.innerText||link.textContent||'').trim(),href=link.href||link.getAttribute('href');"
                + "if(title.length<3||!href||seen[href])continue;seen[href]=1;"
                + "function cellText(n){return cells.length>n?(cells[n].innerText||cells[n].textContent||'').trim():'—';}"
                + "out.push({title:title,href:href,size:cellText(3),seeds:cellText(4),"
                + "leeches:cellText(5),uploaded:cellText(6)});}}"
                + "return JSON.stringify(out);"
                + "}catch(e){return '[]';}})()";

        web.evaluateJavascript(script, value -> {
            if (completed[0]) return;
            completed[0] = true;
            if (timeoutHolder[0] != null) browserHandler.removeCallbacks(timeoutHolder[0]);
            try {
                String json = new org.json.JSONArray("[" + value + "]").getString(0);
                org.json.JSONArray array = new org.json.JSONArray(json);
                List<Torrent> parsed = new ArrayList<>(array.length());
                for (int i = 0; i < array.length(); i++) {
                    org.json.JSONObject item = array.optJSONObject(i);
                    if (item == null) continue;
                    Torrent torrent = new Torrent();
                    torrent.title = item.optString("title", "").trim();
                    torrent.href = item.optString("href", "").trim();
                    if (torrent.title.length() < 3 || torrent.href.isEmpty()) continue;
                    torrent.size = item.optString("size", "—");
                    torrent.seeds = item.optString("seeds", "—");
                    torrent.leeches = item.optString("leeches", "—");
                    torrent.uploaded = item.optString("uploaded", "—");
                    parsed.add(torrent);
                }
                logBrowsePerf("catalog-js-extract",
                        android.os.SystemClock.elapsedRealtime() - parseStartedAt,
                        "htmlChars=" + html.length() + " torrents=" + parsed.size());
                if (parsed.isEmpty()) {
                    Log.w("KinozalPerf", "catalog JS returned no rows; using Jsoup fallback");
                    parseCatalogWithJsoup(html, requestedPath, parseStartedAt);
                } else {
                    finishCatalogLoad(html, requestedPath, parsed);
                }
            } catch (Exception e) {
                Log.w("KinozalPerf", "catalog JS result failed; using Jsoup fallback", e);
                parseCatalogWithJsoup(html, requestedPath, parseStartedAt);
            }
        });
    }

    private void parseCatalogWithJsoup(String html, String requestedPath, long parseStartedAt) {
        executeSafely(io, () -> {
            try {
                long domStartedAt = android.os.SystemClock.elapsedRealtime();
                Document document = Jsoup.parse(html, client.getMirror());
                long domDuration = android.os.SystemClock.elapsedRealtime() - domStartedAt;
                long extractStartedAt = android.os.SystemClock.elapsedRealtime();
                List<Torrent> parsed = parseTorrents(document);
                long extractDuration = android.os.SystemClock.elapsedRealtime() - extractStartedAt;
                logBrowsePerf("catalog-dom-parse", domDuration, "htmlChars=" + html.length());
                logBrowsePerf("catalog-extract", extractDuration, "torrents=" + parsed.size());
                logBrowsePerf("catalog-parse",
                        android.os.SystemClock.elapsedRealtime() - parseStartedAt,
                        "htmlChars=" + html.length() + " torrents=" + parsed.size() + " fallback=jsoup");
                runOnUiThread(() -> finishCatalogLoad(html, requestedPath, parsed));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!java.util.Objects.equals(currentPath, requestedPath)) return;
                    progress.setVisibility(View.GONE);
                    status.setText("Ошибка обработки каталога");
                    addEmpty("Не удалось обработать список раздач: " + safe(e.getMessage()));
                });
            }
        });
    }

    private void finishCatalogLoad(String html, String requestedPath, List<Torrent> parsed) {
        if (!java.util.Objects.equals(currentPath, requestedPath)
                || !"browse".equals(activeFeedScreen)) return;
        lastHtml = html;
        progress.setVisibility(View.GONE);
        torrents.clear();
        torrents.addAll(parsed);
        setCatalogStatus("Раздач", parsed.size());
        long renderStartedAt = android.os.SystemClock.elapsedRealtime();
        renderTorrents(parsed);
        long renderDuration = android.os.SystemClock.elapsedRealtime() - renderStartedAt;
        long totalDuration = browsePerfStartedAtMs > 0
                ? android.os.SystemClock.elapsedRealtime() - browsePerfStartedAtMs : -1;
        logBrowsePerf("catalog-render", renderDuration,
                "totalSinceTap=" + totalDuration + "ms torrents=" + parsed.size());
        browsePerfStartedAtMs = 0L;
        if (parsed.isEmpty()) addEmpty("Страница загружена, но раздачи не найдены. Возможно, фильтр не применился.");
    }

    private List<Torrent> parseTorrents(Document doc) {
        List<Torrent> out = new ArrayList<>();
        java.util.HashSet<String> seenHrefs = new java.util.HashSet<>();
        for (Element table : doc.select("table.t_peer")) {
            for (Element row : table.getElementsByTag("tr")) {
                Elements cells = row.children();
                Element nameCell = null;
                for (Element cell : cells) {
                    if ("td".equals(cell.tagName()) && cell.hasClass("nam")) {
                        nameCell = cell;
                        break;
                    }
                }
                if (nameCell == null) continue;
                for (Element anchor : nameCell.children()) {
                    if (!"a".equals(anchor.tagName())
                            || !anchor.attr("href").contains("details.php?id=")) continue;
                    String name = anchor.text().trim();
                    if (name.length() < 3) continue;
                    String href = anchor.absUrl("href");
                    if (href.isEmpty()) href = anchor.attr("href");
                    if (!seenHrefs.add(href)) continue;
                    Torrent torrent = new Torrent();
                    torrent.title = name;
                    torrent.href = href;
                    torrent.size = cells.size() > 3 ? cells.get(3).text().trim() : "—";
                    torrent.seeds = cells.size() > 4 ? cells.get(4).text().trim() : "—";
                    torrent.leeches = cells.size() > 5 ? cells.get(5).text().trim() : "—";
                    torrent.uploaded = cells.size() > 6 ? cells.get(6).text().trim() : "—";
                    out.add(torrent);
                }
            }
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

    private void setLocalSort(String key) {
        if (key.equals(localSort)) localSortDescending = !localSortDescending;
        else {
            localSort = key;
            localSortDescending = true;
        }
        if ("local:favorites".equals(currentPath)) {
            renderTorrents(new ArrayList<>(readFavorites().values()));
        } else if ("local:recently-viewed".equals(currentPath)) {
            renderTorrents(new ArrayList<>(readRecentlyViewed().values()));
        }
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
        boolean recentList = "local:recently-viewed".equals(currentPath);
        boolean favoritesList = "local:favorites".equals(currentPath);
        boolean localManageList = recentList || favoritesList;
        if (localManageList) {
            LinearLayout localSortBar = new LinearLayout(this);
            localSortBar.setGravity(Gravity.CENTER_VERTICAL);
            localSortBar.setPadding(dp(8), dp(4), dp(8), dp(8));
            Button localDateButton = button("");
            Button localSizeButton = button("");
            Button localSeedsButton = button("");
            Button[] localSortButtons = {localDateButton, localSizeButton, localSeedsButton};
            String[] localSortKeys = {"date", "size", "seeds"};
            String[] localSortTitles = {"Дата", "Размер", "Сиды"};
            for (int i = 0; i < localSortButtons.length; i++) {
                final String key = localSortKeys[i];
                Button b = localSortButtons[i];
                b.setText(localSortTitles[i] + (key.equals(localSort)
                        ? (localSortDescending ? " ↓" : " ↑") : ""));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(38), 1f);
                p.rightMargin = dp(5);
                localSortBar.addView(b, p);
                b.setOnClickListener(v -> setLocalSort(key));
            }
            list.addView(localSortBar);
            if (!"recent".equals(localSort)) {
                java.util.Collections.sort(displayItems, (a, b) -> {
                    int cmp;
                    if ("size".equals(localSort)) cmp = Double.compare(sizeValue(a.size), sizeValue(b.size));
                    else if ("seeds".equals(localSort)) cmp = Integer.compare(parseInt(a.seeds), parseInt(b.seeds));
                    else cmp = dateValue(a.uploaded).compareTo(dateValue(b.uploaded));
                    return localSortDescending ? -cmp : cmp;
                });
            }
        }
        if (recentList) addHistoryClearButton();
        if (favoritesList) addFavoritesClearButton();

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
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
        headerRow.addView(header, new LinearLayout.LayoutParams(0, -2, 1f));
        if (localManageList) headerRow.addView(new View(this), new LinearLayout.LayoutParams(dp(52), dp(1)));
        list.addView(headerRow);

        for (Torrent t : displayItems) {
            LinearLayout card = new LinearLayout(this);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(14), dp(8), dp(14), dp(8));
            card.setBackground(shape(PANEL, 0));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, dp(66), 1f);
            TextView name = text(t.title, 16, Color.WHITE, true); name.setMaxLines(2);
            card.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView size = text(displayTorrentValue(t.size), 13, GOLD, false);
            size.setGravity(Gravity.CENTER);
            card.addView(size, new LinearLayout.LayoutParams(dp(85), -2));
            TextView uploaded = text(displayTorrentValue(t.uploaded), 12, Color.rgb(170, 205, 180), false);
            uploaded.setGravity(Gravity.CENTER);
            uploaded.setMaxLines(2);
            card.addView(uploaded, new LinearLayout.LayoutParams(dp(96), -2));
            TextView seeds = text(displayTorrentValue(t.seeds), 13, TEAL, true);
            seeds.setGravity(Gravity.CENTER);
            card.addView(seeds, new LinearLayout.LayoutParams(dp(55), -2));
            TextView leeches = text(displayTorrentValue(t.leeches), 13, RED, true);
            leeches.setGravity(Gravity.CENTER);
            card.addView(leeches, new LinearLayout.LayoutParams(dp(55), -2));
            card.setFocusable(true);
            final Runnable[] prefetchTask = {null};
            card.setOnFocusChangeListener((v, focused) -> {
                v.setBackground(shape(focused ? Color.rgb(57, 68, 70) : PANEL, focused ? GOLD : 0));
                if (prefetchTask[0] != null) browserHandler.removeCallbacks(prefetchTask[0]);
                if (focused) {
                    // Do not start WebView prefetch while the user is moving
                    // through the list. Wait until this row remains focused.
                    prefetchTask[0] = () -> {
                        if (card.hasFocus() && card.isAttachedToWindow()) {
                            prefetchTorrentDetails(t);
                        }
                    };
                    browserHandler.postDelayed(prefetchTask[0], 850);
                }
            });
            card.setOnClickListener(v -> showTorrent(t));

            LinearLayout itemRow = new LinearLayout(this);
            itemRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(-1, dp(66));
            itemParams.bottomMargin = dp(5);
            itemRow.addView(card, cardParams);
            if (localManageList) {
                View delete = new View(this) {
                    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    @Override protected void onDraw(android.graphics.Canvas canvas) {
                        super.onDraw(canvas);
                        paint.setColor(GOLD);
                        paint.setStyle(android.graphics.Paint.Style.STROKE);
                        paint.setStrokeWidth(dp(3.2f));
                        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                        paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                        float cx = getWidth() / 2f;
                        canvas.save();
                        canvas.scale(0.70f, 0.70f, cx, getHeight() / 2f);
                        float top = dp(7), bottom = getHeight() - dp(7);
                        float w = dp(29), left = cx - w / 2f, right = cx + w / 2f;
                        canvas.drawRoundRect(cx - dp(8), top, cx + dp(8), top + dp(7), dp(3), dp(3), paint);
                        canvas.drawRoundRect(left - dp(5), top + dp(10), right + dp(5), top + dp(18), dp(7), dp(7), paint);
                        android.graphics.Path body = new android.graphics.Path();
                        body.moveTo(left - dp(2), top + dp(18));
                        body.lineTo(left + dp(1), bottom - dp(5));
                        body.quadTo(left + dp(2), bottom, left + dp(8), bottom);
                        body.lineTo(right - dp(8), bottom);
                        body.quadTo(right - dp(2), bottom, right - dp(1), bottom - dp(5));
                        body.lineTo(right + dp(2), top + dp(18));
                        canvas.drawPath(body, paint);
                        for (int i = -1; i <= 1; i++) {
                            float x = cx + i * dp(8);
                            canvas.drawLine(x, top + dp(28), x, bottom - dp(5), paint);
                        }
                        canvas.restore();
                    }
                };
                delete.setContentDescription(recentList ? "Удалить из истории" : "Удалить из избранного");
                delete.setFocusable(true);
                delete.setClickable(true);
                delete.setOnFocusChangeListener((v, focused) ->
                        v.setBackground(shape(focused ? Color.rgb(65, 72, 73) : Color.TRANSPARENT,
                                focused ? GOLD : 0)));
                delete.setOnClickListener(v -> {
                    if (recentList) removeRecentlyViewed(t.href);
                    else removeFavorite(t.href);
                });
                LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(52), dp(54));
                deleteParams.leftMargin = dp(10);
                itemRow.addView(delete, deleteParams);
            }
            list.addView(itemRow, itemParams);
        }
        if (!recentList) {
            LinearLayout pages = new LinearLayout(this); pages.setGravity(Gravity.RIGHT);
            Button prev = button("← Назад"); Button next = button("Далее →");
            LinearLayout.LayoutParams prevParams = new LinearLayout.LayoutParams(-2, -1);
            prevParams.rightMargin = dp(10);
            pages.addView(prev, prevParams);
            pages.addView(next, new LinearLayout.LayoutParams(-2, -1));
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(52)); pp.topMargin = dp(10); list.addView(pages, pp);
            if (favoritesList) {
                prev.setOnClickListener(v -> showMessage("В избранном отображаются все сохранённые раздачи."));
                next.setOnClickListener(v -> showMessage("В избранном отображаются все сохранённые раздачи."));
            } else {
                prev.setOnClickListener(v -> changePage(-1)); next.setOnClickListener(v -> changePage(1));
            }
        }
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
        TvHomeChannels.onBrowsableRequestResult(this, requestCode);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_BACKUP_EXPORT) {
            writeBackupToUri(data.getData());
            return;
        }
        if (requestCode == REQUEST_FEED_DIAGNOSTICS_EXPORT) {
            writeFeedDiagnosticsToUri(data.getData());
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
        executeSafely(io, () -> {
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
        final java.util.Set<String> allowedHosts = new java.util.HashSet<>();
        try {
            for (String mirrorUrl : MIRRORS) {
                String host = java.net.URI.create(mirrorUrl).getHost();
                if (host != null) {
                    host = host.toLowerCase(java.util.Locale.ROOT);
                    allowedHosts.add(host);
                    allowedHosts.add("www." + host);
                }
            }
            String selectedHost = java.net.URI.create(base).getHost();
            if (selectedHost != null) {
                selectedHost = selectedHost.toLowerCase(java.util.Locale.ROOT);
                allowedHosts.add(selectedHost);
                allowedHosts.add("www." + selectedHost);
            }
        } catch (Exception e) { showMessage("Некорректный адрес зеркала."); return; }

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
                if (host == null) return false;
                host = host.toLowerCase(java.util.Locale.ROOT);
                return allowedHosts.contains(host);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                try {
                    if (allowed(Uri.parse(url))) return false;
                } catch (Exception ignored) { }
                showMessage("Переход на посторонний сайт заблокирован.");
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                if (allowed(request.getUrl())) return false;
                showMessage("Переход на посторонний сайт заблокирован.");
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

        Button cursorToggle = button("Курсор");
        Button insert = button("Вставить");
        Button signIn = button("Вход");
        Button done = button("Готово");
        LinearLayout.LayoutParams cursorButtonParams = new LinearLayout.LayoutParams(dp(112), dp(44));
        cursorButtonParams.leftMargin = dp(6);
        credentials.addView(cursorToggle, cursorButtonParams);
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

        FrameLayout webContainer = new FrameLayout(this);
        webContainer.setFocusable(false);
        TvWebCursorView tvCursor = new TvWebCursorView(this, web);
        tvCursor.setVisibility(View.GONE);
        webContainer.addView(web, new FrameLayout.LayoutParams(-1, -1));
        webContainer.addView(tvCursor, new FrameLayout.LayoutParams(-1, -1));
        panel.addView(webContainer, new LinearLayout.LayoutParams(-1, 0, 1f));
        cursorToggle.setOnClickListener(v -> {
            boolean enabled = tvCursor.getVisibility() != View.VISIBLE;
            tvCursor.setVisibility(enabled ? View.VISIBLE : View.GONE);
            cursorToggle.setText("Курсор");
            if (enabled) {
                tvCursor.requestFocus();
                showMessage("Курсор включён: перемещай стрелками, нажми OK для клика.");
            } else {
                web.requestFocus();
            }
        });
        webContainer.post(() -> tvCursor.setCursorPosition(
                webContainer.getWidth() * 0.12f,
                webContainer.getHeight() * 0.18f + dp(36)));

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
        cursorToggle.setFocusable(true);
        cursorToggle.setFocusableInTouchMode(true);
        cursorToggle.post(cursorToggle::requestFocus);
        web.post(() -> {
            web.requestLayout();
            web.loadUrl(base + "/login.php?m=5");
            cursorToggle.post(cursorToggle::requestFocus);
        });
    }

    /**
     * On-screen pointer for Android TV WebView pages that require mouse-like
     * interaction (for example Cloudflare Turnstile). DPAD moves the pointer;
     * OK/ENTER sends a real touch sequence to the underlying WebView.
     */
    private final class TvWebCursorView extends View {
        private final WebView target;
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private float cursorX = dp(45);
        private float cursorY = dp(45);

        void setCursorPosition(float x, float y) {
            cursorX = Math.max(dp(14), Math.min(getWidth() - dp(14), x));
            cursorY = Math.max(dp(14), Math.min(getHeight() - dp(14), y));
            invalidate();
        }
        private final float step = dp(22);

        TvWebCursorView(android.content.Context context, WebView target) {
            super(context);
            this.target = target;
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            if (getVisibility() != View.VISIBLE) return;
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.BLACK);
            canvas.drawCircle(cursorX, cursorY, dp(13), paint);
            paint.setColor(Color.rgb(0, 255, 210));
            paint.setStrokeWidth(dp(4));
            canvas.drawCircle(cursorX, cursorY, dp(12), paint);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(Color.rgb(255, 235, 0));
            android.graphics.Path pointer = new android.graphics.Path();
            pointer.moveTo(cursorX - dp(5), cursorY - dp(9));
            pointer.lineTo(cursorX - dp(5), cursorY + dp(10));
            pointer.lineTo(cursorX, cursorY + dp(5));
            pointer.lineTo(cursorX + dp(5), cursorY + dp(13));
            pointer.lineTo(cursorX + dp(9), cursorY + dp(11));
            pointer.lineTo(cursorX + dp(4), cursorY + dp(3));
            pointer.lineTo(cursorX + dp(11), cursorY + dp(3));
            pointer.close();
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.rgb(255, 0, 180));
            canvas.drawPath(pointer, paint);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(Color.rgb(255, 235, 0));
            canvas.drawPath(pointer, paint);
        }

        @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
            float maxX = Math.max(dp(12), getWidth() - dp(12));
            float maxY = Math.max(dp(12), getHeight() - dp(12));
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT: cursorX = Math.max(dp(12), cursorX - step); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: cursorX = Math.min(maxX, cursorX + step); break;
                case KeyEvent.KEYCODE_DPAD_UP: cursorY = Math.max(dp(12), cursorY - step); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: cursorY = Math.min(maxY, cursorY + step); break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    long now = android.os.SystemClock.uptimeMillis();
                    android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now,
                            android.view.MotionEvent.ACTION_DOWN, cursorX, cursorY, 0);
                    android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 90,
                            android.view.MotionEvent.ACTION_UP, cursorX, cursorY, 0);
                    target.dispatchTouchEvent(down);
                    target.dispatchTouchEvent(up);
                    down.recycle();
                    up.recycle();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    return super.onKeyDown(keyCode, event);
                default:
                    return super.onKeyDown(keyCode, event);
            }
            invalidate();
            return true;
        }
    }

    private void styleCompactCredentialField(EditText field, String hint) {
        field.setSingleLine(true);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(TEAL);
        field.setHint(hint);
        field.setTextSize(fontSize(14));
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
        String[] labels = {"Kinozal Me", "Kinozal Guru", "Kinozal Jumpingcrab"};
        new AlertDialog.Builder(this).setTitle("Зеркало").setItems(labels, (d, which) -> {
            applyMirror(MIRRORS[which]);
        }).setNeutralButton("Ввести адрес вручную", (d, which) -> showCustomMirrorDialog())
                .setNegativeButton("Отмена", null).show();
    }

    private void showCustomMirrorDialog() {
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("https://example.com");
        address.setText(client.getMirror());
        int pad = dp(18);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, dp(8), pad, 0);
        content.addView(address, new LinearLayout.LayoutParams(-1, dp(48)));
        new AlertDialog.Builder(this).setTitle("Своё зеркало").setView(content)
                .setPositiveButton("Сохранить", (d, which) -> {
                    String value = address.getText().toString().trim();
                    if (!value.matches("(?i)^https://[^/\\s]+/?$")) {
                        showMessage("Введите полный HTTPS-адрес зеркала, например https://kinozal.me");
                        return;
                    }
                    applyMirror(value.replaceAll("/+$", ""));
                }).setNegativeButton("Отмена", null).show();
    }

    private void applyMirror(String value) {
        try {
            client.setMirror(value);
            mirror = client.getMirror();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("mirror", mirror).apply();
            load("browse.php", "Все раздачи");
        } catch (IllegalArgumentException e) {
            showMessage("Некорректный адрес зеркала. Требуется HTTPS.");
        }
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
                t.poster = o.optString("poster", "");
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
                o.put("seeds", t.seeds); o.put("leeches", t.leeches); o.put("uploaded", t.uploaded); o.put("poster", t.poster == null ? "" : t.poster);
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
                t.poster = o.optString("poster", "");
                if (!t.href.isEmpty()) result.put(t.href, t);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private void saveRecentlyViewed(Torrent torrent) {
        if (torrent == null || torrent.href == null || torrent.href.isEmpty()) return;
        // Home-feed torrents carry their poster separately from the details parser.
        // Preserve it in history so Continue Watching can build complete TV cards.
        if (torrent.poster == null || torrent.poster.isEmpty()) {
            for (HomeSection section : homeSectionsMemory) {
                for (HomePoster poster : section.items) {
                    if (poster.torrent != null && torrent.href.equals(poster.torrent.href)
                            && poster.imageUrl != null && !poster.imageUrl.isEmpty()) {
                        torrent.poster = poster.imageUrl;
                        break;
                    }
                }
                if (torrent.poster != null && !torrent.poster.isEmpty()) break;
            }
        }
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
                o.put("poster", t.poster == null ? "" : t.poster);
                array.put(o);
            } catch (Exception ignored) { }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(RECENTLY_VIEWED_KEY, array.toString()).apply();
        TvHomeChannels.refreshContinue(this);
    }

    private void addHistoryClearButton() {
        Button clear = button("Очистить историю");
        clear.setAllCaps(false);
        clear.setTextSize(fontSize(14));
        clear.setOnClickListener(v -> clearRecentlyViewed());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(44));
        params.topMargin = dp(8);
        params.bottomMargin = dp(8);
        list.addView(clear, params);
    }

    private void addFavoritesClearButton() {
        Button clear = button("Очистить избранное");
        clear.setAllCaps(false);
        clear.setTextSize(fontSize(14));
        clear.setOnClickListener(v -> {
            writeFavorites(new java.util.LinkedHashMap<>());
            showFavorites();
            showMessage("Избранное очищено");
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(44));
        params.topMargin = dp(8);
        params.bottomMargin = dp(8);
        list.addView(clear, params);
    }

    private void removeFavorite(String href) {
        if (href == null || href.isEmpty()) return;
        java.util.LinkedHashMap<String, Torrent> favorites = readFavorites();
        favorites.remove(href);
        writeFavorites(favorites);
        showFavorites();
    }

    private String displayTorrentValue(String value) {
        return value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value.trim())
                ? "—" : value.trim();
    }

    private void removeRecentlyViewed(String href) {
        if (href == null || href.isEmpty()) return;
        java.util.LinkedHashMap<String, Torrent> recent = readRecentlyViewed();
        recent.remove(href);
        saveRecentlyViewedList(recent);
        showRecentlyViewed();
    }

    private void clearRecentlyViewed() {
        saveRecentlyViewedList(new java.util.LinkedHashMap<>());
        showRecentlyViewed();
        showMessage("История очищена");
    }

    private void saveRecentlyViewedList(java.util.LinkedHashMap<String, Torrent> recent) {
        org.json.JSONArray array = new org.json.JSONArray();
        java.util.List<Torrent> values = new java.util.ArrayList<>(recent.values());
        for (int i = values.size() - 1; i >= 0; i--) {
            Torrent t = values.get(i);
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                o.put("title", t.title); o.put("href", t.href); o.put("size", t.size);
                o.put("seeds", t.seeds); o.put("leeches", t.leeches); o.put("uploaded", t.uploaded);
                o.put("poster", t.poster == null ? "" : t.poster);
                array.put(o);
            } catch (Exception ignored) { }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(RECENTLY_VIEWED_KEY, array.toString()).apply();
        TvHomeChannels.refreshContinue(this);
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
        final java.util.List<String> screenshotThumbs = new java.util.ArrayList<>();
        final java.util.List<String> comments = new java.util.ArrayList<>();
        String screenshotRequestId = "";
        String screenshotPagesd = "";
        String releaseRequestId = "";
        String releasePagesd = "";
        String releaseText = "";
        boolean releaseLoaded = false;
        boolean releaseLoading = false;
        boolean screenshotsLoaded = false;
        boolean screenshotsLoading = false;
    }

    // Warm the next likely details page while the user navigates the list.
    // The click handler reuses this in-flight request instead of starting a
    // second WebView navigation and cancelling the first one.
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
        TorrentDetails cachedDetails = getCachedDetailData(cacheKey);
        if (cachedDetails == null) {
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
                    cachedDetails = parseTorrentDetails(cachedDoc, t);
                    putCachedDetailData(cacheKey, cachedDetails);
                } catch (Exception ignored) {
                    cachedHtml = null;
                }
            }
        }

        if (cachedDetails != null) {
            t.poster = cachedDetails.poster;
            saveRecentlyViewed(t);
            // Reuse the parsed model; no HTML parsing or network wait on repeat opens.
            showTorrentDetails(t, cachedDetails);
        }

        final boolean hasCachedDetails = cachedDetails != null;
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
                putCachedDetailData(cacheKey, details);
                diskCache.put(cacheKey, html.getBytes(StandardCharsets.UTF_8));
                t.poster = details.poster;
                saveRecentlyViewed(t);
                // Do not wait for the secondary screenshot request before
                // presenting the already parsed release details.
                if (href.equals(activeTorrentDetailsHref) && !hasCachedDetails) {
                    progress.setVisibility(View.GONE);
                    showTorrentDetails(t, details);
                }
                // If a cached card is already open, keep it and its DPAD focus
                // intact; the fresh HTML is saved for the next visit instead
                // of rebuilding the dialog underneath the user.
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
            executeSafely(io, () -> {
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

        // The "Релиз" tab is loaded by the same AJAX endpoint as screenshots,
        // with pagesd=0. Capture its parameters without loading it eagerly.
        java.util.regex.Matcher releaseTab = java.util.regex.Pattern.compile(
                "id=\\\"tbch0\\\"[\\s\\S]*?showtab\\(([0-9]+),([0-9]+)\\)")
                .matcher(doc.html());
        if (releaseTab.find()) {
            data.releaseRequestId = releaseTab.group(1);
            data.releasePagesd = releaseTab.group(2);
        }

        // Kinozal comments are embedded in the release page as div.tx blocks.
        // Read-only extraction: no input field, reply or submit action.
        java.util.LinkedHashSet<String> uniqueComments = new java.util.LinkedHashSet<>();
        for (Element comment : doc.select("div.tx")) {
            String value = comment.wholeText().replace('\u00a0', ' ')
                    // Some page fragments contain escaped line breaks as literal characters.
                    .replace("\\n", "\n")
                    .replace("\\r", "\n")
                    .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                    .replaceAll(" *\\n *", "\n")
                    .replaceAll(" {2,}", " ").trim();
            if (!value.isEmpty()) uniqueComments.add(value);
        }
        data.comments.addAll(uniqueComments);
        // Kinozal loads the screenshot tab asynchronously. The detail page
        // contains showtab(id, pagesd), not the screenshot <img> elements.
        java.util.regex.Matcher screenshotTab = java.util.regex.Pattern.compile(
                "id=\\\"tbch[\\s\\S]*?showtab\\(([\\s\\S]*?),([\\s\\S]*?)\\)[\\s\\S]*?>([\\s\\S]*?)<",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(doc.html());
        while (screenshotTab.find()) {
            String tabLabel = Jsoup.parse(screenshotTab.group(3)).text()
                    .toLowerCase(java.util.Locale.ROOT);
            if (!tabLabel.contains("скриншот")) continue;
            data.screenshotRequestId = screenshotTab.group(1).replaceAll("[\\\"']", "").trim();
            data.screenshotPagesd = screenshotTab.group(2).replaceAll("[\\\"']", "").trim();
            break;
        }
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

    /**
     * Kinozal screenshot thumbnails are returned by get_srv_details.php,
     * selected by the id/pagesd values in the Screenshot tab's showtab(...).
     * Only img[src] URLs are retained; original-image href links are ignored.
     */
    private void loadReleaseText(TorrentDetails details,
            java.util.function.Consumer<TorrentDetails> callback) {
        if (details == null || details.releaseRequestId.isEmpty()
                || details.releasePagesd.isEmpty()) {
            callback.accept(details);
            return;
        }
        final String endpoint = Uri.parse(details.sourcePage).buildUpon()
                .path("/get_srv_details.php")
                .clearQuery()
                .appendQueryParameter("id", details.releaseRequestId)
                .appendQueryParameter("pagesd", details.releasePagesd)
                .build().toString();
        java.util.function.Consumer<String> onHtml = html -> {
            String release = "";
            if (html != null && !html.trim().isEmpty()) {
                try {
                    Document fragment = Jsoup.parse(html, endpoint);
                    Element content = fragment.selectFirst("#tabs, .justify, body");
                    if (content != null) release = content.wholeText().trim();
                    if (release.isEmpty() && content != null) release = content.text().trim();
                } catch (Exception e) {
                    Log.w("KinozalRelease", "Cannot parse release tab", e);
                }
            }
            details.releaseText = release;
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                callback.accept(details);
            } else {
                runOnUiThread(() -> callback.accept(details));
            }
        };
        if (sessionWebView != null) {
            fetchBrowserPage(endpoint, null, onHtml);
        } else {
            executeSafely(io, () -> {
                try { onHtml.accept(client.fetch(endpoint)); }
                catch (Exception e) {
                    Log.w("KinozalRelease", "Cannot load release tab", e);
                    runOnUiThread(() -> callback.accept(details));
                }
            });
        }
    }

    private void loadScreenshotThumbnails(TorrentDetails details,
            java.util.function.Consumer<TorrentDetails> callback) {
        if (details == null || details.screenshotRequestId.isEmpty()
                || details.screenshotPagesd.isEmpty()) {
            callback.accept(details);
            return;
        }
        final String endpoint = Uri.parse(details.sourcePage).buildUpon()
                .path("/get_srv_details.php")
                .clearQuery()
                .appendQueryParameter("id", details.screenshotRequestId)
                .appendQueryParameter("pagesd", details.screenshotPagesd)
                .build().toString();

        java.util.function.Consumer<String> onHtml = html -> {
            if (html != null && !html.trim().isEmpty()) {
                try {
                    Document ajax = Jsoup.parse(html, endpoint);
                    for (Element image : ajax.select("img[src]")) {
                        String raw = image.attr("src").trim();
                        if (raw.isEmpty()) continue;
                        String decoded = raw;
                        try {
                            decoded = java.net.URLDecoder.decode(raw, "UTF-8");
                        } catch (Exception ignored) { }
                        String url = decoded;
                        // Match the reference client's rule: decoded src values
                        // containing an absolute HTTP URL are used as-is.
                        if (!url.toLowerCase(java.util.Locale.ROOT).contains("http")) {
                            url = image.absUrl("src");
                        }
                        if (url != null && url.toLowerCase(java.util.Locale.ROOT).contains("http")
                                && !details.screenshotThumbs.contains(url)) {
                            details.screenshotThumbs.add(url);
                        }
                    }
                } catch (Exception e) {
                    Log.w("KinozalScreenshots", "Cannot parse AJAX screenshot response", e);
                }
            }
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                callback.accept(details);
            } else {
                runOnUiThread(() -> callback.accept(details));
            }
        };

        if (sessionWebView != null) {
            fetchBrowserPage(endpoint, null, onHtml);
        } else {
            executeSafely(io, () -> {
                try {
                    onHtml.accept(client.fetch(endpoint));
                } catch (Exception e) {
                    Log.w("KinozalScreenshots", "Cannot load screenshot AJAX endpoint", e);
                    runOnUiThread(() -> callback.accept(details));
                }
            });
        }
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
                putCachedDetailData(key, parsed);
                diskCache.put(key, html.getBytes(StandardCharsets.UTF_8));
                if (href.equals(activeTorrentDetailsHref)) {
                    progress.setVisibility(View.GONE);
                    saveRecentlyViewed(torrent);
                    // Prefetch only the detail HTML; screenshot data stays lazy.
                    showTorrentDetails(torrent, parsed);
                }
            } catch (Exception ignored) { }
        });
    }


    private void showTorrentDetails(Torrent torrent, TorrentDetails data) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(10));
        root.setBackgroundColor(BG);
        // Start slightly smaller and lower; animate the native card into place.
        root.setAlpha(0f);
        root.setScaleX(0.94f);
        root.setScaleY(0.94f);
        root.setTranslationY(dp(32));
        root.setPivotX(getResources().getDisplayMetrics().widthPixels / 2f);
        root.setPivotY(getResources().getDisplayMetrics().heightPixels / 2f);

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
        watch.setTextSize(fontSize(15));
        watch.setTextColor(Color.WHITE);
        watch.setBackground(shape(RED, 0));
        watch.setOnFocusChangeListener((v, focused) ->
                v.setBackground(shape(focused ? Color.rgb(125, 48, 25) : RED, focused ? GOLD : 0)));
        LinearLayout.LayoutParams watchParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        watchParams.rightMargin = dp(8);
        actionRow.addView(watch, watchParams);

        Button favorite = button(isFavorite(torrent) ? "★" : "☆");
        favorite.setTextSize(fontSize(25));
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
        downloadTorrent.setTextSize(fontSize(14));
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
                12, Color.LTGRAY, false);
        externalRatings.setGravity(Gravity.CENTER);
        externalRatings.setSingleLine(true);
        externalRatings.setIncludeFontPadding(false);
        ratings.addView(externalRatings, new LinearLayout.LayoutParams(-1, -2));

        TextView kinozalRating = text("Kinozal  " + data.kinozalRating, 14, TEAL, true);
        kinozalRating.setGravity(Gravity.CENTER);
        kinozalRating.setPadding(0, dp(3), 0, 0);
        ratings.addView(kinozalRating, new LinearLayout.LayoutParams(-1, -2));

        TextView stars = text(data.kinozalStars, 15, GOLD, true);
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
        Button releaseTab = button("Релиз");
        Button screenshotsTab = button("Скриншоты");
        Button commentsTab = button("Комментарии");
        Button jackettTab = button("Другие");
        Button[] tabButtons = {descriptionTab, technicalTab, releaseTab, screenshotsTab, commentsTab, jackettTab};
        for (int tabIndex = 0; tabIndex < tabButtons.length; tabIndex++) {
            LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0, dp(44), 1);
            if (tabIndex > 0) tabParams.leftMargin = dp(10);
            tabs.addView(tabButtons[tabIndex], tabParams);
        }
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
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            detailContent.removeAllViews();
            if (active[0] == 2) {
                if (!data.releaseLoaded && data.releaseText.isEmpty()) {
                    detailContent.addView(text(data.releaseLoading ? "Загружаем сведения о релизе…"
                            : "Загружаем сведения о релизе…", 15, Color.LTGRAY, false));
                    if (!data.releaseLoading && !data.releaseRequestId.isEmpty()
                            && !data.releasePagesd.isEmpty()) {
                        data.releaseLoading = true;
                        loadReleaseText(data, ready -> {
                            data.releaseLoading = false;
                            data.releaseLoaded = true;
                            if (active[0] == 2) render[0].run();
                        });
                    } else if (data.releaseRequestId.isEmpty() || data.releasePagesd.isEmpty()) {
                        data.releaseLoaded = true;
                        detailContent.removeAllViews();
                        detailContent.addView(text("Вкладка «Релиз» на странице не найдена.",
                                15, Color.LTGRAY, false));
                    }
                } else if (!data.releaseText.isEmpty()) {
                    TextView release = text(data.releaseText, 16, Color.WHITE, false);
                    release.setLineSpacing(dp(3), 1f);
                    detailContent.addView(release, new LinearLayout.LayoutParams(-1, -2));
                }
                descriptionTab.setBackground(shape(descriptionTab.hasFocus() ? Color.rgb(65, 72, 73) : PANEL, descriptionTab.hasFocus() ? GOLD : 0));
                technicalTab.setBackground(shape(technicalTab.hasFocus() ? Color.rgb(65, 72, 73) : PANEL, technicalTab.hasFocus() ? GOLD : 0));
                releaseTab.setBackground(shape(releaseTab.hasFocus() ? Color.rgb(65, 72, 73) : PANEL, releaseTab.hasFocus() ? GOLD : 0));
                detailsScroll.scrollTo(0, 0);
                return;
            }
            if (active[0] == 3) {
                if (data.screenshotThumbs.isEmpty()) {
                    String message = data.screenshotsLoading ? "Загружаем скриншоты…"
                            : data.screenshotsLoaded || data.screenshotRequestId.isEmpty()
                            || data.screenshotPagesd.isEmpty()
                            ? "Скриншоты на странице раздачи не найдены."
                            : "Загружаем скриншоты…";
                    detailContent.addView(text(message, 15, Color.LTGRAY, false));
                    if (!data.screenshotsLoaded && !data.screenshotsLoading
                            && !data.screenshotRequestId.isEmpty()
                            && !data.screenshotPagesd.isEmpty()) {
                        data.screenshotsLoading = true;
                        loadScreenshotThumbnails(data, ready -> {
                            data.screenshotsLoading = false;
                            data.screenshotsLoaded = true;
                            if (active[0] == 3) render[0].run();
                        });
                    } else if (data.screenshotRequestId.isEmpty()
                            || data.screenshotPagesd.isEmpty()) {
                        data.screenshotsLoaded = true;
                    }
                } else {
                    detailContent.addView(text("Скриншоты (" + data.screenshotThumbs.size() + ")",
                            13, TEAL, true));
                    for (int shot = 0; shot < data.screenshotThumbs.size(); shot += 2) {
                        LinearLayout shotRow = new LinearLayout(this);
                        shotRow.setOrientation(LinearLayout.HORIZONTAL);
                        shotRow.setGravity(Gravity.TOP);
                        for (int col = 0; col < 2 && shot + col < data.screenshotThumbs.size(); col++) {
                            final int shotIndex = shot + col;
                            ImageView preview = new ImageView(this);
                            preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
                            preview.setBackground(shape(PANEL, 0));
                            preview.setFocusable(false);
                            preview.setContentDescription("Скриншот " + (shotIndex + 1));
                            LinearLayout.LayoutParams imageParams =
                                    new LinearLayout.LayoutParams(0, dp(190), 1);
                            if (col == 1) imageParams.leftMargin = dp(8);
                            shotRow.addView(preview, imageParams);
                            loadImageIntoView(data.screenshotThumbs.get(shotIndex), preview);
                        }
                        detailContent.addView(shotRow,
                                new LinearLayout.LayoutParams(-1, dp(198)));
                    }
                }
                descriptionTab.setBackground(shape(descriptionTab.hasFocus() ? Color.rgb(65, 72, 73) : PANEL, descriptionTab.hasFocus() ? GOLD : 0));
                technicalTab.setBackground(shape(technicalTab.hasFocus() ? Color.rgb(65, 72, 73) : PANEL, technicalTab.hasFocus() ? GOLD : 0));
                screenshotsTab.setBackground(shape(screenshotsTab.hasFocus() ? Color.rgb(65, 72, 73) : PANEL, screenshotsTab.hasFocus() ? GOLD : 0));
                detailsScroll.scrollTo(0, 0);
                return;
            }
            if (active[0] == 4) {
                if (data.comments.isEmpty()) {
                    detailContent.addView(text("Комментарии к этой раздаче не найдены.",
                            15, Color.LTGRAY, false));
                } else {
                    detailContent.addView(text("Комментарии (" + data.comments.size() + ")",
                            13, TEAL, true));
                    for (int commentIndex = 0; commentIndex < data.comments.size(); commentIndex++) {
                        LinearLayout commentCard = new LinearLayout(this);
                        commentCard.setOrientation(LinearLayout.VERTICAL);
                        commentCard.setPadding(dp(12), dp(10), dp(12), dp(10));
                        commentCard.setBackground(shape(PANEL, dp(8)));
                        TextView number = text("Комментарий " + (commentIndex + 1),
                                12, TEAL, true);
                        number.setPadding(0, 0, 0, dp(5));
                        commentCard.addView(number);
                        TextView body = text(data.comments.get(commentIndex),
                                14, Color.WHITE, false);
                        body.setLineSpacing(dp(2), 1.0f);
                        body.setTextIsSelectable(false);
                        commentCard.addView(body, new LinearLayout.LayoutParams(-1, -2));
                        LinearLayout.LayoutParams commentParams =
                                new LinearLayout.LayoutParams(-1, -2);
                        commentParams.bottomMargin = dp(8);
                        detailContent.addView(commentCard, commentParams);
                    }
                }
                detailsScroll.scrollTo(0, 0);
                return;
            }
            String[] keys = groups[active[0] == 0 ? 0 : 1];
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
                    addDetailField(detailContent, key, value, -1, true);
                    index++;
                    continue;
                }
                LinearLayout pair = new LinearLayout(this);
                pair.setOrientation(LinearLayout.HORIZONTAL);
                pair.setGravity(Gravity.TOP);
                addDetailField(pair, key, value, 0, active[0] == 0);
                index++;
                if (index < keys.length) {
                    String nextKey = keys[index];
                    String nextValue = data.fields.get(nextKey);
                    boolean nextFullWidth = active[0] == 0
                            && ("В ролях".equals(nextKey) || "О фильме".equals(nextKey));
                    if (!nextFullWidth && nextValue != null && !nextValue.trim().isEmpty()) {
                        addDetailField(pair, nextKey, nextValue, 1, active[0] == 0);
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
            screenshotsTab.setBackground(shape(screenshotsTab.hasFocus() ? Color.rgb(65, 72, 73) : PANEL, screenshotsTab.hasFocus() ? GOLD : 0));
            detailsScroll.scrollTo(0, 0);
        };
        descriptionTab.setOnFocusChangeListener((v, focused) ->
                v.setBackground(shape(focused ? Color.rgb(65, 72, 73) : PANEL, focused ? GOLD : 0)));
        technicalTab.setOnFocusChangeListener((v, focused) ->
                v.setBackground(shape(focused ? Color.rgb(65, 72, 73) : PANEL, focused ? GOLD : 0)));
        releaseTab.setOnFocusChangeListener((v, focused) ->
                v.setBackground(shape(focused ? Color.rgb(65, 72, 73) : PANEL, focused ? GOLD : 0)));
        screenshotsTab.setOnFocusChangeListener((v, focused) ->
                v.setBackground(shape(focused ? Color.rgb(65, 72, 73) : PANEL, focused ? GOLD : 0)));
        commentsTab.setOnFocusChangeListener((v, focused) ->
                v.setBackground(shape(focused ? Color.rgb(65, 72, 73) : PANEL, focused ? GOLD : 0)));
        jackettTab.setOnFocusChangeListener((v, focused) ->
                v.setBackground(shape(focused ? Color.rgb(65, 72, 73) : PANEL, focused ? GOLD : 0)));
        descriptionTab.setOnClickListener(v -> { active[0] = 0; render[0].run(); });
        technicalTab.setOnClickListener(v -> { active[0] = 1; render[0].run(); });
        releaseTab.setOnClickListener(v -> { active[0] = 2; render[0].run(); });
        screenshotsTab.setOnClickListener(v -> { active[0] = 3; render[0].run(); });
        commentsTab.setOnClickListener(v -> { active[0] = 4; render[0].run(); });
        jackettTab.setOnClickListener(v -> searchJackett(torrent.title, data.poster));
        render[0].run();

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
        // Wait until the dialog has been attached and had a chance to draw
        // its initial state; otherwise the first animation frame can be skipped.
        root.post(() -> root.postOnAnimation(() -> {
            if (root.getParent() == null) return;
            root.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(320L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }));
        if (data.poster != null && !data.poster.isEmpty()) loadImageIntoView(data.poster, poster);
        watch.requestFocus();
        prefetchInfoHash(data);
    }

    private void addDetailField(LinearLayout parent, String key, String value, int column, boolean description) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(8), dp(7), dp(10), dp(9));
        TextView label = text(key, 16, TEAL, true);
        TextView valueView = text(value, 16, Color.WHITE, false);
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
                    .getString("torrserve_url", "http://localhost:8090").trim().replaceAll("/+$", "");
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
        executeSafely(io, () -> {
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
        executeSafely(io, () -> {
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
        executeSafely(io, () -> {
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
    @Override protected void onStart() {
        super.onStart();
        if (feedCacheReceiver == null) {
            feedCacheReceiver = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context context, android.content.Intent intent) {
                    if ("com.goblinmini.kinozalclient.FEED_REFRESH_STATE".equals(intent.getAction())) {
                        handleFeedRefreshState(intent);
                        return;
                    }
                    String group = intent.getStringExtra("feed_group");
                    String path = intent.getStringExtra("feed_path");
                    String refreshError = intent.getStringExtra("refresh_error");
                    if (refreshError != null) {
                        if (("home".equals(group) && "home".equals(activeFeedScreen))
                                || ("collections".equals(group) && "collections".equals(activeFeedScreen))) {
                            status.setText("Обновление не удалось: " + refreshError);
                        }
                        return;
                    }
                    if (group == null || path == null) return;
                    executeSafely(io, () -> {
                        List<HomeSection> snapshot = "home".equals(group)
                                ? readHomeModel() : readCollectionsModel();
                        if (snapshot == null) return;
                        HomeSection changed = null;
                        for (HomeSection section : snapshot) if (path.equals(section.path)) { changed = section; break; }
                        final HomeSection updated = changed;
                        if (updated == null) return;
                        runOnUiThread(() -> {
                            if ("home".equals(group)) {
                                homeSectionsMemory = new ArrayList<>(snapshot);
                                java.io.File tvModel = new java.io.File(getFilesDir(), "kinozal_tv_channels_v1.json");
                                TvHomeChannels.sync(MainActivity.this, tvModel);
                            } else collectionsSectionsMemory = new ArrayList<>(snapshot);
                            if (!isFinishing()) {
                                LinearLayout targetList = "home".equals(group) ? homeList : collectionsList;
                                if (targetList != null) {
                                    LinearLayout previousList = list;
                                    list = targetList;
                                    renderHomeSection(updated);
                                    list = previousList;
                                }
                                if (("home".equals(group) && "home".equals(activeFeedScreen))
                                        || ("collections".equals(group) && "collections".equals(activeFeedScreen))) {
                                    status.setText("Обновлена лента: " + updated.title);
                                }
                            }
                        });
                    });
                }
            };
        }
        android.content.IntentFilter filter = new android.content.IntentFilter(
                "com.goblinmini.kinozalclient.FEED_CACHE_UPDATED");
        filter.addAction("com.goblinmini.kinozalclient.FEED_REFRESH_STATE");
        registerReceiver(feedCacheReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
    }

    @Override protected void onStop() {
        if (feedCacheReceiver != null) {
            try { unregisterReceiver(feedCacheReceiver); } catch (Exception ignored) { }
        }
        super.onStop();
    }

    @Override protected void onDestroy() { if (socks5Bridge != null) socks5Bridge.stop(); io.shutdownNow(); imageIo.shutdownNow(); homeRefreshIo.shutdownNow(); super.onDestroy(); }
}
