"""Offline license verification (no server needed).

A license key is `payload.signature`, both base64url:
  payload  = JSON {"d": device_id or "*", "t": max_tags, "e": expiry_epoch or 0}
  signature= Ed25519 signature of payload, made by the SELLER's private key.

The app embeds only the PUBLIC key, so it can verify a key but never forge one.
Keys are device-bound: a key made for one phone won't unlock another.
"""
import base64
import json
import time

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

# Public key of the license signer. The matching PRIVATE key lives only in the
# separate key-generator app (private repo) — never here.
PUBLIC_KEY_HEX = "fbf4124b4c0cd6faeb1b2ea9960a48f69b4cf14649aea404142df8cec0cef96b"

FREE_TAGS = 1
FREE_HISTORY_HOURS = 24


def _b64d(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def verify(key: str, device_id: str) -> dict:
    """Returns {'licensed': bool, 'tags': int, 'expiry': int, 'reason': str}."""
    try:
        payload_b64, sig_b64 = key.strip().split(".")
        payload = _b64d(payload_b64)
        sig = _b64d(sig_b64)
        Ed25519PublicKey.from_public_bytes(bytes.fromhex(PUBLIC_KEY_HEX)).verify(sig, payload)
        data = json.loads(payload)
    except (ValueError, InvalidSignature, Exception):
        return {"licensed": False, "tags": FREE_TAGS, "reason": "Invalid or corrupted key"}
    if data.get("d") not in ("*", device_id):
        return {"licensed": False, "tags": FREE_TAGS, "reason": "This key belongs to a different phone"}
    exp = int(data.get("e", 0) or 0)
    if exp and time.time() > exp:
        return {"licensed": False, "tags": FREE_TAGS, "reason": "Key expired"}
    return {"licensed": True, "tags": int(data.get("t", 1)), "expiry": exp, "reason": ""}
