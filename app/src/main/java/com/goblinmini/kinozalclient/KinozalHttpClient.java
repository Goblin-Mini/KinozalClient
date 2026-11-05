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
        // Keep Cloudflare clearance cookies imported from the browser check.
        // Clearing the whole jar here removes cf_clearance and causes HTTP 403.
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

    private static final String BROWSER_COOKIE_KEY = "encrypted_browser_cookie_header";
    private static final String SAVED_LOGIN_KEY = "encrypted_saved_login";
    private static final String SAVED_PASSWORD_KEY = "encrypted_saved_password";

    /** Save credentials encrypted with the same Android Keystore AES-GCM key. */
    public synchronized void saveCredentials(String username, String password) throws Exception {
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Логин и пароль не могут быть пустыми");
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String login = Base64.encodeToString(encrypt(username.trim().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        String pass = Base64.encodeToString(encrypt(password.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        prefs.edit().putString(SAVED_LOGIN_KEY, login).putString(SAVED_PASSWORD_KEY, pass).apply();
    }

    /** Persist the WebView cookie header encrypted with Android Keystore AES-GCM. */
    public synchronized void saveBrowserCookies(String cookieHeader) throws Exception {
        if (cookieHeader == null || cookieHeader.trim().isEmpty()) return;
        String encoded = Base64.encodeToString(
                encrypt(cookieHeader.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(BROWSER_COOKIE_KEY, encoded).commit();
    }

    /** Return the encrypted WebView cookie header, or null if no browser session was saved. */
    public synchronized String getSavedBrowserCookies() {
        try {
            String encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(BROWSER_COOKIE_KEY, null);
            if (encoded == null) return null;
            String value = new String(decrypt(Base64.decode(encoded, Base64.NO_WRAP)),
                    StandardCharsets.UTF_8);
            return value.trim().isEmpty() ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns decrypted saved credentials, or null when none were saved. */
    public synchronized String[] getSavedCredentials() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String login = prefs.getString(SAVED_LOGIN_KEY, null);
            String pass = prefs.getString(SAVED_PASSWORD_KEY, null);
            if (login == null || pass == null) return null;
            String username = new String(decrypt(Base64.decode(login, Base64.NO_WRAP)), StandardCharsets.UTF_8);
            String password = new String(decrypt(Base64.decode(pass, Base64.NO_WRAP)), StandardCharsets.UTF_8);
            if (username.isEmpty() || password.isEmpty()) return null;
            return new String[]{username, password};
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized void clearSavedCredentials() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(SAVED_LOGIN_KEY).remove(SAVED_PASSWORD_KEY).apply();
    }

    public synchronized void logout() {
        cookies.getCookieStore().removeAll();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(COOKIE_KEY).remove(BROWSER_COOKIE_KEY).apply();
    }

    /** Download a protected image or torrent using the same authenticated HTTP cookie jar. */
    public synchronized byte[] fetchBytes(String target, String accept) throws Exception {
        String current = target.startsWith("http") ? target : baseUrl + "/" + target.replaceFirst("^/+", "");
        for (int redirect = 0; redirect < 9; redirect++) {
            URL url = new URL(current);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(25000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36");
            connection.setRequestProperty("Accept", accept == null ? "*/*" : accept);
            connection.setRequestProperty("Referer", baseUrl + "/");
            URI uri = URI.create(current);
            List<String> cookieHeaders = cookies.get(uri, new java.util.HashMap<>()).get("Cookie");
            if (cookieHeaders != null && !cookieHeaders.isEmpty()) connection.setRequestProperty("Cookie", cookieHeaders.get(0));
            int code = connection.getResponseCode();
            cookies.put(uri, connection.getHeaderFields());
            String location = connection.getHeaderField("Location");
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                connection.disconnect();
                if (location == null || redirect == 8) throw new java.io.IOException("Слишком много перенаправлений");
                current = new URL(url, location).toString();
                continue;
            }
            if (code < 200 || code >= 300) {
                connection.disconnect();
                throw new java.io.IOException("HTTP " + code + " при загрузке файла");
            }
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                return out.toByteArray();
            } finally {
                connection.disconnect();
            }
        }
        throw new java.io.IOException("Не удалось загрузить файл");
    }

    public synchronized String fetch(String pathOrUrl) throws Exception {
        String target = pathOrUrl.startsWith("http") ? pathOrUrl : baseUrl + "/" + pathOrUrl.replaceFirst("^/+", "");
        String html = request(target, "GET", null, false);
        if (isLoginPage(html)) throw new SecurityException("Сессия Kinozal завершилась. Войдите снова.");
        return html;
    }

    /** Submit a site category/filter form while preserving its original fields. */
    public synchronized String fetchPost(String url, String body) throws Exception {
        String target = url.startsWith("http") ? url : baseUrl + "/" + url.replaceFirst("^/+", "");
        String html = request(target, "POST", body, true);
        if (isLoginPage(html)) throw new SecurityException("Сессия Kinozal завершилась. Войдите снова.");
        return html;
    }

    private String request(String target, String method, String body, boolean formPost) throws Exception {
        String current = target;
        String currentMethod = method;
        String currentBody = body;
        for (int redirect = 0; redirect < 9; redirect++) {
            URL url = new URL(current);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(currentMethod);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36");
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7");
            URI uri = URI.create(current);
            List<String> cookieHeaders = cookies.get(uri, new java.util.HashMap<>()).get("Cookie");
            if (cookieHeaders != null && !cookieHeaders.isEmpty()) connection.setRequestProperty("Cookie", cookieHeaders.get(0));
            if ("POST".equals(currentMethod) && currentBody != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                byte[] data = currentBody.getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) { out.write(data); }
            }
            int code = connection.getResponseCode();
            cookies.put(uri, connection.getHeaderFields());
            String location = connection.getHeaderField("Location");
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                connection.disconnect();
                if (location == null || redirect == 8) throw new java.io.IOException("Слишком много перенаправлений");
                current = new URL(url, location).toString();
                if (code == 303 || ((code == 301 || code == 302) && "POST".equals(currentMethod))) {
                    currentMethod = "GET";
                    currentBody = null;
                }
                continue;
            }
            String retryAfterHeader = connection.getHeaderField("Retry-After");
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String result = input == null ? "" : readAll(input, connection.getContentType());
            connection.disconnect();
            if (code == 429) {
                String retryAfter = retryAfterHeader;
                String wait = (retryAfter == null || retryAfter.trim().isEmpty())
                        ? "Подожди несколько минут и не повторяй вход подряд."
                        : "Повтори не раньше чем через " + retryAfter.trim() + " сек.";
                throw new java.io.IOException("Kinozal временно ограничил запросы (HTTP 429). " + wait);
            }
            if (code >= 400) throw new java.io.IOException("HTTP " + code + " при запросе Kinozal");
            return result;
        }
        throw new java.io.IOException("Не удалось выполнить запрос к Kinozal");
    }

    private boolean isLoginPage(String html) {
        if (html == null || html.isEmpty()) return false;
        org.jsoup.nodes.Document document = org.jsoup.Jsoup.parse(html);
        // A link to takelogin.php can appear in ordinary page navigation.
        // Only classify a page as a login screen when an actual password form exists.
        org.jsoup.select.Elements passwordForms = document.select("form:has(input[type=password])");
        if (passwordForms.isEmpty()) return false;
        for (org.jsoup.nodes.Element form : passwordForms) {
            String action = form.attr("action").toLowerCase(java.util.Locale.ROOT);
            String text = form.text().toLowerCase(java.util.Locale.ROOT);
            if (action.contains("login") || text.contains("войти") || text.contains("вход")
                    || text.contains("login")) return true;
        }
        return false;
    }

    /** Import cookies created by the browser-based Cloudflare check. */
    public synchronized void importBrowserCookies(String cookieHeader) throws Exception {
        if (cookieHeader == null || cookieHeader.trim().isEmpty()) return;
        URI origin = URI.create(baseUrl);
        String host = origin.getHost();
        for (String part : cookieHeader.split(";")) {
            String item = part.trim();
            int equals = item.indexOf('=');
            if (equals <= 0) continue;
            String name = item.substring(0, equals).trim();
            String value = item.substring(equals + 1).trim();
            if (name.isEmpty()) continue;
            HttpCookie cookie = new HttpCookie(name, value);
            // CookieManager.getCookie() returns only cookies applicable to this
            // mirror URL. Give them an explicit host scope so java.net.CookieStore
            // reliably sends them on subsequent native HTTPS requests.
            cookie.setDomain(host);
            cookie.setPath("/");
            cookie.setVersion(0);
            if ("https".equalsIgnoreCase(origin.getScheme())) cookie.setSecure(true);
            cookies.getCookieStore().add(origin, cookie);
        }
        persistCookies();
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

    private static String readAll(InputStream input, String contentType) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            byte[] bytes = out.toByteArray();
            if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef
                    && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
                return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
            }

            String prefix = new String(bytes, 0, Math.min(bytes.length, 8192), StandardCharsets.ISO_8859_1);
            java.util.regex.Matcher meta = java.util.regex.Pattern
                    .compile("(?i)<meta[^>]+charset\\s*=\\s*['\\\"]?([a-z0-9._-]+)").matcher(prefix);
            String charset = meta.find() ? meta.group(1) : null;

            if (charset == null) {
                java.util.regex.Matcher declared = java.util.regex.Pattern
                        .compile("(?i)charset\\s*=\\s*['\\\"]?([a-z0-9._-]+)")
                        .matcher(contentType == null ? "" : contentType);
                if (declared.find()) charset = declared.group(1);
            }

            // Some Kinozal mirrors send a misleading UTF-8 header while the
            // page body is Windows-1251. Validate UTF-8 strictly before trusting it.
            if (charset == null || charset.equalsIgnoreCase("utf-8")
                    || charset.equalsIgnoreCase("utf8")) {
                try {
                    return StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                } catch (java.nio.charset.CharacterCodingException invalidUtf8) {
                    return new String(bytes, java.nio.charset.Charset.forName("windows-1251"));
                }
            }

            try {
                return new String(bytes, java.nio.charset.Charset.forName(charset));
            } catch (Exception unsupported) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }}
