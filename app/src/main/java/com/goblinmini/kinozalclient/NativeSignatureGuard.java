package com.goblinmini.kinozalclient;

import android.content.Context;

/**
 * Second, native implementation of the release signing-certificate check.
 * The Java check remains in place; release builds require both checks to pass.
 */
final class NativeSignatureGuard {
    private static final boolean LIBRARY_LOADED;

    static {
        boolean loaded;
        try {
            System.loadLibrary("ktvguard");
            loaded = true;
        } catch (Throwable error) {
            loaded = false;
        }
        LIBRARY_LOADED = loaded;
    }

    private NativeSignatureGuard() { }

    static boolean isOfficial(Context context) {
        if (context == null || !LIBRARY_LOADED) return false;
        try {
            return AppSignatureGuard.isOfficial(context)
                    && verifyNative(context.getApplicationContext());
        } catch (Throwable error) {
            return false;
        }
    }

    private static native boolean verifyNative(Context context);
}
