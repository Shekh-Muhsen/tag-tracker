package com.tagtracker.app;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;

public class TagApp extends Application {
    static final String PREFS = "tagtracker";
    static final String KEY_INTERVAL = "interval_minutes";
    static final String KEY_DRIVE_URI = "drive_uri";
    static final String KEY_DRIVE_AUTO = "drive_auto";
    static final String KEY_LAST_BACKUP = "last_backup_ms";
    static final String KEY_BACKUP_MINUTES = "backup_minutes";

    /** The Python "tagapp" module, initialised on first use (also from background workers). */
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
}
