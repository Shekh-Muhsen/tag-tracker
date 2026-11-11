# APK versioning and backwards-compatible updates

The repository-root `VERSION` file determines Android `versionName` and
`versionCode` (major * 10000 + minor * 100 + patch, optional patch = 0).
Use e.g. `3.24` or `3.24.0`, and bump it before releasing.

## Publish a release
1. Configure the `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
   `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` secrets if not already set.
   Keep the **same signing key** for every official release.
2. Update `VERSION` and commit on `main`.
3. Create and push the matching tag: `v<contents-of-VERSION>`.
4. GitHub Actions validates the tag, builds the signed APK, and publishes it.

Normal workflow runs expose a **single versioned Actions artifact** named
`TagTracker-vX.Y.Z-apk` (or `TagTracker-vX.Y-apk`). Release assets include
both `TagTracker-vX.Y.Z.apk` and `TagTracker.apk` for compatibility.

**Migration:** Existing Android installations look for `TagTracker.apk`.
Keep that legacy release asset for a transition period so users can update in
place. Newer installations prefer the versioned filename, falling back to the
legacy name. Removing the legacy asset too early would strand older clients.

A missing signing key is allowed for ordinary CI builds but makes tagged
release publication fail with an actionable message, rather than silently
shipping a debug-signed APK. Do not commit keystores or fingerprint checks
specific to a personal fork.
