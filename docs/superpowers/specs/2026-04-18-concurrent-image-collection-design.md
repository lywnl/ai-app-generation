# 并发图片收集接入主流程 - 设计文档

- **日期**: 2026-04-18
- **作者**: lyw
- **状态**: 待评审
- **范围**: `AiCodeGeneratorFacade.generateAndSaveCodeStream` 的 MULTI_FILE / VUE_PROJECT 分支

## 1. 背景与目标

### 1.1 现状

- **主流程**: `AppServiceImpl.chatToGenCode` → `AiCodeGeneratorFacade.generateAndSaveCodeStream` → `AiCodeGeneratorService` 流式生成。**全程无图片收集**,AI 只能根据提示词凭空"想象"图片路径或留占位符。
- **langgraph4j 实验仓**: `src/main/java/com/lyw/appgeneration/langgraph4j/` 下有一套完整的 `CodeGenConcurrentWorkflow`(Plan → 4 并发 Collector → Aggregator → Enhancer → Router → Generator → QualityCheck → Builder),但**未接入任何调用点**,是独立试验品。同时该目录已被加入 `.gitignore`,不属于生产代码。

### 1.2 目标

- 把 langgraph4j 仓里**并发图片收集**这一小段能力抽出来,接入主流程
- 仅用于 **MULTI_FILE / VUE_PROJECT** 两个代码生成类型
- 保持主流程原有流式体验不变,图片收集阶段**阻塞执行**(用户选型)
- 图片收集任何层次的失败都**降级**为原始提示词,**不阻断**代码生成

### 1.3 非目标

- 不接入完整 langgraph4j 工作流(Plan/QualityCheck/Retry/Builder)
- 不改非流式 `generateAndSaveCode` 方法
- 不为 HTML 类型添加图片收集
- 不做 Plan 缓存、URL 存活检测、速率限制
- 不评估 langgraph4j 本身的去留(实验代码是否删除由业务自行决定)

## 2. 架构设计

### 2.1 新包结构

```
com.lyw.appgeneration.ai.image/
 ├── tools/
 │   ├── ImageSearchTool.java          ← 从 langgraph4j/tools 迁入
 │   ├── UndrawIllustrationTool.java   ← 迁入
 │   ├── MermaidDiagramTool.java       ← 迁入
 │   └── LogoGeneratorTool.java        ← 迁入
 ├── model/
 │   ├── ImageResource.java            ← 迁入
 │   ├── ImageCollectionPlan.java      ← 迁入
 │   └── ImageCategoryEnum.java        ← 从 langgraph4j/model/enums 迁入
 ├── ImageCollectionPlanService.java         ← 迁入(LangChain4j AI 接口)
 ├── ImageCollectionPlanServiceFactory.java  ← 迁入
 ├── ImageCollectionService.java       ← 【新建】并发编排
 ├── ImageCollectionPromptBuilder.java ← 【新建】提示词拼装
 └── ImageCollectionExecutorConfig.java ← 【新建】共享线程池 @Configuration
```

### 2.2 组件职责

#### ImageCollectionService(新建)

```java
public String enhancePrompt(String originalPrompt) {
    // 1. 调 ImageCollectionPlanService 产出 ImageCollectionPlan (LLM 决策)
    // 2. 把 plan 的 4 类任务全部提交 CompletableFuture.supplyAsync(executor)
    // 3. allOf().join() 汇总 List<ImageResource>
    // 4. 经 ImageCollectionPromptBuilder 拼装最终提示词
    // 5. 任一环节异常 → 日志 WARN,返回 originalPrompt
}
```

- 对外单一入口 `enhancePrompt`,输入原始提示词,输出增强后提示词
- 内部并发通过 `CompletableFuture` + 共享 `ExecutorService` bean
- 每个 Future 加 `.orTimeout(30s)` 防外部 API 挂死
- 整体容错:任一失败降级为 `originalPrompt`

#### ImageCollectionPromptBuilder(新建)

