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
| Agent 工具调用 | 工具注册层提供 5 个文件工具、`buildProject`、`exit`；在线 Vue 使用“5 个文件工具 + `buildProject`”显式白名单，离线首次生成评测使用“5 个文件工具 + `exit`” |
| 受控 ReAct 构建修复 | Vue 生成、真实构建和最多两个失败处理阶段位于同一 SSE 回合；最多执行 3 次真实构建，任意一次成功或第三次失败后由后端强制结束 |
| 图片采集 Agent | 首条消息自动调度 `Pexels 图片搜索` + `阿里 wan2.2 Logo 生成` + `Mermaid 流程图` + `unDraw 插画` 4 类工具，并行收集封面/Logo/插图素材 |
| 流式 SSE | Reactor `Flux<ServerSentEvent>` + 自定义工具调用流解析器（字符级状态机） |
| Prompt 安全护栏 | 注解 `@PromptSafetyCheck` + AOP 切面 + LangChain4j `InputGuardrail` 双重拦截 |
| 一键部署 | `deployKey` 标识 + Nginx 静态托管，生成产物即时可访问 |
| 代码下载 | 生成目录打包为 ZIP，支持完整工程导出 |
| 网页截图 | Selenium + Chromium + WebDriverManager 容器内自动截屏，封面同步上传腾讯云 COS |
| 应用市场 | 精选作品（priority 排序）+ 我的应用 + 管理员后台，分页缓存 + 游标查询 |
| 分层对话记忆 | L0 Redis 热窗口（100 条 + tool 对成对驱逐）· L1 App 级 5 段滚动摘要 · L2 跨 App 用户偏好抽取，三层 best-effort 降级 |
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
│ │  │   └─ Code Generator     │──── DeepSeek-V4-Flash（主生成）
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
                                  │                         ▲
                                  ▼                         │
                          [文件工具落盘 → buildProject] ────┤
                                  │                         │
                      ┌───────────┴───────────┐             │
                      ▼                       ▼             │
                  [构建成功]             [构建失败诊断] ────┘
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

> **安全要求**：数据库、Redis、PgVector、COS 和模型密钥必须通过环境变量或秘密管理平台注入。不要在受版本控制的配置、README、命令历史或日志中保存秘密字面量；已暴露的凭据需要轮换，不能只修改配置文本。

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
│   │   ├── guardrail/                          # Prompt 安全护栏
│   │   │   ├── PromptSafetyValidator.java
│   │   │   ├── PromptSafetyRules.java
│   │   │   ├── PromptSafetyInputGuardrail.java # LangChain4j InputGuardrail
│   │   │   ├── annotation/PromptSafetyCheck.java
│   │   │   └── aspect/PromptSafetyAspect.java  # AOP 切面
│   │   └── memory/                             # 分层对话记忆（L0/L1/L2）
│   │       ├── LayeredChatMemory.java          # 装饰器：messages() 拼三层，add/clear/id 委托 delegate
│   │       ├── MemorySummaryPromptBuilder.java # L1 五段摘要 Prompt
│   │       └── UserPreferencePromptBuilder.java # L2 偏好抽取 Prompt
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
│   │   ├── MemorySummaryService                # L1 App 级滚动摘要（抽取 + 召回）
│   │   ├── UserMemoryService                   # L2 跨 App 用户偏好（抽取 + 召回）
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
│   │   ├── RedisChatMemoryStoreConfig          # L0 对话记忆 Redis 存储
│   │   ├── MemorySummarizationExecutorConfig   # L1/L2 记忆后台线程池（DiscardPolicy）
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

Vue 工程不能用一次性 chat 生成（结构复杂、文件多），改用 LangChain4j `TokenStream` + 后端受控 ReAct。模型负责生成、诊断和调用工具；构建次数、文件修改权限、循环上限、取消、超时和终态由后端状态机控制，不能依赖 Prompt 自觉退出。

