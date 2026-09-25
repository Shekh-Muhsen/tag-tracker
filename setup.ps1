# One-time setup on Windows. Run from the project folder:
#   powershell -ExecutionPolicy Bypass -File setup.ps1
$ErrorActionPreference = "Stop"
$GfmtCommit = "d46e9528578015b51d3b84dd91bf8f16e9ab850f"

if (-not (Test-Path "vendor\GoogleFindMyTools")) {
    git clone https://github.com/leonboe1/GoogleFindMyTools vendor\GoogleFindMyTools
}
git -C vendor\GoogleFindMyTools fetch --quiet origin
git -C vendor\GoogleFindMyTools checkout --quiet $GfmtCommit

if (-not (Test-Path ".venv")) { python -m venv .venv }
.\.venv\Scripts\python -m pip install --upgrade pip
.\.venv\Scripts\python -m pip install -r requirements.txt -r vendor\GoogleFindMyTools\requirements.txt

Write-Host ""
Write-Host "Setup done. Next steps:" -ForegroundColor Green
Write-Host "  1) .\.venv\Scripts\python -m server.manage adduser <your-name>"
Write-Host "  2) .\.venv\Scripts\python -m server.manage google-login"
Write-Host "  3) .\.venv\Scripts\python -m server"
