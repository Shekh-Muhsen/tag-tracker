package com.tagtracker.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks GitHub Releases for a newer APK and installs it in place, so the user can update
 * from inside the app instead of hunting for the download each time.
 *
 * An update installs OVER the existing app (same package + same signing key), so Android keeps
 * all app data — the Google login, license and history survive, and the user does NOT re-login.
 * That only holds while every release is signed with the same key (the CI keystore).
 */
public class UpdateChecker {
    private static final String LATEST_API =
            "https://api.github.com/repos/Shekh-Muhsen/tag-tracker/releases/latest";
    private static final String LEGACY_ASSET_NAME = "TagTracker.apk";

    /** @param userInitiated true when the user tapped "Check for updates" (so we also report
     *                        "you're up to date"); false for a quiet background check. */
    public static void check(Activity activity, boolean userInitiated) {
        new Thread(() -> {
            try {
                JSONObject rel = fetchLatest();
                String latestTag = rel.optString("tag_name", "");      // e.g. "v3.23"
                String current = currentVersion(activity);             // e.g. "v3.22" or "3.22"
                String apkUrl = findApkUrl(rel);

                if (latestTag.isEmpty() || apkUrl == null) {
                    if (userInitiated) toast(activity, "Couldn't read the latest release.");
                    return;
                }
                if (!isNewer(latestTag, current)) {
                    if (userInitiated) toast(activity, "You're on the latest version (" + current + ").");
                    return;
                }
                String notes = rel.optString("body", "");
                activity.runOnUiThread(() -> promptUpdate(activity, latestTag, apkUrl, notes));
            } catch (Exception e) {
                if (userInitiated) toast(activity, "Update check failed: " + msg(e));
            }
        }).start();
    }

    private static JSONObject fetchLatest() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(LATEST_API).openConnection();
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "TagTracker");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        try {
            if (c.getResponseCode() != 200) throw new RuntimeException("HTTP " + c.getResponseCode());
            return new JSONObject(readAll(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private static String findApkUrl(JSONObject rel) {
        JSONArray assets = rel.optJSONArray("assets");
        if (assets == null) return null;
        String tag = rel.optString("tag_name", "").trim();
        if (tag.isEmpty()) return null;
        if (!tag.startsWith("v") && !tag.startsWith("V")) tag = "v" + tag;
        String expectedName = "TagTracker-" + tag + ".apk";
        String legacyUrl = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String url = asset.optString("browser_download_url", "");
            if (url.isEmpty()) continue;
            String name = asset.optString("name", "");
            if (expectedName.equals(name)) return url;
            if (LEGACY_ASSET_NAME.equals(name)) legacyUrl = url;
        }
        return legacyUrl;
    }

    private static void promptUpdate(Activity activity, String tag, String apkUrl, String notes) {
        String msg = "A new version (" + tag + ") is available.\n\n"
                + "Updating installs over the current app and keeps everything — your Google login, "
                + "license and saved history stay, and you will NOT have to sign in again.";
        if (notes != null && !notes.trim().isEmpty()) {
            msg += "\n\nWhat's new:\n" + notes.trim();
        }
        new AlertDialog.Builder(activity)
                .setTitle("Update available")
                .setMessage(msg)
                .setPositiveButton("Download & install", (d, w) -> ensureCanInstall(activity, apkUrl))
                .setNegativeButton("Later", null)
                .show();
    }

    /** Android 8+ needs the user to allow this app to install apps. Send them to that screen if needed. */
    private static void ensureCanInstall(Activity activity, String apkUrl) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Allow updates")
                    .setMessage("To install the update, allow Tag Tracker to install apps on the next "
                            + "screen, then tap Download & install again.")
                    .setPositiveButton("Open settings", (d, w) -> {
                        try {
                            activity.startActivity(new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (Exception e) {
                            toast(activity, "Open Settings → Apps → Tag Tracker → Install unknown apps.");
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        downloadAndInstall(activity, apkUrl);
    }

    private static void downloadAndInstall(Activity activity, String apkUrl) {
        toast(activity, "Downloading update…");
        new Thread(() -> {
            try {
                File apk = new File(activity.getCacheDir(), "TagTracker-update.apk");
                download(apkUrl, apk);
                Uri uri = FileProvider.getUriForFile(activity,
                        activity.getPackageName() + ".files", apk);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(i);
            } catch (Exception e) {
                toast(activity, "Download failed: " + msg(e));
            }
        }).start();
    }

    private static void download(String urlStr, File dest) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(true);   // GitHub redirects asset URLs to a CDN host
        c.setRequestProperty("User-Agent", "TagTracker");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new RuntimeException("HTTP " + code);
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
    }

    // ---------- version compare ----------

    static String currentVersion(Activity a) {
        try {
            PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** True if `latest` is a strictly higher version than `current`. Tolerates a leading 'v'. */
    static boolean isNewer(String latest, String current) {
        int[] a = parse(latest), b = parse(current);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parse(String v) {
        if (v == null) return new int[0];
        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        String[] parts = v.split("[._-]");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("[^0-9]", ""));
            } catch (Exception e) {
                out[i] = 0;
            }
        }
        return out;
    }

    // ---------- helpers ----------

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
        in.close();
        return sb.toString();
    }

    private static void toast(Activity a, String m) {
        a.runOnUiThread(() -> Toast.makeText(a, m, Toast.LENGTH_LONG).show());
    }

    private static String msg(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }
}