| 工具 | 作用 |
| :--- | :--- |
| `FileWriteTool` | 创建/覆盖文件 |
| `FileReadTool` | 读取已生成文件内容 |
| `FileModifyTool` | 局部修改（避免整文件重写） |
| `FileDeleteTool` | 删除文件 |
| `FileDirReadTool` | 列出工程目录 |
| `BuildProjectTool` | 在线 Vue 执行真实安装与构建，并返回 `vue-build-tool/v1` 结构化诊断 |
| `ExitTool` | 仅用于离线首次生成评测；在线 Vue 不暴露该工具 |

**工具白名单不是“所有工具都可用”**：在线 Vue 只使用 5 个文件工具和 `buildProject`；离线首次生成评测只使用 5 个文件工具和 `exit`，避免在线自动修复污染首次生成质量指标。HTML 与 `MULTI_FILE` 保持原有生成协议，不接入 Vue 构建修复状态机。

**构建状态机**：

| 已完成构建 | 结果 | 文件 mutation 权限 | 下一动作 |
| :---: | :--- | :--- | :--- |
| 第 1 次 | 成功 | 关闭 | 立即结束工具循环 |
| 第 1 次 | `CODE` 失败 | 允许 | 进行最小代码修复后再构建 |
| 第 1 次 | `DEPENDENCY` / `INFRASTRUCTURE` 失败 | 禁止 | 不改业务文件，直接重试构建 |
| 第 2 次 | 成功 | 关闭 | 立即结束工具循环 |
| 第 2 次 | 任意失败 | 仅 `CODE` 允许 | 进入最终根因诊断，再执行最后一次构建 |
| 第 3 次 | 成功 | 关闭 | 立即结束工具循环 |
| 第 3 次 | 任意失败 | 关闭 | 固定失败终态并强制结束，不再请求模型 |

- 真实构建最多 3 次；`BUILD_IN_PROGRESS`、作用域拒绝、CODE 失败后缺少新 mutation 等未真正开始构建的调用不消耗构建次数。
- CODE 失败后，只有新的 `file-tool/v1 APPLIED && changed=true` 才解锁下一次真实构建；重复内容、失败或取消不算修复。
- 非代码故障时，`writeFile`、`modifyFile`、`deleteFile` 在路径解析和文件副作用前被拒绝，读取和 `buildProject` 仍可使用。
- 第二次失败后的“最终诊断”是一个状态机阶段，不等于只允许一次模型请求或一次 `modifyFile`。
- 单轮模型请求和工具执行分别最多 64 次；同时活跃 Vue 回合最多 64 个，第 65 个快速拒绝，防止非构建工具形成无限循环。

**流式工具调用解析器**：`ai/parser/ToolRequestStreamParser.java`

每个 tool call id 维护独立的字符级状态机，处理 LLM 流式吐出参数 JSON 时的字段级 delta 推送（`KEY_READY` → `DELTA` → `VALUE_READY`），让前端实时渲染“正在写入或修改哪个文件”。`writeFile.content`、`modifyFile.oldContent/newContent` 只通过受预算 DELTA 展示；完成事件不会重复携带完整代码。默认预算为：单文件 128,000 code point、单轮累计 mutation 256,000、稳定 AI 文本 384,000、单次文件读取 128,000、目录读取 20,000。超限在写盘、广播或落库前受控终止。

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
POST /api/app/chat/gen/code
Content-Type: application/json; charset=UTF-8
Accept: text/event-stream

{"appId":"1","message":"做一个待办应用"}

