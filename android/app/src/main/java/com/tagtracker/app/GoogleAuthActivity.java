package com.tagtracker.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * The two one-time Google steps, done in an in-app browser (replaces the Chrome
 * automation GoogleFindMyTools uses on a PC):
 *   MODE_SIGN_IN   – Google sign-in page; we read the "oauth_token" cookie it sets.
 *   MODE_UNLOCK    – Google's "unlock encrypted data" page where you enter your phone's
 *                    screen lock; it hands the Find Hub decryption keys to window.mm.
 * The user types their password/PIN into Google's own page; the app never sees them.
 */
public class GoogleAuthActivity extends Activity {
    static final String EXTRA_MODE = "mode";
    static final String MODE_SIGN_IN = "sign_in";
    static final String MODE_UNLOCK = "unlock";

    private WebView web;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean done = false;
    private String mode;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mode = getIntent().getStringExtra(EXTRA_MODE);
        web = new WebView(this);
        setContentView(web);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);

        if (MODE_SIGN_IN.equals(mode)) {
            setTitle("Sign in to Google");
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    checkCookie();
                }
            });
            web.loadUrl("https://accounts.google.com/EmbeddedSetup");
            handler.postDelayed(this::pollCookie, 1000);
        } else {
            setTitle("Unlock Find Hub encryption");
            // Google's page calls window.mm.setVaultSharedKeys(str, vaultKeysJson) like it does
            // inside the Find Hub app. A Java interface named "mm" exists before any page script runs.
            web.addJavascriptInterface(new AuthBridge(), "mm");
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    // Also accept vaultKeys passed as a JS object (Java params only take strings).
                    view.evaluateJavascript("(function(){var j=window.mm;if(!j||j.__w)return;" +
                            "window.mm={__w:1,setVaultSharedKeys:function(s,k){j.setVaultSharedKeys(String(s)," +
                            "typeof k==='string'?k:JSON.stringify(k));},closeView:function(){j.closeView();}};})()", null);
                }
            });
            new Thread(() -> {
                try {
                    String url = TagApp.py(this).callAttr("shared_key_url").toString();
                    runOnUiThread(() -> web.loadUrl(url));
                } catch (Exception e) {
                    runOnUiThread(() -> fail(e));
                }
            }).start();
        }
    }

    private void pollCookie() {
        if (done || isFinishing()) return;
        checkCookie();
        handler.postDelayed(this::pollCookie, 1000);
    }

    private void checkCookie() {
        if (done) return;
        String cookies = CookieManager.getInstance().getCookie("https://accounts.google.com");
        if (cookies == null) return;
        for (String c : cookies.split(";")) {
            String[] kv = c.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("oauth_token")) {
                done = true;
                String token = kv[1];
                Toast.makeText(this, "Signed in, connecting…", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    try {
                        String email = TagApp.py(this).callAttr("sign_in", token).toString();
                        runOnUiThread(() -> {
                            Toast.makeText(this, "Connected " + email, Toast.LENGTH_LONG).show();
                            setResult(RESULT_OK);
                            finish();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> fail(e));
                    }
                }).start();
                return;
            }
        }
    }

    private void fail(Exception e) {
        new AlertDialog.Builder(this)
                .setTitle("Something went wrong")
                .setMessage(String.valueOf(e.getMessage()))
                .setPositiveButton("OK", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    class AuthBridge {
        @JavascriptInterface
        public void setVaultSharedKeys(String str, String vaultKeys) {
            if (done || vaultKeys == null || vaultKeys.equals("undefined")) return;
            done = true;
            try {
                TagApp.py(GoogleAuthActivity.this).callAttr("save_vault_keys", vaultKeys);
                runOnUiThread(() -> {
                    Toast.makeText(GoogleAuthActivity.this, "Encryption unlocked", Toast.LENGTH_LONG).show();
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception e) {
                done = false;
                runOnUiThread(() -> fail(e));
            }
        }

        @JavascriptInterface
        public void closeView() {
            runOnUiThread(GoogleAuthActivity.this::finish);
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
