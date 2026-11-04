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
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
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
        webView.setNextFocusUpId(refresh.getId());
        mirrors.setNextFocusDownId(webView.getId());
        home.setNextFocusDownId(webView.getId());
        refresh.setNextFocusDownId(webView.getId());
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
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setMinHeight(dp(44));
        button.setOnFocusChangeListener((view, hasFocus) -> {
            GradientDrawable focused = new GradientDrawable();
            focused.setColor(hasFocus ? Color.rgb(57, 53, 83) : panel);
            focused.setCornerRadius(dp(10));
            focused.setStroke(dp(hasFocus ? 2 : 1),
                    hasFocus ? accent : Color.rgb(55, 57, 68));
            view.setBackground(focused);
            view.setScaleX(hasFocus ? 1.04f : 1.0f);
            view.setScaleY(hasFocus ? 1.04f : 1.0f);
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
