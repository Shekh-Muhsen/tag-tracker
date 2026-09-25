import threading
import time
import traceback

from . import db, finder
from .config import POLL_MINUTES

status = {"last_run": None, "last_error": None, "running": False, "next_run": None}
_wake = threading.Event()


def poll_once():
    status["running"] = True
    try:
        trackers = finder.list_trackers()
        for name, canonic_id in trackers:
            db.upsert_device(canonic_id, name)
            try:
                locs = finder.locate(canonic_id)
                added = db.insert_locations(canonic_id, locs)
                db.mark_polled(canonic_id, None)
                print(f"[poller] {name}: {len(locs)} reports, {added} new")
            except Exception as e:
                db.mark_polled(canonic_id, str(e))
                print(f"[poller] {name}: {e}")
        status["last_error"] = None
    except Exception as e:
        status["last_error"] = str(e)
        traceback.print_exc()
    finally:
        status["running"] = False
        status["last_run"] = int(time.time())


def _loop():
    while True:
        poll_once()
        status["next_run"] = int(time.time() + POLL_MINUTES * 60)
        _wake.wait(POLL_MINUTES * 60)
        _wake.clear()


def start():
    threading.Thread(target=_loop, name="poller", daemon=True).start()


def trigger_now():
    _wake.set()
