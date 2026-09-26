package com.tagtracker.app;

import android.app.Activity;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.widget.ScrollView;
import android.widget.TextView;

/** Offline help and troubleshooting. */
public class HelpActivity extends Activity {
    private static final String HTML =
        "<h2>How Tag Tracker works</h2>"
        + "<p>Google Find Hub only shows a tag's <i>latest</i> spot. This app checks Find Hub in the "
        + "background every few minutes, <b>saves every location</b>, and draws the travel history on a map. "
        + "It keeps running when the app is closed.</p>"

        + "<h2>One-time setup (two Google steps)</h2>"
        + "<p><b>Step 1 – Sign in.</b> Sign in with the Google account you use in Find Hub. This lets the app "
        + "see your list of tags.</p>"
        + "<p><b>Step 2 – Unlock encryption.</b> This is separate and <b>required to see locations</b>. Tap "
        + "<b>Unlock encryption keys</b> and enter your phone's screen lock (PIN/pattern) on Google's page. "
        + "Without this step the map stays empty even though your tags are listed.</p>"
        + "<p>You'll know both are done when Setup shows <b>Signed in ✓</b> and <b>Encryption unlocked ✓</b>.</p>"

        + "<h2>Seeing locations</h2>"
        + "<p>After setup, tap <b>Check for locations now</b> (menu) once. A tag only reports when someone's "
        + "Android phone passes near it, so the first points can take a little while, and quiet areas give fewer "
        + "points. History builds up over time – use the range buttons (Today … 1 year) and the play button "
        + "to replay a trip. Tap a point, then <b>Open in Google Maps</b> to see it there.</p>"

        + "<h2>Background running</h2>"
        + "<p>Android limits background work to once every 15 minutes at best, and may pause apps to save battery. "
        + "In Setup, tap <b>Allow background running</b> so checks keep happening. The phone needs a network "
        + "connection when a check runs.</p>"

        + "<h2>Google Drive backup</h2>"
        + "<p>In Setup, tap <b>Choose Google Drive file</b> and create a file such as "
        + "<tt>TagTracker-history.csv</tt> in your Drive. The app then <b>uploads your full history to that "
        + "file automatically</b> after new locations arrive, so your data is safe if you lose the phone. Open the "
        + "file in Google Sheets any time. Tap <b>Sync to Drive now</b> to upload immediately. On a new phone, use "
        + "<b>Restore history from Drive</b> to load it back.</p>"

        + "<h2>The map is blank</h2>"
        + "<p>Make sure both setup steps show ✓ and you have run <b>Check for locations now</b>. If tags are "
        + "listed but no locations appear, step 2 (unlock) is usually missing. A brand-new tag has no history yet.</p>"

        + "<h2>Sign-in problems</h2>"
        + "<p>Use the same Google account and, ideally, the same network as your phone – Google can reject the "
        + "keys if requests come from a very different region. If a step fails, open it again from Setup and retry.</p>";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Help");
        ScrollView scroll = new ScrollView(this);
        TextView tv = new TextView(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextSize(15);
        tv.setLineSpacing(0, 1.1f);
        tv.setText(Html.fromHtml(HTML, Html.FROM_HTML_MODE_COMPACT));
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        scroll.addView(tv);
        setContentView(scroll);
    }
}
