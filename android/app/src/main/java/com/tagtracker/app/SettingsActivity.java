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
import android.widget.EditText;
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
        TextView loading = new TextView(this);
        loading.setText("Loading…");
        loading.setPadding(0, dp(20), 0, 0);
        root.addView(loading);
        // Read the Google auth state OFF the UI thread — Python start can take seconds (avoids ANR).
        new Thread(() -> {
            boolean signedIn = false, unlocked = false;
            String email = "", error = null;
            try {
                JSONObject a = new JSONObject(TagApp.py(this).callAttr("account_json").toString());
                signedIn = a.optBoolean("signed_in");
                unlocked = a.optBoolean("unlocked");
                email = a.optString("email");
                error = a.has("error") && !a.isNull("error") ? a.optString("error") : null;
            } catch (Exception e) {
                error = e.getMessage();
            }
            final boolean fSignedIn = signedIn, fUnlocked = unlocked;
            final String fEmail = email, fError = error;
            runOnUiThread(() -> buildUi(fSignedIn, fUnlocked, fEmail, fError));
        }).start();
    }

    private void buildUi(boolean signedIn, boolean unlocked, String email, String error) {
        if (isFinishing() || isDestroyed()) return;
        root.removeAllViews();

        // Welcome / onboarding intro
        TextView welcome = new TextView(this);
        welcome.setText("Set up Tag Tracker");
        welcome.setTextSize(22);
        welcome.setTypeface(welcome.getTypeface(), android.graphics.Typeface.BOLD);
        welcome.setPadding(0, dp(6), 0, dp(2));
        root.addView(welcome);
        note("Find Hub shows only your tag's last spot. This app saves the full history and alerts you if it "
                + "moves. New here? Read the quick guide first – it explains everything.");
        Button guide = new Button(this);
        guide.setText("📖  How it works (guide)");
        guide.setAllCaps(false);
        guide.setOnClickListener(v -> startActivity(new Intent(this, HelpActivity.class)));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.bottomMargin = dp(8);
        root.addView(guide, glp);
        progress(signedIn, unlocked);

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
        boolean bgNotify = TagApp.prefs(this).getBoolean(TagApp.KEY_BG_NOTIFY, true);
        info(bgNotify ? "✓ A status notification shows while the app runs in the background."
                : "Background status notification is off.");
        button(bgNotify ? "Hide background notification" : "Show background notification", v -> {
            TagApp.prefs(this).edit().putBoolean(TagApp.KEY_BG_NOTIFY, !bgNotify).apply();
            render();
        });

        note("Closing the app (swiping it away) does NOT stop tracking – it keeps checking and saving in "
                + "the background, which is what you want. Use the button below only when you want to FULLY stop it.");
        boolean paused = TagApp.prefs(this).getBoolean(TagApp.KEY_PAUSED, false);
        if (paused) {
            info("⏸ Background tracking is STOPPED. No locations are being saved.");
            button("Resume background tracking", v -> {
                TagApp.prefs(this).edit().putBoolean(TagApp.KEY_PAUSED, false).apply();
                PollWorker.schedule(this);
                toast("Tracking resumed");
                render();
            });
        } else {
            button("⏹ Stop tracking (full exit)", v -> {
                TagApp.prefs(this).edit().putBoolean(TagApp.KEY_PAUSED, true).apply();
                PollWorker.stop(this);
                toast("Background tracking stopped. Tap Resume to start again.");
                render();
            });
        }

        heading("3. Sync to Google Drive");
        note("The app keeps a copy on the phone (so the map loads instantly) AND uploads your full history to "
                + "Google Drive – the cloud copy, safe even if you lose the phone.");

        // Default (automatic) mode: one fixed folder in the SAME Google account, no file picking.
        // Signing into the app on another phone with the same account pulls the history back.
        boolean autoDrive = TagApp.prefs(this).getBoolean(TagApp.KEY_DRIVE_AUTO, false);
        info(autoDrive ? "✓ DEFAULT automatic sync is ON (folder ‘TagTracker’ in your Google account)."
                : "DEFAULT: automatic sync to a ‘TagTracker’ folder in your own Google account – no "
                  + "file picking, and it comes back automatically when you sign in on another phone.");
        button(autoDrive ? "Sync default folder now" : "Use automatic default sync (recommended)", v -> {
            TagApp.prefs(this).edit().putBoolean(TagApp.KEY_DRIVE_AUTO, true).apply();
            new Thread(() -> {
                try {
                    TagApp.py(this).callAttr("set_drive_default", true);
                    int got = TagApp.py(this).callAttr("drive_default_restore").toInt();
                    TagApp.py(this).callAttr("drive_default_upload");
                    toast(got > 0 ? "Default sync on – pulled " + got + " saved location(s) from Drive"
                            : "Default sync on – Drive folder ready");
                } catch (Exception e) {
                    toast("Automatic sync needs the Google sign-in first: " + e.getMessage());
                }
                runOnUiThread(this::render);
            }).start();
        });
        if (autoDrive) {
            button("Turn off automatic sync", v -> {
                TagApp.prefs(this).edit().putBoolean(TagApp.KEY_DRIVE_AUTO, false).apply();
                new Thread(() -> { try { TagApp.py(this).callAttr("set_drive_default", false); } catch (Exception ignored) {} }).start();
                render();
            });
        }

        note("— or use a CUSTOM file (any account / location) —\n"
                + "In the picker, tap ☰ and choose GOOGLE DRIVE (not phone storage), pick a folder, and name it "
                + "e.g. TagTracker-history.csv.");
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

        heading("5. Theft / guard alerts");
        note("Turn this ON when the bike is parked. If a tag MOVES more than ~150 m, you get a LOUD "
                + "notification with a Maps link – so you can act fast if it's stolen. Uses the same "
                + "background checks, so no extra battery. Tip: if it's stolen, share the live location and history "
                + "with the police – don't confront the thief yourself.");
        boolean guard = TagApp.prefs(this).getBoolean(TagApp.KEY_GUARD, false);
        info(guard ? "✓ Guard mode is ON – you'll be alerted if a tag moves."
                : "Guard mode is off.");
        button(guard ? "Turn OFF guard mode" : "Turn ON guard mode", v -> {
            TagApp.prefs(this).edit().putBoolean(TagApp.KEY_GUARD, !guard).apply();
            render();
        });

        heading("6. App lock (this phone only)");
        note("Protect the app with a password so no one who picks up your phone can open your tracker. "
                + "The password and hint are stored ONLY on this phone – never sent to Google or Drive, so they "
                + "can't be recovered from anywhere else. If you forget it and the hint doesn't help, reinstall the app.");
        if (TagApp.hasAppLock(this)) {
            info("✓ App lock is ON.");
            button("Change app password", v -> showAppLockDialog());
            button("Turn off app lock", v -> { TagApp.setAppLock(this, null, null); render(); });
        } else {
            button("Set an app password", v -> showAppLockDialog());
        }

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

    private void progress(boolean signedIn, boolean unlocked) {
        LinearLayout r = row();
        r.setPadding(0, dp(2), 0, dp(10));
        addStep(r, "Sign in", signedIn);
        addStep(r, "Unlock", unlocked);
        addStep(r, "Background", true);
        root.addView(r);
    }

    private void addStep(LinearLayout r, String label, boolean done) {
        TextView t = new TextView(this);
        t.setText((done ? "✓ " : "○ ") + label);
        t.setTextSize(12);
        t.setPadding(dp(11), dp(6), dp(11), dp(6));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(999));
        g.setColor(done ? 0xFF16A34A : 0x1A64748B);
        t.setBackground(g);
        t.setTextColor(done ? 0xFFFFFFFF : 0xFF64748B);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        r.addView(t, lp);
    }

    private void showAppLockDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, 0);
        EditText pw = new EditText(this);
        pw.setHint("New app password (min 4)");
        pw.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText hint = new EditText(this);
        hint.setHint("Recovery hint (e.g. ‘usual PIN’)");
        box.addView(pw);
        box.addView(hint);
        new AlertDialog.Builder(this)
                .setTitle("Set app password")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String p = pw.getText().toString();
                    if (p.length() < 4) { toast("Password too short"); return; }
                    TagApp.setAppLock(this, p, hint.getText().toString());
                    TagApp.appUnlocked = true;
                    toast("App lock set");
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
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
