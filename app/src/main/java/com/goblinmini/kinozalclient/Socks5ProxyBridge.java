package com.goblinmini.kinozalclient;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Loopback HTTP proxy bridge. WebView and HttpURLConnection speak HTTP proxy
 * locally; this bridge carries CONNECT and plain HTTP streams over an
 * authenticated upstream SOCKS5 connection. It never binds outside loopback.
 */
public final class Socks5ProxyBridge {
    private static final String PREFS = "kinozal_socks5";
    private static final String KEY_ALIAS = "kinozal_socks5_aes";
    private final Context context;
    private ServerSocket server;
    private ExecutorService workers;
    private Thread acceptThread;
    private volatile Settings settings;
    private volatile boolean running;
    private volatile String lastError = "";
    private int localPort;

    public static final class Settings {
        public final boolean enabled;
        public final String host;
        public final int port;
        public final String username;
        public final String password;
        Settings(boolean enabled, String host, int port, String username, String password) {
            this.enabled = enabled; this.host = host; this.port = port;
            this.username = username; this.password = password;
        }
        public boolean hasCredentials() { return username != null && !username.isEmpty(); }
    }

    public Socks5ProxyBridge(Context context) {
        this.context = context.getApplicationContext();
        this.settings = loadSettings();
    }

    public synchronized Settings getSettings() { return settings; }
    public boolean isRunning() { return running; }
    public String getLastError() { return lastError; }
    public int getLocalPort() { return localPort; }