- 纯粹的拼接逻辑,无依赖
- 从 `PromptEnhancerNode` 移植而来的逻辑:`originalPrompt + "\n\n## 可用素材资源\n..." + 图片分类 + URL 列表`
- 静态方法或无状态组件都可以

#### ImageCollectionExecutorConfig(新建)

```java
@Configuration
public class ImageCollectionExecutorConfig {
    @Bean(name = "imageCollectionExecutor", destroyMethod = "shutdown")
    public ExecutorService imageCollectionExecutor() {
        return ExecutorBuilder.create()
                .setCorePoolSize(10)
                .setMaxPoolSize(20)
                .setWorkQueue(new LinkedBlockingQueue<>(100))
                .setThreadFactory(ThreadFactoryBuilder.create()
                        .setNamePrefix("Image-Collect-").build())
                .build();
    }
}
```

- 参数与 `CodeGenConcurrentWorkflow` 原设定一致
- Spring 管理生命周期,应用关闭时自动 `shutdown()`

### 2.3 数据流

```
chatToGenCode (AppServiceImpl)
    ↓
generateAndSaveCodeStream (AiCodeGeneratorFacade)
    ├─ HTML        → 原流式,无改动
    ├─ MULTI_FILE  → imageCollectionService.enhancePrompt(msg) → 原流式
    └─ VUE_PROJECT → imageCollectionService.enhancePrompt(msg) → 原 TokenStream

ImageCollectionService.enhancePrompt
    ├─ [阻塞 1] ImageCollectionPlanService.planImageCollection → ImageCollectionPlan
    ├─ [阻塞 2] 4 类任务 → CompletableFuture.supplyAsync(..., executor)
    │            ├── ContentImageTasks  → ImageSearchTool
    │            ├── IllustrationTasks  → UndrawIllustrationTool
    │            ├── DiagramTasks       → MermaidDiagramTool
    │            └── LogoTasks          → LogoGeneratorTool
    ├─ [阻塞 3] allOf().join() → 聚合 List<ImageResource>
    └─ PromptBuilder.build(originalPrompt, imageList) → enhancedPrompt
```

## 3. 集成点

### 3.1 Facade 修改

文件:`core/AiCodeGeneratorFacade.java`

新增注入:
```java
@Resource
private ImageCollectionService imageCollectionService;
```

`MULTI_FILE` 分支改动:
```java
case MULTI_FILE -> {
    String finalPrompt = imageCollectionService.enhancePrompt(userMessage);
    Flux<String> codeStream = aiCodeGeneratorService.generateMultiFileCodeStream(finalPrompt);
    yield progressCodeStream(codeStream, CodeGenTypeEnum.MULTI_FILE, appId);
}
```

`VUE_PROJECT` 分支改动:
```java
case VUE_PROJECT -> {
    String finalPrompt = imageCollectionService.enhancePrompt(userMessage);
    TokenStream tokenStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, finalPrompt);
    yield processTokenStream(tokenStream);
}
```

`HTML` 分支:**不改**。

### 3.2 触发判断

**不**在 `ImageCollectionService` 内部做 `genType` 判断,而是由调用方 switch case 决定是否调用。这样 `ImageCollectionService` 职责单一,无反向依赖。

### 3.3 阻塞时机

- `enhancePrompt` 同步方法,在 Facade 方法体内阻塞执行(非 `Flux.defer`)
- 典型耗时 5~10 秒(LLM Plan + 4 类 API 并发,取最慢的)
- 完成后再立即生成 Flux 返回
- 失败抛异常(该情况已被降级机制吞掉,实际不应抛出)

## 4. 错误处理(四层降级)

| 层 | 触发 | 动作 |
|---|---|---|
| Plan LLM 失败 | `planImageCollection` 抛异常或返 `null` | `log.warn` + 直接返回 `originalPrompt`,**跳过**后续收集 |
| 单任务失败 | 某工具 API 抛异常 | 该 `Future` 内部 catch → `Collections.emptyList()`,其他任务正常继续 |
| 单任务超时 | `.orTimeout(30s)` 触发 | 当作空列表,继续聚合 |
| 聚合/拼装异常 | `allOf().join()` 或 builder 异常 | 最外层 catch → 返回 `originalPrompt` |

