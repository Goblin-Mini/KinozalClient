package com.goblinmini.kinozalclient;

import android.app.Application;
import android.util.Log;

public final class KinozalApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
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
