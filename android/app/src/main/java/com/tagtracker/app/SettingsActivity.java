package com.tagtracker.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** Setup and settings: Google connection, background checking, Google Drive backup. */
public class SettingsActivity extends Activity {
    private static final int REQ_SIGN_IN = 1, REQ_UNLOCK = 2, REQ_DRIVE_FILE = 3;
    private static final int[] INTERVALS = {15, 30, 60, 120};

    private LinearLayout root;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Tag Tracker setup");

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        root.removeAllViews();

        boolean connected = false;
        String email = "";
        String error = null;
        try {
            JSONObject a = new JSONObject(TagApp.py(this).callAttr("account_json").toString());
            connected = a.optBoolean("connected");
            email = a.optString("email");
            error = a.has("error") && !a.isNull("error") ? a.optString("error") : null;
        } catch (Exception e) {
            error = e.getMessage();
        }

        heading("1. Connect your Google account");
        note("Sign in with the Google account you use in Find Hub, then unlock the encryption with your "
                + "phone's screen lock. This is done on Google's own pages – the app never sees your password.");

        if (connected) {
            info("✓ Connected" + (email.isEmpty() ? "" : " as " + email));
            button("Re-run encryption unlock", v -> startAuth(GoogleAuthActivity.MODE_UNLOCK, REQ_UNLOCK));
        } else {
            button("Sign in to Google", v -> startAuth(GoogleAuthActivity.MODE_SIGN_IN, REQ_SIGN_IN));
            button("Unlock encryption keys", v -> startAuth(GoogleAuthActivity.MODE_UNLOCK, REQ_UNLOCK));
        }
        if (error != null) info("Note: " + error);

        heading("2. Background checking");
        note("The app checks Find Hub in the background and saves every location. Android's minimum interval "
                + "is 15 minutes. For reliable background work, allow the app to ignore battery optimisation.");
        int interval = TagApp.intervalMinutes(this);
        info("Currently every " + interval + " minutes.");
        LinearLayout rowI = row();
        for (int m : INTERVALS) {
            Button b = chip(m + " min", v -> {
                TagApp.prefs(this).edit().putInt(TagApp.KEY_INTERVAL, m).apply();
                PollWorker.schedule(this);
                render();
            });
            if (m == interval) b.setEnabled(false);
            rowI.addView(b);
        }
        root.addView(rowI);
        if (!isIgnoringBattery()) {
            button("Allow background running", v -> requestIgnoreBattery());
        } else {
            info("✓ Battery optimisation is off for this app.");
        }

        heading("3. Back up to Google Drive");
        note("Choose (or create) a CSV file in your Google Drive. The app overwrites it with your full history "
                + "every hour. Tip: in the picker, open Drive, pick a folder, and type a name like TagTracker-history.csv.");
        if (DriveBackup.enabled(this)) {
            info("✓ Backing up to the chosen Drive file.");
            button("Back up now", v -> new Thread(() -> {
                String err = DriveBackup.backupNow(this);
                toast(err == null ? "Backed up to Drive" : "Backup failed: " + err);
            }).start());
            button("Choose a different file", v -> pickDriveFile());
            button("Turn off Drive backup", v -> {
                TagApp.prefs(this).edit().remove(TagApp.KEY_DRIVE_URI).apply();
                render();
            });
        } else {
            button("Choose Google Drive file", v -> pickDriveFile());
        }

        heading("4. Your tags");
        button("Refresh tag list from Google", v -> new Thread(() -> {
            try {
                JSONArray tags = new JSONArray(TagApp.py(this).callAttr("list_tags_json").toString());
                toast(tags.length() + " tag(s) found");
            } catch (Exception e) {
                toast("Could not list tags: " + e.getMessage());
            }
        }).start());
        button("Check for locations now", v -> {
            PollWorker.runNow(this);
            toast("Checking Find Hub…");
        });

        status = new TextView(this);
        status.setPadding(0, dp(16), 0, 0);
        root.addView(status);
        button("Open map", v -> { startActivity(new Intent(this, MainActivity.class)); finish(); });
    }

    private void startAuth(String mode, int req) {
        Intent i = new Intent(this, GoogleAuthActivity.class);
        i.putExtra(GoogleAuthActivity.EXTRA_MODE, mode);
        startActivityForResult(i, req);
    }

    private void pickDriveFile() {
        // ACTION_CREATE_DOCUMENT lets the user create a new file (or pick an existing one to overwrite) in Drive.
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "TagTracker-history.csv");
        try {
            startActivityForResult(i, REQ_DRIVE_FILE);
        } catch (Exception e) {
            toast("No file picker available");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQ_DRIVE_FILE && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
            TagApp.prefs(this).edit().putString(TagApp.KEY_DRIVE_URI, uri.toString())
                    .putLong(TagApp.KEY_LAST_BACKUP, 0).apply();
            new Thread(() -> {
                String err = DriveBackup.backupNow(this);
                toast(err == null ? "Drive backup set up" : "Backup failed: " + err);
            }).start();
        }
        render();
    }

    // ---------- battery optimisation ----------

    private boolean isIgnoringBattery() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    @SuppressLint("BatteryLife")
    private void requestIgnoreBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    // ---------- tiny view helpers ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private void heading(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setPadding(0, dp(22), 0, dp(4));
        root.addView(t);
    }

    private void note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setPadding(0, 0, 0, dp(8));
        root.addView(t);
    }

    private void info(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setPadding(0, dp(4), 0, dp(4));
        root.addView(t);
    }

    private void button(String text, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        root.addView(b, lp);
    }

    private Button chip(String text, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }
}
