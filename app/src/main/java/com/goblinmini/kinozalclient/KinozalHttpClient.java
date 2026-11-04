package com.goblinmini.kinozalclient;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Native HTTP session for Kinozal. No WebView, JavaScript or browser cookie jar.
 * Credentials are sent only to the selected HTTPS mirror. Session cookies are
 * encrypted with an Android Keystore AES-GCM key before being persisted.
 */
public final class KinozalHttpClient {
    public static final String DEFAULT_MIRROR = "https://kinozal.cloudns.nz";
    private static final String PREFS = "kinozal_native_session";
    private static final String COOKIE_KEY = "encrypted_cookie_jar";
    private static final String KEY_ALIAS = "kinozal_session_aes";
    private final Context context;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private String baseUrl;

    public KinozalHttpClient(Context context, String mirror) {
        this.context = context.getApplicationContext();
        setMirror(mirror);
        restoreCookies();
    }

    public synchronized void setMirror(String mirror) {
        String value = mirror == null ? DEFAULT_MIRROR : mirror.trim();
        if (!value.matches("(?i)^https://[^/]+/?$")) {
            throw new IllegalArgumentException("Зеркало должно быть HTTPS-адресом");
        }
        baseUrl = value.replaceAll("/+$", "");
    }

    public synchronized String getMirror() { return baseUrl; }

    public synchronized boolean isAuthenticated() {
        try {
            String html = request(baseUrl + "/browse.php", "GET", null, false);
            return !isLoginPage(html) && !html.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized void login(String username, String password) throws Exception {
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Введите логин и пароль");
        }
        cookies.getCookieStore().removeAll();
        String loginUrl = baseUrl + "/login.php?m=5";
        String loginHtml = request(loginUrl, "GET", null, false);
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(loginHtml, loginUrl);
        org.jsoup.nodes.Element form = doc.select("form").stream()
                .filter(f -> !f.select("input[type=password]").isEmpty())
                .findFirst().orElseThrow(() -> new SecurityException("Не найдена форма входа на выбранном зеркале"));
        java.util.LinkedHashMap<String, String> fields = new java.util.LinkedHashMap<>();
        for (org.jsoup.nodes.Element input : form.select("input[name]")) {
            String type = input.attr("type").toLowerCase(java.util.Locale.ROOT);
            if (type.equals("submit") || type.equals("button") || type.equals("file")) continue;
            fields.put(input.attr("name"), input.hasAttr("value") ? input.attr("value") : "");
        }
        org.jsoup.nodes.Element userField = form.select("input[type=text],input[type=email],input:not([type])").stream()
                .filter(e -> !e.attr("name").isEmpty()).findFirst()
                .orElseThrow(() -> new SecurityException("Не найдено поле логина"));
        org.jsoup.nodes.Element passField = form.select("input[type=password][name]").first();
        if (passField == null) throw new SecurityException("Не найдено поле пароля");
        fields.put(userField.attr("name"), username.trim());
        fields.put(passField.attr("name"), password);
        StringBuilder body = new StringBuilder();
        for (java.util.Map.Entry<String, String> entry : fields.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(enc(entry.getKey())).append('=').append(enc(entry.getValue()));
        }
        String action = form.hasAttr("action") ? form.absUrl("action") : loginUrl;
        if (action.isEmpty()) action = loginUrl;
        String response = request(action, "POST", body.toString(), true);
        String probe = request(baseUrl + "/browse.php", "GET", null, false);
        if (isLoginPage(probe) || probe.isEmpty()) {
            cookies.getCookieStore().removeAll();
            throw new SecurityException("Вход не подтверждён сайтом. Проверьте логин и пароль; возможно, зеркало требует дополнительную проверку.");
        }
        persistCookies();
    }

    public synchronized void logout() {
        cookies.getCookieStore().removeAll();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(COOKIE_KEY).apply();
    }

    public synchronized String fetch(String pathOrUrl) throws Exception {
        String target = pathOrUrl.startsWith("http") ? pathOrUrl : baseUrl + "/" + pathOrUrl.replaceFirst("^/+", "");
        String html = request(target, "GET", null, false);
        if (isLoginPage(html)) throw new SecurityException("Сессия Kinozal завершилась. Войдите снова.");
        return html;
    }

    private String request(String target, String method, String body, boolean formPost) throws Exception {
        URL url = new URL(target);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        c.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7");
        URI uri = URI.create(target);
        List<String> cookieHeaders = cookies.get(uri, new java.util.HashMap<>()).get("Cookie");
        if (cookieHeaders != null && !cookieHeaders.isEmpty()) c.setRequestProperty("Cookie", cookieHeaders.get(0));
        if (formPost) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) { out.write(data); }
        }
        int code = c.getResponseCode();
        cookies.put(uri, c.getHeaderFields());
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String result = in == null ? "" : readAll(in);
        c.disconnect();
        return result;
    }

    private boolean isLoginPage(String html) {
        String s = html == null ? "" : html.toLowerCase(java.util.Locale.ROOT);
        return s.contains("name=\"password\"") && (s.contains("вход") || s.contains("login"))
                || s.contains("login.php?m=5") || s.contains("takelogin.php");
    }

    private void persistCookies() throws Exception {
        StringBuilder value = new StringBuilder();
        for (HttpCookie cookie : cookies.getCookieStore().getCookies()) {
            value.append(cookie.getName()).append("\t")
                    .append(cookie.getValue()).append("\t")
                    .append(cookie.getDomain() == null ? "" : cookie.getDomain()).append("\t")
                    .append(cookie.getPath() == null ? "/" : cookie.getPath()).append("\t")
                    .append(cookie.getMaxAge()).append("\n");
        }
        byte[] encrypted = encrypt(value.toString().getBytes(StandardCharsets.UTF_8));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(COOKIE_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply();
    }

    private void restoreCookies() {
        try {
            String encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(COOKIE_KEY, null);
            if (encoded == null) return;
            String raw = new String(decrypt(Base64.decode(encoded, Base64.NO_WRAP)), StandardCharsets.UTF_8);
            for (String line : raw.split("\n")) {
                String[] p = line.split("\t", -1);
                if (p.length != 5) continue;
                HttpCookie cookie = new HttpCookie(p[0], p[1]);
                if (!p[2].isEmpty()) cookie.setDomain(p[2]);
                cookie.setPath(p[3].isEmpty() ? "/" : p[3]);
                try { cookie.setMaxAge(Long.parseLong(p[4])); } catch (Exception ignored) {}
                cookies.getCookieStore().add(URI.create(baseUrl), cookie);
            }
        } catch (Exception e) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(COOKIE_KEY).apply();
        }
    }

    private byte[] encrypt(byte[] plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plain);
        byte[] result = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
        return result;
    }

    private byte[] decrypt(byte[] input) throws Exception {
        byte[] iv = java.util.Arrays.copyOfRange(input, 0, 12);
        byte[] data = java.util.Arrays.copyOfRange(input, 12, input.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
        return cipher.doFinal(data);
    }

    private SecretKey getKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return ((SecretKey) store.getKey(KEY_ALIAS, null));
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static String readAll(InputStream input) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
