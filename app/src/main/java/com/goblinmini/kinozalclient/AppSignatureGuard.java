package com.goblinmini.kinozalclient;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Defense-in-depth release signer verification.
 *
 * Checks Android's installed-package signer metadata and independently
 * verifies the signed DEX entry in the installed APK file. The file check
 * does not trust PackageManager's reported Signature object, so simple
 * PackageInfo/Signature spoofing is insufficient.
 *
 * This is a client-side friction layer, not an unbypassable trust anchor.
 */
final class AppSignatureGuard {
    private static final String OFFICIAL_CERT_SHA256 =
            "C3CEC28612A951F18A0016CD193B5CAD5404905712EE0A4BFC593FDFE0E83281";

    private AppSignatureGuard() { }

    static boolean isOfficial(Context context) {
        if (context == null) return false;
        try {
            return hasOfficialPackageManagerSigner(context)
                    && hasOfficialApkFileSigner(context);
        } catch (Throwable error) {
            return false;
        }
    }

    private static boolean hasOfficialPackageManagerSigner(Context context) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo info;
        if (Build.VERSION.SDK_INT >= 28) {
            info = pm.getPackageInfo(context.getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null || info.signingInfo.hasMultipleSigners()) return false;
            return matches(info.signingInfo.getApkContentsSigners());
        } else {
            //noinspection deprecation
            info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
            //noinspection deprecation
            return matches(info.signatures);
        }
    }

    /**
     * Reads the complete classes.dex entry so JarFile performs v1 signature
     * verification, then pins the signing certificate. v1 signing is enabled
     * explicitly in the release build for minSdk 23 compatibility.
     */
    private static boolean hasOfficialApkFileSigner(Context context) throws Exception {
        ApplicationInfo appInfo = context.getApplicationInfo();
        if (appInfo == null || appInfo.sourceDir == null) return false;

        try (JarFile apk = new JarFile(appInfo.sourceDir, true)) {
            JarEntry dex = apk.getJarEntry("classes.dex");
            if (dex == null || dex.isDirectory()) return false;

            try (InputStream input = apk.getInputStream(dex)) {
                byte[] buffer = new byte[16 * 1024];
                while (input.read(buffer) != -1) {
                    // Consume the entire entry; verification occurs at EOF.
                }
            }

            Certificate[] certificates = dex.getCertificates();
            if (certificates == null || certificates.length == 0) return false;

            boolean officialSignerFound = false;
            for (Certificate certificate : certificates) {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(certificate.getEncoded());
                officialSignerFound |= constantTimeEquals(OFFICIAL_CERT_SHA256, toHex(digest));
            }
            return officialSignerFound;
        }
    }

    private static boolean matches(Signature[] signatures) throws Exception {
        if (signatures == null || signatures.length != 1) return false;
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(signatures[0].toByteArray());
        return constantTimeEquals(OFFICIAL_CERT_SHA256, toHex(digest));
    }

    private static String toHex(byte[] digest) {
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(String.format(Locale.US, "%02X", value & 0xff));
        }
        return result.toString();
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null || expected.length() != actual.length()) return false;
        int difference = 0;
        for (int i = 0; i < expected.length(); i++) {
            difference |= expected.charAt(i) ^ actual.charAt(i);
        }
        return difference == 0;
    }
}
