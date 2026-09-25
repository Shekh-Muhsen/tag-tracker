import os

import uvicorn

from .config import HOST, PORT

if __name__ == "__main__":
    # Only trust X-Forwarded-For from a local reverse proxy / tunnel, otherwise
    # anyone could fake their IP and dodge the login rate limit.
    trusted = os.environ.get("TT_TRUSTED_PROXIES", "127.0.0.1")
    uvicorn.run("server.app:app", host=HOST, port=PORT, proxy_headers=True, forwarded_allow_ips=trusted)
