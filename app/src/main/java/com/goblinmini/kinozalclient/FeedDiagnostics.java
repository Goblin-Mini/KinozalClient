package com.goblinmini.kinozalclient;

import android.content.Context;

/** Retained as a compatibility shim; verbose feed diagnostics are disabled. */
public final class FeedDiagnostics {
    private FeedDiagnostics() { }
    public static void start(Context context, String group, String mirror, String[] paths, String source) { }
    public static void log(Context context, String group, String stage, String details) { }
    public static String read(Context context) { return ""; }
}
