package com.goblinmini.kinozalclient;

import android.app.Activity;
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
    private final List<Torrent> torrents = new ArrayList<>();
    private int page = 0;
    private volatile String lastHtml = "";

    private static class Category { String title, href; }

    private static class Torrent {
        String title, href, size, seeds, leeches;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        mirror = getSharedPreferences(PREFS, MODE_PRIVATE).getString("mirror", MIRRORS[0]);
        client = new KinozalHttpClient(this, mirror);
        buildShell();
        load("browse.php", "Все раздачи");
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
        addMenu("Поиск", () -> focusSearch());
        addMenu("Все раздачи", () -> load("browse.php", "Все раздачи"));
        addMenu("Фильмы", () -> categories());
        addMenu("Сериалы", () -> categories());
        addMenu("Наше кино", () -> categories());
        addMenu("Зарубежные сериалы", () -> categories());
        addMenu("Российские сериалы", () -> categories());
        addMenu("Телевидение", () -> categories());
        addMenu("Спорт", () -> categories());
        addMenu("Избранное", () -> showFavorites());
        addMenu("Авторизация", () -> showLogin());
        addMenu("Зеркало", () -> chooseMirror());
        status = text("Подключение…", 11, TEAL, false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, dp(28));
        statusParams.topMargin = dp(4);
        sidebar.addView(status, statusParams);

        heading = text("Все раздачи", 25, GOLD, true);
        main.addView(heading, new LinearLayout.LayoutParams(-1, dp(48)));
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
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true); progress.setVisibility(View.GONE);
        main.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        main.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        sidebar.getChildAt(1).requestFocus();
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

    private void load(String path, String title) {
        currentPath = path; currentTitle = title; heading.setText(title);
        progress.setVisibility(View.VISIBLE); list.removeAllViews();
        io.execute(() -> {
            try {
                String html = client.fetch(path);
                lastHtml = html;
                Document doc = Jsoup.parse(html, client.getMirror());
                List<Torrent> parsed = parseTorrents(doc);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE); torrents.clear(); torrents.addAll(parsed);
                    status.setText("Загружено: " + parsed.size());
                    renderTorrents(parsed);
                    if (parsed.isEmpty()) addEmpty("На этой странице не удалось найти список раздач.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> { progress.setVisibility(View.GONE); status.setText("Нет соединения");
                    addEmpty("Не удалось загрузить каталог. " + safe(e.getMessage())); });
            }
        });
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
            } else {
                t.size = "—";
                t.seeds = "—";
                t.leeches = "—";
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
        TextView header = text("  НАЗВАНИЕ РАЗДАЧИ                                      РАЗМЕР       СИДЫ   ЛИЧИ", 11, TEAL, true);
        header.setPadding(dp(12), dp(8), dp(12), dp(8)); list.addView(header);
        for (Torrent t : items) {
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(8), dp(14), dp(8)); row.setBackground(shape(PANEL, 0));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(66)); rp.bottomMargin = dp(5);
            TextView name = text(t.title, 16, Color.WHITE, true); name.setMaxLines(2);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(text(t.size, 13, GOLD, false), new LinearLayout.LayoutParams(dp(85), -2));
            row.addView(text(t.seeds, 13, TEAL, true), new LinearLayout.LayoutParams(dp(55), -2));
            row.addView(text(t.leeches, 13, RED, true), new LinearLayout.LayoutParams(dp(55), -2));
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

    private void categories() {
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
                                ? "browse.php" : form.absUrl("action");
                        if (action.isEmpty()) action = client.getMirror() + "/browse.php";
                        String separator = action.contains("?") ? "&" : "?";
                        String target = action + separator
                                + java.net.URLEncoder.encode(field, "UTF-8") + "="
                                + java.net.URLEncoder.encode(value, "UTF-8");
                        Category item = new Category(); item.title = label; item.href = target;
                        unique.putIfAbsent(target, item);
                    }
                }

                found.addAll(unique.values());
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    if (found.isEmpty()) {
                        showMessage("На странице зеркала не найдены ссылки разделов Kinozal. Проверьте выбранное зеркало и авторизацию.");
                        return;
                    }
                    String[] labels = new String[found.size()];
                    for (int i = 0; i < found.size(); i++) labels[i] = found.get(i).title;
                    new AlertDialog.Builder(this).setTitle("Категории Kinozal")
                            .setItems(labels, (dialog, which) -> {
                                Category selected = found.get(which);
                                load(selected.href, selected.title);
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
        String[] saved = client.getSavedCredentials();
        if (saved != null) {
            progress.setVisibility(View.VISIBLE);
            status.setText("Восстановление сессии…");
            io.execute(() -> {
                try {
                    client.login(saved[0], saved[1]);
                    runOnUiThread(() -> {
                        progress.setVisibility(View.GONE);
                        status.setText("Авторизован");
                        showMessage("Вход выполнен. Сессия сохранена.");
                        load("browse.php", "Все раздачи");
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        progress.setVisibility(View.GONE);
                        status.setText("Требуется вход");
                        showMessage("Не удалось восстановить сессию: " + safe(e.getMessage()));
                        showNativeLoginForm(saved);
                    });
                }
            });
            return;
        }
        showNativeLoginForm(null);
    }

    private void showNativeLoginForm(String[] saved) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        form.setBackgroundColor(BG);

        EditText username = new EditText(this);
        username.setSingleLine(true);
        username.setTextColor(GOLD);
        username.setHintTextColor(TEAL);
        username.setHint("Логин Kinozal");
        username.setBackground(shape(PANEL, TEAL));
        username.setPadding(dp(14), 0, dp(14), 0);
        username.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        form.addView(username, new LinearLayout.LayoutParams(-1, dp(52)));

        EditText password = new EditText(this);
        password.setSingleLine(true);
        password.setTextColor(GOLD);
        password.setHintTextColor(TEAL);
        password.setHint("Пароль");
        password.setBackground(shape(PANEL, TEAL));
        password.setPadding(dp(14), 0, dp(14), 0);
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams passwordParams = new LinearLayout.LayoutParams(-1, dp(52));
        passwordParams.topMargin = dp(10);
        form.addView(password, passwordParams);

        if (saved != null) {
            username.setText(saved[0]);
            password.setText(saved[1]);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Авторизация Kinozal")
                .setView(form)
                .setPositiveButton("Войти", null)
                .setNeutralButton("Забыть данные", null)
                .setNegativeButton("Отмена", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String user = username.getText().toString().trim();
                String pass = password.getText().toString();
                if (user.isEmpty() || pass.isEmpty()) {
                    showMessage("Введите логин и пароль.");
                    return;
                }
                progress.setVisibility(View.VISIBLE);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                io.execute(() -> {
                    try {
                        client.login(user, pass);
                        client.saveCredentials(user, pass);
                        runOnUiThread(() -> {
                            progress.setVisibility(View.GONE);
                            dialog.dismiss();
                            status.setText("Авторизован");
                            showMessage("Вход выполнен. Данные сохранены, повторный ввод не потребуется.");
                            load("browse.php", "Все раздачи");
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            progress.setVisibility(View.GONE);
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            showMessage("Ошибка входа: " + safe(e.getMessage()));
                        });
                    }
                });
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                client.clearSavedCredentials();
                client.logout();
                username.setText("");
                password.setText("");
                showMessage("Сохранённые данные и сессия очищены.");
            });
        });
        dialog.show();
    }

    private void chooseMirror() {
        String[] labels = {"Kinozal CloudNS", "Kinozal Guru", "Kinozal TV", "Kinozal Me"};
        new AlertDialog.Builder(this).setTitle("Зеркало").setItems(labels, (d, which) -> {
            mirror = MIRRORS[which]; getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("mirror", mirror).apply();
            client.setMirror(mirror); load("browse.php", "Все раздачи");
        }).setNegativeButton("Отмена", null).show();
    }

    private void showFavorites() { addEmpty("Избранное пока пусто. Добавление раздач будет подключено вместе с карточкой раздачи."); }

    private void showTorrent(Torrent t) {
        new AlertDialog.Builder(this).setTitle(t.title)
                .setMessage("Размер: " + t.size + "\nСиды: " + t.seeds + "\nЛичи: " + t.leeches)
                .setPositiveButton("Открыть на сайте", (d,w) -> {
                    load(t.href.replace(client.getMirror() + "/", ""), t.title);
                }).setNegativeButton("Закрыть", null).show();
    }

    private void addEmpty(String message) {
        list.removeAllViews(); TextView empty = text(message, 17, Color.LTGRAY, false);
        empty.setPadding(dp(22), dp(24), dp(22), dp(24)); list.addView(empty);
    }

    private String safe(String s) { return s == null ? "Ошибка сети" : s; }

    @Override public void onBackPressed() { load("browse.php", "Все раздачи"); }
    @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }
}
