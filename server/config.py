import os
import secrets
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

DATA_DIR = Path(os.environ.get("TT_DATA_DIR", ROOT / "data")).resolve()
GFMT_DIR = Path(os.environ.get("TT_GFMT_DIR", ROOT / "vendor" / "GoogleFindMyTools")).resolve()
DB_PATH = DATA_DIR / "tracker.db"
GOOGLE_SECRETS_PATH = DATA_DIR / "google_secrets.json"

# How often to ask Google for new tag locations. Keep >= 5 min to avoid rate limits.
POLL_MINUTES = max(1.0, float(os.environ.get("TT_POLL_MINUTES", "10")))
POLLING_ENABLED = os.environ.get("TT_POLLING", "1") != "0"

HOST = os.environ.get("TT_HOST", "0.0.0.0")
PORT = int(os.environ.get("TT_PORT", "8000"))

# Set to 1 when served over HTTPS so the session cookie is only sent securely.
COOKIE_SECURE = os.environ.get("TT_COOKIE_SECURE", "0") == "1"
SESSION_DAYS = int(os.environ.get("TT_SESSION_DAYS", "30"))

DATA_DIR.mkdir(parents=True, exist_ok=True)


def _load_secret_key() -> str:
    env = os.environ.get("TT_SECRET_KEY")
    if env:
        return env
    path = DATA_DIR / "session_secret.txt"
    if not path.exists():
        path.write_text(secrets.token_urlsafe(48))
    return path.read_text().strip()


SECRET_KEY = _load_secret_key()
