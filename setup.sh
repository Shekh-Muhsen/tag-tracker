#!/usr/bin/env sh
# One-time setup on Linux / macOS / Raspberry Pi. Run from the project folder: sh setup.sh
set -e
GFMT_COMMIT=d46e9528578015b51d3b84dd91bf8f16e9ab850f

[ -d vendor/GoogleFindMyTools ] || git clone https://github.com/leonboe1/GoogleFindMyTools vendor/GoogleFindMyTools
git -C vendor/GoogleFindMyTools fetch --quiet origin
git -C vendor/GoogleFindMyTools checkout --quiet "$GFMT_COMMIT"

[ -d .venv ] || python3 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r requirements.txt -r vendor/GoogleFindMyTools/requirements.txt

echo
echo "Setup done. Next steps:"
echo "  1) .venv/bin/python -m server.manage adduser <your-name>"
echo "  2) .venv/bin/python -m server.manage google-login   (needs Chrome, or copy data/google_secrets.json from a PC)"
echo "  3) .venv/bin/python -m server"
