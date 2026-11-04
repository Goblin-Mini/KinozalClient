package com.goblinmini.kinozalclient;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String PREFS = "kinozal_settings";
    private static final String DEFAULT_MIRROR = "https://kinozal.cloudns.nz";
    private static final String[] MIRRORS = {
            "https://kinozal.cloudns.nz",
            "https://kinozal.guru",
            "https://kinozal.tv",
            "https://kinozal.me"
    };

    private int background = Color.rgb(16, 17, 22);
    private int panel = Color.rgb(27, 29, 37);
    private int accent = Color.rgb(139, 128, 249);
    private WebView webView;
    private TextView title;
    private TextView address;
    private ProgressBar progress;
    private EditText searchInput;
    private Button categoriesButton;
    private Button searchButton;
    private String mirror;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mirror = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("mirror", DEFAULT_MIRROR);
        buildScreen();
        loadHome();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);

        title = new TextView(this);
        title.setText("KINOZAL");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));

        Button mirrors = makeButton("Зеркало");
        Button home = makeButton("Главная");
        Button refresh = makeButton("Обновить");

        mirrors.setId(View.generateViewId());
        home.setId(View.generateViewId());
        refresh.setId(View.generateViewId());

        mirrors.setOnClickListener(v -> showMirrors());
        home.setOnClickListener(v -> loadHome());
        refresh.setOnClickListener(v -> {
            if (webView != null) webView.reload();
        });

        // Explicit D-pad focus order for Android TV remotes.
        mirrors.setNextFocusRightId(home.getId());
        home.setNextFocusLeftId(mirrors.getId());
        home.setNextFocusRightId(refresh.getId());
        refresh.setNextFocusLeftId(home.getId());

        header.addView(mirrors, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        header.addView(home, spacedButtonParams());
        header.addView(refresh, spacedButtonParams());
        root.addView(header);

        LinearLayout catalogBar = new LinearLayout(this);
        catalogBar.setGravity(Gravity.CENTER_VERTICAL);
        catalogBar.setOrientation(LinearLayout.HORIZONTAL);
        catalogBar.setPadding(0, dp(4), 0, dp(4));

        categoriesButton = makeButton("Категории");
        categoriesButton.setId(View.generateViewId());
        categoriesButton.setOnClickListener(v -> showCategories());
        catalogBar.addView(categoriesButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));

        searchInput = new EditText(this);
        searchInput.setId(View.generateViewId());
        searchInput.setSingleLine(true);
        searchInput.setTextSize(14);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setHintTextColor(Color.LTGRAY);
        searchInput.setHint("Поиск по Kinozal");
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setPadding(dp(12), 0, dp(12), 0);
        searchInput.setBackground(makeSearchBackground());
        searchInput.setFocusable(true);
        searchInput.setFocusableInTouchMode(true);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                0, dp(44), 1);
        searchParams.leftMargin = dp(8);
        catalogBar.addView(searchInput, searchParams);

        searchButton = makeButton("Найти");
        searchButton.setId(View.generateViewId());
        searchButton.setOnClickListener(v -> submitSiteSearch());
        LinearLayout.LayoutParams searchButtonParams = spacedButtonParams();
        catalogBar.addView(searchButton, searchButtonParams);
        searchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                submitSiteSearch();
                return true;
            }
            return false;
        });

        categoriesButton.setNextFocusRightId(searchInput.getId());
        searchInput.setNextFocusLeftId(categoriesButton.getId());
        searchInput.setNextFocusRightId(searchButton.getId());
        searchButton.setNextFocusLeftId(searchInput.getId());
        refresh.setNextFocusDownId(categoriesButton.getId());
        categoriesButton.setNextFocusUpId(refresh.getId());
        searchInput.setNextFocusUpId(refresh.getId());
        searchButton.setNextFocusUpId(refresh.getId());
        root.addView(catalogBar);

        address = new TextView(this);
        address.setTextColor(Color.LTGRAY);
        address.setTextSize(11);
        address.setSingleLine(true);
        address.setPadding(dp(4), dp(2), dp(4), dp(6));
        root.addView(address);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        webView = new WebView(this);
        webView.setId(View.generateViewId());
        webView.setNextFocusUpId(searchButton.getId());
        mirrors.setNextFocusDownId(webView.getId());
        home.setNextFocusDownId(webView.getId());
        refresh.setNextFocusDownId(categoriesButton.getId());
        categoriesButton.setNextFocusDownId(webView.getId());
        searchInput.setNextFocusDownId(webView.getId());
        searchButton.setNextFocusDownId(webView.getId());
        webView.setBackgroundColor(background);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setLoadsImagesAutomatically(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onReceivedTitle(WebView view, String pageTitle) {
                title.setText(pageTitle == null || pageTitle.trim().isEmpty()
                        ? "KINOZAL" : pageTitle);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                address.setText(url);
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        // Give TV users a visible, predictable starting focus.
        mirrors.post(() -> {
            if (!isFinishing() && mirrors.isShown()) {
                mirrors.requestFocus();
            }
        });
    }

    private GradientDrawable makeSearchBackground() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(panel);
        shape.setCornerRadius(dp(10));
        shape.setStroke(dp(1), Color.rgb(75, 77, 91));
        return shape;
    }

    private void showCategories() {
        if (webView == null) return;
        String script = "(function(){"
                + "var out=[],seen={};"
                + "document.querySelectorAll('a[href]').forEach(function(a){"
                + "var t=(a.innerText||a.textContent||'').trim().replace(/\\s+/g,' ');"
                + "var h=a.getAttribute('href')||'';"
                + "if(!t||t.length>70||!h||h.charAt(0)==='#')return;"
                + "if(/(?:cat|category|browse|section)=/i.test(h)||/browse\\.php/i.test(h)){"
                + "var k=t+'|'+h;if(!seen[k]){seen[k]=1;out.push({title:t,href:h});}}"
                + "});return JSON.stringify(out.slice(0,100));})()";
        webView.evaluateJavascript(script, value -> {
            try {
                Object decoded = new org.json.JSONTokener(value).nextValue();
                org.json.JSONArray items = new org.json.JSONArray(String.valueOf(decoded));
                if (items.length() == 0) {
                    Toast.makeText(this,
                            "На этой странице категории не обнаружены. Открой главную страницу сайта и попробуй снова.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                java.util.ArrayList<String> labels = new java.util.ArrayList<>();
                java.util.ArrayList<String> links = new java.util.ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    org.json.JSONObject item = items.getJSONObject(i);
                    String label = item.optString("title", "").trim();
                    String href = item.optString("href", "").trim();
                    if (!label.isEmpty() && !href.isEmpty()) {
                        labels.add(label);
                        links.add(href);
                    }
                }
                if (labels.isEmpty()) return;
                new AlertDialog.Builder(this)
                        .setTitle("Категории сайта")
                        .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                            try {
                                java.net.URI base = java.net.URI.create(mirror + "/");
                                java.net.URI target = base.resolve(links.get(which));
                                if (target.getHost() != null
                                        && target.getHost().equalsIgnoreCase(base.getHost())
                                        && ("https".equalsIgnoreCase(target.getScheme())
                                        || "http".equalsIgnoreCase(target.getScheme()))) {
                                    webView.loadUrl(target.toString());
                                } else {
                                    Toast.makeText(this, "Ссылка категории ведёт на другой домен",
                                            Toast.LENGTH_LONG).show();
                                }
                            } catch (Exception ex) {
                                Toast.makeText(this, "Некорректная ссылка категории",
                                        Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton("Закрыть", null)
                        .show();
            } catch (Exception e) {
                Toast.makeText(this, "Не удалось прочитать категории этой страницы",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void submitSiteSearch() {
        String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "Введи название для поиска", Toast.LENGTH_SHORT).show();
            searchInput.requestFocus();
            return;
        }
        String escaped = org.json.JSONObject.quote(query);
        String script = "(function(){"
                + "var q=" + escaped + ";"
                + "var inputs=Array.from(document.querySelectorAll('input:not([type=hidden]),textarea'));"
                + "var candidates=inputs.filter(function(i){"
                + "var n=((i.name||'')+' '+(i.id||'')+' '+(i.placeholder||'')).toLowerCase();"
                + "return /search|query|keyword|name|поиск/i.test(n)||i.type==='search';"
                + "});"
                + "var el=candidates.find(function(i){return i.form;})||candidates[0];"
                + "if(!el)return 'NO_SEARCH_FIELD';"
                + "el.value=q;el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                + "if(el.form){if(el.form.requestSubmit)el.form.requestSubmit();else el.form.submit();return 'SUBMITTED';}"
                + "var b=el.closest('form');if(b){b.submit();return 'SUBMITTED';}"
                + "return 'NO_SEARCH_FORM';})()";
        webView.evaluateJavascript(script, result -> {
            if ("\"NO_SEARCH_FIELD\"".equals(result)
                    || "\"NO_SEARCH_FORM\"".equals(result)) {
                Toast.makeText(this,
                        "Не удалось определить поисковую форму. Нужна проверка разметки сайта.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private LinearLayout.LayoutParams spacedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        params.leftMargin = dp(6);
        return params;
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(panel);
        shape.setCornerRadius(dp(10));
        button.setBackground(shape);
        // Remove the platform Button tint so our custom focus colors stay visible.
        button.setBackgroundTintList(null);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setMinHeight(dp(44));
        button.setElevation(dp(2));
        button.setOnFocusChangeListener((view, hasFocus) -> {
            GradientDrawable stateBackground = new GradientDrawable();
            stateBackground.setColor(hasFocus
                    ? Color.rgb(91, 75, 170)
                    : panel);
            stateBackground.setCornerRadius(dp(10));
            stateBackground.setStroke(dp(hasFocus ? 3 : 1),
                    hasFocus ? Color.WHITE : Color.rgb(75, 77, 91));
            view.setBackground(stateBackground);
            view.setElevation(dp(hasFocus ? 10 : 2));
            view.setScaleX(hasFocus ? 1.06f : 1.0f);
            view.setScaleY(hasFocus ? 1.06f : 1.0f);
            button.setTextColor(Color.WHITE);
        });
        return button;
    }

    private void showMirrors() {
        String[] options = {
                "Kinozal CloudNS (по умолчанию)",
                "Kinozal Guru",
                "Kinozal TV",
                "Kinozal Me",
                "Указать свой адрес"
        };
        new AlertDialog.Builder(this)
                .setTitle("Выбор зеркала")
                .setItems(options, (dialog, which) -> {
                    if (which < MIRRORS.length) {
                        setMirror(MIRRORS[which]);
                    } else {
                        showCustomMirrorDialog();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showCustomMirrorDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setText(mirror);
        input.setHint("https://example.com");
        new AlertDialog.Builder(this)
                .setTitle("Адрес своего зеркала")
                .setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.matches("(?i)^https?://[^\\s]+$")) {
                        setMirror(value.replaceAll("/+$", ""));
                    } else {
                        Toast.makeText(this, "Укажите корректный HTTP(S) URL",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void setMirror(String value) {
        mirror = value;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("mirror", mirror).apply();
        loadHome();
    }

    private void loadHome() {
        address.setText(mirror);
        webView.loadUrl(mirror);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
