package com.tagtracker.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** A single, low-priority notification that reports the latest background check / Drive sync. */
final class Notify {
    private static final String CHANNEL = "sync";
    private static final int ID = 1;

    private Notify() {}

    private static NotificationManager mgr(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null
                && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Sync status",
                    NotificationManager.IMPORTANCE_LOW); // low = no sound, minimal battery
            ch.setDescription("Shows when the app checks Find Hub and syncs to Google Drive");
            nm.createNotificationChannel(ch);
        }
        return nm;
    }

    static void syncStatus(Context ctx, String text) {
        NotificationManager nm = mgr(ctx);
        if (nm == null) return;
        PendingIntent tap = PendingIntent.getActivity(ctx, 0, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx);
        Notification n = b.setContentTitle("Tag Tracker")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(tap)
                .setOnlyAlertOnce(true)
                .setOngoing(false)
                .build();
        try {
            nm.notify(ID, n);
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS not granted (Android 13+); silently skip.
        }
    }
}
