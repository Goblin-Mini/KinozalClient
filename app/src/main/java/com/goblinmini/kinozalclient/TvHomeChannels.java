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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class TvHomeChannels {
    private static final String TAG = "KinozalTvChannels";
    private static final String PREFS = "kinozal_settings";
    private static final String RECENT_KEY = "recently_viewed";
    private static final String[] CHANNEL_KEYS = {
            "new_releases", "top_month", "top_ru_series", "top_foreign_series",
            "top_movies", "top_cartoons", "top_music", "top_games", "continue"
    };
    private static final String[] CHANNEL_NAMES = {
            "Kinozal — Новинки кино",
            "Kinozal — Топ раздач месяца",
            "Kinozal — Топ русские сериалы",
            "Kinozal — Топ иностранные сериалы",
            "Kinozal — Топ фильмы",
            "Kinozal — Топ мульты",
            "Kinozal — Топ музыка",
            "Kinozal — Топ игр",
            "Kinozal — Продолжить просмотр"
    };
    private static final String[] FEED_PATHS = {
            "novinki.php",
            "top.php?j=&t=0&d=0&k=0&f=0&w=2&s=0",
            "top.php?j=&t=31&d=0&k=0&f=0&w=2&s=0",
            "top.php?j=&t=32&d=0&k=0&f=0&w=2&s=0",
            "top.php?j=&t=1&d=0&k=0&f=0&w=2&s=0",
            "top.php?t=2&d=0&f=0&k=0&j=&s=0&w=2",
            "top.php?j=&t=4&d=0&k=0&f=0&w=2&s=0",
            "top.php?j=&t=7&d=0&k=0&f=0&w=2&s=0",
            "local:recently-viewed"
    };
    private static final int REQUEST_BROWSABLE = 7410;
    private static final List<Long> PENDING_BROWSABLE = new ArrayList<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private TvHomeChannels() { }

    static void sync(Activity activity, File modelFile) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity == null || modelFile == null) return;
        if (!activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .getBoolean("tv_channels_enabled", true)) return;
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
                boolean hadChannels = !helper.getAllChannels().isEmpty();
                String[] obsoleteIds = {"new_movies", "new_series", "top_series", "top_all"};
                for (String key : obsoleteIds) {
                    PreviewChannel old = findChannel(helper, "kinozal:" + key);
                    if (old != null) {
                        helper.deletePreviewChannel(old.getId());
                        Log.i(TAG, "Deleted replaced legacy channel " + key);
                    }
                }
                PreviewChannel obsoleteArrivals = findChannel(helper, "kinozal:new_arrivals");
                if (obsoleteArrivals != null) {
                    helper.deletePreviewChannel(obsoleteArrivals.getId());
                    Log.i(TAG, "Deleted obsolete new-arrivals channel");
                }


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
                        if (id < 0) {
                            Log.e(TAG, "TvProvider rejected channel creation: " + CHANNEL_NAMES[i]);
                            continue;
                        }
                        channelIds[i] = id;
                        created[i] = true;
                        Log.i(TAG, "Created channel " + CHANNEL_NAMES[i] + " id=" + id);
                    } else {
                        channelIds[i] = channel.getId();
                    }

                    JSONArray programs = i == CHANNEL_KEYS.length - 1 ? readRecentPrograms(activity) : findFeedItems(feeds, FEED_PATHS[i]);
                    replacePrograms(activity, helper, channelIds[i], CHANNEL_KEYS[i], programs);
                }

                // Ask Android TV to pin only the first collection (Новинки кино).
                // Other collections are published silently and must not trigger
                // a chain of system confirmation dialogs.
                final long firstChannel = !hadChannels ? firstValidChannel(channelIds) : -1L;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing()) return;
                    try {
                        if (firstChannel > 0) {
                            TvContractCompat.requestChannelBrowsable(activity, firstChannel);
                            Log.i(TAG, "Requested home-screen visibility only for Новинки кино id=" + firstChannel);
                        }
                        synchronized (PENDING_BROWSABLE) {
                            PENDING_BROWSABLE.clear();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Could not request Новинки кино channel visibility", e);
                    }
                });
            } catch (SecurityException e) {
                Log.w(TAG, "TV provider permission is unavailable on this device", e);
            } catch (Exception e) {
                Log.w(TAG, "Channel synchronization failed", e);
            }
        });
    }

    static void removeAll(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity == null) return;
        EXECUTOR.execute(() -> {
            try {
                PreviewChannelHelper helper = new PreviewChannelHelper(activity);
                for (PreviewChannel channel : helper.getAllChannels()) {
                    if (channel.getInternalProviderId() != null
                            && channel.getInternalProviderId().startsWith("kinozal:")) {
                        helper.deletePreviewChannel(channel.getId());
                        Log.i(TAG, "Deleted channel after user disabled TV channels: " + channel.getDisplayName());
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not remove Kinozal preview channels", e);
            }
        });
    }

    private static int firstValidIndex(long[] ids) {
        for (int i = 0; i < ids.length; i++) if (ids[i] > 0) return i;
        return -1;
    }

    private static long firstValidChannel(long[] ids) {
        int index = firstValidIndex(ids);
        return index < 0 ? -1L : ids[index];
    }

    private static void requestNextBrowsable(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        final long channelId;
        synchronized (PENDING_BROWSABLE) {
            if (PENDING_BROWSABLE.isEmpty()) return;
            channelId = PENDING_BROWSABLE.remove(0);
        }
        try {
            Intent request = new Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE);
            request.putExtra(TvContractCompat.EXTRA_CHANNEL_ID, channelId);
            activity.startActivityForResult(request, REQUEST_BROWSABLE);
            Log.i(TAG, "Requested user approval for channel id=" + channelId);
        } catch (Exception e) {
            Log.e(TAG, "Could not launch channel approval for id=" + channelId, e);
            requestNextBrowsable(activity);
        }
    }

    static void onBrowsableRequestResult(Activity activity, int requestCode) {
        if (requestCode == REQUEST_BROWSABLE) requestNextBrowsable(activity);
    }

    static void refreshContinue(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity == null || activity.isFinishing()) return;
        EXECUTOR.execute(() -> {
            try {
                PreviewChannelHelper helper = new PreviewChannelHelper(activity);
                PreviewChannel channel = findChannel(helper, "kinozal:continue");
                if (channel == null) return;
                replacePrograms(activity, helper, channel.getId(), "continue", readRecentPrograms(activity));
            } catch (Exception e) {
                Log.w(TAG, "Could not refresh continue-watching channel", e);
            }
        });
    }

    private static JSONArray findCombinedFeedItems(JSONArray feeds, String firstPath, String secondPath) {
        JSONArray combined = new JSONArray();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (String path : new String[]{firstPath, secondPath}) {
            JSONArray items = findFeedItems(feeds, path);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String href = item.optString("href", "");
                if (!href.isEmpty() && !seen.add(href)) continue;
                combined.put(item);
            }
        }
        return combined;
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
            String image = item.optString("image", "").trim();
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
                    .setDescription(description.toString())
                    .setWeight(limit - i)
                    .setIntentUri(Uri.parse(openIntent.toUri(Intent.URI_INTENT_SCHEME)))
                    .setInternalProviderId("kinozal:" + channelKey + ":" + href);
            Uri posterUri = (image.startsWith("https://") || image.startsWith("http://"))
                    ? Uri.parse(image)
                    : Uri.parse("android.resource://" + activity.getPackageName()
                            + "/" + R.drawable.kinozal_poster_placeholder);
            program.setPosterArtUri(posterUri);
            program.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3);
            try {
                helper.publishPreviewProgram(program.build());
            } catch (Exception e) {
                Log.w(TAG, "Could not publish program " + title, e);
            }
        }
    }
}