# 本地 Nginx 开发代理实施计划

> **执行要求：** 在当前工作树内逐项实施并验证；不创建 Git 提交，不修改 `prod/`，不操作现有数据库容器。

**目标：** 使用本地 Docker 中的 Nginx 提供 `http://localhost` 统一开发入口，并保留 Vite HMR、后端 API 和 SSE 流式响应能力。

**架构：** 独立开发 Nginx 容器监听宿主机 `80` 端口。`/api` 转发到宿主机 Spring Boot `9025`，其他请求和 WebSocket 转发到宿主机 Vite `5173`。

**技术栈：** Docker Compose、Nginx 1.27 Alpine、Vite 7、Vue 3、Spring Boot。

## 全局约束

- 所有新增文件使用 UTF-8。
- 只修改当前工作树。
- 不修改 `prod/` 生产配置。
- 不启动新的 MySQL、Redis、PostgreSQL 或 Spring Boot 容器。
- 不覆盖工作区已有未提交改动。
- 不执行 Git commit 或 push。

---

### 任务一：建立独立开发代理配置

**文件：**

- 新建：`dev/nginx/nginx.conf`
- 新建：`dev/docker-compose.yml`

- [ ] 编写 Nginx 配置：监听容器 `80`；`/api` 代理到 `host.docker.internal:9025`；其他请求代理到 `host.docker.internal:5173`；配置 SSE 禁用缓冲、长超时和 Vite WebSocket 升级。
- [ ] 编写独立 Compose：只包含 `nginx` 服务，映射 `80:80`，只读挂载开发配置，并声明宿主机网关。
- [ ] 执行 `docker run --rm -v "$PWD/dev/nginx/nginx.conf:/etc/nginx/nginx.conf:ro" nginx:1.27-alpine nginx -t`，预期 Nginx 配置语法检查成功。
- [ ] 执行 `docker compose -f dev/docker-compose.yml config`，预期 Compose 配置解析成功且只有一个 `nginx` 服务。

### 任务二：验证开发 Nginx 能安全访问 Vite

**文件：**

- 不修改前端配置。

- [ ] 保持 Vite 只监听 `127.0.0.1:5173`，验证 macOS Docker Desktop 可通过 `host.docker.internal` 访问，避免把开发端口暴露到局域网。
- [ ] 执行 `npm run type-check`，预期 TypeScript 类型检查通过。
- [ ] 执行 `npm run build`，预期前端生产构建通过。
- [ ] 确认 Vite 监听地址仍为 `127.0.0.1:5173`。

### 任务三：启动并验证本地 Nginx

**文件：**

- 不新增文件。

- [ ] 确认宿主机 `80` 空闲，执行 `docker compose -f dev/docker-compose.yml up -d`。
- [ ] 执行 `docker compose -f dev/docker-compose.yml ps` 和容器健康检查，确认 Nginx 运行。
- [ ] 执行 `curl -fsS http://localhost/`，确认返回前端 HTML。
- [ ] 执行 `curl -fsS http://localhost/api/actuator/health`，确认经 Nginx 返回后端 `UP`。
- [ ] 使用浏览器打开 `http://localhost`，检查页面渲染、控制台错误和静态资源加载。
- [ ] 发起 WebSocket 升级探针，确认 Vite HMR 请求到达上游而不是被 Nginx 拒绝。
- [ ] 检查 Nginx 容器日志，确认没有 `connect() failed`、`502` 或配置错误。

### 任务四：自审与交付

- [ ] 检查 `git diff`，确保只新增开发代理文件并修改 Vite 监听地址，没有覆盖已有用户改动。
- [ ] 对照设计逐项核验入口、API、SSE、HMR、生产隔离和容器范围。
- [ ] 向用户报告访问地址、容器名称、启动/停止命令、具体改动内容和验证证据。
