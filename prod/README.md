# 生产部署（仅上传 prod 目录）

## 1. 本地先生成产物（前后端打包进 prod）

在项目根目录执行：

```powershell
.\prod\build-artifacts.ps1
```

该步骤会生成这些内容：

- `prod/artifacts/backend/app.jar`
- `prod/artifacts/frontend/dist/*`
- `prod/sql/schema.sql`
- `prod/embed_text/*`
- `prod/grafana/dashboards/ai-model-observability-dashboard.json`

## 2. 上传到服务器

只上传 `prod` 目录到服务器，例如：

`/opt/ai-app-generation/prod`

## 3. 服务器部署

进入服务器上的 `prod` 目录：

```bash
cd /opt/ai-app-generation/prod
cp .env.example .env
```

默认账号密码（已预置）：

- MySQL：`admin / lyw666`（root 密码 `lyw666`）
- Redis（ACL）：`admin / lyw666`
- PostgreSQL：`admin / lyw666`
- Grafana：`admin / lyw666`

如果暂时不填 API Key，请保留这些键且值为空：

```env
DEEPSEEK_API_KEY=
DASHSCOPE_API_KEY=
COS_HOST=
TEN_SERCET_ID=
TEN_SECRET_KEY=
PEXELS_API_KEY=
```

启动：

```bash
docker compose --env-file .env -f docker-compose.yml build
docker compose --env-file .env -f docker-compose.yml up -d
```

### 启用 Vue Hybrid 检索（默认关闭）

`RAG_HYBRID_ENABLED` 默认值为 `false`。仅当以下真实门禁严格依次完成后，才允许开启：

1. 正式 23 条摄取并物理核验通过。
2. 30 条真实检索达标。
3. 十条首次生成 10/10。
4. 在 `.env` 设置 `RAG_HYBRID_ENABLED=true`。
5. 重启 backend：

   ```bash
   docker compose --env-file .env -f docker-compose.yml up -d --force-recreate backend
   ```

任一步失败都保持 `RAG_HYBRID_ENABLED=false`。默认 Maven、PGVector 协议探针、五骨架策展构建都不能替代以上三项真实成绩，也不得据此开启 Hybrid。

PostgreSQL 说明：

- 当前已改为直接使用 `pgvector/pgvector:pg16` 镜像
- 不再在本地编译 pgvector（避免慢速 `apt + git + make`）
- 若你已提前拉取镜像，可直接启动：

```bash
docker compose --env-file .env -f docker-compose.yml up -d pg
```

## 4. 常用检查

```bash
docker compose --env-file .env -f docker-compose.yml ps
docker compose --env-file .env -f docker-compose.yml logs -f backend
```

访问：

- 业务入口：`http://43.138.69.10:100`
- 后端健康：`http://43.138.69.10:9025/api/actuator/health`
- Prometheus：`http://43.138.69.10:9090`
- Grafana：`http://43.138.69.10:3000`

## 5. 说明

- 后端镜像内包含 `chromium + chromedriver`，满足截图服务。
- 后端镜像内全局安装 `@mermaid-js/mermaid-cli`，命令为 `mmdc`。
- 服务器部署时不再依赖项目根目录源码，只依赖 `prod` 本目录文件。