data: {"d":"<!DOCTYPE html>"}
data: {"d":"<html>"}
event: turn-outcome
data: {"protocol":"vue-turn/v1","outcome":"SUCCEEDED","message":"项目已生成并构建成功。","refreshPreview":true}
event: done
data:
```

若 User 尚未提交就被参数、鉴权或业务门禁拒绝，则不发送正文和 `turn-outcome`，改为：

```text
event: business-error
data: {"protocol":"generation-error/v1","kind":"BUSINESS","code":40000,"message":"..."}
event: done
data:
```

- 请求正文使用 JSON，`appId` 保持字符串，用户消息不进入 URL 和访问日志
- 原始请求体在 Jackson 反序列化前受 **262,144 字节**硬上限保护；反序列化后的用户消息再受 **32,000 Unicode code point** 业务上限保护
- 默认 `message` 事件用 `{"d": "片段"}` 包装；工具消息也走该正文通道
- 命名事件只允许 `heartbeat`、`business-error`、`turn-outcome`、`done`；未知命名事件属于协议错误，不能退化为聊天正文
- `turn-outcome` 是已提交 Vue 回合的唯一业务终态，随后发送唯一 `done`；`done` 只表示 SSE 传输正常收尾，不表示业务成功
- `business-error` 只承载 User 提交前的安全错误，并以 `done` 结束
- 前端只有在 `turn-outcome.outcome=SUCCEEDED` 且收到 `done` 后刷新预览；失败、取消、超时、系统错误和协议错误都保留旧预览
- 心跳每 15 秒发送一次。Vue 回合是从操作租约领取开始计算的 1,800 秒绝对截止，不会因持续输出 token 而重置；Spring MVC 异步超时为 1,845 秒，Nginx 读写超时为 1,860 秒，给取消和终态持久化留出收尾余量
- 生成 POST 只消费 JSON，并使用显式可信 Origin；HTTP 413、其他非 2xx 或错误的 SSE `Content-Type` 由前端转换为固定安全文案，不显示代理响应正文

### 7. 分层对话记忆（L0 / L1 / L2）

**模块**：`ai/memory/`（`LayeredChatMemory` / `MemorySummaryPromptBuilder` / `UserPreferencePromptBuilder`）、`service/{MemorySummaryService, UserMemoryService}`、`config/{RedisChatMemoryStoreConfig, MemorySummarizationExecutorConfig}`

零代码生成是**多轮、长周期**的：用户会反复说"换个配色""刚才那版不要了""我习惯用 Vue3 + TS"。每轮都把全部历史塞给模型会让 Token 爆掉；只留最近几条又会丢掉早期的关键约束。为此项目实现了一套**三层记忆**，在"上下文完整"与"Token 可控"之间取平衡 —— 设计参考了 Claude Code 的会话记忆机制（`sessionMemory.ts` 的衰减分层、`extractMemories.ts` 的游标抽取、5 段摘要模板、连续失败熔断阈值）。

```
LayeredChatMemory.messages() 每轮拼装后送入大模型：

  ┌─ L2 跨 App 用户偏好 ──────┐  "该用户的通用偏好"（语言 / 视觉风格 / 技术栈 / 交互习惯）
  │   (UserMessage, AiMessage) │   ← 跨应用，源自 app_memory
  ├─ L1 本 App 滚动摘要 ──────┤  "本应用早期对话的 5 段摘要"
  │   (UserMessage, AiMessage) │   ← 单应用，源自 app_memory_summary
  ├─ L0 热窗口 ───────────────┤  执行期含 user / ai / tool；Vue 终态折叠本轮工具尾部
  │   delegate.messages()      │   ← Redis，MessageWindowChatMemory
  └────────────────────────────┘
