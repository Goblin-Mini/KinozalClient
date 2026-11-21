package com.goblinmini.kinozalclient;

import android.content.Context;

/** Mandatory native gate. Any loading/JNI failure is fatal by design. */
final class NativeAppGuard {
    static {
        System.loadLibrary("ktvguard");
    }

    private NativeAppGuard() { }

    private static native boolean nativeVerify(Context context);

    static void enforce(Context context) {
        final boolean trusted;
        try {
            trusted = nativeVerify(context);
        } catch (Throwable error) {
            throw new SecurityException("Native integrity check failed", error);
        }
        if (!trusted) throw new SecurityException("Untrusted or modified APK");
    }

    static boolean isTrusted(Context context) {
        try {
            return context != null && nativeVerify(context);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
