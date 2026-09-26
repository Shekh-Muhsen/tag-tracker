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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.Date;

/** Setup and settings: Google connection, background checking, Google Drive backup. */
public class SettingsActivity extends Activity {
    private static final int REQ_SIGN_IN = 1, REQ_UNLOCK = 2, REQ_DRIVE_FILE = 3, REQ_RESTORE = 4, REQ_IMPORT = 5;
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

        boolean signedIn = false, unlocked = false;
        String email = "";
        String error = null;
        try {
            JSONObject a = new JSONObject(TagApp.py(this).callAttr("account_json").toString());
            signedIn = a.optBoolean("signed_in");
            unlocked = a.optBoolean("unlocked");
            email = a.optString("email");
            error = a.has("error") && !a.isNull("error") ? a.optString("error") : null;
        } catch (Exception e) {
            error = e.getMessage();
        }

        heading("1. Connect your Google account");
        note("Two quick steps on Google's own pages (the app never sees your password or PIN). "
                + "Do both – locations stay hidden until step 2 is done.");

        // Step 1
        info((signedIn ? "✓ " : "① ") + "Step 1: Sign in"
                + (signedIn && !email.isEmpty() ? " – " + email : ""));
        button(signedIn ? "Sign in again" : "Sign in to Google",
                v -> startAuth(GoogleAuthActivity.MODE_SIGN_IN, REQ_SIGN_IN));

        // Step 2
        info((unlocked ? "✓ " : "② ") + "Step 2: Unlock encryption (needed to see locations)");
        button(unlocked ? "Unlock again" : "Unlock encryption keys",
                v -> startAuth(GoogleAuthActivity.MODE_UNLOCK, REQ_UNLOCK));
        if (signedIn && !unlocked) {
            note("⚠ You're signed in and your tags are listed, but the map will stay empty until you finish "
                    + "step 2. Tap “Unlock encryption keys” and enter your phone's screen lock.");
        }
        if (signedIn && unlocked) info("✓ All set – the app can read and decrypt your tags.");
        if (error != null) info("Note: " + error);

        // Option B alternative: import a login file made on a PC (most reliable).
        note("— or —");
        note("Option B: If the steps above fail, run the desktop setup once on a PC "
                + "(python -m server.manage google-login), copy the resulting data/google_secrets.json to this "
                + "phone, and import it here. No re-login needed on the phone.");
        button("Import login file (google_secrets.json)", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "*/*"});
            try {
                startActivityForResult(i, REQ_IMPORT);
            } catch (Exception e) {
                toast("No file picker available");
            }
        });

        heading("2. Background checking");
        note("The app checks Find Hub in the background with Android WorkManager (Google's battery-efficient "
                + "scheduler) and saves every location. It uses almost no battery while idle: it only wakes for a "
                + "few seconds per check, needs a network connection, and closes the connection in between. It uses "
                + "NO device-location permission (locations come from Google's network, not your phone's GPS). "
                + "Android's minimum interval is 15 minutes; pick a longer one to use even less battery.");
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
            note("Optional: only if your phone (Xiaomi/Samsung/etc.) aggressively kills background apps, allow "
                    + "unrestricted running so checks aren't skipped. Not required on most phones.");
            button("Allow unrestricted background (optional)", v -> requestIgnoreBattery());
        } else {
            info("✓ Unrestricted background running is allowed.");
        }

        heading("3. Sync to Google Drive");
        note("The app keeps a copy on the phone (so the map loads instantly) AND uploads your full history to "
                + "Google Drive – the cloud copy, safe even if you lose the phone.\n"
                + "IMPORTANT: in the picker, tap the ☰ menu on the left and choose GOOGLE DRIVE (not Downloads/"
                + "phone storage), pick a folder, and name the file e.g. TagTracker-history.csv. After that it "
                + "uploads automatically whenever new locations arrive.");
        if (DriveBackup.enabled(this)) {
            long last = TagApp.prefs(this).getLong(TagApp.KEY_LAST_BACKUP, 0);
            info("✓ Auto-syncing to your Drive file." + (last > 0
                    ? "  Last sync: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(new Date(last)) : "  (no sync yet)"));
            button("Sync to Drive now", v -> new Thread(() -> {
                String err = DriveBackup.backupNow(this);
                toast(err == null ? "Synced to Drive" : "Sync failed: " + err);
                runOnUiThread(this::render);
            }).start());
            button("Restore history from a Drive file", v -> pickRestoreFile());
            button("Choose a different file", v -> pickDriveFile());
            button("Turn off Drive sync", v -> {
                TagApp.prefs(this).edit().remove(TagApp.KEY_DRIVE_URI).apply();
                render();
            });
        } else {
            button("Choose Google Drive file", v -> pickDriveFile());
            button("Restore history from a Drive file", v -> pickRestoreFile());
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
        button("Help", v -> startActivity(new Intent(this, HelpActivity.class)));
    }

    private void pickRestoreFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/csv", "text/comma-separated-values", "text/plain"});
        try {
            startActivityForResult(i, REQ_RESTORE);
        } catch (Exception e) {
            toast("No file picker available");
        }
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
                toast(err == null ? "Drive sync set up" : "Sync failed: " + err);
                runOnUiThread(this::render);
            }).start();
        } else if (requestCode == REQ_IMPORT && data != null && data.getData() != null) {
            Uri uri = data.getData();
            new Thread(() -> {
                try {
                    File tmp = new File(getCacheDir(), "import-secrets.json");
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         OutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    String stateJson = TagApp.py(this).callAttr("import_secrets", tmp.getAbsolutePath()).toString();
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    JSONObject st = new JSONObject(stateJson);
                    toast(st.optBoolean("unlocked")
                            ? "Login imported – signed in and unlocked. Tap Check now."
                            : "Login imported, but it has no encryption key (unlock part). Locations may not decrypt.");
                    runOnUiThread(this::render);
                } catch (Exception e) {
                    toast("Import failed: " + e.getMessage());
                }
            }).start();
        } else if (requestCode == REQ_RESTORE && data != null && data.getData() != null) {
            Uri uri = data.getData();
            new Thread(() -> {
                try {
                    File tmp = new File(getCacheDir(), "restore.csv");
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         OutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    int added = TagApp.py(this).callAttr("import_csv", tmp.getAbsolutePath()).toInt();
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    toast("Restored " + added + " location(s) from the file");
                } catch (Exception e) {
                    toast("Restore failed: " + e.getMessage());
                }
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
