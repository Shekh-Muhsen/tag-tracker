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


def _haversine_m(a_lat, a_lon, b_lat, b_lon) -> float:
    import math
    r = 6371000.0
    p = math.pi / 180
    dlat = (b_lat - a_lat) * p
    dlon = (b_lon - a_lon) * p
    h = (math.sin(dlat / 2) ** 2
         + math.cos(a_lat * p) * math.cos(b_lat * p) * math.sin(dlon / 2) ** 2)
    return 2 * r * math.asin(math.sqrt(h))


def guard_on() -> bool:
    return _load_status().get("guard", False)


def set_guard(on: bool):
    _save_status(guard=bool(on))


def new_movements(threshold_m: float = 150) -> str:
    """Theft/guard alerts: devices whose newest saved point is more than threshold metres
    from the point before it (i.e. it MOVED). Each moving point is reported only once.
    threshold is above typical GPS jitter so a parked tag doesn't false-alarm."""
    from server import db
    s = _load_status()
    seen = s.get("guard_seen", {})
    out = []
    for d in db.get_devices():
        pts = [p for p in db.get_history(d["id"], 0, 2 ** 40) if p["lat"] is not None]
        if len(pts) < 2:
            continue
        a, b = pts[-2], pts[-1]
        if b["ts"] <= seen.get(d["id"], 0):
            continue  # already evaluated this point
        seen[d["id"]] = b["ts"]
        dm = _haversine_m(a["lat"], a["lon"], b["lat"], b["lon"])
        if dm >= threshold_m:
            out.append({"name": d["name"], "id": d["id"], "lat": b["lat"], "lon": b["lon"],
                        "moved_m": round(dm), "ts": b["ts"]})
    _save_status(guard_seen=seen)
    return json.dumps(out)


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


def _month_bounds(ym: str):
    import calendar
    y, m = int(ym[:4]), int(ym[5:7])
    start = int(time.mktime((y, m, 1, 0, 0, 0, 0, 0, -1)))
    ny, nm = (y + 1, 1) if m == 12 else (y, m + 1)
    return start, int(time.mktime((ny, nm, 1, 0, 0, 0, 0, 0, -1))) - 1


# ~1000 rows per chunk keeps each gzipped file well under 100 KB (~10-20 KB on the wire).
CHUNK_ROWS = 1000