```

**L0 · 原始热窗口**（`RedisChatMemoryStore` + LangChain4j `MessageWindowChatMemory`）

- 按 `appId` 持久化到 Redis（TTL `3600s`），窗口上限 **100 条消息**，满后由 `MessageWindowChatMemory` 内置逻辑裁剪，并保证 **tool 调用 / 结果成对驱逐**（不留孤儿 tool 消息把模型搞崩）
- 冷启动（应用重启 / 缓存过期）时，`loadChatHistoryToMemory(appId, delegate, 20)` 从 MySQL 回填最近 20 条原文重建窗口
- 在线 Vue 的 ReAct 执行期会临时保留模型消息、工具请求和工具结果，供下一次模型调用继续诊断。唯一终态收尾器定位本轮最后一条 User，删除其后的原始 AI/tool 尾部，再追加一条 `AiMessage(canonicalAiText)`；同一个不可变 `canonicalAiText` 同时写入 MySQL `chat_history`，因此修复后的真实落盘代码和精简构建轨迹可在刷新后恢复，而错误工具尾部不会长期占据 L0
- `canonicalAiText` 中的代码变更只接受受信 `file-tool/v1 APPLIED && changed=true` 证明已真实落盘的内容。构建错误摘要只进入当前模型工具结果和实时构建卡片；原始 npm 日志只进入专用服务端日志；`readFile/readDir` 正文只进入当前模型工具结果，前端完成事件保留安全状态元数据但固定 `content=null`
- L0 折叠无法确认成功时，系统清除不可信的 Redis/Caffeine L0，下一轮从 MySQL 冷重建，避免残留工具消息或读取正文继续参与上下文
- 当前首轮 RAG/图片增强后的 `UserMessage` 仍可能进入 L0，直到窗口淘汰、显式清理或 MySQL 冷重建。当前 L0 仍按 **100 条消息**而非 token 裁剪；本次 ReAct 改造没有改变这一算法

**L1 · App 级滚动摘要**（`MemorySummaryService` + `app_memory_summary`，每 App 一行）

- **何时**：对话结束钩子异步触发，新增满 **8 条** 才提炼（避免高频小提炼），单次最多并入 60 条
- **摘要什么**：`MemorySummaryPromptBuilder` 用 **5 段固定模板**（应用目标与定位 / 用户偏好与硬约束 / 已否决的方案 / 关键设计决策与理由 / 当前进度速览），预算约 1800 token。铁律是**只摘"从当前代码状态推导不出来"的信息** —— 已生成的代码持久在 `vue_project_<appId>` 可随时读取，绝不复述进摘要
- **怎么滚动**：`lastSummarizedId` 游标（指向 `chat_history.id`）+ 旧摘要增量合并，**成功才推进游标**
- **读取**：`getCurrentSummary` 先查 Redis 缓存（`mem:summary:{appId}`，堵住工具循环里的 N+1 次 MySQL 读），未命中回查 MySQL 并 write-through 回填

**L2 · 跨 App 用户偏好**（`UserMemoryService` + `app_memory` / `app_memory_extract_cursor`）

- **解决什么**：L1 只在单个应用内有效；但"喜欢深色极简""技术栈用 Vue3 + TS"这类偏好，对该用户的**每个**应用都成立。L2 把它们抽出来，在用户新建 / 打开任意应用时带出
- **抽取**：对话结束异步触发，模型输出**结构化 JSON 偏好数组**，按半封闭类别（语言偏好 / 视觉风格 / 技术栈倾向 / 交互习惯 / 其他）归类，按 `(userId, type, name)` **去重 upsert**（同类只更新内容）。Prompt 明确**排除"应用特有需求"**，杜绝与 L1 重叠
- **召回**：`recallByApp(appId)` → `appId → userId` 反查（进程内 `ConcurrentHashMap` 缓存，归属永不变）→ 取 top-10 最新偏好 → Redis 缓存（`mem:pref:{userId}`），偏好变更时主动失效

**装饰器，而非文本切片**：`LayeredChatMemory implements ChatMemory`，**包裹** `MessageWindowChatMemory`，只重写 `messages()` 做三层拼接，`add / clear / id` 全部委托 delegate。这样 L0 的 Redis 持久化、窗口裁剪、tool 对成对驱逐全部复用，装饰器自身不存储、不裁剪。每层在 `messages()` 里各自前置一对 `(UserMessage, AiMessage)`，既给摘要 / 偏好加上"这是背景、不是新指令"的引导语，又保证 user / ai 严格交替（LangChain4j 的硬约束）。

**关键决策与权衡**：

- **全链路 best-effort**：L1 / L2 的提炼跑在共享后台线程池（core 5 / max 10 / queue 200，**队列满直接 `DiscardPolicy` 丢弃**），失败则 `failCount++` 且游标不前进，连续失败 ≥ 3 触发 **circuit breaker** 暂停 —— 任意一层挂掉都自动降级回纯 L0，绝不阻塞或拖慢主对话流
- **零竞态**：异步线程只读 `chat_history`、只写各自的记忆表（L1 写 `app_memory_summary`，L2 写 `app_memory` / 游标表），不碰 delegate 与 Redis 窗口；L1 按 `appId`、L2 按 `userId` 各自 single-flight 去重
- **摘要 ≠ 压缩**：L1 在第 4 轮（满 8 条）就可能生成，但 L0 要累计到 100 条才真正驱逐。早期摘要与原文**双重存在**是有意的安全冗余 —— 等窗口满时，L1 的价值才从"冗余"变成"唯一上下文来源"
- **构建信息边界**：L1 只吸收仍有效的应用目标、硬约束、已否决方案和关键设计决策；L2 只提取跨应用稳定偏好。两层都排除构建错误、原始日志、读取正文和一次性修复轨迹，并且只在 MySQL/L0 稳定终态闭合后异步触发
- **当前按消息条数（100）而非 token 裁剪**：HTML / 多文件无工具循环、每轮约 +2 条（100 条 ≈ 50 轮，偏宽松），Vue 工程含工具循环、每轮可达几十条（偏紧）。这是已知权衡，后续可演进为 token 感知或按 `CodeGenType` 分档

### 8. 数据库设计

**MySQL 业务库** `ai_app_generation`：

| 表 | 关键字段 | 索引设计 |
| :--- | :--- | :--- |
| `user` | userAccount(uk) / userRole / userAvatar | `uk_userAccount` 唯一，`idx_userName` 提速搜索 |
| `app` | initPrompt / codeGenType / deployKey(uk) / priority / userId | `uk_deployKey` 保证部署标识唯一，`idx_userId` 加速我的列表 |
| `chat_history` | message / messageType (user/ai) / appId / userId | **`idx_appId_createTime` 联合索引** —— 游标分页核心 |
| `app_memory_summary` | summary(MEDIUMTEXT 5 段) / lastSummarizedId(游标) / summaryTokens / failCount | `uk_appId` 每应用一行 —— **L1 滚动摘要** |
| `app_memory` | userId / type(USER_PREFERENCE) / name(类别) / content / appId(溯源) | `uk_userId_type_name` 偏好去重键 —— **L2 跨 App 偏好** |
| `app_memory_extract_cursor` | appId / userId / lastExtractedId(游标) / failCount | `uk_appId` 每应用一行 —— **L2 抽取游标** |

> **记忆存储分工**：L0 窗口本身存于 **Redis**（`MessageWindowChatMemory`）；Vue 每轮原始可见 User 与折叠后的 `canonicalAiText` 同时写入 MySQL `chat_history`，作为刷新回放和 L0 冷重建的稳定来源。L1 / L2 落 **MySQL** 上述三表，并各带一层 Redis 缓存（`mem:summary:{appId}` / `mem:pref:{userId}`，TTL 1h）堵住工具循环内的高频读。

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
| 应用 | `/api/app` | `POST /add` 创建 · `POST /chat/gen/code` 流式生成（SSE） · `POST /deploy` 部署 · `GET /download/{appId}` 下载 ZIP · `POST /good/list/page/vo` 精选 |
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
  mvc.async.request-timeout: 1845000  # SSE 运输层：30 分 45 秒
  session:
    store-type: redis
    timeout: 2592000                  # 30 天

langchain4j:
  open-ai:
    chat-model:                       # 主生成
      base-url: https://api.deepseek.com
      model-name: deepseek-v4-flash
    streaming-chat-model:             # 流式生成
    reasoning-streaming-chat-model:   # 推理任务
    routing-chat-model:               # 路由分类（Qwen-Turbo）
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1

rag:
  enabled: true
  templates-dir: ${RAG_TEMPLATES_DIR:embed_text}   # 默认读取项目根目录，可用环境变量覆盖
  ingest.enabled: false                            # 模板入库开关（手动触发）
  pgvector: { host: localhost, port: 5432, database: ai_codegen_rag }
  embedding: { model-name: text-embedding-v4, dimension: 1024 }
  retrieval: { top-k: 3, min-score: 0.30 }
  rerank: { enabled: true, model-name: gte-rerank-v2, top-n: 10 }
  prompt: { max-context-chars: 4000 }

ai:
  vue:
    tool-budget:
      max-single-file-code-points: 128000
      max-cumulative-mutation-code-points: 256000
      max-canonical-ai-text-code-points: 384000
      max-read-file-code-points: 128000
      max-read-dir-code-points: 20000
```

