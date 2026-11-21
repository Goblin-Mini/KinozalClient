package com.goblinmini.kinozalclient;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-256-GCM storage for Plus and cloud-device credentials, backed by Android Keystore. */
final class PlusSecureStore {
    private static final String FILE_NAME = "ktv_secure_state.bin";
    private static final String KEY_ALIAS = "ktv_secure_state_aes_v1";
    private static final byte[] AAD = "ru.gm.ktv:secure-state:v1".getBytes(StandardCharsets.UTF_8);

    private PlusSecureStore() { }

    static synchronized String getString(Context context, String name, String fallback) {
        try { return read(context).optString(name, fallback); }
        catch (Exception error) { clearFile(context); return fallback; }
    }

    static synchronized long getLong(Context context, String name, long fallback) {
        try { JSONObject state = read(context); return state.has(name) ? state.getLong(name) : fallback; }
        catch (Exception error) { clearFile(context); return fallback; }
    }

    static synchronized boolean getBoolean(Context context, String name, boolean fallback) {
        try { JSONObject state = read(context); return state.has(name) ? state.getBoolean(name) : fallback; }
        catch (Exception error) { clearFile(context); return fallback; }
    }

    static synchronized void putString(Context context, String name, String value) throws Exception {
        JSONObject state = read(context); state.put(name, value); write(context, state);
    }

    static synchronized void putLong(Context context, String name, long value) throws Exception {
        JSONObject state = read(context); state.put(name, value); write(context, state);
    }

    static synchronized void putBoolean(Context context, String name, boolean value) throws Exception {
        JSONObject state = read(context); state.put(name, value); write(context, state);
    }

    static synchronized void remove(Context context, String... names) throws Exception {
        JSONObject state = read(context);
        for (String name : names) state.remove(name);
        write(context, state);
    }

    private static JSONObject read(Context context) throws Exception {
        File file = new File(context.getNoBackupFilesDir(), FILE_NAME);
        if (!file.isFile()) return new JSONObject();
        byte[] encrypted;
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            encrypted = output.toByteArray();
        }
        if (encrypted.length < 13) throw new SecurityException("secure_store_corrupt");
        byte[] iv = new byte[12];
        byte[] ciphertext = new byte[encrypted.length - 12];
        System.arraycopy(encrypted, 0, iv, 0, 12);
        System.arraycopy(encrypted, 12, ciphertext, 0, ciphertext.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
        cipher.updateAAD(AAD);
        return new JSONObject(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
    }

    private static void write(Context context, JSONObject state) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        cipher.updateAAD(AAD);
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(state.toString().getBytes(StandardCharsets.UTF_8));
        byte[] output = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, output, 0, iv.length);
        System.arraycopy(ciphertext, 0, output, iv.length, ciphertext.length);
        File directory = context.getNoBackupFilesDir();
        File target = new File(directory, FILE_NAME);
        File temporary = new File(directory, FILE_NAME + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            stream.write(output);
            stream.getFD().sync();
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new java.io.IOException("secure_store_commit_failed");
        }
    }

    private static SecretKey getKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static void clearFile(Context context) {
        File file = new File(context.getNoBackupFilesDir(), FILE_NAME);
        if (file.exists()) file.delete();
        File temporary = new File(context.getNoBackupFilesDir(), FILE_NAME + ".tmp");
        if (temporary.exists()) temporary.delete();
    }
}
