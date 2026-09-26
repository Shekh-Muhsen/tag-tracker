package com.tagtracker.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Rings the tag when the user taps "Ring" on a movement alert notification. */
public class RingReceiver extends BroadcastReceiver {
    static final String EXTRA_DEVICE = "device";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String id = intent.getStringExtra(EXTRA_DEVICE);
        if (id == null) return;
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                TagApp.py(ctx).callAttr("play_sound", id);
            } catch (Exception ignored) {
            } finally {
                pr.finish();
            }
        }).start();
    }
}