Vue 业务层使用 30 分钟绝对截止，Spring 的 30 分 45 秒用于终态收尾；生产 Nginx 对精确生成路径设置 `client_max_body_size 272k`，读写超时均为 31 分钟。三层顺序必须保持“业务 < Spring < Nginx”，不能把 Reactor 空闲超时当作绝对回合时限。

---

## 生产部署

完整部署文档：[`prod/README.md`](./prod/README.md)

> **当前发布边界**：Vue 受控 ReAct 构建只能用于**单实例、可信用户和可信项目源码的内测**，不能直接开放给不可信公网用户。当前构建进程仍与主后端共享权限，`AppOperationLeaseManager` 也是单 JVM 进程内租约。公开生产前必须把构建迁移到独立非 root 执行环境，并限制可写目录、CPU、内存、PID、总时限、出站网络、日志速率和磁盘；多实例部署前必须改为带 owner token、TTL、续租和 fencing token 的 Redis 分布式租约。

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

访问：<http://localhost:3000>。管理员账号和密码必须通过环境变量或秘密管理平台配置，不在文档或版本库中提供默认秘密。

### Vue 构建修复指标

受控 ReAct 增加了低基数业务指标，标签不包含 `appId`、`userId`、`turnId`、路径、错误摘要或日志正文：

