# Start Tag Tracker with Google Drive backup (double-click or run in PowerShell).
Set-Location $PSScriptRoot
$env:RCLONE_CONFIG   = "$PSScriptRoot\data\rclone.conf"
$env:TT_RCLONE_REMOTE = "gdrive:TagTracker"
$env:TT_BACKUP_MINUTES = "30"
$env:PATH = "$PSScriptRoot\data;$env:PATH"
Write-Host "Tag Tracker running at http://localhost:8000  (Ctrl+C to stop)" -ForegroundColor Green
.\.venv\Scripts\python -m server
