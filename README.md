# Tag Tracker

Keeps the **full location history** of your Google **Find Hub** tags and shows it on a map.
The Find Hub app only shows where a tag is *right now*. Tag Tracker checks Find Hub every few minutes,
**saves every location it gets**, and lets you look back over today, 2 days, 7 days, 30 days, 90 days, a
full year, or any date range you choose.

- 🌐 **Web app** with login. Shows the travel path on a map, lets you replay the trip, gives distance and time stats, and exports CSV or GPX files.
- 📱 **Android app** (`android/`). GitHub Actions builds the APK automatically.
- 🔒 Private by default. Your locations and Google tokens stay on your own server, in `data/`.

## How it works

```
Tag ──BLE──▶ nearby Android phones ──▶ Google Find Hub (end-to-end encrypted)
                                             │  every N minutes
                                             ▼
                          Tag Tracker server (this repo, runs on your PC / VPS / Raspberry Pi)
                          • signs in to your Google account (one time)
                          • decrypts the reports and stores them in SQLite
                                             │
                               web browser  /  Android app  (login required)
```

Google has no public Find Hub API. The server uses the open-source
[GoogleFindMyTools](https://github.com/leonboe1/GoogleFindMyTools), which works like the official Find Hub app does.

**Things to know**

- History starts **when you start the server**. Google does not keep old history, so earlier trips can't be loaded.
- The server only records **while it is running**. For unbroken history, run it on a machine that stays on, such as a cheap VPS, a Raspberry Pi or an always-on PC.
- The tag reports a location only when an Android phone passes near it. Quiet areas give fewer points.
- On your phone, open Find Hub settings and turn on **"Find your offline devices" → "With network in all areas"** to get the most reports.
- This uses an unofficial API, so a Google change could break it for a while. If it does, update the pinned `GoogleFindMyTools` commit in `setup.ps1`, `setup.sh` and `Dockerfile`.
- Don't set the check interval too short. Every **10 minutes** (the default) is a sensible value.

## 1. Install the server (Windows)

You need Python 3.11+, Git and Google Chrome.

```powershell
git clone https://github.com/<you>/tag-tracker.git
cd tag-tracker
powershell -ExecutionPolicy Bypass -File setup.ps1
```

Create your web login:

```powershell
.\.venv\Scripts\python -m server.manage adduser myname
```

Connect your Google account (one time only):

```powershell
.\.venv\Scripts\python -m server.manage google-login
```

A Chrome window opens. Sign in to the **Google account you use in Find Hub**. Google may also ask for
your phone's **screen lock PIN or pattern**, which unlocks the encryption key for your tags' locations. The tokens are saved to
`data/google_secrets.json`. **Keep this file private.** It gives access to your Find Hub.

Start the server:

```powershell
.\start.ps1
```

Open http://localhost:8000 and sign in.

Want to try the map before connecting your tag? Run `python -m server.manage demo` to add a sample tag with a year of fake history.

### Linux / Raspberry Pi / VPS

```sh
sh setup.sh
.venv/bin/python -m server.manage adduser myname
# Headless machines have no Chrome. Run google-login on your Windows PC,
# then copy data/google_secrets.json into this machine's data/ folder.
.venv/bin/python -m server
```

Or use Docker: `docker compose up -d`, then run `docker compose exec tracker python -m server.manage adduser myname`.

### Settings (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `TT_POLL_MINUTES` | `10` | How often to check Find Hub |
| `TT_PORT` | `8000` | Web port |
| `TT_DATA_DIR` | `./data` | Folder for the database and secrets |
| `TT_COOKIE_SECURE` | `0` | Set to `1` when you serve the site over HTTPS |
| `TT_SESSION_DAYS` | `30` | How long a login lasts |
| `TT_RCLONE_REMOTE` | *(off)* | Google Drive backup target, e.g. `gdrive:TagTracker` |
| `TT_BACKUP_MINUTES` | `60` | How often to back up |

## Google Drive backup (keep your history safe online)

The server can copy your data to Google Drive every hour. It uploads:
- `tracker.db`: the full database, which you can restore on any machine
- `csv/locations-YYYY-MM.csv`: one file per month that you can open in Google Sheets

Google sign-in tokens are **never** uploaded.

1. Install [rclone](https://rclone.org/downloads/). On Windows, run `winget install Rclone.Rclone`.
2. Connect it to your Drive: run `rclone config` and choose **n** (new remote). Name it `gdrive`, pick storage **drive**, and leave
   client id and secret blank. Choose scope **1** (full access) or **3** (`drive.file`, which only sees files rclone creates). Accept the rest,
   then sign in to Google in the browser that opens.
3. Turn on the backup before you start the server:
   ```powershell
   $env:TT_RCLONE_REMOTE = "gdrive:TagTracker"
   .\start.ps1
   ```
   A `TagTracker` folder appears in your Drive. The status line in the web app shows when the last backup ran.

Other commands: `python -m server.manage backup` uploads a backup now. `python -m server.manage restore` downloads the
Drive backup onto a new machine. Stop the server before you restore.

With Docker, copy your `rclone.conf` (run `rclone config file` to find it) into `data/`, then set `TT_RCLONE_REMOTE` in `docker-compose.yml`.

## 2. Reach it from your phone and from anywhere

Pick one:

- **Tailscale** (easiest and private): install Tailscale on the server and on your phone. Then use `http://<server-tailscale-name>:8000`.
- **Cloudflare Tunnel** (a public HTTPS address, no router changes): run `cloudflared tunnel --url http://localhost:8000`, or set up a named tunnel. Then set `TT_COOKIE_SECURE=1`.
- A VPS with a reverse proxy (Caddy or nginx) and HTTPS.

Don't open port 8000 on your router without HTTPS.

## 3. Android app

Every push to `main` that changes `android/` builds an APK. In GitHub, open **Actions → Android APK → latest run**,
download the **TagTracker-apk** artifact, unzip it and install it (allow "install unknown apps").
To create a proper release, push a tag such as `git tag v1.0 && git push --tags`. The APK is then attached to the GitHub Release.

On first launch the app asks for your server address, for example `https://tracker.example.com`. You can change it later
with **Change server** at the bottom of the panel.

*Optional: stable signing.* By default each build is signed with a temporary debug key, so moving to a newer
build may mean uninstalling the old one first. To avoid that, create a keystore and add these repository secrets:
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

## Admin commands

```
python -m server.manage adduser <name>   # add a user or reset their password
python -m server.manage deluser <name>
python -m server.manage users
python -m server.manage poll             # fetch locations once
```

## License

GPL-3.0, the same license as GoogleFindMyTools, which this project uses.
