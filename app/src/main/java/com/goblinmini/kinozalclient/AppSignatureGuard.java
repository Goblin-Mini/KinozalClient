package com.goblinmini.kinozalclient;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Rejects repackaged builds signed with a certificate other than the official
 * Kinozal TV release certificate. This is a tamper-resistance layer, not a
 * claim that a fully patched/rooted client is impossible.
 */
final class AppSignatureGuard {
    private static final String OFFICIAL_CERT_SHA256 =
            "C3CEC28612A951F18A0016CD193B5CAD5404905712EE0A4BFC593FDFE0E83281";

    private AppSignatureGuard() { }

    static boolean isOfficial(Context context) {
        if (context == null) return false;
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= 28) {
                info = pm.getPackageInfo(context.getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.signingInfo == null || info.signingInfo.hasMultipleSigners()) {
                    return false;
                }
                Signature[] signatures = info.signingInfo.getApkContentsSigners();
                return matches(signatures);
            } else {
                //noinspection deprecation
                info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
                //noinspection deprecation
                return matches(info.signatures);
            }
        } catch (Exception error) {
            return false;
        }
    }

    private static boolean matches(Signature[] signatures) throws Exception {
        if (signatures == null || signatures.length != 1) return false;
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(signatures[0].toByteArray());
        StringBuilder actual = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            actual.append(String.format(Locale.US, "%02X", value & 0xff));
        }
        return constantTimeEquals(OFFICIAL_CERT_SHA256, actual.toString());
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null || expected.length() != actual.length()) return false;
        int difference = 0;
        for (int i = 0; i < expected.length(); i++) difference |= expected.charAt(i) ^ actual.charAt(i);
        return difference == 0;
    }
}
