param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot\..").Path,
    [string]$ApiBaseUrl = "/api"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-Checked([string]$File, [string[]]$Arguments) {
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "命令失败（退出码 $LASTEXITCODE）: $File $($Arguments -join ' ')" }
}

$prodDir = (Resolve-Path $PSScriptRoot).Path
$frontendDir = Join-Path $ProjectRoot "ai-app-generation-frontend"
$artifactsFrontendDir = Join-Path $prodDir "artifacts\frontend"
$artifactsBackendDir = Join-Path $prodDir "artifacts\backend"
$prodSqlDir = Join-Path $prodDir "sql"
$prodEmbedDir = Join-Path $prodDir "embed_text"
$prodDashboardDir = Join-Path $prodDir "grafana\dashboards"
$releaseFile = Join-Path $prodDir "artifacts\RELEASE"
New-Item -ItemType Directory -Force -Path $artifactsFrontendDir, $artifactsBackendDir, $prodSqlDir, $prodEmbedDir, $prodDashboardDir | Out-Null

Push-Location $frontendDir
try { $env:VITE_API_BASE_URL = $ApiBaseUrl; Invoke-Checked "npm" @("run", "build") } finally { Pop-Location }
Push-Location $ProjectRoot
try {
    Invoke-Checked "mvn" @("-q", "-DskipTests", "package")
    $jar = Get-ChildItem "target" -Filter "*.jar" | Where-Object { $_.Name -notlike "*original*" } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw "未找到后端 JAR。" }
} finally { Pop-Location }

Remove-Item -Recurse -Force (Join-Path $artifactsFrontendDir "dist") -ErrorAction SilentlyContinue
Copy-Item -Recurse -Force (Join-Path $frontendDir "dist") (Join-Path $artifactsFrontendDir "dist")
Copy-Item -Force $jar.FullName (Join-Path $artifactsBackendDir "app.jar")
Copy-Item -Force (Join-Path $ProjectRoot "sql\schema.sql") (Join-Path $prodSqlDir "schema.sql")
Remove-Item -Recurse -Force (Join-Path $prodSqlDir "migrations") -ErrorAction SilentlyContinue
Copy-Item -Recurse -Force (Join-Path $ProjectRoot "sql\migrations") (Join-Path $prodSqlDir "migrations")
Remove-Item -Recurse -Force (Join-Path $prodEmbedDir "*") -ErrorAction SilentlyContinue
Copy-Item -Recurse -Force (Join-Path $ProjectRoot "embed_text\*") $prodEmbedDir
Copy-Item -Force (Join-Path $ProjectRoot "grafana\ai-model-observability-dashboard.json") (Join-Path $prodDashboardDir "ai-model-observability-dashboard.json")

$release = Get-Date -Format "yyyyMMdd-HHmmss"
Set-Content -Path $releaseFile -Value $release -Encoding utf8
Get-ChildItem $artifactsFrontendDir, $artifactsBackendDir, $prodSqlDir, $prodEmbedDir -File -Recurse | Get-FileHash -Algorithm SHA256 | ForEach-Object { "$($_.Hash)  $($_.Path.Substring($prodDir.Length + 1))" } | Set-Content -Path (Join-Path $prodDir "artifacts\SHA256SUMS") -Encoding utf8
Write-Host "构建完成，版本: $release"