| 指标 | 主要标签 | 含义 |
| :--- | :--- | :--- |
| `vue_build_attempts_total` | `attempt/result/stage/failure_kind` | 每次真实构建的唯一结果 |
| `vue_build_attempt_duration_seconds` | `attempt/result/stage` | 真实构建耗时直方图 |
| `vue_turn_outcomes_total` | `outcome/phase` | Vue 回合唯一业务终态 |
| `vue_turn_admissions_total` | `result` | 64 个全局准入许可的领取、拒绝和释放 |
| `vue_turn_cancellations_total` | `trigger/result` | 取消请求、完成、超时和失败 |
| `vue_memory_l0_sync_total` | `action/result` | L0 折叠、失效和冷重建结果 |
| `app_operations_total` | `operation/result/conflict_with` | 生成、部署、下载、删除的统一 app 租约结果 |
| `generation_sse_protocol_results_total` | `result/error_kind` | SSE 控制协议结果 |
| `generation_sse_publisher_terminations_total` | `result` | Reactor 发布流完成、订阅取消或发布异常 |

以下查询先使用 15 分钟窗口，低流量时再用 30 分钟确认。指标名已由项目中的 `PrometheusMeterRegistry.scrape()` 自动化测试锁定；部署后仍需从真实 `/api/actuator/prometheus` 核验采集、重标记和告警链路。

```promql
# 第 1 次构建成功率
sum(rate(vue_build_attempts_total{attempt="1",result="succeeded"}[15m]))
/
clamp_min(sum(rate(vue_build_attempts_total{attempt="1"}[15m])), 1e-9)
```

```promql
# 第一次修复阶段条件成功率
sum(rate(vue_build_attempts_total{attempt="2",result="succeeded"}[15m]))
/
clamp_min(sum(rate(vue_build_attempts_total{attempt="2"}[15m])), 1e-9)
```

```promql
# 最终诊断阶段条件成功率
sum(rate(vue_build_attempts_total{attempt="3",result="succeeded"}[15m]))
/
clamp_min(sum(rate(vue_build_attempts_total{attempt="3"}[15m])), 1e-9)
```

```promql
# 第三次构建最终失败占全部初始构建的比例
sum(rate(vue_build_attempts_total{attempt="3",result="failed"}[15m]))
/
clamp_min(sum(rate(vue_build_attempts_total{attempt="1"}[15m])), 1e-9)
```

