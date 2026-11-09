package com.goblinmini.kinozalclient;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a short-lived, invisible WebView from a Worker. Cloudflare's browser
 * challenge needs JavaScript; a plain HttpURLConnection cannot complete it.
 * All WebView creation, navigation and destruction happen on the main thread.
 */
final class BrowserFeedFetcher {
    static final class Result {
        final String html;
        final String cookies;
        final int httpStatus;
        final String finalUrl;
        final long durationMs;
        Result(String html, String cookies, int httpStatus, String finalUrl, long durationMs) {
            this.html = html; this.cookies = cookies; this.httpStatus = httpStatus;
            this.finalUrl = finalUrl; this.durationMs = durationMs;
        }
    }

    private BrowserFeedFetcher() { }

    // ProxyController is process-wide. Serialize feed WebViews so one worker cannot clear\n    // another worker's override while its page is still loading.\n    static synchronized Result fetch(Context context, String url, String userAgent, Integer localProxyPort,
            long timeoutMs) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Result> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicInteger httpStatus = new java.util.concurrent.atomic.AtomicInteger(-1);
        final AtomicReference<String> finalUrl = new AtomicReference<>(url);
        Handler main = new Handler(Looper.getMainLooper());

        Runnable start = () -> {
            final WebView web;
            try {
                web = new WebView(context);
                WebSettings settings = web.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setLoadsImagesAutomatically(false);
                settings.setBlockNetworkImage(true);
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                if (userAgent != null && !userAgent.trim().isEmpty()) {
                    settings.setUserAgentString(userAgent);
                }
                CookieManager cookies = CookieManager.getInstance();
                cookies.setAcceptCookie(true);
                cookies.setAcceptThirdPartyCookies(web, true);
                web.setWebChromeClient(new WebChromeClient());

                final long started = android.os.SystemClock.elapsedRealtime();
                final boolean[] finished = {false};
                final Runnable[] poll = new Runnable[1];
                Runnable cleanup = () -> {
                    try { web.stopLoading(); } catch (Exception ignored) { }
                    try { web.destroy(); } catch (Exception ignored) { }
                };
                poll[0] = () -> {
                    if (finished[0]) return;
                    web.evaluateJavascript("(function(){return JSON.stringify({html:"
                            + "document.documentElement.outerHTML,text:(document.body&&document.body.innerText)||''});})()", value -> {
                        if (finished[0]) return;
                        try {
                            String json = new JSONArray("[" + value + "]").getString(0);
                            org.json.JSONObject page = new org.json.JSONObject(json);
                            String html = page.optString("html", "");
                            String text = page.optString("text", "").toLowerCase(java.util.Locale.ROOT);
                            boolean challenge = text.contains("just a moment")
                                    || text.contains("enable javascript and cookies")
                                    || text.contains("checking your browser")
                                    || text.contains("attention required");
                            if (!html.isEmpty() && !challenge) {
                                finished[0] = true;
                                cookies.flush();
                                result.set(new Result(html, cookies.getCookie(url), httpStatus.get(),
                                        finalUrl.get(), android.os.SystemClock.elapsedRealtime() - started));
                                cleanup.run();
                                done.countDown();
                            } else if (android.os.SystemClock.elapsedRealtime() - started > 60000L) {
                                throw new IllegalStateException("Cloudflare не завершил JavaScript-проверку за 60 секунд");
                            } else {
                                main.postDelayed(poll[0], 1500L);
                            }
                        } catch (Throwable e) {
                            if (e instanceof IllegalStateException
                                    && e.getMessage() != null
                                    && e.getMessage().contains("Cloudflare")) {
                                finished[0] = true;
                                failure.set(e);
                                cleanup.run();
                                done.countDown();
                            } else {
                                main.postDelayed(poll[0], 1000L);
                            }
                        }
                    });
                };
                web.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view, String pageUrl) {
                        super.onPageFinished(view, pageUrl);
                        finalUrl.set(pageUrl == null ? url : pageUrl);
                        if (!finished[0]) main.postDelayed(poll[0], 1200L);
                    }
                    @Override public void onReceivedHttpError(WebView view,
                            android.webkit.WebResourceRequest request,
                            android.webkit.WebResourceResponse response) {
                        super.onReceivedHttpError(view, request, response);
                        if (request.isForMainFrame() && response != null) {
                            httpStatus.set(response.getStatusCode());
                            finalUrl.set(request.getUrl() == null ? url : request.getUrl().toString());
                        }
                    }
                    @Override public void onReceivedError(WebView view,
                            android.webkit.WebResourceRequest request,
                            android.webkit.WebResourceError error) {
                        super.onReceivedError(view, request, error);
                        if (request.isForMainFrame() && !finished[0]) {
                            finished[0] = true;
                            failure.set(new java.io.IOException("WebView: " + error.getDescription()));
                            cleanup.run();
                            done.countDown();
                        }
                    }
                });
                web.loadUrl(url);
            } catch (Throwable e) {
                failure.set(e);
                done.countDown();
            }
        };

        Runnable configureProxy = () -> {
            if (localProxyPort == null) {
                start.run();
                return;
            }
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                failure.set(new IllegalStateException("WebView не поддерживает SOCKS5 proxy override для фонового обновления"));
                done.countDown();
                return;
            }
            ProxyConfig config = new ProxyConfig.Builder()
                    .addProxyRule("http://127.0.0.1:" + localProxyPort)
                    .build();
            ProxyController.getInstance().setProxyOverride(config, main::post, start);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) configureProxy.run();
        else main.post(configureProxy);

        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            main.post(() -> {
                if (failure.get() == null && result.get() == null) {
                    failure.set(new java.util.concurrent.TimeoutException("Фоновый WebView превысил время ожидания"));
                    done.countDown();
                }
            });
            done.await(2, TimeUnit.SECONDS);
        }
        if (localProxyPort != null && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            CountDownLatch clear = new CountDownLatch(1);
            main.post(() -> ProxyController.getInstance().clearProxyOverride(main::post, clear::countDown));
            clear.await(5, TimeUnit.SECONDS);
        }
        if (failure.get() != null) throw new Exception(failure.get());
        if (result.get() == null) throw new java.io.IOException("Фоновый WebView не вернул страницу");
        return result.get();
    }
}
