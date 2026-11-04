package com.goblinmini.kinozalclient;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
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
    private int accent = Color.rgb(241, 210, 156);
    private WebView webView;
    private TextView title;
    private TextView address;
    private ProgressBar progress;
    private EditText searchInput;
    private Button categoriesButton;
    private Button searchButton;
    private String mirror;
    private boolean showSectionResultsWhenLoaded;

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
        Button results = makeButton("Список");
        Button details = makeButton("Детали");
        Button refresh = makeButton("Обновить");

        mirrors.setId(View.generateViewId());
        home.setId(View.generateViewId());
        refresh.setId(View.generateViewId());
        results.setId(View.generateViewId());
        details.setId(View.generateViewId());

        mirrors.setOnClickListener(v -> showMirrors());
        home.setOnClickListener(v -> loadHome());
        results.setOnClickListener(v -> showNativeResults());
        details.setOnClickListener(v -> showNativeDetails());
        refresh.setOnClickListener(v -> {
            if (webView != null) webView.reload();
        });

        // Explicit D-pad focus order for Android TV remotes.
        mirrors.setNextFocusRightId(home.getId());
        home.setNextFocusLeftId(mirrors.getId());
        home.setNextFocusRightId(results.getId());
        results.setNextFocusLeftId(home.getId());
        results.setNextFocusRightId(details.getId());
        details.setNextFocusLeftId(results.getId());
        details.setNextFocusRightId(refresh.getId());
        refresh.setNextFocusLeftId(details.getId());

        header.addView(mirrors, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        header.addView(home, spacedButtonParams());
        header.addView(results, spacedButtonParams());
        header.addView(details, spacedButtonParams());
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

        Button torrentsSection = makeButton("Раздачи");
        torrentsSection.setId(View.generateViewId());
        torrentsSection.setOnClickListener(v -> openSection("browse.php"));
        categoriesButton.setNextFocusDownId(torrentsSection.getId());
        searchInput.setNextFocusDownId(torrentsSection.getId());
        searchButton.setNextFocusDownId(torrentsSection.getId());
        torrentsSection.setNextFocusUpId(searchButton.getId());
        root.addView(torrentsSection, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));

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
        results.setNextFocusDownId(webView.getId());
        details.setNextFocusDownId(webView.getId());
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
                if (showSectionResultsWhenLoaded) {
                    showSectionResultsWhenLoaded = false;
                    view.postDelayed(() -> showNativeResults(), 250);
                }
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
                + "var form=document.querySelector('form#srchform');"
                + "var el=form&&form.querySelector('input[name=\\\"s\\\"],input#s');"
                + "if(!el){"
                + "var inputs=Array.from(document.querySelectorAll('input:not([type=hidden]),textarea'));"
                + "var candidates=inputs.filter(function(i){"
                + "var n=((i.name||'')+' '+(i.id||'')+' '+(i.placeholder||'')).toLowerCase();"
                + "return /search|query|keyword|name|поиск/i.test(n)||i.type==='search';"
                + "});"
                + "el=candidates.find(function(i){return i.form;})||candidates[0];"
                + "form=el&&el.form;"
                + "}"
                + "if(!el)return 'NO_SEARCH_FIELD';"
                + "if(!form)form=el.form;"
                + "if(!form)return 'NO_SEARCH_FORM';"
                + "el.value=q;el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                + "var submit=form.querySelector('input[type=submit],button[type=submit]');"
                + "if(form.requestSubmit){if(submit)form.requestSubmit(submit);else form.requestSubmit();}"
                + "else form.submit();"
                + "return 'SUBMITTED';})()";
        webView.evaluateJavascript(script, result -> {
            if ("\"NO_SEARCH_FIELD\"".equals(result)
                    || "\"NO_SEARCH_FORM\"".equals(result)) {
                inspectSearchMarkup();
            }
        });
    }

    // Extract visible result rows from the current Kinozal page.
    // Values are read from result cells only; cookies and account data are not accessed.
    private void showNativeResults() {
        if (webView == null) return;
        String script = "(function(){"
                + "var out=[],seen={};"
                + "Array.from(document.querySelectorAll('tr')).forEach(function(row){"
                + "var cells=Array.from(row.querySelectorAll('td'));"
                + "if(cells.length<4)return;"
                + "var text=(row.innerText||row.textContent||'').replace(/\\s+/g,' ').trim();"
                + "if(!text||!/(?:ГБ|МБ|GB|MB)/i.test(text))return;"
                + "var links=Array.from(row.querySelectorAll('a[href]'));"
                + "var a=links.filter(function(x){return (x.innerText||x.textContent||'').trim().length>12;})"
                + ".sort(function(x,y){return (y.innerText||y.textContent||'').trim().length-(x.innerText||x.textContent||'').trim().length;})[0];"
                + "if(!a)return;"
                + "var title=(a.innerText||a.textContent||'').replace(/\\s+/g,' ').trim();"
                + "var href=a.href||'';if(!title||!href||seen[href])return;seen[href]=1;"
                + "var meta=cells.map(function(c){return (c.innerText||c.textContent||'').replace(/\\s+/g,' ').trim();}).filter(Boolean).join('  •  ');"
                + "var img=row.querySelector('img');"
                + "out.push({title:title,href:href,meta:meta,image:img?(img.src||''):''});"
                + "});"
                + "if(out.length===0){"
                + "Array.from(document.querySelectorAll('a[href*=\"details.php?id=\"]')).forEach(function(a){"
                + "var title=(a.innerText||a.textContent||'').replace(/\\s+/g,' ').trim();"
                + "var href=a.href||'';if(!title||title.length<8||!href||seen[href])return;"
                + "var box=a.closest('tr,article,li,.item,.block,.torrent,.post,.movie,.film,div')||a.parentElement;"
                + "var meta=box?(box.innerText||box.textContent||'').replace(/\\s+/g,' ').trim():title;"
                + "meta=meta.replace(title,'').trim();"
                + "var img=box?box.querySelector('img'):null;"
                + "seen[href]=1;out.push({title:title,href:href,meta:meta,image:img?(img.src||''):''});"
                + "});"
                + "}"
                + "return JSON.stringify(out.slice(0,150));})()";
        webView.evaluateJavascript(script, value -> {
            try {
                Object decoded = new org.json.JSONTokener(value).nextValue();
                org.json.JSONArray items = new org.json.JSONArray(String.valueOf(decoded));
                if (items.length() == 0) {
                    Toast.makeText(this, "На этой странице не удалось распознать строки раздач. Открой страницу результатов поиска и повтори.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                final AlertDialog[] listDialog = new AlertDialog[1];
                android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                LinearLayout list = new LinearLayout(this);
                list.setOrientation(LinearLayout.VERTICAL);
                list.setPadding(dp(10), dp(8), dp(10), dp(8));
                for (int i = 0; i < items.length(); i++) {
                    org.json.JSONObject item = items.getJSONObject(i);
                    String itemTitle = item.optString("title", "Без названия");
                    String itemUrl = item.optString("href", "");
                    String meta = item.optString("meta", "");
                    LinearLayout card = new LinearLayout(this);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setPadding(dp(14), dp(12), dp(14), dp(12));
                    card.setFocusable(true);
                    card.setFocusableInTouchMode(true);
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(panel);
                    bg.setCornerRadius(dp(10));
                    bg.setStroke(dp(1), Color.rgb(65, 67, 82));
                    card.setBackground(bg);
                    TextView name = new TextView(this);
                    name.setText(itemTitle);
                    name.setTextColor(Color.WHITE);
                    name.setTextSize(16);
                    name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    TextView info = new TextView(this);
                    info.setText(meta);
                    info.setTextColor(Color.LTGRAY);
                    info.setTextSize(12);
                    info.setPadding(0, dp(6), 0, 0);
                    card.addView(name);
                    card.addView(info);
                    card.setOnClickListener(v -> {
                        if (!itemUrl.isEmpty()) {
                            if (listDialog[0] != null) listDialog[0].dismiss();
                            webView.post(() -> webView.loadUrl(itemUrl));
                        }
                    });
                    card.setOnFocusChangeListener((v, focused) -> {
                        GradientDrawable state = new GradientDrawable();
                        state.setColor(focused ? Color.rgb(74, 63, 45) : panel);
                        state.setCornerRadius(dp(10));
                        state.setStroke(dp(focused ? 3 : 1),
                                focused ? Color.WHITE : Color.rgb(65, 67, 82));
                        v.setBackground(state);
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.bottomMargin = dp(8);
                    list.addView(card, lp);
                }
                scroll.addView(list);
                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle("Найденные раздачи (" + items.length() + ")")
                        .setView(scroll)
                        .setNegativeButton("Закрыть", null)
                        .create();
                listDialog[0] = dialog;
                dialog.show();
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT);
                }
            } catch (Exception e) {
                Toast.makeText(this, "Не удалось обработать результаты Kinozal",
                        Toast.LENGTH_LONG).show();
            }
        });
    }


    private void downloadAndSendTorrent(String torrentLink, String posterUrl, String torrentTitle) {
        final String userAgent = webView.getSettings().getUserAgentString();
        final String referer = webView.getUrl();
        final String cookies = CookieManager.getInstance().getCookie(torrentLink);
        new Thread(() -> {
            java.net.HttpURLConnection connection = null;
            try {
                java.net.URL url = new java.net.URL(torrentLink);
                connection = (java.net.HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", userAgent);
                connection.setRequestProperty("Accept", "application/x-bittorrent,application/octet-stream,*/*");
                if (referer != null) connection.setRequestProperty("Referer", referer);
                if (cookies != null) connection.setRequestProperty("Cookie", cookies);
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
                java.io.File target = new java.io.File(getCacheDir(), "kinozal-transfer.torrent");
                try (java.io.InputStream input = connection.getInputStream();
                     java.io.FileOutputStream output = new java.io.FileOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                if (target.length() < 20) throw new java.io.IOException("Сервер вернул пустой или слишком маленький файл");
                try (java.io.FileInputStream check = new java.io.FileInputStream(target)) {
                    int first = check.read();
                    if (first != 'd') throw new java.io.IOException(
                            "Вместо .torrent получен ответ сайта. Проверь авторизацию Kinozal.");
                }
                Uri fileUri = Uri.parse("content://" + getPackageName() + ".torrentprovider/torrent");
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setPackage("ru.yourok.torrserve");
                send.setType("application/x-bittorrent");
                send.putExtra(Intent.EXTRA_STREAM, fileUri);
                send.putExtra("poster", posterUrl == null ? "" : posterUrl);
                send.putExtra("title", torrentTitle == null ? "" : torrentTitle);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(android.content.ClipData.newUri(getContentResolver(), "torrent", fileUri));
                runOnUiThread(() -> {
                    try {
                        startActivity(send);
                    } catch (android.content.ActivityNotFoundException e) {
                        Toast.makeText(this, "TorrServe MatriX не принимает файл через ACTION_SEND",
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Не удалось скачать торрент: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    // Build a structured first-pass detail view from labeled fields on the torrent page.
    private void showNativeDetails() {
        if (webView == null) return;
        String script = "(function(){"
                + "var body=document.body?document.body.innerText:'';"
                + "body=body.replace(/\\r/g,'').replace(/[ \\t]+/g,' ').replace(/\\n{2,}/g,'\\n').trim();"
                + "var labels=['Название','Оригинальное название','Год выпуска','Жанр','Выпущено','Режиссер','В ролях','О фильме','Качество','Видео','Аудио','Субтитры','Продолжительность','Перевод','Размер'];"
                + "var positions=[];"
                + "labels.forEach(function(label){var re=new RegExp('(?:^|[\\\\n ])'+label+'\\\\s*:', 'gi');"
                + "var m;while((m=re.exec(body))!==null){var p=m.index+(m[0].length-label.length-1);positions.push({label:label,start:p,valueStart:re.lastIndex});}});"
                + "positions.sort(function(a,b){return a.start-b.start;});"
                + "var fields={},seen={};"
                + "positions.forEach(function(p,i){if(seen[p.label])return;seen[p.label]=1;var next=body.length;"
                + "for(var j=i+1;j<positions.length;j++){if(positions[j].start>p.valueStart){next=positions[j].start;break;}}"
                + "var v=body.slice(p.valueStart,next).replace(/^[\\s:]+|[\\s]+$/g,'').trim();var stop=['Техданные','Релиз','Содержание','Скриншоты','Подобные','Топ по жанрам','Топ по персонам','Топ раздающего','Подобные раздачи не найдены','Комментарии ( Комментировать )'];stop.forEach(function(x){var q=v.toLowerCase().indexOf(x.toLowerCase());if(q>=0)v=v.slice(0,q).trim();});if(v)fields[p.label]=v.slice(0,3500);});"
                + "var candidates=[];function addImage(u,w,h,alt){if(!u)return;try{u=new URL(u,location.href).href;}catch(e){return;}if(!/^https?:/i.test(u))return;var ratio=h>0?w/h:0,score=0,txt=(u+' '+(alt||'')).toLowerCase();if(/poster|cover|облож|film|kino|pic|image/i.test(txt))score+=5;if(ratio>0.48&&ratio<0.82)score+=8;else if(ratio>0.35&&ratio<0.95)score+=3;if(w>=100&&h>=140)score+=3;if(w>0&&h>0)score+=Math.min(4,(w*h)/100000);candidates.push({url:u,score:score,area:w*h});}"
                + "Array.from(document.images).forEach(function(im){var w=im.naturalWidth||im.width||0,h=im.naturalHeight||im.height||0,alt=im.alt||im.title||'';addImage(im.currentSrc||im.src,w,h,alt);['data-src','data-original','data-lazy-src','data-image','data-original-src'].forEach(function(k){addImage(im.getAttribute(k),w,h,alt);});});"
                + "Array.from(document.querySelectorAll('[style*=background]')).forEach(function(el){var bg=getComputedStyle(el).backgroundImage||el.style.backgroundImage,m=bg.match(/url\\([\\\"']?(.*?)[\\\"']?\\)/i);if(m)addImage(m[1],el.clientWidth||0,el.clientHeight||0,el.className||'');});"
                + "candidates.sort(function(a,b){return b.score-a.score||b.area-a.area;});var unique=[],seenUrls={};candidates.forEach(function(c){if(!seenUrls[c.url]){seenUrls[c.url]=1;unique.push(c);}});"
                + "var poster=unique.length?unique[0].url:'';"
                + "var torrentLinks=[];function addTorrent(u,t,meta,rank){if(!u)return;var raw=String(u).trim();if(!/^magnet:/i.test(raw)){try{raw=new URL(raw,location.href).href;}catch(e){return;}}var txt=String(t||'')+' '+String(meta||'')+' '+raw;var magnet=/^magnet:/i.test(raw),file=/\\.torrent(?:[?#]|$)/i.test(raw),download=/(скачать|download|torrent|торрент)/i.test(txt);if(!magnet&&!file&&!download)return;var score=Number(rank)||0;if(magnet)score+=150;if(file)score+=120;if(/торрент|torrent/i.test(txt))score+=45;if(/скачать|download/i.test(txt))score+=25;if(/\\/(get|download|dl)([./?_-]|$)|download\\.php/i.test(raw))score+=35;torrentLinks.push({url:raw,score:score});}"
                + "Array.from(document.querySelectorAll('a[href],a[onclick],button[onclick],[role=button][onclick]')).forEach(function(a){var attrs=[a.className,a.id,a.getAttribute('title'),a.getAttribute('aria-label'),a.getAttribute('download'),a.getAttribute('rel')].join(' ');addTorrent(a.getAttribute('href')||'',a.innerText||a.textContent,attrs,0);var oc=a.getAttribute('onclick')||'',m=oc.match(/(?:location(?:\\.href)?\\s*=|open\\s*\\()\\s*['\\\"]([^'\\\"]+)/i);if(m)addTorrent(m[1],a.innerText||a.textContent,attrs+' '+oc,10);});"
                + "Array.from(document.querySelectorAll('[data-href],[data-url],[data-download],[data-torrent],[onclick]')).forEach(function(el){var oc=el.getAttribute('onclick')||'',attrs=[el.className,el.id,el.getAttribute('title'),el.getAttribute('aria-label'),el.getAttribute('data-download'),el.getAttribute('data-torrent')].join(' '),m=oc.match(/(?:location(?:\\.href)?\\s*=|open\\s*\\()\\s*['\\\"]([^'\\\"]+)/i);addTorrent(el.getAttribute('data-href')||el.getAttribute('data-url')||el.getAttribute('data-download')||el.getAttribute('data-torrent')||(m?m[1]:''),el.innerText||el.textContent,attrs+' '+oc,5);});"
                + "Array.from(document.querySelectorAll('form[action]')).forEach(function(f){var meta=[f.id,f.className,f.getAttribute('name'),f.innerText||f.textContent].join(' ');if(/torrent|торрент|download|скачать/i.test(meta))addTorrent(f.action,meta,'form '+meta,15);});"
                + "torrentLinks.sort(function(a,b){return b.score-a.score;});return JSON.stringify({title:document.title||'',fields:fields,poster:poster,posterCandidates:unique.slice(0,8).map(function(c){return c.url;}),torrentLink:torrentLinks.length?torrentLinks[0].url:''});})()";
        webView.evaluateJavascript(script, value -> {
            try {
                Object decoded = new org.json.JSONTokener(value).nextValue();
                org.json.JSONObject data = new org.json.JSONObject(String.valueOf(decoded));
                org.json.JSONObject fields = data.optJSONObject("fields");
                if (fields == null || fields.length() == 0) {
                    Toast.makeText(this, "Не удалось найти подписанные поля на странице раздачи",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                LinearLayout root = new LinearLayout(this);
                root.setOrientation(LinearLayout.VERTICAL);
                root.setPadding(dp(12), dp(8), dp(12), dp(8));
                LinearLayout tabs = new LinearLayout(this);
                tabs.setOrientation(LinearLayout.HORIZONTAL);
                tabs.setGravity(Gravity.CENTER_VERTICAL);
                android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                scroll.setFillViewport(true);
                LinearLayout content = new LinearLayout(this);
                content.setOrientation(LinearLayout.VERTICAL);
                content.setPadding(dp(14), dp(8), dp(14), dp(12));
                scroll.addView(content);
                LinearLayout mainArea = new LinearLayout(this);
                mainArea.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout posterColumn = new LinearLayout(this);
                posterColumn.setOrientation(LinearLayout.VERTICAL);
                posterColumn.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                posterColumn.setPadding(0, 0, 0, 0);
                ImageView posterView = new ImageView(this);
                posterView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                posterView.setBackgroundColor(Color.TRANSPARENT);
                TextView posterStatus = new TextView(this);
                posterStatus.setTextColor(Color.WHITE);
                posterStatus.setTextSize(12);
                posterStatus.setGravity(Gravity.CENTER);
                posterStatus.setPadding(dp(4), dp(8), dp(4), dp(8));
                String posterUrl = data.optString("poster", "");
                org.json.JSONArray posterCandidates = data.optJSONArray("posterCandidates");
                int candidateCount = posterCandidates == null ? 0 : posterCandidates.length();
                String posterLabel = posterUrl.isEmpty() ? "Постер не найден" : "Найден адрес постера";
                posterStatus.setText(posterLabel + "\\nКандидатов: " + candidateCount);
                posterColumn.addView(posterView, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));
                Button watchButton = makeButton("Просмотр");
                watchButton.setTextSize(12);
                watchButton.setMinHeight(dp(40));
                watchButton.setPadding(dp(10), dp(5), dp(10), dp(5));
                watchButton.setOnClickListener(v -> {
                    String torrentLink = data.optString("torrentLink", "").trim();
                    if (torrentLink.isEmpty()) {
                        Toast.makeText(this, "Не удалось определить ссылку скачивания на странице раздачи",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        Uri torrentUri = Uri.parse(torrentLink);
                        if ("magnet".equalsIgnoreCase(torrentUri.getScheme())) {
                            Intent intent = new Intent(Intent.ACTION_VIEW, torrentUri);
                            intent.setPackage("ru.yourok.torrserve");
                            intent.putExtra("poster", data.optString("poster", ""));
                            intent.putExtra("title", data.optString("title", ""));
                            startActivity(intent);
                        } else {
                            downloadAndSendTorrent(torrentLink,
                                    data.optString("poster", ""), data.optString("title", ""));
                        }
                    } catch (android.content.ActivityNotFoundException e) {
                        Toast.makeText(this, "TorrServe MatriX не установлен", Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Не удалось передать торрент: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
                watchButton.setBackgroundTintList(null);
                watchButton.setBackground(buttonBackground(Color.rgb(174, 55, 18), Color.rgb(174, 55, 18)));
                watchButton.setTextColor(Color.WHITE);
                watchButton.setOnFocusChangeListener((view, hasFocus) -> {
                    view.setBackground(buttonBackground(Color.rgb(174, 55, 18),
                            hasFocus ? Color.WHITE : Color.rgb(174, 55, 18)));
                    view.setElevation(dp(hasFocus ? 10 : 2));
                });
                LinearLayout.LayoutParams watchParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
                watchParams.topMargin = dp(10);
                posterColumn.addView(watchButton, watchParams);
                LinearLayout.LayoutParams posterParams = new LinearLayout.LayoutParams(dp(200),
                        ViewGroup.LayoutParams.MATCH_PARENT);
                posterParams.rightMargin = dp(10);
                mainArea.addView(posterColumn, posterParams);
                if (!posterUrl.isEmpty() && (posterUrl.startsWith("https://") || posterUrl.startsWith("http://"))) {
                    // Read WebView and cookie values on the UI thread before starting the worker.
                    final String posterUserAgent = webView.getSettings().getUserAgentString();
                    final String posterPageUrl = webView.getUrl();
                    final String posterCookie = CookieManager.getInstance().getCookie(posterUrl);
                    new Thread(() -> {
                        try {
                            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                                    new java.net.URL(posterUrl).openConnection();
                            conn.setConnectTimeout(7000);
                            conn.setReadTimeout(10000);
                            conn.setInstanceFollowRedirects(true);
                            conn.setRequestProperty("User-Agent", posterUserAgent);
                            conn.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
                            if (posterPageUrl != null) conn.setRequestProperty("Referer", posterPageUrl);
                            if (posterCookie != null) conn.setRequestProperty("Cookie", posterCookie);
                            android.graphics.Bitmap bitmap;
                            int responseCode = conn.getResponseCode();
                            try (java.io.InputStream input = responseCode >= 200 && responseCode < 300
                                    ? conn.getInputStream() : conn.getErrorStream()) {
                                bitmap = responseCode >= 200 && responseCode < 300 && input != null
                                        ? BitmapFactory.decodeStream(input) : null;
                            } finally { conn.disconnect(); }
                            if (bitmap != null) {
                                runOnUiThread(() -> {
                                    posterView.setImageBitmap(bitmap);
                                });
                            } else {
                            }
                        } catch (Exception e) {
                        }
                    }).start();
                }
                root.addView(tabs);
                mainArea.addView(scroll, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                LinearLayout.LayoutParams mainAreaParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
                mainAreaParams.topMargin = dp(16);
                root.addView(mainArea, mainAreaParams);
                //
                /*                root.addView(tabs);
                root.addView(scroll, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)); */

                String[][] groups = {
                        {"Описание","Название","Оригинальное название","Год выпуска","Жанр","Выпущено","Режиссер","В ролях","О фильме"},
                        {"Техданные","Качество","Видео","Аудио","Субтитры","Продолжительность","Перевод","Размер"}
                };
                final int[] active = {0};
                Runnable[] render = new Runnable[1];
                render[0] = () -> {
                    content.removeAllViews();
                    if (active[0] == 0) {
                        String[] compactKeys = {"Название","Оригинальное название","Год выпуска","Жанр","Выпущено","Режиссер"};
                        for (int k = 0; k < compactKeys.length; k += 2) {
                            LinearLayout row = new LinearLayout(this);
                            row.setOrientation(LinearLayout.HORIZONTAL);
                            row.setGravity(Gravity.TOP);
                            for (int c = 0; c < 2; c++) {
                                String key = compactKeys[k + c];
                                String valueText = fields.optString(key, "").trim();
                                LinearLayout block = new LinearLayout(this);
                                block.setOrientation(LinearLayout.VERTICAL);
                                block.setPadding(dp(8), dp(5), dp(8), dp(7));
                                TextView label = new TextView(this);
                                label.setText(key);
                                label.setTextColor(accent);
                                label.setTextSize(12);
                                label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                                TextView valueView = new TextView(this);
                                valueView.setText(valueText.isEmpty() ? "—" : valueText);
                                valueView.setTextColor(Color.WHITE);
                                valueView.setTextSize(14);
                                valueView.setLineSpacing(dp(1), 1.0f);
                                block.addView(label);
                                block.addView(valueView);
                                row.addView(block, new LinearLayout.LayoutParams(0,
                                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                            }
                            content.addView(row);
                            View divider = new View(this);
                            divider.setBackgroundColor(Color.rgb(65, 67, 82));
                            content.addView(divider, new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                        }
                        String[] wideKeys = {"В ролях","О фильме"};
                        for (String key : wideKeys) {
                            String valueText = fields.optString(key, "").trim();
                            if (valueText.isEmpty()) continue;
                            LinearLayout block = new LinearLayout(this);
                            block.setOrientation(LinearLayout.VERTICAL);
                            block.setPadding(dp(8), dp(7), dp(8), dp(9));
                            TextView label = new TextView(this);
                            label.setText(key);
                            label.setTextColor(accent);
                            label.setTextSize(12);
                            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                            TextView valueView = new TextView(this);
                            valueView.setText(valueText);
                            valueView.setTextColor(Color.WHITE);
                            valueView.setTextSize(14);
                            valueView.setLineSpacing(dp(1), 1.0f);
                            block.addView(label);
                            block.addView(valueView);
                            content.addView(block);
                            View divider = new View(this);
                            divider.setBackgroundColor(Color.rgb(65, 67, 82));
                            content.addView(divider, new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                        }
                    } else {
                        String[] group = groups[active[0]];
                        boolean added = false;
                        for (int k = 1; k < group.length; k++) {
                            String key = group[k];
                            String valueText = fields.optString(key, "").trim();
                            if (valueText.isEmpty()) continue;
                            added = true;
                            LinearLayout block = new LinearLayout(this);
                            block.setOrientation(LinearLayout.VERTICAL);
                            block.setPadding(dp(8), dp(5), dp(8), dp(7));
                            TextView label = new TextView(this);
                            label.setText(key);
                            label.setTextColor(accent);
                            label.setTextSize(12);
                            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                            TextView valueView = new TextView(this);
                            valueView.setText(valueText);
                            valueView.setTextColor(Color.WHITE);
                            valueView.setTextSize(14);
                            valueView.setLineSpacing(dp(1), 1.0f);
                            block.addView(label);
                            block.addView(valueView);
                            content.addView(block);
                            View divider = new View(this);
                            divider.setBackgroundColor(Color.rgb(65, 67, 82));
                            content.addView(divider, new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                        }
                        if (!added) {
                            TextView empty = new TextView(this);
                            empty.setText("В этом разделе пока нет данных.");
                            empty.setTextColor(Color.LTGRAY);
                            empty.setTextSize(15);
                            empty.setPadding(dp(8), dp(14), dp(8), dp(14));
                            content.addView(empty);
                        }
                    }
                    for (int n = 0; n < tabs.getChildCount(); n++) {
                        View child = tabs.getChildAt(n);
                        if (child instanceof Button) {
                            ((Button) child).setTextColor(n == active[0] ? Color.WHITE : Color.LTGRAY);
                        }
                    }
                    scroll.scrollTo(0, 0);
                };
                for (int n = 0; n < groups.length; n++) {
                    final int index = n;
                    Button tab = makeButton(groups[n][0]);
                    tab.setTextSize(12);
                    tab.setMinHeight(dp(40));
                    tab.setPadding(dp(10), dp(5), dp(10), dp(5));
                    tab.setOnClickListener(v -> {
                        active[0] = index;
                        render[0].run();
                    });
                    tabs.addView(tab, spacedButtonParams());
                }
                render[0].run();

                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle("Подробности раздачи")
                        .setView(root)
                        .setNegativeButton("Закрыть", null)
                        .create();
                dialog.show();
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT);
                }
                posterColumn.post(() -> watchButton.requestFocus());
            } catch (Exception e) {
                Toast.makeText(this, "Не удалось обработать сведения о раздаче",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    // Collect form structure only; never read entered values or cookies.
    private void inspectSearchMarkup() {
        String script = "(function(){"
                + "var clean=function(s){return (s||'').replace(/\\s+/g,' ').trim().slice(0,160);};"
                + "var out={url:location.href,title:document.title,forms:[],unboundInputs:[]};"
                + "Array.from(document.forms).forEach(function(f,idx){"
                + "var item={index:idx,method:(f.method||'get').toUpperCase(),action:f.getAttribute('action')||'',id:f.id||'',name:f.getAttribute('name')||'',className:clean(f.className),fields:[]};"
                + "Array.from(f.querySelectorAll('input,textarea,select,button')).forEach(function(e){"
                + "item.fields.push({tag:e.tagName.toLowerCase(),type:e.type||'',name:e.name||'',id:e.id||'',placeholder:e.getAttribute('placeholder')||'',ariaLabel:e.getAttribute('aria-label')||'',text:clean(e.innerText||e.textContent)});});out.forms.push(item);});"
                + "Array.from(document.querySelectorAll('input,textarea')).filter(function(e){return !e.form;}).forEach(function(e){"
                + "out.unboundInputs.push({tag:e.tagName.toLowerCase(),type:e.type||'',name:e.name||'',id:e.id||'',placeholder:e.getAttribute('placeholder')||'',ariaLabel:e.getAttribute('aria-label')||''});});"
                + "return JSON.stringify(out);})()";
        webView.evaluateJavascript(script, value -> {
            try {
                Object decoded = new org.json.JSONTokener(value).nextValue();
                String report = new org.json.JSONObject(String.valueOf(decoded)).toString(2);
                TextView hint = new TextView(this);
                hint.setText("Сфотографируй отчёт. Если он не помещается, прокрути вниз. Значения полей и cookies не считываются.");
                hint.setTextColor(Color.LTGRAY);
                hint.setTextSize(14);
                hint.setPadding(dp(16), dp(10), dp(16), dp(10));
                TextView details = new TextView(this);
                details.setText(report);
                details.setTextColor(Color.WHITE);
                details.setTextSize(14);
                details.setTypeface(android.graphics.Typeface.MONOSPACE);
                details.setTextIsSelectable(true);
                details.setPadding(dp(16), dp(8), dp(16), dp(16));
                android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                scroll.setFillViewport(true);
                scroll.addView(details);
                LinearLayout content = new LinearLayout(this);
                content.setOrientation(LinearLayout.VERTICAL);
                content.addView(hint);
                content.addView(scroll, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle("Структура поисковой разметки")
                        .setView(content)
                        .setPositiveButton("Готово", null)
                        .create();
                dialog.show();
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT);
                }
            } catch (Exception e) {
                Toast.makeText(this, "Не удалось получить разметку страницы",
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
        button.setFocusableInTouchMode(false);
        button.setMinHeight(dp(44));
        button.setElevation(dp(2));
        button.setOnFocusChangeListener((view, hasFocus) -> {
            GradientDrawable stateBackground = new GradientDrawable();
            stateBackground.setColor(hasFocus
                    ? Color.rgb(74, 63, 45)
                    : panel);
            stateBackground.setCornerRadius(dp(10));
            stateBackground.setStroke(dp(hasFocus ? 3 : 1),
                    hasFocus ? accent : Color.rgb(75, 77, 91));
            view.setBackground(stateBackground);
            view.setElevation(dp(hasFocus ? 10 : 2));
            view.setScaleX(hasFocus ? 1.06f : 1.0f);
            view.setScaleY(hasFocus ? 1.06f : 1.0f);
            button.setTextColor(Color.WHITE);
        });
        return button;
    }

    private GradientDrawable buttonBackground(int fillColor, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(10));
        drawable.setStroke(dp(2), strokeColor);
        return drawable;
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

    private void openSection(String path) {
        if (webView == null) return;
        String base = mirror == null ? DEFAULT_MIRROR : mirror;
        base = base.replaceAll("/+$", "");
        String url = base + "/" + path;
        showSectionResultsWhenLoaded = true;
        address.setText(url);
        webView.loadUrl(url);
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
