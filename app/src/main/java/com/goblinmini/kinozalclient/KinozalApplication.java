package com.goblinmini.kinozalclient;

import android.app.Application;
import android.util.Log;

public final class KinozalApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // Debug builds remain installable for development. Release builds must
        // pass both independent Java and native certificate checks before startup.
        if (!BuildConfig.DEBUG && !NativeSignatureGuard.isOfficial(this)) {
            throw new SecurityException("Unofficial or modified Kinozal TV package");
        }
        // Restore periodic cloud sync after process/app restart for Plus devices.
        CloudSyncScheduler.ensureScheduled(this);
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                CrashDiagnostics.write(this, thread, error);
            } catch (Throwable ignored) { }
            Log.e("KinozalCrash", "Uncaught application exception", error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }
}
