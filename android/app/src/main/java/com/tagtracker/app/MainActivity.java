package com.tagtracker.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Standalone map screen. Everything runs on the phone: the bundled web UI (web/) is served
 * from the APK assets and talks to the Python core through the "Native" JS bridge instead of
 * an HTTP server. No login, no remote server.
 */
public class MainActivity extends Activity {
    private WebView web;
    private WebViewAssetLoader loader;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // First launch (or signed out) goes to setup.
        boolean connected = false;
        try {
            connected = new JSONObject(TagApp.py(this).callAttr("account_json").toString())
                    .optBoolean("connected");
        } catch (Exception ignored) {
        }
        if (!connected && savedInstanceState == null) {
            startActivity(new Intent(this, SettingsActivity.class));
        }

        loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        setContentView(web);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.addJavascriptInterface(new Native(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        PollWorker.schedule(this);
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("https://appassets.androidplatform.net/assets/web/index.html");
    }

    /** JS -> Python bridge. Method names match the fetch() paths the web UI would call on the server. */
    class Native {
        @JavascriptInterface
        public String devices() {
            return TagApp.py(MainActivity.this).callAttr(
                    "devices_json", TagApp.intervalMinutes(MainActivity.this),
                    DriveBackup.enabled(MainActivity.this)).toString();
        }

        @JavascriptInterface
        public String history(String deviceId, long start, long end) {
            return TagApp.py(MainActivity.this).callAttr("history_json", deviceId, start, end).toString();
        }

        @JavascriptInterface
        public void pollNow() {
            PollWorker.runNow(MainActivity.this);
        }

        @JavascriptInterface
        public void rename(String deviceId, String name) {
            TagApp.py(MainActivity.this).callAttr("rename", deviceId, name);
        }

        @JavascriptInterface
        public void openSettings() {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        }

        /** Writes an export to the cache and hands it to a share/save chooser. */
        @JavascriptInterface
        public void export(String deviceId, long start, long end, String format) {
            new Thread(() -> {
                try {
                    String name = "track-" + start + "-" + end + "." + format;
                    File out = new File(getCacheDir(), name);
                    if (format.equals("gpx")) {
                        TagApp.py(MainActivity.this).callAttr("export_gpx", out.getAbsolutePath(), deviceId, start, end);
                    } else {
                        TagApp.py(MainActivity.this).callAttr("export_csv", out.getAbsolutePath(), deviceId, start, end);
                    }
                    Uri uri = androidx.core.content.FileProvider.getUriForFile(
                            MainActivity.this, getPackageName() + ".files", out);
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType(format.equals("gpx") ? "application/gpx+xml" : "text/csv");
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    runOnUiThread(() -> startActivity(Intent.createChooser(share, "Save or share " + name)));
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
