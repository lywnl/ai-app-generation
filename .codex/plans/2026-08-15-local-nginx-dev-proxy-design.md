# 本地 Nginx 开发代理设计

## 目标

浏览器统一通过 `http://localhost` 访问当前开发环境，同时保留 Vite 热更新，并让 `/api` 请求经由 Nginx 转发到本地 Spring Boot 后端。

## 架构

```text
浏览器 http://localhost
        |
        v
Docker 中的开发 Nginx :80
        |-- /api/* ------> host.docker.internal:9025
        `-- 其他请求 ----> host.docker.internal:5173
                              `-- 透传 WebSocket，保留 Vite HMR
```

## 设计约束

- 开发代理配置独立放在 `dev/`，不修改或复用 `prod/` 生产配置。
- 前端继续由 Vite 提供，不把 `dist` 复制进开发 Nginx。
- 后端继续使用宿主机当前运行的 `9025` 端口。
- 前端继续使用相对 API 根路径 `/api`，浏览器只访问 Nginx。
- SSE 生成接口和普通 API 均禁用代理缓冲，并使用长超时。
- Nginx 转发前端请求时透传 WebSocket 升级头，支持 Vite HMR。
- Vite 继续只监听 `127.0.0.1:5173`；macOS Docker Desktop 通过 `host.docker.internal` 访问宿主机回环服务，避免把开发端口暴露到局域网。
- 不启动或替换现有 MySQL、Redis、PostgreSQL 和后端进程。

## 验收标准

1. `http://localhost` 返回前端页面。
2. Vue History 路由经 Nginx 访问时由 Vite 正常处理，不返回 Nginx 404。
3. `http://localhost/api/actuator/health` 经 Nginx 返回后端健康响应。
4. `/api/app/chat/gen/code` 保持流式代理所需配置，不发生 Nginx 响应缓冲。
5. Vite HMR WebSocket 能够通过 Nginx 建立。
6. 现有 `prod/` 配置与已有数据库容器不受影响。

## 风险与回退

- Docker Desktop 必须能够解析 `host.docker.internal`；Compose 同时声明 `host-gateway` 映射以兼容支持该能力的 Docker 环境。
- 本机 `80` 端口若被其他进程占用，Nginx 容器无法启动；启动前先检查端口。
- 回退只需停止开发 Nginx 容器；Vite 与生产部署配置均不受影响。