def drive_default_upload() -> str:
    """Minimal-bandwidth sync: history is split into fixed ~100 KB chunks (chunk-00000.csv.gz …)
    under TagTracker/history/. Rows are only appended, so a filled chunk never changes — each
    sync re-uploads ONLY the last, growing chunk (gzipped, ~10-20 KB). Nothing else is re-sent."""
    from server import db, drive_sync, finder
    from server.config import DATA_DIR
    if not finder.auth_state()["signed_in"]:
        raise RuntimeError("Not signed in to Google yet. Do Setup → Sign in first.")
    try:
        token = drive_sync.token_from_login()
    except Exception as e:
        raise RuntimeError("Couldn't get Google Drive access from the sign-in. Use the CUSTOM Drive file "
                           "option in Setup instead (it's reliable). Details: " + str(e))
    client = drive_sync.DriveClient(token)
    try:
        folder = client.history_folder()
    except Exception as e:
        raise RuntimeError("Google Drive rejected access (the unofficial login may not grant Drive). "
                           "Use the CUSTOM Drive file option in Setup instead. Details: " + str(e))

    # Free tier: back up only the last 1 day, as one small file. Pro: full chunked history.
    if not license_state()["licensed"]:
        floor = int(time.time()) - 24 * 3600
        tmp = str(DATA_DIR / "recent-1day.csv.gz")
        db.export_csv(tmp, None, floor, 2 ** 40, gz=True)
        client.upload(tmp, "recent-1day.csv.gz", folder, content_type="application/gzip")
        _save_status(drive_default_last=int(time.time()), drive_default_error=None)
        return "synced last 1 day (free)"

    total = db.location_count()
    nchunks = max(1, (total + CHUNK_ROWS - 1) // CHUNK_ROWS)
    manifest = _load_status().get("drive_chunks", {})
    tmp = str(DATA_DIR / "chunk.csv.gz")
    changed = 0
    for i in range(nchunks):
        rows = min(CHUNK_ROWS, total - i * CHUNK_ROWS)
        if manifest.get(str(i)) == rows:
            continue  # sealed, unchanged -> skip (no upload)
        db.export_chunk(tmp, i * CHUNK_ROWS, CHUNK_ROWS, gz=True)
        client.upload(tmp, f"chunk-{i:05d}.csv.gz", folder, content_type="application/gzip")
        manifest[str(i)] = rows
        changed += 1
    _save_status(drive_chunks=manifest, drive_default_last=int(time.time()), drive_default_error=None)
    return f"synced {changed} chunk(s)"


def drive_default_restore() -> int:
    """Cross-device retrieve, backward-compatible: scans the WHOLE TagTracker folder tree and
    imports EVERY CSV it finds — new gzipped chunks, old monthly files, the original
    history.csv, or any hand-made CSV. All merged safely (duplicates are ignored)."""
    from server import db, drive_sync, finder
    from server.config import DATA_DIR
    if not finder.auth_state()["signed_in"]:
        return 0
    client = drive_sync.DriveClient(drive_sync.token_from_login())
    root = client.ensure_folder("TagTracker")
    tmp = str(DATA_DIR / "restore-file")
    added = 0
    seen = set()

    def scan(folder_id):
        nonlocal added
        if folder_id in seen:
            return
        seen.add(folder_id)
        for f in client.list_children(folder_id):
            name = (f.get("name") or "").lower()
            if f.get("mimeType") == drive_sync.FOLDER_MIME:
                scan(f["id"])  # recurse into history/, csv/, etc.
            elif name.endswith(".csv.gz") or name.endswith(".gz"):
                client.download_id(f["id"], tmp)
                try:
                    added += db.import_gz(tmp)
                except Exception:
                    pass
            elif name.endswith(".csv"):
                client.download_id(f["id"], tmp)
                try:
                    added += db.import_csv(tmp)
                except Exception:
                    pass

    scan(root)
    return added


def record_backup(error: str | None):
    _save_status(backup_last_run=int(time.time()), backup_error=error)


# ---------- data for the map UI (same shapes as the web server's API) ----------

def set_phone_id(phone_id: str):
    _save_status(phone_id=phone_id)


def license_state(phone_id: str = "") -> dict:
    from server import license as lic
    if not phone_id:
        phone_id = _load_status().get("phone_id", "")
    key = _load_status().get("license_key")
    if not key:
        return {"licensed": False, "tags": lic.FREE_TAGS, "free_hours": lic.FREE_HISTORY_HOURS,
                "reason": "", "expiry": 0}
    r = lic.verify(key, phone_id)
    r["free_hours"] = lic.FREE_HISTORY_HOURS
    return r


def set_license(key: str, phone_id: str) -> str:
    from server import license as lic
    r = lic.verify((key or "").strip(), phone_id)
    if r["licensed"]:
        _save_status(license_key=(key or "").strip())
    return json.dumps(r)


def clear_license():
    _save_status(license_key=None)


def devices_json(interval_minutes: int, backup_enabled: bool, parked: bool = False, phone_id: str = "") -> str:
    from server import db, license as lic
    s = _load_status()
    info = license_state(phone_id)
    devices = db.get_devices()
    if not info["licensed"]:
        # Free tier: only the first tag is usable; mark the rest as locked (Pro).
        for i, d in enumerate(devices):
            d["locked"] = i >= lic.FREE_TAGS
    return json.dumps({
        "devices": devices,
        "license": info,
        "guard": bool(parked),
        "poller": {"enabled": True, "running": bool(s.get("running")), "last_run": s.get("last_run"),
                   "last_error": s.get("last_error"), "interval_minutes": interval_minutes},
        "backup": {
            "enabled": backup_enabled or bool(s.get("drive_default")),
            "mode": "default" if s.get("drive_default") else ("custom" if backup_enabled else None),
            "last_run": s.get("drive_default_last") or s.get("backup_last_run"),
            "last_error": s.get("drive_default_error") or s.get("backup_error"),
        },
    })


def history_json(device_id: str, start: int, end: int, phone_id: str = "") -> str:
    from server import db, license as lic
    info = license_state(phone_id)
    if not info["licensed"]:
        # Free tier: only the last 24h of history.
        floor = int(time.time()) - lic.FREE_HISTORY_HOURS * 3600
        start = max(int(start), floor)
    return json.dumps({"points": db.get_history(device_id, int(start), int(end)), "licensed": info["licensed"]})


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
