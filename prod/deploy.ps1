param(
    [string]$EnvFile = ".env"
)

Set-Location -Path $PSScriptRoot

if (-not (Test-Path $EnvFile)) {
    Write-Error "未找到环境变量文件: $EnvFile，请先从 .env.example 复制为 .env 并填写配置。"
    exit 1
}

docker compose --env-file $EnvFile -f docker-compose.yml build
if ($LASTEXITCODE -ne 0) {
    Write-Error "Docker 镜像构建失败。"
    exit $LASTEXITCODE
}

docker compose --env-file $EnvFile -f docker-compose.yml up -d
if ($LASTEXITCODE -ne 0) {
    Write-Error "Docker 服务启动失败。"
    exit $LASTEXITCODE
}

docker compose --env-file $EnvFile -f docker-compose.yml ps
