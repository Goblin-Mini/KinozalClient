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
    private static final String PREFS = "kinozal_tv_channels";
    private static final String[] FEED_PATHS = {"novinki.php", "top.php?c=0&d=0&f=0&j=&k=0&s=0&t=0"};
    private static final String[] CHANNEL_IDS = {"kinozal:novinki", "kinozal:top"};
    private static final String[] CHANNEL_NAMES = {"Kinozal — Новинки", "Kinozal — Топ раздач"};
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private TvHomeChannels() { }

    static void sync(Activity activity, File modelFile) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity == null || modelFile == null) return;
        EXECUTOR.execute(() -> {
            try {
                if (!modelFile.isFile() || modelFile.length() <= 0 || modelFile.length() > 8L * 1024L * 1024L) return;
                JSONObject root = new JSONObject(new String(Files.readAllBytes(modelFile.toPath()), StandardCharsets.UTF_8));
                JSONArray feeds = root.optJSONArray("feeds");
                if (feeds == null) return;

                PreviewChannelHelper helper = new PreviewChannelHelper(activity);
                long[] ids = new long[2];
                boolean[] created = new boolean[2];
                Bitmap logo = loadAppLogo(activity);

                for (int index = 0; index < FEED_PATHS.length; index++) {
                    JSONObject feed = findFeed(feeds, FEED_PATHS[index]);
                    if (feed == null) continue;
                    PreviewChannel channel = findChannel(helper, CHANNEL_IDS[index]);
                    if (channel == null) {
                        Intent appIntent = new Intent(activity, MainActivity.class);
                        appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        PreviewChannel.Builder builder = new PreviewChannel.Builder()
                                .setDisplayName(CHANNEL_NAMES[index])
                                .setDescription(index == 0 ? "Свежие публикации Kinozal" : "Самые популярные раздачи Kinozal")
                                .setInternalProviderId(CHANNEL_IDS[index])
                                .setAppLinkIntent(appIntent);
                        if (logo != null) builder.setLogo(logo);
                        long id = helper.publishChannel(builder.build());
                        if (id < 0) continue;
                        ids[index] = id;
                        created[index] = true;
                    } else {
                        ids[index] = channel.getId();
                    }
                    replacePrograms(activity, helper, ids[index], CHANNEL_IDS[index], feed.optJSONArray("items"));
                }

                activity.runOnUiThread(() -> {
                    try {
                        if (created[0] && ids[0] > 0) {
                            TvContractCompat.requestChannelBrowsable(activity, ids[0]);
                        }
                        if (created[1] && ids[1] > 0 && !activity.isFinishing()) {
                            Intent request = new Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE);
                            request.putExtra(TvContractCompat.EXTRA_CHANNEL_ID, ids[1]);
                            activity.startActivityForResult(request, 802);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Unable to request channel visibility", e);
                    }
                });
            } catch (SecurityException e) {
                Log.w(TAG, "TV provider permission is unavailable on this device", e);
            } catch (Exception e) {
                Log.w(TAG, "Channel synchronization failed", e);
            }
        });
    }

    private static JSONObject findFeed(JSONArray feeds, String path) {
        for (int i = 0; i < feeds.length(); i++) {
            JSONObject feed = feeds.optJSONObject(i);
            if (feed != null && path.equals(feed.optString("path"))) return feed;
        }
        return null;
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
        if (items == null) return;
        String[] projection = {BaseColumns._ID};
        try (Cursor cursor = activity.getContentResolver().query(
                TvContractCompat.PreviewPrograms.CONTENT_URI,
                projection,
                TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID + "=?",
                new String[]{String.valueOf(channelId)},
                null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long oldId = cursor.getLong(0);
                    helper.deletePreviewProgram(oldId);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not clear old channel programs", e);
        }

        int limit = Math.min(items.length(), 20);
        for (int i = 0; i < limit; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String title = item.optString("title").trim();
            String href = item.optString("href").trim();
            if (title.isEmpty() || href.isEmpty()) continue;

            Intent playIntent = new Intent(activity, MainActivity.class);
            playIntent.setAction(Intent.ACTION_VIEW);
            playIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            playIntent.putExtra("tv_torrent_href", href);
            playIntent.putExtra("tv_torrent_title", title);

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
                    .setIntentUri(Uri.parse(playIntent.toUri(Intent.URI_INTENT_SCHEME)))
                    .setInternalProviderId(channelKey + ":" + href);
            String image = item.optString("image", "").trim();
            if (image.startsWith("https://") || image.startsWith("http://")) {
                program.setPosterArtUri(Uri.parse(image));
                program.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3);
            }
            helper.publishPreviewProgram(program.build());
        }
    }
}
