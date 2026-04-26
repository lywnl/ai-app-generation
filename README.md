<div align="center">

# AI App Generation

**一句话需求 → 可部署 Web 应用** · 基于 LangChain4j 的 AI 全栈零代码（NoCode）应用生成平台

[![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.4-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![LangChain4j](https://img.shields.io/badge/LangChain4j-1.1.0-1C3C3C?logo=langchain&logoColor=white)](https://docs.langchain4j.dev/)
[![Vue](https://img.shields.io/badge/Vue-3.5-4FC08D?logo=vuedotjs&logoColor=white)](https://vuejs.org/)
[![Vite](https://img.shields.io/badge/Vite-7.0-646CFF?logo=vite&logoColor=white)](https://vitejs.dev/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)](https://docs.docker.com/compose/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](#-许可证)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](#-参与贡献)

[功能特性](#-核心特性) · [架构设计](#-系统架构) · [快速开始](#-快速开始) · [项目结构](#-项目结构) · [核心模块](#-核心模块解析) · [API 文档](#-api-文档) · [部署](#-生产部署)

</div>

---

## 项目简介

`ai-app-generation` 是一个面向 C 端开发者与产品同学的 **AI 驱动应用生成平台**，对标美团 NoCode、Bolt.new、Lovable 等同类型产品。用户只需用自然语言描述应用需求，平台即可：

1. **AI 智能识别**应当生成什么形态的代码（单文件 HTML / 多文件项目 / Vue 完整工程）
2. **检索 RAG 模板库**为大模型注入高质量上下文片段，提升生成稳定性
3. **流式输出**生成过程，实时反馈代码片段、工具调用、思考过程
4. **一键部署**生成产物到平台静态服务，并支持代码 ZIP 下载

平台内置：智能路由、检索增强（RAG）、Agent 工具调用、Prompt 安全护栏、流式 SSE、对象存储、网页截图、应用市场、可观测监控等完整能力。

---

## 核心特性

| 能力 | 说明 |
| :--- | :--- |
| 三种代码生成模式 | `HTML` 单文件 / `MULTI_FILE` 多文件 / `VUE_PROJECT` Vue 工程 |
| AI 智能路由 | 由 Qwen-Turbo 自动判断需求适合的生成模式 |
| RAG 检索增强 | PgVector 向量检索 + DashScope `text-embedding-v4` + `gte-rerank-v2` 二次重排 |
| Agent 工具调用 | Vue 工程模式下 AI 自主调用 `FileWrite/Read/Modify/Delete/DirRead/Exit` 6 类工具完成多文件项目搭建 |
| 图片采集 Agent | 首条消息自动调度 `Pexels 图片搜索` + `阿里 wan2.2 Logo 生成` + `Mermaid 流程图` + `unDraw 插画` 4 类工具，并行收集封面/Logo/插图素材 |
| 流式 SSE | Reactor `Flux<ServerSentEvent>` + 自定义工具调用流解析器（字符级状态机） |
| Prompt 安全护栏 | 注解 `@PromptSafetyCheck` + AOP 切面 + LangChain4j `InputGuardrail` 双重拦截 |
| 一键部署 | `deployKey` 标识 + Nginx 静态托管，生成产物即时可访问 |
| 代码下载 | 生成目录打包为 ZIP，支持完整工程导出 |
| 网页截图 | Selenium + Chromium + WebDriverManager 容器内自动截屏，封面同步上传腾讯云 COS |
| 应用市场 | 精选作品（priority 排序）+ 我的应用 + 管理员后台，分页缓存 + 游标查询 |
| 对话历史 | 按 `appId + createTime` 联合索引的游标分页 |
| 双层缓存 | Caffeine 本地缓存 + Redis 分布式缓存 + Spring `@Cacheable` 自动失效 |
| 可观测 | Spring Actuator + Micrometer + Prometheus + Grafana AI 模型可观测看板 |
| 用户体系 | Spring Session + Redis 持久化（30 天）+ AOP `@AuthCheck` 角色拦截 |

---

## 系统架构

```
┌──────────────────────────────────────────────────────────────────────┐
│                         浏览器（Vue 3 + AntDV）                        │
└──────────────┬─────────────────────────────────────┬─────────────────┘
               │ /api (HTTP / SSE)                   │ /static (生成产物)
┌──────────────▼─────────────────┐    ┌──────────────▼─────────────────┐
│  Spring Boot 3.5.4 (port 9025) │    │     Nginx (静态资源 / 部署托管)   │
│ ┌────────────────────────────┐ │    └────────────────────────────────┘
│ │  Controller (App / User /  │ │
│ │  ChatHistory / Health)     │ │
│ ├────────────────────────────┤ │
│ │  AppService (业务编排)       │ │
│ │  ├─ AiCodeGenTypeRouting   │──── Qwen-Turbo（路由分类）
│ │  ├─ AiCodeGeneratorFacade  │
│ │  │   ├─ RAG Retrieval      │──── PgVector + Rerank
│ │  │   ├─ Image Collection   │──── DashScope + Pexels（并行 Agent）
│ │  │   └─ Code Generator     │──── DeepSeek-Chat（主生成）
│ │  ├─ ScreenshotService      │──── Selenium + Chromium
│ │  └─ ProjectDownload        │──── Hutool ZipUtil
│ ├────────────────────────────┤ │
│ │  Guardrail (Prompt 安全)    │
│ │  AOP (@AuthCheck)          │
│ │  Cache (Caffeine + Redis)  │
│ └────────────────────────────┘ │
└─┬────────┬────────┬────────┬──┘
  │        │        │        │
  ▼        ▼        ▼        ▼
MySQL    Redis    PgVector  COS
(业务)   (Session) (RAG)    (封面截图)

┌────────────────────────────┐    ┌──────────────────────────────────┐
│  Prometheus (port 9090)    │ ─► │  Grafana (port 3000)             │
│  抓取 /actuator/prometheus  │    │  AI Model Observability Dashboard │
└────────────────────────────┘    └──────────────────────────────────┘
```

**核心数据流（流式生成）**

```
User Prompt
   │
   ▼
[Prompt 安全护栏] ─► [AI 智能路由：Qwen-Turbo] ─► [图片采集 Agent (并行 4 工具)]
                                                        │
                                                        ▼
                              [RAG 检索 → Rerank → 拼接片段]
                                                        │
                                                        ▼
                          [DeepSeek 流式生成] ── SSE Flux ──► 浏览器
                                  │
                                  ▼
                          [代码解析 → 文件落盘 → Vue 工程构建]
                                  │
                                  ▼
                          [Selenium 截屏 → COS 上传 → 更新封面]
```

---

## 技术栈

### 后端

| 类别 | 选型 | 版本 |
| :--- | :--- | :--- |
| 语言 / 框架 | Java / Spring Boot | 25 / 3.5.4 |
| AI 编排 | LangChain4j (含 OpenAI Starter / Reactor / PgVector) | 1.1.0 |
| Agent 工作流 | LangGraph4j | 1.6.0-rc2 |
| AI 模型 | DeepSeek（主生成）+ 阿里 DashScope（路由 / Embedding / Rerank / 文生图） | - |
| ORM | MyBatis-Flex（含 codegen） | 1.11.0 |
| 数据库 | MySQL 8.0.40 + PostgreSQL 16 (pgvector) | - |
| 缓存 / Session | Redis 7.0.15 + Redisson + Spring Session + Caffeine | - |
| API 文档 | Knife4j (OpenAPI 3) | 4.4.0 |
| 网页截图 | Selenium + WebDriverManager | 4.33.0 / 6.1.0 |
| 对象存储 | 腾讯云 COS SDK | 5.6.227 |
| 工具库 | Hutool / Lombok | 5.8.38 / - |
| 监控 | Spring Actuator + Micrometer Prometheus | - |

### 前端

| 类别 | 选型 |
| :--- | :--- |
| 框架 | Vue 3.5 + Composition API |
| 构建 | Vite 7 |
| 状态管理 | Pinia 3 |
| UI 组件库 | Ant Design Vue 4.2 |
| 路由 | Vue Router 4 |
| 类型 | TypeScript 5.8 |
| HTTP | Axios 1.11 |
| 类型生成 | `@umijs/openapi`（基于 OpenAPI 自动生成 TS 接口） |
| Markdown | markdown-it + highlight.js |

### 基础设施

| 类别 | 选型 |
| :--- | :--- |
| 容器化 | Docker + Docker Compose |
| 反向代理 | Nginx |
| 监控可视化 | Prometheus + Grafana |
| 数据库迁移 | Flyway 风格 SQL（`V1__hnsw_index.sql` 等） |

---

## 快速开始

### 环境要求

| 依赖 | 最低版本 | 备注 |
| :--- | :--- | :--- |
| JDK | 25 | 项目使用 Java 25 特性（switch pattern） |
| Maven | 3.9+ | 或直接使用项目自带 `mvnw` |
| Node.js | 20 LTS+ | 前端构建 |
| MySQL | 8.0+ | 端口默认 `3406`（可改） |
| Redis | 7.0+ | 端口 `6379`，需开启 ACL |
| PostgreSQL | 16 + pgvector | 端口 `5432`，库 `ai_codegen_rag` |

### 必需的 API Key

启动前请准备好以下 Key（写入环境变量）：

| 变量名 | 用途 | 申请地址 |
| :--- | :--- | :--- |
| `DEEPSEEK_API_KEY` | 主代码生成 / 推理 | https://platform.deepseek.com |
| `DASHSCOPE_API_KEY` | 智能路由 / Embedding / Rerank / 文生图 | https://dashscope.aliyun.com |
| `PEXELS_API_KEY` | 免费图片素材搜索 | https://www.pexels.com/api |
| `COS_HOST` / `TEN_SERCET_ID` / `TEN_SECRET_KEY` | 腾讯云 COS 对象存储 | https://console.cloud.tencent.com/cos |

### 1. 克隆仓库

```bash
git clone https://gitee.com/lywynl/ai-app-generation.git
cd ai-app-generation
```

### 2. 初始化数据库

```bash
# MySQL：执行 sql/schema.sql
mysql -u root -p < sql/schema.sql

# PostgreSQL：启用 pgvector 扩展
psql -U admin -d ai_codegen_rag -f prod/postgres/init/01-enable-pgvector.sql
```

### 3. 配置环境变量

将 API Key 注入到环境变量（PowerShell 示例）：

```powershell
$env:DEEPSEEK_API_KEY="sk-xxx"
$env:DASHSCOPE_API_KEY="sk-xxx"
$env:PEXELS_API_KEY="xxx"
$env:COS_HOST="https://xxx.cos.ap-beijing.myqcloud.com"
$env:TEN_SERCET_ID="xxx"
$env:TEN_SECRET_KEY="xxx"
```

> **温馨提示**：`application.yml` 中数据库密码默认为开发期占位 `lyw666`，请上线前改成自己的安全密码或迁移到环境变量。

### 4. 启动后端

```bash
# Windows
mvnw.cmd spring-boot:run

# Linux / macOS
./mvnw spring-boot:run
```

启动成功后访问：

- API 文档（Knife4j）：<http://localhost:9025/api/doc.html>
- 健康检查：<http://localhost:9025/api/actuator/health>
- Prometheus 指标：<http://localhost:9025/api/actuator/prometheus>

### 5. 启动前端

```bash
cd ai-app-generation-frontend
npm install
npm run dev
```

前端默认 <http://localhost:5173>，已通过 Vite 代理转发到后端 `/api`。

---

## 项目结构

```
ai-app-generation/
├── ai-app-generation-frontend/         # Vue 3 前端（独立子项目）
│   ├── src/
│   │   ├── api/                        # OpenAPI 自动生成的 TS 接口
│   │   ├── components/                 # 通用组件
│   │   ├── pages/                      # 路由页面（编辑器、应用市场、登录等）
│   │   ├── stores/                     # Pinia 状态
│   │   └── router/                     # Vue Router
│   ├── package.json
│   └── vite.config.ts
│
├── src/main/java/com/lyw/appgeneration/
│   ├── AiAppGenerationApplication.java # 启动类（@EnableCaching + @MapperScan）
│   │
│   ├── ai/                             # AI 能力层（核心）
│   │   ├── AiCodeGeneratorService.java         # AI Service 接口（@SystemMessage / @UserMessage）
│   │   ├── AiGeneratorServiceFactory.java      # 按 appId + 类型构建独立 AI 实例（含工具/记忆）
│   │   ├── AiCodeGenTypeRoutingService.java    # 智能路由：自动选择 HTML/多文件/Vue
│   │   ├── AiCodeGenTypeRoutingServiceFactory.java
│   │   ├── model/                              # AI 响应数据结构（HtmlCodeResult, MultiFileCodeResult）
│   │   │   └── message/                        # 流式消息：AiResponse / ToolRequest / ToolExecuted / ...
│   │   ├── parser/
│   │   │   └── ToolRequestStreamParser.java    # 工具调用流解析器（字符级状态机）
│   │   ├── tools/                              # Vue 工程模式下的 Agent 工具
│   │   │   ├── BaseTool.java                   # 工具基类
│   │   │   ├── FileWriteTool / FileReadTool
│   │   │   ├── FileModifyTool / FileDeleteTool
│   │   │   ├── FileDirReadTool                 # 目录读取
│   │   │   └── ExitTool                        # AI 主动退出循环
│   │   ├── image/                              # 图片采集 Agent
│   │   │   ├── ImageCollectionService.java     # 入口：增强 Prompt
│   │   │   ├── ImageCollectionPlanService.java # 编排器：解析 4 类工具的并行计划
│   │   │   ├── ImageCollectionPromptBuilder.java
│   │   │   ├── ImageCollectionExecutorConfig.java  # 自定义线程池
│   │   │   ├── tools/
│   │   │   │   ├── ImageSearchTool.java        # Pexels 搜索
│   │   │   │   ├── LogoGeneratorTool.java      # 阿里 wan2.2 文生图
│   │   │   │   ├── MermaidDiagramTool.java     # mmdc CLI 渲染流程图
│   │   │   │   └── UndrawIllustrationTool.java # unDraw 插画
│   │   │   └── model/                          # ImageCategoryEnum / ImageCollectionPlan / ImageResource
│   │   └── guardrail/                          # Prompt 安全护栏
│   │       ├── PromptSafetyValidator.java
│   │       ├── PromptSafetyRules.java
│   │       ├── PromptSafetyInputGuardrail.java # LangChain4j InputGuardrail
│   │       ├── annotation/PromptSafetyCheck.java
│   │       └── aspect/PromptSafetyAspect.java  # AOP 切面
│   │
│   ├── core/                           # 代码生成核心引擎
│   │   ├── AiCodeGeneratorFacade.java          # 统一门面（同步 + 流式）
│   │   ├── parser/                             # 代码解析器（HTML / 多文件 / Executor）
│   │   ├── saver/                              # 文件落盘模板（HTML / 多文件 / Executor）
│   │   ├── handler/                            # 流处理器（SimpleText / JsonMessage / Executor）
│   │   └── builder/VueProjectBuilder.java      # Vue 工程后置构建（npm install / build）
│   │
│   ├── controller/
│   │   ├── AppController.java                  # 应用 CRUD / 流式生成 / 部署 / 下载
│   │   ├── UserController.java                 # 用户 CRUD / 登录 / 当前用户
│   │   ├── ChatHistoryController.java          # 对话历史游标查询
│   │   ├── StaticResourceController.java       # 部署产物静态服务
│   │   └── HealthController.java
│   │
│   ├── service/
│   │   ├── AppService / UserService / ChatHistoryService
│   │   ├── ScreenshotService                   # Selenium 截屏
│   │   ├── ProjectDownloadService              # ZIP 下载
│   │   └── rag/                                # RAG 检索增强
│   │       ├── RagRetrievalService.java        # 向量召回（带降级）
│   │       ├── RagRerankService.java           # gte-rerank-v2 二次重排
│   │       ├── RagPromptAssembler.java         # 提示词拼接
│   │       ├── ingest/TemplateIngestService.java  # 模板嵌入入库
│   │       ├── exception/RerankException.java
│   │       └── model/                          # TemplateDoc / RetrievedSnippet
│   │
│   ├── config/                         # Spring 配置
│   │   ├── CorsConfig / JsonConfig / CosClientConfig
│   │   ├── RoutingAiModelConfig                # 多 AI 模型 Bean 装配
│   │   ├── StreamingChatModelConfig
│   │   ├── ReasoningStreamingChatModelConfig
│   │   ├── RedisChatMemoryStoreConfig          # 对话记忆存储
│   │   ├── RedisCacheManagerConfig             # Redis Cache
│   │   ├── RagConfig + RagProperties           # RAG 配置类
│   │   └── ...
│   │
│   ├── manger/                         # Manager 层（注：包名为 manger 非 manager，源码原状）
│   │   ├── CosManager.java                     # 腾讯云 COS 上传
│   │   ├── ToolManager.java                    # AI 工具集中注册
│   │   └── AppFileStateManager.java            # 应用文件状态管理
│   │
│   ├── aop/AuthInterceptor.java        # 权限拦截切面
│   ├── annotation/AuthCheck.java       # @AuthCheck 注解
│   ├── exception/                      # 业务异常 / 错误码 / ThrowUtils
│   ├── common/                         # BaseResponse / ResultUtils / PageRequest
│   ├── constants/                      # AppConstant / UserConstant / RagConstants
│   ├── model/                          # entity / dto / vo / enums
│   ├── mapper/                         # MyBatis-Flex Mapper
│   ├── generator/MyBatisCodeGenerator  # 代码生成器（开发期工具）
│   └── utils/SpringContextUtil / CacheKeyUtils
│
├── src/main/resources/
│   ├── application.yml                 # 主配置（DeepSeek / Qwen / Redis / MySQL / RAG）
│   └── db/migration/V1__hnsw_index.sql # PgVector HNSW 索引
│
├── embed_text/                         # RAG 模板库（30+ 已策展模板）
│   ├── html/                           # 纯 HTML 模板（landing-hero / pricing-table / ...）
│   ├── multi-file/                     # 多文件模板（todo-app / weather-search / ...）
│   └── vue-project/                    # Vue 工程模板（login-form / dashboard / ...）
│
├── prod/                               # 生产部署目录（独立可发布）
│   ├── docker-compose.yml              # 7 容器编排
│   ├── docker/
│   │   ├── Dockerfile.backend          # 后端镜像（含 Chromium / mermaid-cli）
│   │   ├── Dockerfile.nginx
│   │   └── Dockerfile.postgres
│   ├── nginx/nginx.conf
│   ├── redis/users.acl                 # Redis ACL
│   ├── postgres/init/                  # pgvector 初始化
│   ├── prometheus/prometheus.yml
│   ├── grafana/                        # 数据源、仪表盘自动 provision
│   ├── sql/schema.sql
│   ├── embed_text/                     # 同步 RAG 模板库
│   ├── artifacts/                      # 构建产物（jar + 前端 dist）
│   ├── build-artifacts.ps1             # 一键打包脚本
│   ├── deploy.ps1
│   ├── .env.example
│   └── README.md                       # 部署文档
│
├── sql/schema.sql                      # 业务表结构（user / app / chat_history）
├── docs/                               # 设计文档（图片采集并发设计等）
├── docker/                             # 开发环境 Docker
└── pom.xml
```

---

## 核心模块解析

### 1. AI 智能路由

**类**：`ai/AiCodeGenTypeRoutingService.java`

调用 Qwen-Turbo（轻量 + 低成本）对用户的需求做分类，自动判定应当走哪条生成流水线：

```
用户输入 ─► Qwen-Turbo 分类 ─► CodeGenTypeEnum
                                ├─ HTML          单文件页面
                                ├─ MULTI_FILE    HTML + CSS + JS 多文件
                                └─ VUE_PROJECT   完整 Vue 3 工程
```

**为什么这样设计**：不同形态的应用对模型的要求不同，单文件 HTML 用一次 chat 就够了，Vue 工程则需要文件级 Agent 来反复读写。先分类，再选择对应的 AI Service 与系统提示词，能显著降低 Token 消耗与生成失败率。

### 2. RAG 检索增强（Retrieval-Augmented Generation）

**模块**：`service/rag/`

完整流水线：

```
用户 prompt ──► [Embedding: text-embedding-v4 (1024 维)]
                       │
                       ▼
              [PgVector HNSW 召回 top-K=10] (RagRetrievalService)
                       │
                       ▼
              [gte-rerank-v2 重排 → top-K=3] (RagRerankService)
                       │
                       ▼
              [拼接到用户消息前] (RagPromptAssembler)
                       │
                       ▼
              [DeepSeek 主生成]
```

**关键决策**：
- 召回阶段最低分阈值放宽到 `0.30`，给 rerank 足够候选池
- 整条链路有降级保护：检索/重排任意一步失败都不影响主生成（`RagRetrievalService` 内部 try-catch）
- 模板提示词上下文预算 `4000` 字符（约 2000 token），避免挤占用户 prompt 空间

### 3. Vue 工程模式 · Agent 工具调用

**类**：`AiCodeGeneratorFacade.processTokenStream()` + `ai/tools/*`

Vue 工程不能用一次性 chat 生成（结构复杂、文件多），改用 LangChain4j `TokenStream` + 工具调用：

| 工具 | 作用 |
| :--- | :--- |
| `FileWriteTool` | 创建/覆盖文件 |
| `FileReadTool` | 读取已生成文件内容 |
| `FileModifyTool` | 局部修改（避免整文件重写） |
| `FileDeleteTool` | 删除文件 |
| `FileDirReadTool` | 列出工程目录 |
| `ExitTool` | AI 主动判断完成、退出循环 |

**流式工具调用解析器**：`ai/parser/ToolRequestStreamParser.java`

每个 tool call id 维护独立的字符级状态机，处理 LLM 流式吐出参数 JSON 时的字段级 delta 推送（`KEY_READY` → `DELTA` → `VALUE_READY`），让前端实时渲染"正在写入哪个文件"，体验接近 Cursor / Bolt.new。

### 4. 图片采集 Agent

**模块**：`ai/image/`

仅在 **首条消息** 触发（`isFirstMessage = true`），通过自定义线程池 `ImageCollectionExecutorConfig` 并行调度 4 类工具：

| 工具 | 数据源 | 用途 |
| :--- | :--- | :--- |
| `ImageSearchTool` | Pexels API | 真实场景照片（背景/产品图） |
| `LogoGeneratorTool` | 阿里 DashScope `wan2.2-t2i-flash` | 文生图，定制 Logo |
| `MermaidDiagramTool` | mermaid-cli (`mmdc`) | 流程图 / 架构图渲染 |
| `UndrawIllustrationTool` | unDraw | SVG 风格插画 |

收集结果会被拼接到原 prompt，让大模型在生成代码时能直接引用真实素材 URL。

### 5. Prompt 安全护栏

**模块**：`ai/guardrail/`

三道防线：

1. **注解 + AOP 切面**：`@PromptSafetyCheck` 标记需要校验的方法，`PromptSafetyAspect` 在调用前静态规则匹配
2. **LangChain4j InputGuardrail**：`PromptSafetyInputGuardrail` 在 AI Service 入口拦截
3. **规则集中管理**：`PromptSafetyRules` 统一维护黑词、注入特征、长度上限

### 6. 流式 SSE 协议

**控制器**：`AppController.chatToGenCode()` 返回 `Flux<ServerSentEvent<String>>`

```
GET /api/app/chat/gen/code?appId=1&message=做一个待办应用
Content-Type: text/event-stream

data: {"d":"<!DOCTYPE html>"}
data: {"d":"<html>"}
event: tool_request
data: {"toolName":"FileWriteTool","arguments":"..."}
event: business-error
data: {"error":true,"code":50000,"message":"..."}
event: done
data: 
```

- 单条消息体用 `{"d": "片段"}` 包装，前端只需读 `d` 字段拼接
- `business-error` 事件统一承载业务异常（含错误码）
- Reactor `Flux.create` + `onErrorResume` 保证流不中断

### 7. 数据库设计

**MySQL 业务库** `ai_app_generation`：

| 表 | 关键字段 | 索引设计 |
| :--- | :--- | :--- |
| `user` | userAccount(uk) / userRole / userAvatar | `uk_userAccount` 唯一，`idx_userName` 提速搜索 |
| `app` | initPrompt / codeGenType / deployKey(uk) / priority / userId | `uk_deployKey` 保证部署标识唯一，`idx_userId` 加速我的列表 |
| `chat_history` | message / messageType (user/ai) / appId / userId | **`idx_appId_createTime` 联合索引** —— 游标分页核心 |

**PostgreSQL 向量库** `ai_codegen_rag`：

- pgvector 1024 维向量列
- HNSW 索引（`V1__hnsw_index.sql`），近邻检索 O(log n)

---

## API 文档

启动后端后访问 Knife4j：

```
http://localhost:9025/api/doc.html
```

主要接口分组：

| 模块 | 路径前缀 | 核心接口 |
| :--- | :--- | :--- |
| 应用 | `/api/app` | `POST /add` 创建 · `GET /chat/gen/code` 流式生成（SSE） · `POST /deploy` 部署 · `GET /download/{appId}` 下载 ZIP · `POST /good/list/page/vo` 精选 |
| 用户 | `/api/user` | `POST /register` · `POST /login` · `GET /get/login` 当前用户 |
| 对话 | `/api/chatHistory` | `POST /list/my/page` 我的对话游标分页 |
| 静态 | `/api/static/**` | 部署产物访问 |
| 监控 | `/api/actuator/{health,info,prometheus}` | 健康 / 指标 |

---

## 配置说明

主配置文件 `src/main/resources/application.yml` 关键项：

```yaml
server:
  port: 9025
  servlet:
    context-path: /api

spring:
  mvc.async.request-timeout: 600000   # SSE 流式接口异步超时：10 分钟
  session:
    store-type: redis
    timeout: 2592000                  # 30 天

langchain4j:
  open-ai:
    chat-model:                       # 主生成
      base-url: https://api.deepseek.com
      model-name: deepseek-chat
    streaming-chat-model:             # 流式生成
    reasoning-streaming-chat-model:   # 推理任务
    routing-chat-model:               # 路由分类（Qwen-Turbo）
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1

rag:
  enabled: true
  templates-dir: D:/ai-app-generation/embed_text   # 模板库根目录
  ingest.enabled: false                            # 模板入库开关（手动触发）
  pgvector: { host: localhost, port: 5432, database: ai_codegen_rag }
  embedding: { model-name: text-embedding-v4, dimension: 1024 }
  retrieval: { top-k: 3, min-score: 0.30 }
  rerank: { enabled: true, model-name: gte-rerank-v2, top-n: 10 }
  prompt: { max-context-chars: 4000 }
```

---

## 生产部署

完整部署文档：[`prod/README.md`](./prod/README.md)

### 一键部署（Docker Compose）

```bash
# 1. 本地打包前后端产物到 prod/artifacts/
.\prod\build-artifacts.ps1

# 2. 上传 prod 目录到服务器（例如 /opt/ai-app-generation/prod）

# 3. 服务器进入 prod 目录
cd /opt/ai-app-generation/prod
cp .env.example .env
# 编辑 .env 填入 API Key、数据库密码

# 4. 一键启动
docker compose --env-file .env up -d
```

### 端口映射

| 服务 | 容器端口 | 主机端口（默认） | 用途 |
| :--- | :--- | :--- | :--- |
| Nginx | 100 | `${NGINX_HOST_PORT}` 100 | 业务总入口（前端 + 部署产物） |
| Backend | 9025 | `${BACKEND_HOST_PORT}` 9025 | Spring Boot API |
| MySQL | 3306 | - | 业务数据 |
| Redis | 6379 | - | Session / 缓存 |
| PostgreSQL | 5432 | - | RAG 向量库 |
| Prometheus | 9090 | `${PROMETHEUS_HOST_PORT}` 9090 | 指标采集 |
| Grafana | 3000 | `${GRAFANA_HOST_PORT}` 3000 | 可视化看板 |

### 健康检查

```bash
docker compose ps
docker compose logs -f backend
curl http://localhost:9025/api/actuator/health
```

---

## 监控与可观测性

预置 Grafana 看板 `prod/grafana/dashboards/ai-model-observability-dashboard.json`，开箱即用：

- 模型调用 QPS / 平均延迟 / P99
- Token 消耗（输入 / 输出）
- 工具调用次数 / 成功率
- RAG 检索命中率 / 重排耗时
- JVM / HikariCP / Redis 连接池

访问：<http://localhost:3000>（默认账号 `admin / lyw666`，请上线前修改）

---

## 路线图

- [x] 三种代码生成模式（HTML / 多文件 / Vue）
- [x] AI 智能路由
- [x] RAG 模板检索 + Rerank
- [x] 流式 SSE + 工具调用流解析
- [x] 图片采集 Agent（4 工具并行）
- [x] Prompt 安全护栏
- [x] 一键部署 + 代码下载
- [x] Docker Compose 全栈部署 + Grafana 监控
- [ ] 多模型支持（接入 Claude、GPT-4、月之暗面等）
- [ ] 应用模板市场（社区共享）
- [ ] 在线协作编辑
- [ ] 端到端 E2E 测试覆盖

---

## 参与贡献

1. Fork 本仓库
2. 新建特性分支：`git checkout -b feat/your-feature`
3. 提交代码（遵循 [约定式提交](https://www.conventionalcommits.org/zh-hans/)）：
   - `feat:` 新功能
   - `fix:` Bug 修复
   - `refactor:` 重构
   - `docs:` 文档
   - `chore:` 杂项
4. 推送分支并提交 Pull Request

> 提交代码前请确保通过 `mvn clean verify` 与前端 `npm run lint`。

---

## 许可证

本项目采用 [MIT License](LICENSE)。

---

## 致谢

本项目站在以下优秀开源项目的肩膀上：

- [Spring Boot](https://spring.io/projects/spring-boot)
- [LangChain4j](https://docs.langchain4j.dev/) · 强大的 Java AI 编排框架
- [LangGraph4j](https://github.com/bsorrentino/langgraph4j) · Agent 工作流
- [MyBatis-Flex](https://mybatis-flex.com/) · 优雅的 ORM
- [Vue.js](https://vuejs.org/) · 渐进式前端框架
- [Ant Design Vue](https://antdv.com/) · 企业级 UI 组件
- [pgvector](https://github.com/pgvector/pgvector) · PostgreSQL 向量扩展
- [DeepSeek](https://www.deepseek.com/) · 高性价比代码生成模型
- [阿里云 DashScope](https://dashscope.aliyun.com/) · 通义千问 / Embedding / Rerank
- [Hutool](https://hutool.cn/) · Java 工具集

---

<div align="center">

**如果这个项目对你有帮助，欢迎 Star 支持一下！**

Made with ♥ by [@lywynl](https://gitee.com/lywynl)

</div>
