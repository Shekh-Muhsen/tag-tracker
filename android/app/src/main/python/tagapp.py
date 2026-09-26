"""Python side of the standalone Android app.

Reuses the server package (db, finder, poller) that is bundled into the APK at build
time. Java calls these functions through Chaquopy; everything returns plain strings.
"""
import json
import os
import time
import traceback

_ready = False


def init(files_dir: str):
    global _ready
    if _ready:
        return
    os.environ["TT_DATA_DIR"] = files_dir
    os.environ["TT_POLLING"] = "0"
    import certifi
    os.environ.setdefault("SSL_CERT_FILE", certifi.where())
    from server import db
    db.init()
    _ready = True


def _status_path():
    from server.config import DATA_DIR
    return DATA_DIR / "app_status.json"


def _load_status() -> dict:
    try:
        return json.loads(_status_path().read_text())
    except Exception:
        return {}


def _save_status(**kw):
    s = _load_status()
    s.update(kw)
    _status_path().write_text(json.dumps(s))


# ---------- account ----------

def account_json() -> str:
    from server import finder
    try:
        st = finder.auth_state()
    except Exception as e:
        return json.dumps({"connected": False, "signed_in": False, "unlocked": False,
                           "email": "", "error": str(e)})
    st["connected"] = st["signed_in"] and st["unlocked"]
    return json.dumps(st)


def sign_in(oauth_token: str) -> str:
    from server import finder
    return finder.sign_in_with_oauth_token(oauth_token)


def shared_key_url() -> str:
    from server import finder
    return finder.shared_key_url()


def import_secrets(path: str) -> str:
    from server import finder
    return json.dumps(finder.import_secrets_file(path))


def save_vault_keys(vault_keys: str):
    from server import finder
    finder.save_vault_keys(vault_keys)


def list_tags_json() -> str:
    from server import db, finder
    tags = finder.list_trackers()
    for name, cid in tags:
        db.upsert_device(cid, name)
    return json.dumps([{"name": n, "id": i} for n, i in tags])


# ---------- polling ----------

def poll() -> int:
    """Runs one check of all tags. Returns number of new locations."""
    from server import finder, poller
    _save_status(running=True)
    try:
        added = poller.poll_once()
        _save_status(running=False, last_run=int(time.time()), last_error=poller.status["last_error"],
                     last_added=added)
        return added
    except BaseException as e:  # SystemExit from the library must not kill the app
        traceback.print_exc()
        _save_status(running=False, last_run=int(time.time()), last_error=str(e) or type(e).__name__)
        return 0
    finally:
        # Auto-sync new points to the default Drive folder (fresh token minted each time,
        # so it keeps working 24/7 with no re-login). Best-effort; never fails the poll.
        try:
            if _drive_default_on() and _load_status().get("last_added"):
                drive_default_upload()
        except Exception as e:
            _save_status(drive_default_error=str(e))
        # Close the network connection so the app uses ~no battery until the next check.
        try:
            finder.stop_listening()
        except Exception:
            pass


def _drive_default_on() -> bool:
    return _load_status().get("drive_default", False)


def set_drive_default(on: bool):
    _save_status(drive_default=bool(on))


def locate_now(device_id: str) -> int:
    """Fetches this one tag's current location right now and saves it. Returns new-point count.
    Raises a clear error if step 2 (encryption unlock) hasn't been done."""
    from server import db, finder
    st = finder.auth_state()
    if not st["signed_in"]:
        raise RuntimeError("Not signed in yet. Do step 1 in Setup.")
    if not st["unlocked"]:
        raise RuntimeError("Encryption is locked. Do step 2 (Unlock encryption keys) in Setup to see locations.")
    added = db.insert_locations(device_id, finder.locate(device_id))
    return added


def play_sound(device_id: str):
    from server import finder
    if not finder.auth_state()["signed_in"]:
        raise RuntimeError("Not signed in yet.")
    finder.play_sound(device_id, True)


def stop_sound(device_id: str):
    from server import finder
    finder.play_sound(device_id, False)


def drive_default_upload() -> str:
    """Upload the full history to the DEFAULT Drive folder (same Google account as the tags).
    This is what makes the data appear automatically on another phone."""
    from server import db, drive_sync
    from server.config import DATA_DIR
    tmp = str(DATA_DIR / "history-upload.csv")
    db.export_csv(tmp)
    drive_sync.DriveClient(drive_sync.token_from_login()).upload(tmp)
    _save_status(drive_default_last=int(time.time()), drive_default_error=None)
    return "ok"


def drive_default_restore() -> int:
    """Pull history from the DEFAULT Drive folder onto this device (cross-device retrieve)."""
    from server import db, drive_sync
    from server.config import DATA_DIR
    tmp = str(DATA_DIR / "history-restore.csv")
    if not drive_sync.DriveClient(drive_sync.token_from_login()).download(tmp):
        return 0
    return db.import_csv(tmp)


def record_backup(error: str | None):
    _save_status(backup_last_run=int(time.time()), backup_error=error)


# ---------- data for the map UI (same shapes as the web server's API) ----------

def devices_json(interval_minutes: int, backup_enabled: bool) -> str:
    from server import db
    s = _load_status()
    return json.dumps({
        "devices": db.get_devices(),
        "poller": {"enabled": True, "running": bool(s.get("running")), "last_run": s.get("last_run"),
                   "last_error": s.get("last_error"), "interval_minutes": interval_minutes},
        "backup": {
            "enabled": backup_enabled or bool(s.get("drive_default")),
            "mode": "default" if s.get("drive_default") else ("custom" if backup_enabled else None),
            "last_run": s.get("drive_default_last") or s.get("backup_last_run"),
            "last_error": s.get("drive_default_error") or s.get("backup_error"),
        },
    })


def history_json(device_id: str, start: int, end: int) -> str:
    from server import db
    return json.dumps({"points": db.get_history(device_id, int(start), int(end))})


def rename(device_id: str, name: str):
    from server import db
    db.rename_device(device_id, name.strip() or "Tag", None)


def export_csv(path: str, device_id: str | None = None, start: int = 0, end: int = 2**40) -> int:
    from server import db
    return db.export_csv(path, device_id or None, int(start), int(end))


def export_gpx(path: str, device_id: str, start: int, end: int) -> int:
    from server import db
    pts = [p for p in db.get_history(device_id, int(start), int(end)) if p["lat"] is not None]
    body = "".join(
        f'<trkpt lat="{p["lat"]}" lon="{p["lon"]}"><time>'
        f'{time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(p["ts"]))}</time></trkpt>' for p in pts)
    with open(path, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" creator="Tag Tracker" '
                'xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>' + body + "</trkseg></trk></gpx>")
    return len(pts)


def import_csv(path: str) -> int:
    from server import db
    return db.import_csv(path)


def demo():
    from server import manage
    manage.demo()
