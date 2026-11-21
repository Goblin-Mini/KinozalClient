package com.goblinmini.kinozalclient;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Performs one cloud sync attempt from WorkManager. */
public final class CloudSyncWorker extends Worker {
    public CloudSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (!CloudSyncManager.isActivated(context)) {
            return Result.success();
        }
        try {
            CloudSyncManager.sync(context);
            return Result.success();
        } catch (Exception error) {
            android.util.Log.w("KinozalCloudSync",
                    "Background sync will be retried: " + error.getClass().getSimpleName()
                            + ": " + error.getMessage());
            return Result.retry();
        }
    }
}
