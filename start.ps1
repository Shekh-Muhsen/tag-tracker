# Start the tracker server on Windows (keep this window open, or run it as a scheduled task at log-on).
Set-Location $PSScriptRoot
.\.venv\Scripts\python -m server
