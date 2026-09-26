"""Automatic Google Drive sync using the Drive REST API.

This powers the "default" storage: the app keeps history in a fixed folder in the user's
own Drive, so signing into the same app (same Google account) on ANY phone pulls the
history straight back — no manual file picking.

Token sources (a "custom" setup can use a different account/file instead):
  - token_from_login(): mint a Drive token from the SAME Google sign-in used for the tags
    (default; tag account == Drive account).
  - any OAuth access token (e.g. a separate Drive authorization) for a custom account.
"""
import json

import requests

DRIVE = "https://www.googleapis.com/drive/v3"
UPLOAD = "https://www.googleapis.com/upload/drive/v3"
FOLDER_MIME = "application/vnd.google-apps.folder"
DEFAULT_FOLDER = "TagTracker"
DEFAULT_FILE = "history.csv"


def token_from_login() -> str:
    """A Drive token minted from the tag Google login (default = same account)."""
    from . import finder
    finder._ensure_imported()
    from Auth.token_retrieval import request_token
    from Auth.username_provider import get_username

    # play_services=True uses the GMS app id, which carries Google API scopes.
    return request_token(get_username(), "drive.file", True)


class DriveClient:
    def __init__(self, token: str):
        self.h = {"Authorization": "Bearer " + token}

    def _list(self, q: str, fields: str = "files(id,name,modifiedTime)"):
        r = requests.get(f"{DRIVE}/files", headers=self.h,
                         params={"q": q, "fields": fields, "spaces": "drive", "pageSize": 10}, timeout=30)
        r.raise_for_status()
        return r.json().get("files", [])

    def ensure_folder(self, name: str = DEFAULT_FOLDER) -> str:
        found = self._list(f"name='{name}' and mimeType='{FOLDER_MIME}' and trashed=false")
        if found:
            return found[0]["id"]
        r = requests.post(f"{DRIVE}/files", headers={**self.h, "Content-Type": "application/json"},
                          data=json.dumps({"name": name, "mimeType": FOLDER_MIME}), timeout=30)
        r.raise_for_status()
        return r.json()["id"]

    def find_file(self, folder_id: str, name: str = DEFAULT_FILE):
        found = self._list(f"name='{name}' and '{folder_id}' in parents and trashed=false")
        return found[0]["id"] if found else None

    def upload(self, local_path: str, name: str = DEFAULT_FILE, folder: str = DEFAULT_FOLDER) -> str:
        folder_id = self.ensure_folder(folder)
        with open(local_path, "rb") as f:
            data = f.read()
        fid = self.find_file(folder_id, name)
        if fid:  # overwrite existing
            r = requests.patch(f"{UPLOAD}/files/{fid}?uploadType=media",
                               headers={**self.h, "Content-Type": "text/csv"}, data=data, timeout=120)
        else:  # create new (multipart: metadata + content)
            b = "tagtracker7boundary"
            body = (f"--{b}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                    + json.dumps({"name": name, "parents": [folder_id]})
                    + f"\r\n--{b}\r\nContent-Type: text/csv\r\n\r\n").encode() + data + f"\r\n--{b}--".encode()
            r = requests.post(f"{UPLOAD}/files?uploadType=multipart",
                              headers={**self.h, "Content-Type": f"multipart/related; boundary={b}"},
                              data=body, timeout=120)
        r.raise_for_status()
        return r.json()["id"]

    def download(self, dest: str, name: str = DEFAULT_FILE, folder: str = DEFAULT_FOLDER) -> bool:
        """Downloads the history file if it exists. Returns False if there's nothing stored yet."""
        folder_id = self.ensure_folder(folder)
        fid = self.find_file(folder_id, name)
        if not fid:
            return False
        r = requests.get(f"{DRIVE}/files/{fid}?alt=media", headers=self.h, timeout=120)
        r.raise_for_status()
        with open(dest, "wb") as f:
            f.write(r.content)
        return True
