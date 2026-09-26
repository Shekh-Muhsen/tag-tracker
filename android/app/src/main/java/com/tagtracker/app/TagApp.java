package com.tagtracker.app;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.security.MessageDigest;
import java.security.SecureRandom;

public class TagApp extends Application {
    static final String PREFS = "tagtracker";
    static final String KEY_INTERVAL = "interval_minutes";
    static final String KEY_DRIVE_URI = "drive_uri";
    static final String KEY_DRIVE_AUTO = "drive_auto";
    static final String KEY_LAST_BACKUP = "last_backup_ms";
    static final String KEY_BACKUP_MINUTES = "backup_minutes";
    static final String KEY_GUARD = "guard_mode";
    // App lock (local to THIS phone only — never synced to Drive or Google).
    static final String KEY_APP_PW = "app_pw";        // salt:hash
    static final String KEY_APP_HINT = "app_hint";    // recovery hint text
    static volatile boolean appUnlocked = false;      // reset when the process is killed

    /** Warm up Python off the UI thread so the first screen never freezes (avoids ANR). */
    @Override
    public void onCreate() {
        super.onCreate();
        new Thread(() -> {
            try {
                py(this);
            } catch (Throwable ignored) {
            }
        }).start();
    }

    /** The Python "tagapp" module, initialised on first use (also from background workers).
     *  Call this OFF the main thread the first time — Python start can take a few seconds. */
    static synchronized PyObject py(Context ctx) {
        if (!Python.isStarted()) Python.start(new AndroidPlatform(ctx.getApplicationContext()));
        PyObject mod = Python.getInstance().getModule("tagapp");
        File data = new File(ctx.getFilesDir(), "data");
        //noinspection ResultOfMethodCallIgnored
        data.mkdirs();
        mod.callAttr("init", data.getAbsolutePath());
        return mod;
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    static int intervalMinutes(Context ctx) {
        return prefs(ctx).getInt(KEY_INTERVAL, 15);
    }

    static int backupMinutes(Context ctx) {
        return prefs(ctx).getInt(KEY_BACKUP_MINUTES, 60);
    }

    // ---------- app lock ----------

    static boolean hasAppLock(Context ctx) {
        return prefs(ctx).getString(KEY_APP_PW, null) != null;
    }

    static String appHint(Context ctx) {
        return prefs(ctx).getString(KEY_APP_HINT, "");
    }

    static void setAppLock(Context ctx, String password, String hint) {
        if (password == null || password.isEmpty()) {
            prefs(ctx).edit().remove(KEY_APP_PW).remove(KEY_APP_HINT).apply();
            return;
        }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        prefs(ctx).edit()
                .putString(KEY_APP_PW, hex(salt) + ":" + hashHex(salt, password))
                .putString(KEY_APP_HINT, hint == null ? "" : hint)
                .apply();
    }

    static boolean checkAppLock(Context ctx, String password) {
        String stored = prefs(ctx).getString(KEY_APP_PW, null);
        if (stored == null) return true;
        String[] parts = stored.split(":");
        if (parts.length != 2) return false;
        return hashHex(unhex(parts[0]), password).equals(parts[1]);
    }

    private static String hashHex(byte[] salt, String pw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(pw.getBytes("UTF-8"));
            // stretch a bit
            byte[] h = md.digest();
            for (int i = 0; i < 5000; i++) h = MessageDigest.getInstance("SHA-256").digest(h);
            return hex(h);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