```promql
# 各 attempt 构建耗时 P95
histogram_quantile(0.95,
  sum by (le, attempt) (
    rate(vue_build_attempt_duration_seconds_bucket[15m])
  )
)
```

```promql
# Vue 全局准入拒绝率
sum(rate(vue_turn_admissions_total{result="rejected"}[15m]))
/
clamp_min(sum(rate(vue_turn_admissions_total{result=~"acquired|rejected"}[15m])), 1e-9)
```

```promql
# 同 app 操作拒绝率
sum(rate(app_operations_total{result="rejected"}[15m]))
/
clamp_min(sum(rate(app_operations_total[15m])), 1e-9)
```

```promql
# Vue 取消完成率
sum(rate(vue_turn_cancellations_total{result="completed"}[30m]))
/
clamp_min(sum(rate(vue_turn_cancellations_total{result="requested"}[30m])), 1e-9)
```

```promql
# L0 同步失败次数
sum(increase(vue_memory_l0_sync_total{result="failed"}[15m]))
```

```promql
# SSE 控制协议异常率
sum(rate(generation_sse_protocol_results_total{result="protocol_error"}[15m]))
/
clamp_min(sum(rate(generation_sse_protocol_results_total[15m])), 1e-9)
```

```promql
# 生成回合提交前系统降级率
sum(rate(generation_sse_protocol_results_total{
  result="business_error",error_kind="system"
}[15m]))
/
clamp_min(sum(rate(generation_sse_protocol_results_total[15m])), 1e-9)
```

```promql
# SSE 发布链错误率；不等同于 socket 写出失败率
sum(rate(generation_sse_publisher_terminations_total{result="publisher_error"}[15m]))
/
clamp_min(sum(rate(generation_sse_publisher_terminations_total[15m])), 1e-9)
```

### 构建日志、灰度与回滚

- 原始 npm 输出只写入 `${VUE_BUILD_LOG_DIR:-logs/vue-build}/raw-build.log`，专用 logger 设置 `additivity=false`，单文件 10MB、保留 14 天、总量 1GB；业务 SSE 和稳定记忆只使用脱敏、截断后的错误摘要
- 任意 15 分钟出现协议错误、取消失败或 L0 失效/重建失败，立即停止扩大灰度；确认隐私泄漏、第 4 次真实构建或删除后晚写时直接回滚
- 第 1 次构建至少 20 个样本后，最终失败比例超过 10%、SSE 发布链错误率超过 5%，或同 app 拒绝率较同流量基线上升 2 倍，停止扩大灰度
- 构建 P95 先采集小流量 24 小时基线；超过同 attempt 基线 1.5 倍时告警，不用任意固定秒数代替容量基线
- Nginx 499/5xx 必须按 `/api/app/chat/gen/code` 单独聚合。`publisher_error` 只表示应用发布链错误，不能冒充代理断连或真实 socket 写出失败
- POST 请求、前置字节门禁、`vue-build-tool/v1`、`turn-outcome -> done` 和前端解析器属于同一兼容单元，必须前后端成对回滚；不能只回滚一侧
- 回滚构建工具时必须同时移除在线 `buildProject` 白名单和强制终止协议；回滚依赖复用前先安全清理服务生成的依赖状态标记与 `node_modules`
- 本次没有新增数据库表或字段，不需要数据库回滚脚本；但 MySQL、Redis、Caffeine 和文件系统之间没有跨资源事务，进程在删除步骤间崩溃仍需要人工检查残留

---

## 路线图

- [x] 三种代码生成模式（HTML / 多文件 / Vue）
- [x] AI 智能路由
- [x] RAG 模板检索 + Rerank
- [x] 流式 SSE + 工具调用流解析
- [x] 图片采集 Agent（4 工具并行）
- [x] Prompt 安全护栏
- [x] 分层对话记忆（L0 Redis 热窗口 / L1 App 级摘要 / L2 跨 App 用户偏好）
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
