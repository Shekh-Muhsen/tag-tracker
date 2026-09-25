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
}
