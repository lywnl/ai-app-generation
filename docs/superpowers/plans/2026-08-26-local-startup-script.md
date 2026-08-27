# 本地前后端与中间件统一启动脚本实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 新增一个可重复执行的本地启动脚本，统一管理 MySQL、Redis、Milvus、etcd、MinIO、Nginx、Spring Boot 后端和 Vite 前端。

**架构：** 使用 Bash 作为唯一入口。中间件由 Docker Compose 管理，脚本按宿主机 IPv4 端口和 Docker 健康状态判断是否需要启动；已经占用的中间件端口不重启。前后端由脚本定位并终止旧的本项目进程后重新启动，通过项目专属 `screen` 会话托管，显式绑定 `0.0.0.0` 并使用 `127.0.0.1` 做 IPv4 验证。

**技术栈：** Bash、Docker Compose、MySQL 8、Redis 7、Milvus 2.5、etcd、MinIO、Nginx、Maven Wrapper、Node.js/Vite。

## 全局约束

- 所有本地服务必须通过 IPv4 地址 `127.0.0.1` 验证，不能只检查 `localhost`。
- 后端固定端口 `9025`，前端固定端口 `5173`，Nginx 固定端口 `80`。
- 中间件端口已经被占用时不重启、不删除容器、不删除数据卷。
- 前后端每次执行脚本都重启；通过项目专属 `screen` 会话托管后台进程，并仅允许终止命令行中包含当前项目路径的进程。
- 本地启动脚本的密码和 API Key 只从 `dev/.env` 读取，不写入脚本、日志或终端输出；生产 Compose 继续读取 `prod/.env`。
- 运行时 PID、日志和状态文件放入项目 `logs/runtime/`。
- 不修改业务代码、应用配置和现有生产 Compose。
- 不执行 Git push；本次实现不自动提交业务代码。

### 任务 1：补充本地 MySQL/Redis Compose 配置

**文件：**

- 新增：`dev/docker-compose.middleware.yml`
- 复用：`sql/schema.sql`
- 复用：`prod/redis/start-redis.sh` 的密码约束思路，但本地 Redis 保持 `requirepass` 兼容现有 `application.yml` 的 `default` 用户。

- [x] 使用 `127.0.0.1:3406:3306` 和 `127.0.0.1:6379:6379` 显式 IPv4 映射。
- [x] MySQL 使用持久化卷和只读 schema 初始化文件，Redis 使用持久化卷和密码环境变量。
- [x] 为 MySQL、Redis 增加 Docker healthcheck。
- [x] 使用独立容器名，避免与用户当前已有的非 Compose 容器冲突；脚本先检查端口，端口已存在时不执行 Compose 启动。

### 任务 2：实现统一启动脚本

**文件：**

- 新增：`scripts/start-local.sh`
- 运行时目录：`logs/runtime/`（由脚本自动创建）

- [x] 加载 `dev/.env`，缺少文件或必需密码时立即失败且不输出密钥。
- [x] 检查 Docker、Docker Compose、npm、Maven Wrapper、`curl`、`lsof` 和 `screen` 是否可用。
- [x] 通过 `lsof`/`curl -4` 检查 IPv4 端口，不使用仅解析 `localhost` 的检查。
- [x] MySQL、Redis、Milvus、Nginx：端口已占用时记录跳过；端口未占用时启动对应 Compose 服务；不删除容器或数据卷。
- [x] Milvus 同时检查 `19530` 和 `9091`；只开放一个端口时报告部分启动并停止流程，不能误判为健康。
- [x] 后端停止前根据 PID 文件和 `9025` 端口定位项目进程；非当前项目进程占用端口时拒绝杀进程并退出。
- [x] 前端停止前根据 PID 文件和 `5173` 端口定位项目进程；非当前项目进程占用端口时拒绝杀进程并退出。
- [x] 后端通过 `SERVER_ADDRESS=0.0.0.0`、`SERVER_PORT=9025` 和 `-Djava.net.preferIPv4Stack=true` 启动，并等待 `/api/actuator/health` 返回 `UP`。
- [x] 前端通过 `npm run dev -- --host 0.0.0.0 --port 5173` 启动，并等待 IPv4 HTTP 响应。
- [x] 使用项目专属 `screen` 会话托管前后端，确保启动脚本退出或终端关闭后进程不被回收。
- [x] Nginx 在前后端就绪后启动或复用，并验证 `127.0.0.1:80`。
- [x] 脚本退出前输出访问地址、PID 和健康状态；后台日志写入 `logs/runtime/`。

### 任务 3：补充脚本契约测试

**文件：**

- 新增：`scripts/start-local.test.sh`

- [x] 覆盖脚本语法检查。
- [x] 覆盖 IPv4 绑定参数、前后端端口、Compose 文件路径、健康检查路径和非项目进程保护逻辑。
- [x] 覆盖中间件端口存在时跳过启动、前后端重启、PID/日志目录等关键契约。

### 任务 4：验证与文档

- [x] 运行 `bash scripts/start-local.test.sh`。
- [x] 运行新增中间件 Compose 和 Nginx Compose 的 `config` 校验，不实际重建数据卷。
- [x] 在当前环境执行 `bash scripts/start-local.sh`，验证 MySQL、Redis、Milvus、Nginx、后端和前端。
- [x] 重复执行脚本，确认中间件不重启、前后端重新生成 PID 并重启。
- [x] 用 `lsof` 和 `curl -4` 确认 `9025`、`5173`、`80` 均可通过 IPv4 访问。
- [x] 检查 `git diff`，确认不包含用户已有的后端改动、密钥和数据文件。
