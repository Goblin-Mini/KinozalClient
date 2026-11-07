package com.goblinmini.kinozalclient;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.provider.BaseColumns;
import android.util.Log;

import androidx.tvprovider.media.tv.PreviewChannel;
import androidx.tvprovider.media.tv.PreviewChannelHelper;
import androidx.tvprovider.media.tv.PreviewProgram;
import androidx.tvprovider.media.tv.TvContractCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class TvHomeChannels {
    private static final String TAG = "KinozalTvChannels";
    private static final String PREFS = "kinozal_settings";
    private static final String RECENT_KEY = "recently_viewed";
    private static final String[] CHANNEL_KEYS = {
            "new_movies", "new_series", "top_movies", "top_series", "top_all", "continue"
    };
    private static final String[] CHANNEL_NAMES = {
            "Kinozal — Новинки фильмов",
            "Kinozal — Новинки сериалов",
            "Kinozal — Топ фильмов",
            "Kinozal — Топ сериалов",
            "Kinozal — Общий топ",
            "Kinozal — Продолжить просмотр"
    };
    private static final String[] FEED_PATHS = {
            "novinki.php?c=1002",
            "novinki.php?c=1001",
            "top.php?c=1002&d=0&f=0&j=&k=0&s=0&t=0",
            "top.php?c=1001&d=0&f=0&j=&k=0&s=0&t=0",
            "top.php?c=0&d=0&f=0&j=&k=0&s=0&t=0",
            "local:recently-viewed"
    };
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private TvHomeChannels() { }

    static void sync(Activity activity, File modelFile) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity == null || modelFile == null) return;
        EXECUTOR.execute(() -> {
            try {
                if (!modelFile.isFile() || modelFile.length() <= 0 || modelFile.length() > 8L * 1024L * 1024L) return;
                JSONObject root = new JSONObject(new String(Files.readAllBytes(modelFile.toPath()), StandardCharsets.UTF_8));
                if (root.optInt("version") != 1) return;
                String savedMirror = root.optString("mirror", "");
                if (savedMirror.isEmpty()) return;
                JSONArray feeds = root.optJSONArray("feeds");
                if (feeds == null) return;

                PreviewChannelHelper helper = new PreviewChannelHelper(activity);
                long[] channelIds = new long[CHANNEL_KEYS.length];
                boolean[] created = new boolean[CHANNEL_KEYS.length];
                Bitmap logo = loadAppLogo(activity);

                for (int i = 0; i < CHANNEL_KEYS.length; i++) {
                    PreviewChannel channel = findChannel(helper, "kinozal:" + CHANNEL_KEYS[i]);
                    if (channel == null) {
                        Intent appIntent = new Intent(activity, MainActivity.class);
                        appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        PreviewChannel.Builder builder = new PreviewChannel.Builder()
                                .setDisplayName(CHANNEL_NAMES[i])
                                .setDescription("Подборка раздач с " + savedMirror)
                                .setInternalProviderId("kinozal:" + CHANNEL_KEYS[i])
                                .setAppLinkIntent(appIntent);
                        if (logo != null) builder.setLogo(logo);
                        long id = helper.publishChannel(builder.build());
                        if (id < 0) continue;
                        channelIds[i] = id;
                        created[i] = true;
                    } else {
                        channelIds[i] = channel.getId();
                    }

                    JSONArray programs = i == 5 ? readRecentPrograms(activity) : findFeedItems(feeds, FEED_PATHS[i]);
                    replacePrograms(activity, helper, channelIds[i], CHANNEL_KEYS[i], programs);
                }

                activity.runOnUiThread(() -> {
                    for (int i = 0; i < channelIds.length; i++) {
                        if (!created[i] || channelIds[i] <= 0 || activity.isFinishing()) continue;
                        try {
                            TvContractCompat.requestChannelBrowsable(activity, channelIds[i]);
                        } catch (Exception e) {
                            Log.w(TAG, "Could not request visibility for " + CHANNEL_NAMES[i], e);
                        }
                    }
                });
            } catch (SecurityException e) {
                Log.w(TAG, "TV provider permission is unavailable on this device", e);
            } catch (Exception e) {
                Log.w(TAG, "Channel synchronization failed", e);
            }
        });
    }

    private static JSONArray findFeedItems(JSONArray feeds, String path) {
        for (int i = 0; i < feeds.length(); i++) {
            JSONObject feed = feeds.optJSONObject(i);
            if (feed != null && path.equals(feed.optString("path"))) {
                JSONArray items = feed.optJSONArray("items");
                return items == null ? new JSONArray() : items;
            }
        }
        return new JSONArray();
    }

    private static JSONArray readRecentPrograms(Activity activity) {
        String raw = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getString(RECENT_KEY, "[]");
        try {
            JSONArray source = new JSONArray(raw);
            JSONArray result = new JSONArray();
            for (int i = 0; i < Math.min(source.length(), 20); i++) {
                JSONObject old = source.optJSONObject(i);
                if (old == null) continue;
                JSONObject item = new JSONObject();
                item.put("title", old.optString("title", ""));
                item.put("href", old.optString("href", ""));
                item.put("size", old.optString("size", "—"));
                item.put("seeds", old.optString("seeds", "—"));
                item.put("image", old.optString("poster", ""));
                result.put(item);
            }
            return result;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static PreviewChannel findChannel(PreviewChannelHelper helper, String internalId) {
        for (PreviewChannel channel : helper.getAllChannels()) {
            if (internalId.equals(channel.getInternalProviderId())) return channel;
        }
        return null;
    }

    private static Bitmap loadAppLogo(Activity activity) {
        try {
            Drawable drawable = activity.getPackageManager().getApplicationIcon(activity.getPackageName());
            int size = 160;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
            return bitmap;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void replacePrograms(Activity activity, PreviewChannelHelper helper,
            long channelId, String channelKey, JSONArray items) {
        if (channelId <= 0 || items == null) return;
        String[] projection = {BaseColumns._ID};
        try (Cursor cursor = activity.getContentResolver().query(
                TvContractCompat.PreviewPrograms.CONTENT_URI, projection,
                TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID + "=?",
                new String[]{String.valueOf(channelId)}, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) helper.deletePreviewProgram(cursor.getLong(0));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not clear old programs for " + channelKey, e);
        }

        int limit = Math.min(items.length(), 20);
        for (int i = 0; i < limit; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String title = item.optString("title").trim();
            String href = item.optString("href").trim();
            if (title.isEmpty() || href.isEmpty()) continue;

            Intent openIntent = new Intent(activity, MainActivity.class);
            openIntent.setAction(Intent.ACTION_VIEW);
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            openIntent.putExtra("tv_torrent_href", href);
            openIntent.putExtra("tv_torrent_title", title);

            StringBuilder description = new StringBuilder();
            String size = item.optString("size", "");
            String seeds = item.optString("seeds", "");
            if (!size.isEmpty() && !size.equals("—")) description.append("Размер: ").append(size);
            if (!seeds.isEmpty() && !seeds.equals("—")) {
                if (description.length() > 0) description.append("  •  ");
                description.append("Сиды: ").append(seeds);
            }

            PreviewProgram.Builder program = new PreviewProgram.Builder()
                    .setChannelId(channelId)
                    .setType(TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                    .setTitle(title)
                    .setDescription(description.length() == 0 ? "Открыть карточку раздачи Kinozal" : description.toString())
                    .setWeight(limit - i)
                    .setIntentUri(Uri.parse(openIntent.toUri(Intent.URI_INTENT_SCHEME)))
                    .setInternalProviderId("kinozal:" + channelKey + ":" + href);
            String image = item.optString("image", "").trim();
            if (image.startsWith("https://") || image.startsWith("http://")) {
                program.setPosterArtUri(Uri.parse(image));
                program.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3);
            }
            try {
                helper.publishPreviewProgram(program.build());
            } catch (Exception e) {
                Log.w(TAG, "Could not publish program " + title, e);
            }
        }
    }
}