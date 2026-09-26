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
            // Start clean so a leftover/premature oauth_token from a previous try isn't exchanged.
            CookieManager.getInstance().removeAllCookies(v -> {
                web.loadUrl("https://accounts.google.com/EmbeddedSetup");
                handler.postDelayed(this::pollCookie, 1500);
            });
        } else {
            setTitle("Unlock Find Hub encryption");
            Toast.makeText(this, "Sign in again to unlock encryption", Toast.LENGTH_LONG).show();
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
                    // The unlock page needs a normal accounts.google.com web session (the step-1
                    // device login doesn't create one). So sign in on accounts.google.com first,
                    // and only load the unlock page once signed in (redirected to myaccount).
                    if (!unlockLoaded && url != null && url.startsWith("https://myaccount.google.com")) {
                        unlockLoaded = true;
                        setTitle("Unlock Find Hub encryption");
                        new Thread(() -> {
                            try {
                                String u = TagApp.py(GoogleAuthActivity.this).callAttr("shared_key_url").toString();
                                runOnUiThread(() -> web.loadUrl(u));
                            } catch (Exception e) {
                                runOnUiThread(() -> fail(e));
                            }
                        }).start();
                    }
                }
            });
            // Fresh session so the sign-in actually happens (avoids a stale/partial 401 session).
            CookieManager.getInstance().removeAllCookies(v -> web.loadUrl("https://accounts.google.com/"));
        }
    }

    private boolean unlockLoaded = false;

    private void pollCookie() {
        if (done || isFinishing()) return;
        checkCookie();
        handler.postDelayed(this::pollCookie, 1000);
    }

    private String lastTried = "";
    private long lastTryMs = 0;
    private volatile boolean inFlight = false;
    private int attempts = 0;
    private static final int MAX_ATTEMPTS = 20; // ~2 min at 6s spacing; avoids hammering Google

    private void checkCookie() {
        if (done || inFlight || attempts >= MAX_ATTEMPTS) return;
        String cookies = CookieManager.getInstance().getCookie("https://accounts.google.com");
        if (cookies == null) return;
        String token = null;
        for (String c : cookies.split(";")) {
            String[] kv = c.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("oauth_token")) {
                token = kv[1];
                break;
            }
        }
        if (token == null || token.isEmpty()) return;
        // The EmbeddedSetup page sets oauth_token early, before sign-in is finished; that token
        // fails with BadAuthentication. Retry the same value periodically (it becomes valid once
        // sign-in completes) and try any new value immediately.
        long now = System.currentTimeMillis();
        if (token.equals(lastTried) && now - lastTryMs < 6000) return;
        lastTried = token;
        lastTryMs = now;
        inFlight = true;
        attempts++;
        final String t = token;
        new Thread(() -> {
            try {
                tryExchange(t);
            } finally {
                inFlight = false;
                if (!done && attempts >= MAX_ATTEMPTS) {
                    runOnUiThread(() -> new AlertDialog.Builder(this)
                            .setTitle("Couldn't finish sign-in")
                            .setMessage("Sign-in didn't complete. To protect your account the app stopped retrying. "
                                    + "You can try again, or use the more reliable Option B: import a login file "
                                    + "made on a PC (see Setup).")
                            .setPositiveButton("OK", (d, w) -> finish())
                            .setCancelable(false).show());
                }
            }
        }).start();
    }

    private void tryExchange(String token) {
        try {
            String email = TagApp.py(this).callAttr("sign_in", token).toString();
            done = true;
            runOnUiThread(() -> {
                Toast.makeText(this, "Connected " + email, Toast.LENGTH_LONG).show();
                setResult(RESULT_OK);
                finish();
            });
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            // Not-yet-signed-in: keep waiting for the final token instead of failing.
            if (msg.contains("BadAuthentication") || msg.contains("try again")) return;
            done = true;
            runOnUiThread(() -> fail(e));
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
