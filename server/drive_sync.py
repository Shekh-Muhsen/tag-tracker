"""Efficient automatic Google Drive sync using the Drive REST API.

Powers the "default" storage: history is kept in a dedicated folder in the user's own
Drive (TagTracker/history/), split into ONE CSV per month. Each sync only re-uploads the
CURRENT month's small file, so upload size stays bounded no matter how long the history
grows. Signing into the app on another phone (same Google account) pulls it all back.

Everything lives under a specific folder — never the Drive root.
"""
import json
import time

import requests


def _retry(make_request):
    """Calls make_request() and retries with exponential backoff on Drive rate limits
    (HTTP 429, or 403 'rateLimitExceeded'/'Quota exceeded'). Keeps sync robust when many
    small chunks are synced in a row."""
    delay = 1.0
    r = None
    for _ in range(6):
        r = make_request()
        if r.status_code == 429 or (r.status_code == 403 and (
                "ateLimit" in r.text or "Quota exceeded" in r.text or "userRateLimit" in r.text)):
            time.sleep(delay)
            delay = min(delay * 2, 20)
            continue
        return r
    return r

DRIVE = "https://www.googleapis.com/drive/v3"
UPLOAD = "https://www.googleapis.com/upload/drive/v3"
FOLDER_MIME = "application/vnd.google-apps.folder"
ROOT_FOLDER = "TagTracker"
SUB_FOLDER = "history"


def token_from_login() -> str:
    """A Drive token minted fresh from the tag Google login (default = same account)."""
    from . import finder
    finder._ensure_imported()
    from Auth.token_retrieval import request_token
    from Auth.username_provider import get_username

    return request_token(get_username(), "drive.file", True)


class DriveClient:
    def __init__(self, token: str):
        self.h = {"Authorization": "Bearer " + token}

    def _list(self, q: str, fields: str = "files(id,name,mimeType,modifiedTime,size)"):
        r = _retry(lambda: requests.get(f"{DRIVE}/files", headers=self.h,
                   params={"q": q, "fields": fields, "spaces": "drive", "pageSize": 200}, timeout=30))
        r.raise_for_status()
        return r.json().get("files", [])

    def ensure_folder(self, name: str, parent_id: str = None) -> str:
        q = f"name='{name}' and mimeType='{FOLDER_MIME}' and trashed=false"
        if parent_id:
            q += f" and '{parent_id}' in parents"
        found = self._list(q)
        if found:
            return found[0]["id"]
        meta = {"name": name, "mimeType": FOLDER_MIME}
        if parent_id:
            meta["parents"] = [parent_id]
        r = _retry(lambda: requests.post(f"{DRIVE}/files", headers={**self.h, "Content-Type": "application/json"},
                   data=json.dumps(meta), timeout=30))
        r.raise_for_status()
        return r.json()["id"]

    def history_folder(self) -> str:
        """The dedicated TagTracker/history/ folder id (created if missing)."""
        return self.ensure_folder(SUB_FOLDER, self.ensure_folder(ROOT_FOLDER))

    def find_file(self, name: str, parent_id: str):
        return next(iter(self._list(f"name='{name}' and '{parent_id}' in parents and trashed=false")), None)

    def upload(self, local_path: str, name: str, parent_id: str, content_type: str = "text/csv") -> str:
        with open(local_path, "rb") as f:
            data = f.read()
        existing = self.find_file(name, parent_id)
        if existing:
            r = _retry(lambda: requests.patch(f"{UPLOAD}/files/{existing['id']}?uploadType=media",
                       headers={**self.h, "Content-Type": content_type}, data=data, timeout=120))
        else:
            b = "tagtracker7boundary"
            body = (f"--{b}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                    + json.dumps({"name": name, "parents": [parent_id]})
                    + f"\r\n--{b}\r\nContent-Type: {content_type}\r\n\r\n").encode() + data + f"\r\n--{b}--".encode()
            r = _retry(lambda: requests.post(f"{UPLOAD}/files?uploadType=multipart",
                       headers={**self.h, "Content-Type": f"multipart/related; boundary={b}"},
                       data=body, timeout=120))
        r.raise_for_status()
        return r.json()["id"]

    def list_children(self, parent_id: str):
        return self._list(f"'{parent_id}' in parents and trashed=false")

    def download_id(self, file_id: str, dest: str):
        r = _retry(lambda: requests.get(f"{DRIVE}/files/{file_id}?alt=media", headers=self.h, timeout=120))
        r.raise_for_status()
        with open(dest, "wb") as f:
            f.write(r.content)
