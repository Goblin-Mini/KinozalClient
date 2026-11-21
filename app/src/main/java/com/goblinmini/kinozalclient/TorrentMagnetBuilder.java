package com.goblinmini.kinozalclient;

import android.net.Uri;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Builds a BitTorrent v1 magnet from the exact bencoded info dictionary bytes. */
final class TorrentMagnetBuilder {
    private TorrentMagnetBuilder() {}

    static String build(byte[] torrent, String displayTitle) throws Exception {
        if (torrent == null || torrent.length == 0 || torrent.length > 16 * 1024 * 1024) {
            throw new IOException("Некорректный размер .torrent-файла");
        }
        Parser parser = new Parser(torrent);
        Node root = parser.parse();
        if (root.type != 'd') throw new IOException("Корень .torrent должен быть словарём");
        Node info = root.dict.get("info");
        if (info == null || info.type != 'd') throw new IOException("В .torrent отсутствует словарь info");

        Node metaVersion = info.dict.get("meta version");
        if (metaVersion != null && metaVersion.type == 'i'
                && metaVersion.integer == 2 && !info.dict.containsKey("pieces")) {
            throw new IOException("Обнаружен v2-only торрент: TorrServe требует проверку поддержки btmh");
        }

        byte[] infoBytes = java.util.Arrays.copyOfRange(torrent, info.start, info.end);
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(infoBytes);
        StringBuilder hash = new StringBuilder(40);
        for (byte b : digest) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));

        String name = displayTitle == null ? "" : displayTitle.trim();
        if (name.isEmpty()) {
            Node infoName = info.dict.get("name");
            if (infoName != null && infoName.type == 's') name = infoName.text();
        }

        StringBuilder magnet = new StringBuilder("magnet:?xt=urn:btih:").append(hash);
        if (!name.isEmpty()) magnet.append("&dn=").append(Uri.encode(name));

        LinkedHashSet<String> trackers = new LinkedHashSet<>();
        addTracker(trackers, root.dict.get("announce"));
        Node announceList = root.dict.get("announce-list");
        if (announceList != null && announceList.type == 'l') {
            for (Node tier : announceList.list) {
                if (tier.type == 'l') {
                    for (Node tracker : tier.list) addTracker(trackers, tracker);
                } else addTracker(trackers, tier);
            }
        }
        for (String tracker : trackers) magnet.append("&tr=").append(Uri.encode(tracker));
        return magnet.toString();
    }

    private static void addTracker(LinkedHashSet<String> trackers, Node node) {
        if (node == null) return;
        if (node.type == 's') {
            String value = node.text().trim();
            if (value.startsWith("udp://") || value.startsWith("http://")
                    || value.startsWith("https://")) trackers.add(value);
        } else if (node.type == 'l') {
            for (Node item : node.list) addTracker(trackers, item);
        }
    }

    private static final class Node {
        final char type;
        final int start, end;
        final Map<String, Node> dict;
        final List<Node> list;
        final byte[] bytes;
        final long integer;

        Node(char type, int start, int end, Map<String, Node> dict,
                List<Node> list, byte[] bytes, long integer) {
            this.type = type; this.start = start; this.end = end;
            this.dict = dict; this.list = list; this.bytes = bytes; this.integer = integer;
        }
        String text() { return new String(bytes, StandardCharsets.UTF_8); }
    }

    private static final class Parser {
        final byte[] data;
        int pos;
        int depth;
        Parser(byte[] data) { this.data = data; }

        Node parse() throws IOException {
            Node node = value();
            if (pos != data.length) throw new IOException("Лишние данные после .torrent");
            return node;
        }

        Node value() throws IOException {
            if (++depth > 128) throw new IOException("Слишком глубокая структура bencode");
            try {
                if (pos >= data.length) throw new IOException("Неожиданный конец .torrent");
                int start = pos;
                int token = data[pos] & 0xff;
                if (token == 'd') {
                    pos++;
                    Map<String, Node> map = new java.util.LinkedHashMap<>();
                    while (peek() != 'e') {
                        Node key = value();
                        if (key.type != 's') throw new IOException("Ключ словаря не является строкой");
                        String keyText = key.text();
                        Node child = value();
                        if (map.put(keyText, child) != null) throw new IOException("Повторяющийся ключ: " + keyText);
                    }
                    pos++;
                    return new Node('d', start, pos, map, null, null, 0);
                }
                if (token == 'l') {
                    pos++;
                    List<Node> list = new ArrayList<>();
                    while (peek() != 'e') list.add(value());
                    pos++;
                    return new Node('l', start, pos, null, list, null, 0);
                }
                if (token == 'i') {
                    pos++;
                    int end = indexOf((byte) 'e', pos);
                    if (end < 0) throw new IOException("Незавершённое целое число");
                    String number = new String(data, pos, end - pos, StandardCharsets.US_ASCII);
                    if (!number.matches("-?(0|[1-9][0-9]*)")) throw new IOException("Некорректное целое число");
                    long parsed;
                    try { parsed = Long.parseLong(number); }
                    catch (NumberFormatException e) { throw new IOException("Число вне диапазона", e); }
                    pos = end + 1;
                    return new Node('i', start, pos, null, null, null, parsed);
                }
                if (token >= '0' && token <= '9') {
                    int colon = indexOf((byte) ':', pos);
                    if (colon < 0) throw new IOException("Некорректная длина строки");
                    String lengthText = new String(data, pos, colon - pos, StandardCharsets.US_ASCII);
                    if (!lengthText.matches("0|[1-9][0-9]*")) throw new IOException("Некорректная длина строки");
                    int length;
                    try { length = Integer.parseInt(lengthText); }
                    catch (NumberFormatException e) { throw new IOException("Строка слишком длинная", e); }
                    pos = colon + 1;
                    if (length < 0 || length > data.length - pos) throw new IOException("Строка выходит за границы файла");
                    byte[] bytes = java.util.Arrays.copyOfRange(data, pos, pos + length);
                    pos += length;
                    return new Node('s', start, pos, null, null, bytes, 0);
                }
                throw new IOException("Неизвестный маркер bencode: " + token);
            } finally { depth--; }
        }

        int peek() throws IOException {
            if (pos >= data.length) throw new IOException("Незавершённый контейнер bencode");
            return data[pos] & 0xff;
        }
        int indexOf(byte target, int from) {
            for (int i = from; i < data.length; i++) if (data[i] == target) return i;
            return -1;
        }
    }
}
