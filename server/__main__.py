import uvicorn

from .config import HOST, PORT

if __name__ == "__main__":
    uvicorn.run("server.app:app", host=HOST, port=PORT, proxy_headers=True, forwarded_allow_ips="*")