    public synchronized void saveSettings(boolean enabled, String host, int port,
            String username, String password) throws Exception {
        String h = host == null ? "" : host.trim();
        String u = username == null ? "" : username.trim();
        String p = password == null ? "" : password;
        if (enabled) {
            if (h.isEmpty()) throw new IllegalArgumentException("Укажите адрес SOCKS5-сервера");
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Порт должен быть от 1 до 65535");
            if (u.length() > 255 || p.length() > 255) throw new IllegalArgumentException("Логин и пароль SOCKS5 не должны превышать 255 байт");
            if (u.getBytes(StandardCharsets.UTF_8).length > 255 || p.getBytes(StandardCharsets.UTF_8).length > 255)
                throw new IllegalArgumentException("Логин и пароль SOCKS5 не должны превышать 255 байт");
            if (u.isEmpty() != p.isEmpty()) throw new IllegalArgumentException("Для авторизации укажите и логин, и пароль");
        }
        String encrypted = p.isEmpty() ? "" : Base64.encodeToString(encrypt(p.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", enabled).putString("host", h).putInt("port", port)
                .putString("username", u).putString("password_enc", encrypted).commit();
        settings = new Settings(enabled, h, port, u, p);
    }

    private Settings loadSettings() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String password = "";
        try {
            String encoded = prefs.getString("password_enc", "");
            if (!encoded.isEmpty()) password = new String(decrypt(Base64.decode(encoded, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
        return new Settings(prefs.getBoolean("enabled", false),
                prefs.getString("host", ""), prefs.getInt("port", 1080),
                prefs.getString("username", ""), password);
    }

    /** Verify SOCKS5 negotiation, authentication, TLS and a real HTTPS response. */
    public void testConnection(String host, int port, String username, String password) throws Exception {
        String h = host == null ? "" : host.trim();
        String u = username == null ? "" : username.trim();
        String p = password == null ? "" : password;
        if (h.isEmpty()) throw new IllegalArgumentException("Укажите адрес SOCKS5-сервера");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Некорректный порт SOCKS5");
        if (u.isEmpty() != p.isEmpty()) throw new IllegalArgumentException("Укажите и логин, и пароль");
        Settings probe = new Settings(true, h, port, u, p);
        Socket tunnel = null;
        SSLSocket tls = null;
        try {
            tunnel = openSocks(probe, "ipwho.is", 443);
            tunnel.setSoTimeout(15000);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            tls = (SSLSocket) factory.createSocket(tunnel, "ipwho.is", 443, true);
            tls.setSoTimeout(15000);
            tls.startHandshake();
            OutputStream out = tls.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: ipwho.is\r\nAccept: application/json\r\n"
                    + "User-Agent: KinozalClient/0.5.13\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    tls.getInputStream(), StandardCharsets.ISO_8859_1));
            String status = reader.readLine();
            if (status == null || !status.matches("HTTP/1\\.[01] [1-5][0-9]{2}.*"))
                throw new java.io.IOException("SOCKS5: TLS установлен, но HTTP-ответ не получен");
            int code = Integer.parseInt(status.substring(9, 12));
            if (code < 200 || code >= 400)
                throw new java.io.IOException("HTTPS через SOCKS5 вернул HTTP " + code);
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) { }
            if (line == null) throw new java.io.IOException("HTTPS: оборваны заголовки ответа");
        } finally {
            close(tls);
            if (tls == null) close(tunnel);
        }
    }

    public synchronized Proxy start() throws Exception {
        stop();
        Settings cfg = settings;
        if (!cfg.enabled) return Proxy.NO_PROXY;
        if (cfg.host.isEmpty() || cfg.port < 1 || cfg.port > 65535)
            throw new IllegalStateException("Проверьте адрес и порт SOCKS5");
        ServerSocket listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        server = listener;
        localPort = listener.getLocalPort();
        workers = Executors.newCachedThreadPool();
        running = true;
        lastError = "";
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = listener.accept();
                    workers.execute(() -> handleClient(socket, cfg));
                } catch (Exception e) {
                    if (running) lastError = safeMessage(e);
                }
            }
        }, "Kinozal-SOCKS5-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return localHttpProxy();
    }

    public synchronized void stop() {
        running = false;
        if (server != null) try { server.close(); } catch (Exception ignored) { }
        server = null;
        if (workers != null) workers.shutdownNow();
        workers = null;
        localPort = 0;
    }

    public Proxy localHttpProxy() {
        if (!running || localPort == 0) return Proxy.NO_PROXY;
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", localPort));
    }

    private void handleClient(Socket client, Settings cfg) {
        Socket upstream = null;
        try {
            client.setSoTimeout(30000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            byte[] head = readHeader(in, 65536);
            if (head == null) return;
            String header = new String(head, StandardCharsets.ISO_8859_1);
            String[] lines = header.split("\\r\\n");
            if (lines.length == 0) throw new java.io.IOException("Пустой HTTP-запрос");
            String[] request = lines[0].split(" ", 3);
            if (request.length < 3) throw new java.io.IOException("Некорректная строка HTTP-запроса");
            boolean connect = "CONNECT".equalsIgnoreCase(request[0]);
            String host;
            int port;
            if (connect) {
                HostPort hp = parseHostPort(request[1], 443);
                host = hp.host; port = hp.port;
            } else {
                URI uri = URI.create(request[1]);
                if (uri.isAbsolute()) {
                    host = uri.getHost();
                    port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
                } else {
                    HostPort hp = findHost(lines, 80);
                    host = hp.host; port = hp.port;
                }
            }
            if (host == null || host.isEmpty()) throw new java.io.IOException("Не найден адрес назначения");
            upstream = openSocks(cfg, host, port);
            upstream.setSoTimeout(0);
            if (connect) {
                out.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: KinozalClient\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            } else {
                URI uri = URI.create(request[1]);
                String path = uri.isAbsolute() ? (uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                        + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()) : request[1];
                StringBuilder rewritten = new StringBuilder(request[0]).append(' ').append(path).append(' ').append(request[2]).append("\r\n");
                for (int i = 1; i < lines.length; i++) {
                    String line = lines[i];
                    if (line.isEmpty() || line.toLowerCase(java.util.Locale.ROOT).startsWith("proxy-connection:")) continue;
                    rewritten.append(line).append("\r\n");
                }
                rewritten.append("\r\n");
                upstream.getOutputStream().write(rewritten.toString().getBytes(StandardCharsets.ISO_8859_1));
                upstream.getOutputStream().flush();
            }
            tunnel(client, upstream);
        } catch (Exception e) {
            lastError = safeMessage(e);
            try {
                if (!client.isClosed()) client.getOutputStream().write(
                        "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            } catch (Exception ignored) { }
        } finally {
            close(client); close(upstream);
        }
    }

    private static final class HostPort {
        final String host; final int port;
        HostPort(String host, int port) { this.host = host; this.port = port; }
    }

    private HostPort findHost(String[] lines, int defaultPort) throws Exception {
        for (String line : lines) if (line.regionMatches(true, 0, "Host:", 0, 5))
            return parseHostPort(line.substring(5).trim(), defaultPort);
        throw new java.io.IOException("Отсутствует заголовок Host");
    }

    private HostPort parseHostPort(String value, int defaultPort) throws Exception {
        String host = value.trim();
        int port = defaultPort;
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            if (end < 0) throw new java.io.IOException("Некорректный IPv6 адрес");
            String rest = host.substring(end + 1);
            if (rest.startsWith(":")) port = Integer.parseInt(rest.substring(1));
            host = host.substring(1, end);
        } else {
            int colon = host.lastIndexOf(':');
            if (colon > 0 && host.indexOf(':') == colon) {
                port = Integer.parseInt(host.substring(colon + 1));
                host = host.substring(0, colon);
            }
        }
        if (host.isEmpty() || port < 1 || port > 65535) throw new java.io.IOException("Некорректный адрес назначения");
        return new HostPort(host, port);
    }

    private Socket openSocks(Settings cfg, String targetHost, int targetPort) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(cfg.host, cfg.port), 12000);
        socket.setSoTimeout(15000);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        boolean auth = cfg.hasCredentials();
        out.write(auth ? new byte[]{5, 2, 0, 2} : new byte[]{5, 1, 0});
        out.flush();
        int version = in.read(), method = in.read();
        if (version != 5 || method == 255 || (method != 0 && method != 2))
            throw new java.io.IOException("SOCKS5: сервер отклонил способ авторизации");
        if (method == 2) {
            if (!auth) throw new java.io.IOException("SOCKS5: сервер требует логин и пароль");
            byte[] u = cfg.username.getBytes(StandardCharsets.UTF_8);
            byte[] p = cfg.password.getBytes(StandardCharsets.UTF_8);
            out.write(1); out.write(u.length); out.write(u); out.write(p.length); out.write(p); out.flush();
            int authVersion = in.read(), status = in.read();
            if (authVersion != 1 || status != 0) throw new java.io.IOException("SOCKS5: неверный логин или пароль");
        } else if (auth && method == 0) {
            // The server explicitly selected no-auth; this is valid for servers
            // that allow both methods, and avoids assuming credentials are mandatory.
        }
        byte[] hostBytes = targetHost.getBytes(StandardCharsets.UTF_8);
        out.write(new byte[]{5, 1, 0});
        if (targetHost.indexOf(':') >= 0) {
            byte[] address = InetAddress.getByName(targetHost).getAddress();
            out.write(4); out.write(address);
        } else if (targetHost.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            out.write(1); out.write(InetAddress.getByName(targetHost).getAddress());
        } else {
            if (hostBytes.length > 255) throw new java.io.IOException("Слишком длинное имя хоста");
            out.write(3); out.write(hostBytes.length); out.write(hostBytes);
        }
        out.write((targetPort >> 8) & 255); out.write(targetPort & 255); out.flush();
        int v = in.read(), rep = in.read(); in.read();
        int atyp = in.read();
        if (v != 5 || rep != 0) throw new java.io.IOException("SOCKS5: соединение отклонено (код " + rep + ")");
        int addressLength;
        if (atyp == 1) addressLength = 4;
        else if (atyp == 4) addressLength = 16;
        else if (atyp == 3) addressLength = in.read();
        else throw new java.io.IOException("SOCKS5: неизвестный тип адреса");
        readFully(in, addressLength + 2);
        socket.setSoTimeout(0);
        return socket;
    }

    private static byte[] readHeader(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int state = 0, value;
        while (out.size() < max && (value = in.read()) != -1) {
            out.write(value);
            if ((state == 0 || state == 2) && value == 13) state++;
            else if ((state == 1 || state == 3) && value == 10) {
                if (state == 3) return out.toByteArray();
                state++;
            } else state = value == 13 ? 1 : 0;
        }
        if (out.size() >= max) throw new java.io.IOException("Слишком большой HTTP-заголовок");
        return null;
    }

    private static void readFully(InputStream in, int count) throws Exception {
        while (count > 0) {
            int n = in.read(new byte[Math.min(count, 512)]);
            if (n < 0) throw new java.io.IOException("Оборван ответ SOCKS5");
            count -= n;
        }
    }

    private void tunnel(Socket left, Socket right) throws Exception {
        CountDownLatch done = new CountDownLatch(2);
        copyAsync(left, right, done);
        copyAsync(right, left, done);
        done.await();
    }

    private void copyAsync(Socket from, Socket to, CountDownLatch done) {
        workers.execute(() -> {
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) != -1) { out.write(buffer, 0, n); out.flush(); }
            } catch (Exception ignored) {
            } finally {
                done.countDown();
                try { to.shutdownOutput(); } catch (Exception ignored) { }
            }
        });
    }

    private static void close(Socket socket) { if (socket != null) try { socket.close(); } catch (Exception ignored) { } }
    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private byte[] encrypt(byte[] plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] iv = cipher.getIV(), encrypted = cipher.doFinal(plain);
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
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
}