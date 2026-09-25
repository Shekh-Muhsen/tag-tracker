package com.tagtracker.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

/**
 * Thin wrapper around the Tag Tracker web app. The server address is asked on first
 * launch and can be changed from the "Change server" link inside the app.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "tagtracker";
    private static final String KEY_URL = "server_url";

    private WebView web;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        web.addJavascriptInterface(new Bridge(), "TagTrackerApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                Uri base = Uri.parse(serverUrl());
                if (u.getHost() != null && u.getHost().equals(base.getHost())) return false;
                // External links (e.g. "Open in Google Maps") go to the proper app.
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, u));
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) showConnectionError(String.valueOf(err.getDescription()));
            }
        });
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
            r.addRequestHeader("User-Agent", userAgent);
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(this, "Downloading " + name, Toast.LENGTH_SHORT).show();
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else if (serverUrl().isEmpty()) {
            askServerUrl();
        } else {
            web.loadUrl(serverUrl());
        }
    }

    private String serverUrl() {
        return prefs.getString(KEY_URL, "");
    }

    private void askServerUrl() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://tracker.example.com");
        input.setText(serverUrl());
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle("Tag Tracker server")
                .setMessage("Enter the address of your Tag Tracker server.")
                .setView(input)
                .setCancelable(!serverUrl().isEmpty())
                .setPositiveButton("Connect", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
                    while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
                    prefs.edit().putString(KEY_URL, url).apply();
                    web.clearHistory();
                    web.loadUrl(url);
                })
                .show();
    }

    private void showConnectionError(String detail) {
        new AlertDialog.Builder(this)
                .setTitle("Can't reach server")
                .setMessage(serverUrl() + "\n\n" + detail)
                .setPositiveButton("Retry", (d, w) -> web.loadUrl(serverUrl()))
                .setNegativeButton("Change server", (d, w) -> askServerUrl())
                .show();
    }

    class Bridge {
        @JavascriptInterface
        public void changeServer() {
            runOnUiThread(MainActivity.this::askServerUrl);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
