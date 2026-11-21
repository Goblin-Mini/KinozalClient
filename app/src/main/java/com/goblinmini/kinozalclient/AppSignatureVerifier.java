package com.goblinmini.kinozalclient;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Checks that this installed APK is signed by the official Kinozal TV release key.
 * This is a client-side tamper signal, not a substitute for server-side authorization:
 * a determined attacker controlling a modified APK can patch this check.
 */
final class AppSignatureVerifier {
    // SHA-256 of the official release signing certificate (DER encoded X.509).
    private static final String OFFICIAL_CERT_SHA256 = "C3CEC28612A951F18A0016CD193B5CAD5404905712EE0A4BFC593FDFE0E83281";

    private AppSignatureVerifier() { }

    static boolean isTrusted(Context context) {
        if (context == null) return false;
        try {
            PackageManager pm = context.getPackageManager();
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = pm.getPackageInfo(
                        context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.signingInfo == null || info.signingInfo.hasMultipleSigners()) return false;
                signatures = info.signingInfo.getApkContentsSigners();
            } else {
                //noinspection deprecation
                PackageInfo info = pm.getPackageInfo(
                        context.getPackageName(), PackageManager.GET_SIGNATURES);
                //noinspection deprecation
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length != 1) return false;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(signatures[0].toByteArray());
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format(Locale.US, "%02X", value & 0xff));
            }
            return OFFICIAL_CERT_SHA256.equals(hex.toString());
        } catch (Exception ignored) {
            return false;
        }
    }
}