原则:**全部 WARN/ERROR 日志,不抛 `BusinessException`**。图片是锦上添花,缺了不能挡主流程。

## 5. 测试策略

### 5.1 单元测试 `ImageCollectionServiceTest`

Spring `@Mock` 4 个 Tool + `ImageCollectionPlanService`,覆盖 4 个场景:

1. **正常路径**:4 类任务全有结果 → `enhancePrompt` 返回拼接后的完整提示词,包含所有 URL
2. **Plan 失败**:`planImageCollection` 抛异常 → 返回 `originalPrompt` 原样
3. **部分工具失败**:`ImageSearchTool` 抛异常,其他 3 类成功 → 聚合结果包含成功的 3 类图片
4. **单任务超时**:模拟工具 sleep > 30s → 该任务计空,其他任务结果不受影响

### 5.2 手动端到端验证(改完必跑)

- **MULTI_FILE**:发起真实请求 → 日志显示收集图片数 > 0 → 生成的 html 文件中含 `pexels.com` 或其他源的 URL
- **VUE_PROJECT**:同上,查看生成代码的 `<img src="">` 或类似位置
- **HTML**:确认**没有**图片收集日志,Flux 启动耗时无显著延迟

## 6. 迁移步骤(供 writing-plans 展开)

1. 建新包 `com.lyw.appgeneration.ai.image/` 及子包 `tools/` `model/`
2. 从 `langgraph4j/tools/` 复制 4 个 Tool,调整 package
3. 从 `langgraph4j/model/` 复制 `ImageResource` `ImageCollectionPlan`
4. 从 `langgraph4j/model/enums/` 复制 `ImageCategoryEnum`
5. 从 `langgraph4j/ai/` 复制 `ImageCollectionPlanService` + Factory,调整 package
6. 新建 `ImageCollectionPromptBuilder`(从 `PromptEnhancerNode` 提炼拼装逻辑)
7. 新建 `ImageCollectionExecutorConfig`(@Configuration + @Bean)
8. 新建 `ImageCollectionService`(核心编排逻辑)
9. 修改 `AiCodeGeneratorFacade`:注入 + MULTI_FILE / VUE_PROJECT 两处 case 增加 `enhancePrompt` 调用
10. 写 `ImageCollectionServiceTest`
11. 本地跑 MULTI_FILE / VUE_PROJECT / HTML 三种类型手动验证
12. 删除 `src/main/java/com/lyw/appgeneration/langgraph4j/` 整个目录(确认已全部迁出)及 `.gitignore` 里的 `langgraph4j/` 规则

## 7. 开放问题

- 无。所有设计分岔已在 brainstorming 中定型。

## 8. 附录:关键文件清单

### 迁入(改 package + 保留原文件名)

- `langgraph4j/tools/ImageSearchTool.java`
- `langgraph4j/tools/UndrawIllustrationTool.java`
- `langgraph4j/tools/MermaidDiagramTool.java`
- `langgraph4j/tools/LogoGeneratorTool.java`
- `langgraph4j/model/ImageResource.java`
- `langgraph4j/model/ImageCollectionPlan.java`
- `langgraph4j/model/enums/ImageCategoryEnum.java`
- `langgraph4j/ai/ImageCollectionPlanService.java`
- `langgraph4j/ai/ImageCollectionPlanServiceFactory.java`

### 新建

- `ai/image/ImageCollectionService.java`
- `ai/image/ImageCollectionPromptBuilder.java`
- `ai/image/ImageCollectionExecutorConfig.java`
- `test/.../ImageCollectionServiceTest.java`

### 修改

- `core/AiCodeGeneratorFacade.java`

### 删除

- 整个 `src/main/java/com/lyw/appgeneration/langgraph4j/` 目录
- `.gitignore` 中的 `langgraph4j/` 规则
