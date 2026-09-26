package com.tagtracker.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

/** Premium onboarding / "how it works" guide, rendered from the bundled help.html. */
public class HelpActivity extends Activity {
    /** Set true when opened as first-run onboarding, so "Got it" goes to setup. */
    static final String EXTRA_ONBOARD = "onboard";

    private boolean onboard;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("How it works");
        onboard = getIntent().getBooleanExtra(EXTRA_ONBOARD, false);

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        WebView web = new WebView(this);
        setContentView(web);
        web.getSettings().setJavaScriptEnabled(true);
        web.addJavascriptInterface(new Bridge(), "TagTrackerApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if ("appassets.androidplatform.net".equals(r.getUrl().getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl()));
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        web.loadUrl("https://appassets.androidplatform.net/assets/web/help.html");
    }

    class Bridge {
        @JavascriptInterface
        public void doneHelp() {
            runOnUiThread(() -> {
                if (onboard) startActivity(new Intent(HelpActivity.this, SettingsActivity.class));
                finish();
            });
        }
    }
}
