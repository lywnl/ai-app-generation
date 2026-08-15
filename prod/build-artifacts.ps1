param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot\..").Path,
    [string]$ApiBaseUrl = "/api"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$prodDir = $PSScriptRoot
$frontendDir = Join-Path $ProjectRoot "ai-app-generation-frontend"
$backendDir = $ProjectRoot
$artifactsFrontendDir = Join-Path $prodDir "artifacts\frontend"
$artifactsBackendDir = Join-Path $prodDir "artifacts\backend"
$prodSqlDir = Join-Path $prodDir "sql"
$prodEmbedDir = Join-Path $prodDir "embed_text"
$prodDashboardDir = Join-Path $prodDir "grafana\dashboards"

New-Item -ItemType Directory -Force -Path $artifactsFrontendDir, $artifactsBackendDir, $prodSqlDir, $prodEmbedDir, $prodDashboardDir | Out-Null

Write-Host "[1/5] Build frontend for production..."
Push-Location $frontendDir
$env:VITE_API_BASE_URL = $ApiBaseUrl
npm run build
Pop-Location

Write-Host "[2/5] Build backend jar..."
Push-Location $backendDir
mvn -q -DskipTests package
$jar = Get-ChildItem -Path "target" -Filter "*.jar" | Where-Object { $_.Name -notlike "*original*" } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { throw "No backend jar found in target directory." }
Pop-Location

Write-Host "[3/5] Copy frontend/backend artifacts into prod..."
Remove-Item -Recurse -Force (Join-Path $artifactsFrontendDir "dist") -ErrorAction SilentlyContinue
Copy-Item -Recurse -Force (Join-Path $frontendDir "dist") (Join-Path $artifactsFrontendDir "dist")
Copy-Item -Force $jar.FullName (Join-Path $artifactsBackendDir "app.jar")

Write-Host "[4/5] Sync runtime dependency files..."
Copy-Item -Force (Join-Path $ProjectRoot "sql\schema.sql") (Join-Path $prodSqlDir "schema.sql")
Remove-Item -Recurse -Force (Join-Path $prodEmbedDir "*") -ErrorAction SilentlyContinue
Copy-Item -Recurse -Force (Join-Path $ProjectRoot "embed_text\*") $prodEmbedDir
Copy-Item -Force (Join-Path $ProjectRoot "grafana\ai-model-observability-dashboard.json") (Join-Path $prodDashboardDir "ai-model-observability-dashboard.json")

Write-Host "[5/5] Done. You can upload the prod directory now."
Write-Host ("Backend jar: " + (Join-Path $artifactsBackendDir "app.jar"))
Write-Host ("Frontend dist: " + (Join-Path $artifactsFrontendDir "dist"))
