package com.goblinmini.kinozalclient;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.provider.SearchManager;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Exposes cached Kinozal feed items to Android/ATV-RU global search. */
public final class KinozalSearchProvider extends ContentProvider {
    public static final String AUTHORITY = "ru.gm.ktv.search";
    private static final String MODEL_FILE = "kinozal_tv_channels_v1.json";
    private static final String[] COLUMNS = {
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA
    };

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        String query = uri == null ? "" : uri.getLastPathSegment();
        if (query == null || query.equals("search_suggest_query")) query = "";
        query = query.trim();
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        if (query.length() < 2 || getContext() == null) return cursor;

        File model = new File(getContext().getFilesDir(), MODEL_FILE);
        if (!model.isFile() || model.length() <= 0 || model.length() > 8L * 1024L * 1024L) return cursor;
        try {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(model.toPath()), StandardCharsets.UTF_8));
            JSONArray feeds = root.optJSONArray("feeds");
            if (feeds == null) return cursor;
            String needle = query.toLowerCase(Locale.ROOT);
            Set<String> seen = new HashSet<>();
            long rowId = 1;
            for (int f = 0; f < feeds.length() && cursor.getCount() < 10; f++) {
                JSONObject feed = feeds.optJSONObject(f);
                JSONArray items = feed == null ? null : feed.optJSONArray("items");
                if (items == null) continue;
                for (int i = 0; i < items.length() && cursor.getCount() < 10; i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    String title = item.optString("title", "").trim();
                    String href = item.optString("href", "").trim();
                    if (title.isEmpty() || href.isEmpty()
                            || !title.toLowerCase(Locale.ROOT).contains(needle)
                            || !seen.add(href)) continue;
                    String description = feed.optString("title", "Раздача Kinozal");
                    Uri deepLink = new Uri.Builder().scheme("kinozal").authority("torrent")
                            .appendQueryParameter("href", href)
                            .appendQueryParameter("title", title).build();
                    cursor.addRow(new Object[]{rowId++, title, description,
                            android.content.Intent.ACTION_VIEW, deepLink.toString()});
                }
            }
        } catch (Exception ignored) {
            // Search must fail closed and never break the host's global search UI.
        }
        return cursor;
    }

    @Override public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.android.search.suggest";
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read-only"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("read-only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("read-only"); }
}
