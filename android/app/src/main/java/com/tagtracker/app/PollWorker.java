package com.tagtracker.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Background job: asks Google Find Hub for the tags' locations, stores them, and
 * (when due) copies the history to Google Drive. WorkManager keeps running it when the
 * app is closed and after reboots.
 */
public class PollWorker extends Worker {
    private static final String PERIODIC = "tag-poll";
    private static final String ONCE = "tag-poll-now";

    public PollWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        int added = TagApp.py(ctx).callAttr("poll").toInt();
        DriveBackup.backupIfDue(ctx, added > 0);

        // Guard mode: alert loudly if a tag moved (e.g. a stolen bike being ridden away).
        if (added > 0 && TagApp.prefs(ctx).getBoolean(TagApp.KEY_GUARD, false)) {
            try {
                org.json.JSONArray moves = new org.json.JSONArray(
                        TagApp.py(ctx).callAttr("new_movements").toString());
                for (int i = 0; i < moves.length(); i++) {
                    org.json.JSONObject m = moves.getJSONObject(i);
                    Notify.theftAlert(ctx, m.getString("id"), m.getString("name"), m.getInt("moved_m"),
                            m.getDouble("lat"), m.getDouble("lon"));
                }
            } catch (Exception ignored) {
            }
        }
        boolean drive = DriveBackup.enabled(ctx)
                || TagApp.prefs(ctx).getBoolean(TagApp.KEY_DRIVE_AUTO, false);
        String when = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                .format(new java.util.Date());
        boolean guarding = TagApp.prefs(ctx).getBoolean(TagApp.KEY_GUARD, false);
        boolean ongoing = TagApp.prefs(ctx).getBoolean(TagApp.KEY_BG_NOTIFY, true);
        String prefix = guarding ? "🔒 Guarding" : "Active";
        Notify.syncStatus(ctx, added > 0
                ? prefix + " · " + added + " new location(s)" + (drive ? " · synced" : "") + " · " + when
                : prefix + " · last check " + when, ongoing);
        return Result.success();
    }

    private static Constraints constraints() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }

    /** (Re)starts the repeating background check with the chosen interval. */
    static void schedule(Context ctx) {
        int minutes = Math.max(15, TagApp.intervalMinutes(ctx)); // Android's minimum is 15 min
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(PollWorker.class, minutes, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req);
    }

    static void runNow(Context ctx) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(PollWorker.class)
                .setConstraints(constraints())
                .build();
        WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, req);
    }

    /** Fully stop background tracking (manual exit). Reopening the app resumes it. */
    static void stop(Context ctx) {
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC);
        WorkManager.getInstance(ctx).cancelUniqueWork(ONCE);
        android.app.NotificationManager nm =
                (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancelAll();
    }
}
