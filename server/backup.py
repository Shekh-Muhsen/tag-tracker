"""Copies the location history to Google Drive (or any rclone remote).

Uploads to <remote>:
  tracker.db                 full SQLite database (restore with `manage restore`)
  csv/locations-YYYY-MM.csv  one readable file per month (opens in Google Sheets)

Google tokens (google_secrets.json) are never uploaded.
"""
import csv
import shutil
import sqlite3
import subprocess
import threading
import time

from . import db
from .config import BACKUP_MINUTES, DATA_DIR, DB_PATH, RCLONE_REMOTE

STAGE = DATA_DIR / "backup"
status = {"enabled": bool(RCLONE_REMOTE), "remote": RCLONE_REMOTE, "last_run": None, "last_error": None}


def _rclone(*args):
    exe = shutil.which("rclone")
    if not exe:
        raise RuntimeError("rclone is not installed (see README: Google Drive backup)")
    r = subprocess.run([exe, *args], capture_output=True, text=True, timeout=600)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip().splitlines()[-1] if r.stderr.strip() else f"rclone exit {r.returncode}")


def _month_bounds(year, month):
    start = int(time.mktime((year, month, 1, 0, 0, 0, 0, 0, -1)))
    ny, nm = (year + 1, 1) if month == 12 else (year, month + 1)
    return start, int(time.mktime((ny, nm, 1, 0, 0, 0, 0, 0, -1))) - 1


def _write_csvs(conn, months):
    (STAGE / "csv").mkdir(parents=True, exist_ok=True)
    names = {r[0]: r[1] for r in conn.execute("SELECT id, name FROM devices")}
    for y, m in months:
        start, end = _month_bounds(y, m)
        rows = conn.execute(
            "SELECT device_id, ts, lat, lon, altitude, accuracy, is_own_report, semantic_name "
            "FROM locations WHERE ts BETWEEN ? AND ? ORDER BY device_id, ts", (start, end)).fetchall()
        if not rows:
            continue
        with open(STAGE / "csv" / f"locations-{y:04d}-{m:02d}.csv", "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["tag", "time_local", "unix", "lat", "lon", "altitude", "accuracy_m", "own_report", "place"])
            for dev, ts, lat, lon, alt, acc, own, sem in rows:
                w.writerow([names.get(dev, dev), time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(ts)),
                            ts, lat, lon, alt, acc, own, sem or ""])


def run_once(full_csv=False):
    STAGE.mkdir(parents=True, exist_ok=True)
    snapshot = STAGE / "tracker.db"
    with db.connect() as src:
        dst = sqlite3.connect(snapshot)
        src.backup(dst)  # consistent copy even while the poller writes
        dst.close()
    conn = sqlite3.connect(snapshot)
    try:
        if full_csv:
            months = [tuple(map(int, r[0].split("-"))) for r in conn.execute(
                "SELECT DISTINCT strftime('%Y-%m', ts, 'unixepoch', 'localtime') FROM locations")]
        else:  # only months that can still change
            now = time.localtime()
            prev = (now.tm_year - 1, 12) if now.tm_mon == 1 else (now.tm_year, now.tm_mon - 1)
            months = [prev, (now.tm_year, now.tm_mon)]
        _write_csvs(conn, months)
    finally:
        conn.close()
    _rclone("copy", str(STAGE), RCLONE_REMOTE)


def restore():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    tmp = DATA_DIR / "restore.db"
    _rclone("copyto", f"{RCLONE_REMOTE.rstrip('/')}/tracker.db", str(tmp))
    return tmp


def _loop():
    first = True
    while True:
        try:
            run_once(full_csv=first)
            status["last_error"] = None
            first = False
        except Exception as e:
            status["last_error"] = str(e)
            print(f"[backup] {e}")
        status["last_run"] = int(time.time())
        time.sleep(BACKUP_MINUTES * 60)


def start():
    if RCLONE_REMOTE:
        threading.Thread(target=_loop, name="backup", daemon=True).start()
