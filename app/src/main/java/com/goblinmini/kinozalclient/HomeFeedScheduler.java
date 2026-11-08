package com.goblinmini.kinozalclient;

import android.content.Context;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Independent, staggered refresh chains for Home and Collections. */
public final class HomeFeedScheduler {
    static final String KEY_GROUP = "feed_group";
    static final String KEY_INDEX = "feed_index";
    static final String KEY_PATH = "feed_path";
    static final String KEY_TITLE = "feed_title";

    private static final String[] HOME_PATHS = {
        "novinki.php",
        "top.php?j=&t=0&d=0&k=0&f=0&w=2&s=0",
        "top.php?j=&t=31&d=0&k=0&f=0&w=2&s=0",
        "top.php?j=&t=32&d=0&k=0&f=0&w=2&s=0",
        "top.php?j=&t=1&d=0&k=0&f=0&w=2&s=0",
        "top.php?t=2&d=0&f=0&k=0&j=&s=0&w=2",
        "top.php?j=&t=4&d=0&k=0&f=0&w=2&s=0",
        "top.php?j=&t=7&d=0&k=0&f=0&w=2&s=0"
    };
    private static final String[] HOME_TITLES = {
        "Новинки кино", "Топ раздач месяца", "Топ русские сериалы",
        "Топ иностранные сериалы", "Топ фильмы", "Топ мульты", "Топ музыка", "Топ игр"
    };
    private static final String[] COLLECTION_PATHS = {
        "top.php?j=Netflix", "top.php?j=HBO", "top.php?j=Marvel",
        "top.php?j=Walt%20Disney%20Pictures", "top.php?j=Pixar",
        "top.php?j=%D0%9F%D1%81%D0%B8%D1%85%D0%BE%D0%BB%D0%BE%D0%B3%D0%B8%D1%87%D0%B5%D1%81%D0%BA%D0%B8%D0%B9%20%D1%82%D1%80%D0%B8%D0%BB%D0%BB%D0%B5%D1%80",
        "top.php?j=%D0%9D%D0%BE%D0%B2%D0%BE%D0%B3%D0%BE%D0%B4%D0%BD%D0%B8%D0%B9",
        "top.php?j=%D0%A0%D0%B5%D0%B0%D0%BB%D1%8C%D0%BD%D1%8B%D0%B5%20%D1%81%D0%BE%D0%B1%D1%8B%D1%82%D0%B8%D1%8F",
        "top.php?j=%D0%BB%D1%83%D1%87%D1%88%D0%B8%D0%B9%20%D1%84%D0%B8%D0%BB%D1%8C%D0%BC%20%D0%9E%D1%81%D0%BA%D0%B0%D1%80",
        "top.php?j=%D1%8D%D0%BA%D1%80%D0%B0%D0%BD%D0%B8%D0%B7%D0%B0%D1%86%D0%B8%D1%8F",
        "top.php?j=%D1%84%D0%B8%D0%BB%D1%8C%D0%BC%D1%8B%20%D0%BE%20%D1%81%D0%BF%D0%BE%D1%80%D1%82%D0%B5"
    };
    private static final String[] COLLECTION_TITLES = {
        "Netflix", "HBO", "Marvel", "Walt Disney", "Pixar", "Триллер",
        "Новый год", "Реальные события", "Оскар", "Экранизации", "Спорт"
    };

    private HomeFeedScheduler() { }

    public static void scheduleInitial(Context context, String group) {
        String[] paths = "home".equals(group) ? HOME_PATHS : COLLECTION_PATHS;
        String[] titles = "home".equals(group) ? HOME_TITLES : COLLECTION_TITLES;
        String mirror = context.getSharedPreferences("kinozal_settings", Context.MODE_PRIVATE)
                .getString("mirror", KinozalHttpClient.DEFAULT_MIRROR);
        String marker = "v1|" + mirror;
        String key = "scheduled_" + group;
        android.content.SharedPreferences prefs = context.getSharedPreferences("kinozal_feed_schedule", Context.MODE_PRIVATE);
        if (marker.equals(prefs.getString(key, ""))) return;
        long baseMinutes = "home".equals(group) ? 120L : 300L;
        for (int i = 0; i < paths.length; i++) {
            enqueue(context, group, i, paths[i], titles[i], baseMinutes + i * 5L,
                    ExistingWorkPolicy.REPLACE);
        }
        prefs.edit().putString(key, marker).apply();
    }

    /** Queue every feed in order, with a five-minute gap between requests. */
    public static void refreshAll(Context context, String group) {
        String[] paths = "home".equals(group) ? HOME_PATHS : COLLECTION_PATHS;
        String[] titles = "home".equals(group) ? HOME_TITLES : COLLECTION_TITLES;
        for (int i = 0; i < paths.length; i++) {
            enqueue(context, group, i, paths[i], titles[i], i * 5L, ExistingWorkPolicy.REPLACE);
        }
    }

    public static void refreshNow(Context context, String group, int index) {
        String[] paths = "home".equals(group) ? HOME_PATHS : COLLECTION_PATHS;
        String[] titles = "home".equals(group) ? HOME_TITLES : COLLECTION_TITLES;
        if (index < 0 || index >= paths.length) return;
        enqueue(context, group, index, paths[index], titles[index], 0L, ExistingWorkPolicy.REPLACE);
    }

    static void scheduleNext(Context context, String group, int index, String path, String title) {
        long minutes = ("home".equals(group) ? 120L : 300L) + index * 5L;
        enqueue(context, group, index, path, title, minutes, ExistingWorkPolicy.APPEND_OR_REPLACE);
    }

    private static void enqueue(Context context, String group, int index, String path,
            String title, long delayMinutes, ExistingWorkPolicy policy) {
        Data input = new Data.Builder()
                .putString(KEY_GROUP, group)
                .putInt(KEY_INDEX, index)
                .putString(KEY_PATH, path)
                .putString(KEY_TITLE, title)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(HomeFeedRefreshWorker.class)
                .setInputData(input)
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .addTag("kinozal-feed-" + group)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                "kinozal-feed-" + group + "-" + index, policy, request);
    }
}
