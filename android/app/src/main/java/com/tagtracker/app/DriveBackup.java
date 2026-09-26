package com.tagtracker.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Keeps a CSV copy of the full history in a file the user picked in Google Drive
 * (via the system file picker, so no Google Cloud setup is needed).
 */
final class DriveBackup {
    private DriveBackup() {}

    static boolean enabled(Context ctx) {
        return TagApp.prefs(ctx).getString(TagApp.KEY_DRIVE_URI, null) != null;
    }

    static void backupIfDue(Context ctx, boolean hasNewData) {
        if (!enabled(ctx)) return;
        // Upload whenever a poll brought new locations, or if we've never synced yet.
        long last = TagApp.prefs(ctx).getLong(TagApp.KEY_LAST_BACKUP, 0);
        if (hasNewData || last == 0) backupNow(ctx);
    }

    /** Returns null on success, or an error message. */
    static String backupNow(Context ctx) {
        SharedPreferences p = TagApp.prefs(ctx);
        String uri = p.getString(TagApp.KEY_DRIVE_URI, null);
        if (uri == null) return "No Google Drive file chosen";
        String error = null;
        File tmp = new File(ctx.getCacheDir(), "backup.csv");
        try {
            TagApp.py(ctx).callAttr("export_csv", tmp.getAbsolutePath());
            try (InputStream in = new FileInputStream(tmp); OutputStream out = open(ctx, Uri.parse(uri))) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            p.edit().putLong(TagApp.KEY_LAST_BACKUP, System.currentTimeMillis()).apply();
        } catch (Exception e) {
            error = e.getMessage() != null ? e.getMessage() : e.toString();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        TagApp.py(ctx).callAttr("record_backup", error);
        return error;
    }

    private static OutputStream open(Context ctx, Uri uri) throws Exception {
        try {
            OutputStream out = ctx.getContentResolver().openOutputStream(uri, "wt"); // overwrite
            if (out != null) return out;
        } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
            // some providers don't know "wt"; "w" truncates there too
        }
        OutputStream out = ctx.getContentResolver().openOutputStream(uri, "w");
        if (out == null) throw new IllegalStateException("Cannot write to the Drive file");
        return out;
    }
}
