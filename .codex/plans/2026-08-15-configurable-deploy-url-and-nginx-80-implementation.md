# 可配置部署 URL 与 Nginx 80 端口统一实施计划

> **执行要求：** 使用测试驱动方式实施；每项生产行为先由失败测试锁定，再写最小实现。

**目标：** 由后端统一返回基于运行时配置生成的完整部署 URL，前端不再拼接部署域名，并将开发、生产 Nginx 全部统一为 80 端口且能直接托管部署产物。

**架构：** MySQL 继续只保存 `deployKey`；Spring 通过必填的 `APP_CODE_DEPLOY_BASE_URL` 绑定并校验部署基地址，统一 URL 构建器同时服务部署接口和 `AppVO`。前端只消费 `deployUrl`；开发和生产 Nginx 在 80 端口按部署标识读取 `code_deploy` 目录。

**技术栈：** Java 25、Spring Boot 3.5.4、JUnit 5、Vue 3、TypeScript、Vitest、Nginx 1.27、Docker Compose。

## 全局约束

- Java、YAML 和前端源码不得写死 `localhost`、`lllyw.cn` 或其他部署公网基地址。
- `APP_CODE_DEPLOY_BASE_URL` 缺失或格式非法时，后端启动失败，不静默回退。
- 数据库不新增 `deployUrl` 字段，历史应用通过 `deployKey` 动态获得当前环境 URL。
- 所有 Nginx 对外端口和容器监听端口固定为 80，不保留其他端口或 `NGINX_HOST_PORT`。
- 不推送远程；所有文件保持 UTF-8。

---

### 任务 1：锁定后端配置和 URL 构建契约

**文件：**

- 新增：`src/test/java/com/lyw/appgeneration/config/AppCodeDeployPropertiesTest.java`
- 新增：`src/test/java/com/lyw/appgeneration/service/AppDeployUrlBuilderTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/service/impl/AppServiceDeploymentLifecycleTest.java`
- 新增：`src/test/java/com/lyw/appgeneration/service/impl/AppServiceAppVoTest.java`

- [x] 测试部署基地址必须显式配置，且仅接受无用户信息、查询参数和片段的 HTTP/HTTPS URL。
- [x] 测试基地址末尾斜杠归一化、业务路径拒绝和部署标识路径段编码。
- [x] 测试部署接口与 `AppVO.deployUrl` 使用同一个 URL 构建器，未部署应用返回空 URL。
- [x] 使用工作区 JDK 25 运行目标测试，确认因缺少新类型或字段而 RED。

### 任务 2：实现后端唯一部署 URL 来源

**文件：**

- 新增：`src/main/java/com/lyw/appgeneration/config/AppCodeDeployProperties.java`
- 新增：`src/main/java/com/lyw/appgeneration/service/AppDeployUrlBuilder.java`
- 修改：`src/main/java/com/lyw/appgeneration/constants/AppConstant.java`
- 修改：`src/main/java/com/lyw/appgeneration/model/vo/app/AppVO.java`
- 修改：`src/main/java/com/lyw/appgeneration/service/impl/AppServiceImpl.java`
- 修改：`src/main/java/com/lyw/appgeneration/controller/AppController.java`
- 修改：`src/main/resources/application.yml`
- 新增：`src/test/resources/application.properties`

- [x] 用 `@ConfigurationProperties(prefix = "app.code.deploy")` 绑定 `base-url` 并在初始化阶段规范化校验。
- [x] 用 `AppDeployUrlBuilder.buildUrl(String deployKey)` 统一生成完整 URL。
- [x] 删除 `AppConstant.CODE_DEPLOY_HOST`，部署成功和 `AppVO` 转换均调用统一构建器。
- [x] 精选应用缓存键加入部署基地址命名空间，切换环境后不命中旧 URL 缓存。
- [x] 重跑后端目标测试，确认 GREEN。

### 任务 3：前端只消费后端 `deployUrl`

**文件：**

- 新增：`ai-app-generation-frontend/src/config/deployUrlContract.test.ts`
- 修改：`ai-app-generation-frontend/src/api/typings.d.ts`
- 修改：`ai-app-generation-frontend/src/pages/HomePage.vue`
- 修改：`ai-app-generation-frontend/src/components/AppCard.vue`
- 修改：`ai-app-generation-frontend/src/pages/app/AppEditPage.vue`
- 修改：`ai-app-generation-frontend/src/config/env.ts`
- 修改：`ai-app-generation-frontend/src/config/env.example.ts`
- 修改：`ai-app-generation-frontend/.env.development`

- [x] 先用静态契约测试锁定 `AppVO.deployUrl`、所有打开作品入口及部署域名配置删除行为并确认 RED。
- [x] 首页、卡片和应用详情只读取 `app.deployUrl`；部署成功弹窗继续使用部署接口返回的完整字符串。
- [x] 删除 `DEPLOY_DOMAIN`、`getDeployUrl` 和 `VITE_DEPLOY_DOMAIN`。
- [x] 运行前端测试、类型检查和生产构建，确认 GREEN。

### 任务 4：统一开发和生产 Nginx 80 端口

**文件：**

- 新增：`src/test/java/com/lyw/appgeneration/config/DeploymentRuntimeConfigTest.java`
- 修改：`dev/docker-compose.yml`
- 修改：`dev/nginx/nginx.conf`
- 修改：`prod/docker-compose.yml`
- 修改：`prod/nginx/nginx.conf`
- 修改：`prod/docker/Dockerfile.nginx`
- 修改：`prod/.env`
- 修改：`prod/.env.example`
- 修改：`prod/build-artifacts.ps1`
- 修改：`prod/README.md`
- 修改：`README.md`

- [x] 先测试开发 Nginx 挂载主机 `tmp/code_deploy` 并具备部署标识静态路由。
- [x] 先测试生产 Nginx、Dockerfile、Compose 全部固定为 80，且部署配置仅来自 `APP_CODE_DEPLOY_BASE_URL`。
- [x] 开发 Nginx 在 API/Vite 路由之前增加部署目录匹配；生产 Nginx 改为 `listen 80` 和通用 `server_name _`。
- [x] 删除 `NGINX_HOST_PORT`、`APP_CODE_DEPLOY_HOST` 和 `VITE_DEPLOY_DOMAIN`，更新构建脚本与文档。
- [x] 使用 `docker compose config`、容器内 `nginx -t` 和真实静态文件请求验证配置。

### 任务 5：全量回归、审查与提交

- [x] 使用 JDK 25 运行 `bash ./mvnw test`。
- [x] 运行前端 `npm test` 和 `npm run build`。
- [x] 运行 `git diff --check`，并搜索部署链路中遗留的旧 Nginx 端口和旧变量。
- [x] 按正确性、可读性、架构、安全和性能五轴审查差异并修复问题。
- [x] 使用全中文提交信息准备本次改造，并确认不推送远程。
