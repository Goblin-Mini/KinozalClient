package com.goblinmini.kinozalclient;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Owns the persistent periodic cloud-sync schedule. */
public final class CloudSyncScheduler {
    private static final String PERIODIC_WORK_NAME = "kinozal_plus_cloud_sync_15m";

    private CloudSyncScheduler() { }

    /**
     * Schedules a 6-hour periodic sync for activated Plus installations.
     * WorkManager persists this request across process death and device reboot.
     */
    public static void ensureScheduled(Context context) {
        Context app = context.getApplicationContext();
        if (!CloudSyncManager.isActivated(app)) return;

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                CloudSyncWorker.class, 6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(
                        androidx.work.BackoffPolicy.EXPONENTIAL,
                        PeriodicWorkRequest.MIN_BACKOFF_MILLIS,
                        TimeUnit.MILLISECONDS)
                .build();

        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request);
    }
}
