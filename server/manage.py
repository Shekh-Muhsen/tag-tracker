"""Admin commands.

  python -m server.manage adduser <username>     create a login (or reset its password)
  python -m server.manage deluser <username>
  python -m server.manage users
  python -m server.manage google-login           one-time Google sign-in (needs Chrome)
  python -m server.manage poll                   fetch tag locations once and exit
  python -m server.manage demo                   add a fake tag with 1 year of sample history
  python -m server.manage backup                 upload a backup to Google Drive now
  python -m server.manage restore                replace local data with the Google Drive backup
"""
import getpass
import math
import random
import sys
import time

from . import db


def adduser(username: str):
    pw = getpass.getpass(f"Password for {username}: ")
    if len(pw) < 8:
        sys.exit("Password must be at least 8 characters.")
    if pw != getpass.getpass("Repeat password: "):
        sys.exit("Passwords do not match.")
    db.upsert_user(username, pw)
    print(f"User '{username}' saved.")


def google_login():
    from . import finder

    print("A Chrome window will open. Sign in to the Google account that owns your tag.")
    print("You may also be asked for your phone's screen lock PIN/pattern - this unlocks")
    print("the end-to-end encryption keys so locations can be decrypted.\n")
    trackers = finder.list_trackers()
    if not trackers:
        print("Signed in, but no trackers were found on this account.")
        return
    print("Trackers found:")
    for name, cid in trackers:
        print(f"  - {name}  ({cid})")
    name, cid = trackers[0]
    print(f"\nTesting location fetch for '{name}'...")
    locs = finder.locate(cid)
    print(f"Got {len(locs)} location report(s). Setup complete.")
    print("Tokens are saved in data/google_secrets.json - keep this file private.")


def demo():
    dev = "demo-tag"
    db.upsert_device(dev, "Demo tag (sample data)")
    now = int(time.time())
    lat, lon = 23.7806, 90.4070  # start point
    locs = []
    t = now - 365 * 86400
    while t < now:
        # drift around with occasional trips
        step = 0.02 if random.random() < 0.05 else 0.002
        ang = random.random() * 2 * math.pi
        lat += step * math.sin(ang)
        lon += step * math.cos(ang)
        locs.append({"ts": t, "lat": round(lat, 6), "lon": round(lon, 6),
                     "accuracy": random.randint(5, 60), "is_own_report": random.random() < 0.3})
        t += random.randint(600, 3600)
    added = db.insert_locations(dev, locs)
    print(f"Inserted {added} demo points.")


def restore():
    from . import backup
    from .config import DB_PATH

    print("Stop the server before restoring. Downloading backup...")
    tmp = backup.restore()
    if DB_PATH.exists():
        old = DB_PATH.with_suffix(f".before-restore-{int(time.time())}.db")
        DB_PATH.rename(old)
        print(f"Current database kept as {old.name}")
    for ext in ("-wal", "-shm"):
        p = DB_PATH.with_name(DB_PATH.name + ext)
        if p.exists():
            p.unlink()
    tmp.rename(DB_PATH)
    print("Restored. Start the server again.")


def main(argv):
    db.init()
    if len(argv) < 1:
        sys.exit(__doc__)
    cmd, args = argv[0], argv[1:]
    if cmd == "adduser" and len(args) == 1:
        adduser(args[0])
    elif cmd == "deluser" and len(args) == 1:
        print("Deleted." if db.delete_user(args[0]) else "No such user.")
    elif cmd == "users":
        print("\n".join(db.list_users()) or "(no users)")
    elif cmd == "google-login":
        google_login()
    elif cmd == "poll":
        from . import poller
        poller.poll_once()
    elif cmd == "demo":
        demo()
    elif cmd == "backup":
        from . import backup
        backup.run_once(full_csv=True)
        print("Backup uploaded.")
    elif cmd == "restore":
        restore()
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
