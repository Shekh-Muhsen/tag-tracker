import csv
import hashlib
import hmac
import os
import sqlite3
import threading
import time
from contextlib import contextmanager

from .config import DB_PATH

_lock = threading.Lock()

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY,
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY,          -- Google canonic device id
    name TEXT NOT NULL,
    color TEXT,
    last_polled_at INTEGER,
    last_error TEXT
);
CREATE TABLE IF NOT EXISTS locations (
    id INTEGER PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(id),
    ts INTEGER NOT NULL,          -- unix seconds when the tag was seen
    lat REAL,
    lon REAL,
    altitude REAL,
    accuracy REAL,
    is_own_report INTEGER,
    semantic_name TEXT,           -- e.g. "Home" for semantic reports without coordinates
    received_at INTEGER NOT NULL  -- when our server fetched it
);
CREATE INDEX IF NOT EXISTS idx_locations_device_ts ON locations(device_id, ts);
-- COALESCE so semantic reports (no lat/lon) are de-duplicated too; NULLs never collide in UNIQUE.
CREATE UNIQUE INDEX IF NOT EXISTS uq_locations ON locations(
    device_id, ts, COALESCE(lat, 999), COALESCE(lon, 999), COALESCE(semantic_name, ''));
"""


@contextmanager
def connect():
    with _lock:
        conn = sqlite3.connect(DB_PATH)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()


def init():
    with connect() as c:
        c.execute("PRAGMA journal_mode=WAL")
        c.executescript(SCHEMA)


# ---------- users ----------

def hash_password(password: str) -> str:
    salt = os.urandom(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 310_000)
    return f"pbkdf2_sha256$310000${salt.hex()}${dk.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        _, iters, salt, digest = stored.split("$")
        dk = hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), int(iters))
        return hmac.compare_digest(dk.hex(), digest)
    except ValueError:
        return False


def upsert_user(username: str, password: str):
    with connect() as c:
        c.execute(
            "INSERT INTO users(username, password_hash, created_at) VALUES(?,?,?) "
            "ON CONFLICT(username) DO UPDATE SET password_hash=excluded.password_hash",
            (username, hash_password(password), int(time.time())),
        )


def delete_user(username: str) -> bool:
    with connect() as c:
        return c.execute("DELETE FROM users WHERE username=?", (username,)).rowcount > 0


def list_users():
    with connect() as c:
        return [r["username"] for r in c.execute("SELECT username FROM users ORDER BY username")]


def check_login(username: str, password: str) -> bool:
    with connect() as c:
        row = c.execute("SELECT password_hash FROM users WHERE username=?", (username,)).fetchone()
    if row is None:
        verify_password(password, hash_password("timing-equalizer"))
        return False
    return verify_password(password, row["password_hash"])


def user_count() -> int:
    with connect() as c:
        return c.execute("SELECT COUNT(*) FROM users").fetchone()[0]


# ---------- devices & locations ----------

PALETTE = ["#2563eb", "#dc2626", "#16a34a", "#9333ea", "#ea580c", "#0891b2", "#be185d", "#4d7c0f"]


def upsert_device(device_id: str, name: str):
    with connect() as c:
        n = c.execute("SELECT COUNT(*) FROM devices").fetchone()[0]
        c.execute(
            "INSERT INTO devices(id, name, color) VALUES(?,?,?) "
            "ON CONFLICT(id) DO UPDATE SET name=excluded.name",
            (device_id, name, PALETTE[n % len(PALETTE)]),
        )


def mark_polled(device_id: str, error: str | None):
    with connect() as c:
        c.execute(
            "UPDATE devices SET last_polled_at=?, last_error=? WHERE id=?",
            (int(time.time()), error, device_id),
        )


def insert_locations(device_id: str, locs: list[dict]) -> int:
    now = int(time.time())
    added = 0
    with connect() as c:
        for l in locs:
            cur = c.execute(
                "INSERT OR IGNORE INTO locations(device_id, ts, lat, lon, altitude, accuracy, "
                "is_own_report, semantic_name, received_at) VALUES(?,?,?,?,?,?,?,?,?)",
                (device_id, l["ts"], l.get("lat"), l.get("lon"), l.get("altitude"),
                 l.get("accuracy"), int(bool(l.get("is_own_report"))), l.get("semantic_name"), now),
            )
            added += cur.rowcount
    return added


def get_devices():
    with connect() as c:
        rows = c.execute(
            """SELECT d.*, l.ts AS last_ts, l.lat AS last_lat, l.lon AS last_lon,
                      l.accuracy AS last_accuracy,
                      (SELECT COUNT(*) FROM locations WHERE device_id=d.id) AS point_count
               FROM devices d
               LEFT JOIN locations l ON l.id = (
                   SELECT id FROM locations WHERE device_id=d.id AND lat IS NOT NULL
                   ORDER BY ts DESC LIMIT 1)
               ORDER BY d.name"""
        ).fetchall()
    return [dict(r) for r in rows]


def rename_device(device_id: str, name: str, color: str | None):
    with connect() as c:
        c.execute("UPDATE devices SET name=?, color=COALESCE(?, color) WHERE id=?", (name, color, device_id))


CSV_HEADER = ["tag_id", "tag", "time_local", "unix", "lat", "lon", "altitude", "accuracy_m", "own_report", "place"]


def export_csv(path, device_id=None, start=0, end=2**40) -> int:
    """Writes locations to a CSV file (opens in Google Sheets / Excel). Returns row count."""
    with connect() as c:
        names = {r["id"]: r["name"] for r in c.execute("SELECT id, name FROM devices")}
        q = ("SELECT device_id, ts, lat, lon, altitude, accuracy, is_own_report, semantic_name "
             "FROM locations WHERE ts BETWEEN ? AND ?")
        args = [start, end]
        if device_id:
            q += " AND device_id=?"
            args.append(device_id)
        rows = c.execute(q + " ORDER BY device_id, ts", args).fetchall()
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(CSV_HEADER)
        for r in rows:
            w.writerow([r["device_id"], names.get(r["device_id"], ""),
                        time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(r["ts"])), r["ts"],
                        r["lat"], r["lon"], r["altitude"], r["accuracy"], r["is_own_report"],
                        r["semantic_name"] or ""])
    return len(rows)


def import_csv(path) -> int:
    """Loads a CSV written by export_csv (e.g. the Google Drive copy). Duplicates are skipped."""
    num = lambda v: float(v) if v not in ("", None) else None
    by_device: dict[str, list] = {}
    names = {}
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            dev = row.get("tag_id") or row.get("tag") or "imported"
            names[dev] = row.get("tag") or dev
            by_device.setdefault(dev, []).append({
                "ts": int(float(row["unix"])), "lat": num(row.get("lat")), "lon": num(row.get("lon")),
                "altitude": num(row.get("altitude")), "accuracy": num(row.get("accuracy_m")),
                "is_own_report": row.get("own_report") in ("1", "True", "true"),
                "semantic_name": row.get("place") or None,
            })
    added = 0
    for dev, locs in by_device.items():
        upsert_device(dev, names[dev])
        added += insert_locations(dev, locs)
    return added


def get_history(device_id: str, start: int, end: int):
    with connect() as c:
        rows = c.execute(
            "SELECT ts, lat, lon, altitude, accuracy, is_own_report, semantic_name FROM locations "
            "WHERE device_id=? AND ts BETWEEN ? AND ? ORDER BY ts",
            (device_id, start, end),
        ).fetchall()
    return [dict(r) for r in rows]
