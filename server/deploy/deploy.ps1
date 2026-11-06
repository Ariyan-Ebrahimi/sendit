# Copies the Sendit server to the VPS and runs the setup script there.
# Usage (from the server folder):  powershell -ExecutionPolicy Bypass -File deploy\deploy.ps1 -HostIp YOUR_SERVER_IP
# You will be asked for the VPS root password (twice: once to copy, once to run).
param(
    [Parameter(Mandatory = $true)][string]$HostIp,
    [string]$User = "root",
    [string]$RemoteDir = "/opt/sendit"
)
$ErrorActionPreference = "Stop"
$serverDir = Split-Path -Parent $PSScriptRoot
if (-not $PSScriptRoot) { $serverDir = (Get-Location).Path }
$bundle = Join-Path $env:TEMP "sendit-server.tgz"

Write-Host "Packing server files..." -ForegroundColor Cyan
if (Test-Path $bundle) { Remove-Item $bundle -Force }
tar -czf $bundle -C $serverDir --exclude=node_modules --exclude=uploads --exclude=.env server.js shares.js account.js admin.js settings.js extras.js plans.js security.js backups.js alerts.js admin-more.js admin-ui.js admin-assets package.json init.sql README.md .env.example deploy
if ($LASTEXITCODE -ne 0) { throw "tar failed" }

Write-Host "Uploading to $User@${HostIp}..." -ForegroundColor Cyan
scp $bundle "${User}@${HostIp}:/tmp/sendit-server.tgz"
if ($LASTEXITCODE -ne 0) { throw "scp failed" }

Write-Host "Running setup on the VPS..." -ForegroundColor Cyan
$remote = "mkdir -p $RemoteDir && tar -xzf /tmp/sendit-server.tgz -C $RemoteDir && sed -i 's/\r$//' $RemoteDir/deploy/setup-vps.sh && bash $RemoteDir/deploy/setup-vps.sh"
ssh -t "${User}@${HostIp}" $remote
if ($LASTEXITCODE -ne 0) { throw "remote setup failed" }

Write-Host "`nChecking from this PC..." -ForegroundColor Cyan
try {
    $r = Invoke-RestMethod "http://${HostIp}:4000/api/health" -TimeoutSec 10
    Write-Host ("Server says: " + ($r | ConvertTo-Json -Compress)) -ForegroundColor Green
} catch {
    Write-Host "Could not reach http://${HostIp}:4000 from here. Open TCP port 4000 in the VPS provider's firewall panel." -ForegroundColor Yellow
}
