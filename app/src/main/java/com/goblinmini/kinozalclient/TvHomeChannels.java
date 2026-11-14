package com.goblinmini.kinozalclient;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
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
    private static final String DIAG_FILE = "kinozal_tv_channels_diagnostics.txt";
    private static void diag(Context context, String event, String details) {
        if (context == null) return;
        String line = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date()) + " " + event + " " + details + "\n";
        synchronized (TvHomeChannels.class) {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(context.getFilesDir(), DIAG_FILE), true)) { out.write(line.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
            catch (Exception e) { Log.w(TAG, "Diagnostic write failed", e); }
        }
    }
    static String readDiagnostics(Activity a) {
        File f = new File(a.getFilesDir(), DIAG_FILE);
        try { return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : ""; }
        catch (Exception e) { return "Ошибка чтения: " + e.getMessage(); }
    }
    static void refreshNow(Activity a) {
        if (a == null) return;
        File model = new File(a.getFilesDir(), "kinozal_home_model_v2.json");
        diag(a, "MANUAL_REFRESH", "modelExists=" + model.isFile());
        sync(a, model);
    }
    static void clearChannelData(Activity a) {
        diag(a, "CLEAR_REQUEST", "remove all Kinozal channels");
        removeAll(a);
    }

    private static final String PREFS = "kinozal_settings";
    private static final String RECENT_KEY = "recently_viewed";
    // Keep only the six collections requested for Android TV.
    private static final String[] CHANNEL_KEYS = {
            "favorites", "continue", "top_movies", "top_ru_series",
            "top_foreign_series", "top_month"
    };
    private static final String[] CHANNEL_NAMES = {
            "Кинозал ТВ — Избранное",
            "Кинозал ТВ — Продолжить просмотр",
            "Кинозал ТВ — Топ фильмы",
            "Кинозал ТВ — Топ русские сериалы",
            "Кинозал ТВ — Топ зарубежные сериалы",
            "Кинозал ТВ — Топ раздач месяца"
    };
    private static final String[] FEED_PATHS = {
            "local:favorites",
            "local:recently-viewed",
            "top.php?j=&t=1&d=0&k=0&f=0&w=2&s=0",
            "top.php?j=&t=31&d=0&k=0&f=0&w=2&s=0",
            "top.php?j=&t=32&d=0&k=0&f=0&w=2&s=0",
            "top.php?j=&t=0&d=0&k=0&f=0&w=2&s=0"
    };
    private static final int REQUEST_BROWSABLE = 7410;
    private static final List<Long> PENDING_BROWSABLE = new ArrayList<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private TvHomeChannels() { }

    static void sync(Activity activity, File modelFile) {
        if (activity == null) return;
        Context appContext = activity.getApplicationContext();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || modelFile == null) return;
        if (!appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("tv_channels_enabled", true)) return;
        diag(appContext, "SYNC_START", "modelExists=" + modelFile.isFile());
        EXECUTOR.execute(() -> syncInternal(appContext, modelFile, activity));
    }

    /** Called by WorkManager: update launcher rows without requiring a running Activity. */
    static void syncFromWorker(Context context, File modelFile) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context == null || modelFile == null) return;
        Context appContext = context.getApplicationContext();
        if (!appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("tv_channels_enabled", true)) return;
        syncInternal(appContext, modelFile, null);
    }

    private static void syncInternal(Context context, File modelFile, Activity activityForBrowsable) {
        try {
            if (!modelFile.isFile() || modelFile.length() <= 0 || modelFile.length() > 8L * 1024L * 1024L) return;
            JSONObject root = new JSONObject(new String(Files.readAllBytes(modelFile.toPath()), StandardCharsets.UTF_8));
            if (root.optInt("version") != 1) return;
            String savedMirror = root.optString("mirror", "");
            if (savedMirror.isEmpty()) return;
            JSONArray feeds = root.optJSONArray("feeds");
            if (feeds == null) { diag(context, "SYNC_ERROR", "feeds array missing"); return; }
            diag(context, "MODEL_READ", "feeds=" + feeds.length() + ", mirror=" + savedMirror);

            PreviewChannelHelper helper = new PreviewChannelHelper(context);
            List<PreviewChannel> allChannelsBeforeSync = helper.getAllChannels();
            List<PreviewChannel> kinozalChannelsBeforeSync = new ArrayList<>();
            int kinozalBrowsableBeforeSync = 0;
            for (PreviewChannel existing : allChannelsBeforeSync) {
                String internalId = existing.getInternalProviderId();
                if (internalId != null && internalId.startsWith("kinozal:")) {
                    kinozalChannelsBeforeSync.add(existing);
                    if (existing.isBrowsable()) kinozalBrowsableBeforeSync++;
                }
            }
            boolean hadKinozalChannels = !kinozalChannelsBeforeSync.isEmpty();
            diag(context, "CHANNELS_BEFORE", "all=" + allChannelsBeforeSync.size()
                    + ", kinozal=" + kinozalChannelsBeforeSync.size()
                    + ", kinozalBrowsable=" + kinozalBrowsableBeforeSync);
            for (PreviewChannel existing : new ArrayList<>(helper.getAllChannels())) {
                String internalId = existing.getInternalProviderId();
                if (internalId != null && internalId.startsWith("kinozal:")
                        && !isRetainedChannel(internalId)) {
                    helper.deletePreviewChannel(existing.getId());
                    Log.i(TAG, "Deleted retired Kinozal channel " + internalId);
                }
            }

            long[] channelIds = new long[CHANNEL_KEYS.length];
            Bitmap logo = loadAppLogo(context);
            for (int i = 0; i < CHANNEL_KEYS.length; i++) {
                PreviewChannel channel = findChannel(helper, "kinozal:" + CHANNEL_KEYS[i]);
                if (channel == null) {
                    Intent appIntent = new Intent(context, MainActivity.class);
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
                    Log.i(TAG, "Created channel " + CHANNEL_NAMES[i] + " id=" + id);
                } else {
                    channelIds[i] = channel.getId();
                    PreviewChannel.Builder renamedBuilder = new PreviewChannel.Builder()
                            .setDisplayName(CHANNEL_NAMES[i])
                            .setDescription("Подборка раздач с " + savedMirror)
                            .setInternalProviderId("kinozal:" + CHANNEL_KEYS[i])
                            .setAppLinkIntent(channel.getAppLinkIntent());
                    if (logo != null) renamedBuilder.setLogo(logo);
                    helper.updatePreviewChannel(channel.getId(), renamedBuilder.build());
                }

                JSONArray programs;
                if ("favorites".equals(CHANNEL_KEYS[i])) {
                    programs = readFavoritePrograms(context);
                } else if ("continue".equals(CHANNEL_KEYS[i])) {
                    programs = readRecentPrograms(context);
                } else {
                    // A partial first-time cache must not erase programs already shown
                    // in another channel while the remaining feeds are still downloading.
                    if (!hasFeed(feeds, FEED_PATHS[i])) continue;
                    programs = findFeedItems(feeds, FEED_PATHS[i]);
                }
                replacePrograms(context, helper, channelIds[i], CHANNEL_KEYS[i], programs);
            }

            List<PreviewChannel> allChannelsAfterSync = helper.getAllChannels();
            int kinozalCount = 0;
            int kinozalBrowsable = 0;
            StringBuilder channelState = new StringBuilder();
            for (PreviewChannel current : allChannelsAfterSync) {
                String internalId = current.getInternalProviderId();
                if (internalId == null || !internalId.startsWith("kinozal:")) continue;
                kinozalCount++;
                if (current.isBrowsable()) kinozalBrowsable++;
                if (channelState.length() > 0) channelState.append(';');
                channelState.append(internalId).append('#').append(current.getId())
                        .append(":browsable=").append(current.isBrowsable());
            }
            diag(context, "SYNC_DONE", "all=" + allChannelsAfterSync.size()
                    + ", kinozal=" + kinozalCount + ", kinozalBrowsable=" + kinozalBrowsable
                    + ", states=" + channelState);

            final long firstChannel = !hadKinozalChannels ? firstValidChannel(channelIds) : -1L;
            final boolean hadBrowsableKinozalChannel = kinozalBrowsableBeforeSync > 0;
            final long hiddenChannel = hadKinozalChannels && !hadBrowsableKinozalChannel
                    ? firstValidChannel(channelIds) : -1L;
            if (activityForBrowsable != null) {
                activityForBrowsable.runOnUiThread(() -> {
                    Activity activity = activityForBrowsable;
                    if (activity.isFinishing()) return;
                    try {
                        if (firstChannel > 0) {
                            TvContractCompat.requestChannelBrowsable(activity, firstChannel);
                            Log.i(TAG, "Requested first Kinozal channel visibility id=" + firstChannel);
                        } else if (hiddenChannel > 0) {
                            android.content.SharedPreferences prefs =
                                    activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
                            long lastPrompt = prefs.getLong("tv_channels_browsable_prompt_at", 0L);
                            long now = System.currentTimeMillis();
                            if (now - lastPrompt >= 24L * 60L * 60L * 1000L) {
                                Intent request = new Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE);
                                request.putExtra(TvContractCompat.EXTRA_CHANNEL_ID, hiddenChannel);
                                activity.startActivityForResult(request, REQUEST_BROWSABLE);
                                prefs.edit().putLong("tv_channels_browsable_prompt_at", now).apply();
                            }
                        } else if (hadBrowsableKinozalChannel) {
                            activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                                    .edit().remove("tv_channels_browsable_prompt_at").apply();
                        }
                        synchronized (PENDING_BROWSABLE) { PENDING_BROWSABLE.clear(); }
                    } catch (Exception e) {
                        diag(context, "BROWSABLE_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
                        Log.e(TAG, "Could not request Kinozal channel visibility", e);
                    }
                });
            }
        } catch (SecurityException e) {
            diag(context, "SYNC_ERROR", "SecurityException: " + e.getMessage());
            Log.w(TAG, "TV provider permission is unavailable on this device", e);
        } catch (Exception e) {
            diag(context, "SYNC_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
            Log.w(TAG, "Channel synchronization failed", e);
        }
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

    static void refreshFavorites(Activity activity) {
        refreshLocalChannel(activity, "favorites");
    }

    static void refreshContinue(Activity activity) {
        refreshLocalChannel(activity, "continue");
    }

    private static void refreshLocalChannel(Activity activity, String channelKey) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity == null || activity.isFinishing()) return;
        EXECUTOR.execute(() -> {
            try {
                PreviewChannelHelper helper = new PreviewChannelHelper(activity);
                PreviewChannel channel = findChannel(helper, "kinozal:" + channelKey);
                if (channel == null) {
                    diag(activity, "LOCAL_CHANNEL_REFRESH", channelKey + " channel not created yet");
                    return;
                }
                JSONArray programs = "favorites".equals(channelKey)
                        ? readFavoritePrograms(activity) : readRecentPrograms(activity);
                replacePrograms(activity, helper, channel.getId(), channelKey, programs);
                diag(activity, "LOCAL_CHANNEL_REFRESH", channelKey + " programs=" + programs.length());
            } catch (Exception e) {
                diag(activity, "LOCAL_CHANNEL_REFRESH_ERROR", channelKey + " " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
                Log.w(TAG, "Could not refresh " + channelKey + " channel", e);
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

    private static boolean hasFeed(JSONArray feeds, String path) {
        for (int i = 0; i < feeds.length(); i++) {
            JSONObject feed = feeds.optJSONObject(i);
            if (feed != null && path.equals(feed.optString("path"))) return true;
        }
        return false;
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

    private static JSONArray readRecentPrograms(Context activity) {
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

    private static String normalizeChannelTitle(String title) {
        String value = title == null ? "" : title.toLowerCase(java.util.Locale.ROOT).trim();
        int slash = value.indexOf(" / ");
        if (slash > 0) value = value.substring(0, slash);
        value = value.replaceAll("\\b(2160p|1080p|720p|480p|4k|uhd|hdr10?|web[- .]?dl|webrip|blu[- .]?ray|bdrip|brrip|dvdrip|hdtv|h265|h264|x265|x264|hevc|avc|aac|dts|ac3|proper|repack|remux|extended|directors cut|theatrical|dubbed|subbed)\\b", " ");
        return value.replaceAll("[^\\p{L}\\p{N}]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static String normalizeChannelImage(String image) {
        try {
            Uri uri = Uri.parse(image);
            String path = uri.getPath();
            return path == null ? "" : path.replaceAll("/+$", "").toLowerCase(java.util.Locale.ROOT);
        } catch (Exception ignored) {
            return image == null ? "" : image.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static boolean isRetainedChannel(String internalId) {
        for (String key : CHANNEL_KEYS) {
            if (("kinozal:" + key).equals(internalId)) return true;
        }
        return false;
    }

    private static JSONArray readFavoritePrograms(Context activity) {
        String raw = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .getString("local_favorites", "[]");
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

    private static Bitmap loadAppLogo(Context activity) {
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

    private static void replacePrograms(Context activity, PreviewChannelHelper helper,
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
        java.util.Set<String> seenTitles = new java.util.HashSet<>();
        java.util.Set<String> seenImages = new java.util.HashSet<>();
        for (int i = 0; i < limit; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String title = item.optString("title").trim();
            String href = item.optString("href").trim();
            String image = item.optString("image", "").trim();
            if (title.isEmpty() || href.isEmpty()) continue;
            // Do not publish broken placeholder tiles to the Android TV launcher.
            if (!(image.startsWith("https://") || image.startsWith("http://"))) continue;
            if (!"continue".equals(channelKey)) {
                String titleKey = normalizeChannelTitle(title);
                String imageKey = normalizeChannelImage(image);
                if (!titleKey.isEmpty() && seenTitles.contains(titleKey)) continue;
                if (!imageKey.isEmpty() && seenImages.contains(imageKey)) continue;
                if (!titleKey.isEmpty()) seenTitles.add(titleKey);
                if (!imageKey.isEmpty()) seenImages.add(imageKey);
            }

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