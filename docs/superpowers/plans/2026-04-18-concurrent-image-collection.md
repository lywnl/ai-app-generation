# 并发图片收集接入主流程 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 langgraph4j 实验仓里的并发图片收集抽离到新包 `com.lyw.appgeneration.ai.image`,接入 `AiCodeGeneratorFacade` 的 MULTI_FILE/VUE_PROJECT 分支,让主流程在代码生成前自动并发收集图片并拼入增强提示词。

**Architecture:** 新建 `ImageCollectionService` 作为单一入口,内部串联 Plan(LLM)→ 4 类任务 `CompletableFuture.supplyAsync` 并发收集 → `allOf().join()` 聚合 → `ImageCollectionPromptBuilder` 拼装。共享 `ExecutorService` bean 管理线程池,每 Future 带 30s 超时。任何层级失败降级为 `originalPrompt`,不抛异常。Facade 只改 MULTI_FILE/VUE_PROJECT 两 case,HTML 不动。

**Tech Stack:** Java 25 + Spring Boot 3.5.4 + LangChain4j 1.1.0 + hutool-all + CompletableFuture + JUnit 5 + Mockito (via spring-boot-starter-test)

**关联 Spec:** `docs/superpowers/specs/2026-04-18-concurrent-image-collection-design.md`

---

## 文件清单(决策锁定)

### 新建
- `src/main/java/com/lyw/appgeneration/ai/image/model/ImageCategoryEnum.java` — 图片分类枚举(从 `langgraph4j/model/enums/` 迁入)
- `src/main/java/com/lyw/appgeneration/ai/image/model/ImageResource.java` — 图片资源 DTO(迁入)
- `src/main/java/com/lyw/appgeneration/ai/image/model/ImageCollectionPlan.java` — LLM 输出的收集计划(迁入)
- `src/main/java/com/lyw/appgeneration/ai/image/tools/ImageSearchTool.java` — Pexels 内容图搜索(迁入)
- `src/main/java/com/lyw/appgeneration/ai/image/tools/UndrawIllustrationTool.java` — Undraw 插画搜索(迁入)
- `src/main/java/com/lyw/appgeneration/ai/image/tools/MermaidDiagramTool.java` — Mermaid 架构图生成(迁入)
- `src/main/java/com/lyw/appgeneration/ai/image/tools/LogoGeneratorTool.java` — DashScope Logo 生成(迁入)
- `src/main/java/com/lyw/appgeneration/ai/image/ImageCollectionPlanService.java` — LLM 规划接口(迁入)
- `src/main/java/com/lyw/appgeneration/ai/image/ImageCollectionPlanServiceFactory.java` — @Configuration 工厂(迁入,依赖 `openAiChatModel` bean)
- `src/main/java/com/lyw/appgeneration/ai/image/ImageCollectionExecutorConfig.java` — 共享线程池 @Configuration
- `src/main/java/com/lyw/appgeneration/ai/image/ImageCollectionPromptBuilder.java` — 纯拼装逻辑 @Component
- `src/main/java/com/lyw/appgeneration/ai/image/ImageCollectionService.java` — 核心编排 @Service
- `src/test/java/com/lyw/appgeneration/ai/image/ImageCollectionPromptBuilderTest.java` — 拼装单元测试
- `src/test/java/com/lyw/appgeneration/ai/image/ImageCollectionServiceTest.java` — Service 单元测试(Mockito)

### 修改
- `src/main/java/com/lyw/appgeneration/core/AiCodeGeneratorFacade.java` — 注入 `ImageCollectionService`,MULTI_FILE/VUE_PROJECT 两 case 加 `enhancePrompt` 调用
- `.gitignore` — 删除 `langgraph4j/` 规则(第 37 行)

### 删除
- `src/main/java/com/lyw/appgeneration/langgraph4j/` 整个目录(实验代码全部清理)

### 提示资源(资源路径不变,已在 `src/main/resources/prompt/`)
- `prompt/image-collection-plan-system-prompt.txt` — `ImageCollectionPlanService` 的 `@SystemMessage(fromResource=...)` 引用,**不动**

---

## Task 1: 迁移 model 类(3 个文件)

**目标**: 把 3 个纯数据类迁到新包,不改逻辑,只改 package 声明和互相引用的 import。

**Files:**
- Create: `src/main/java/com/lyw/appgeneration/ai/image/model/ImageCategoryEnum.java`
- Create: `src/main/java/com/lyw/appgeneration/ai/image/model/ImageResource.java`
- Create: `src/main/java/com/lyw/appgeneration/ai/image/model/ImageCollectionPlan.java`

- [ ] **Step 1.1: 创建 ImageCategoryEnum.java**

从 `src/main/java/com/lyw/appgeneration/langgraph4j/model/enums/ImageCategoryEnum.java` 复制内容,修改首行 package:

```java
package com.lyw.appgeneration.ai.image.model;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

@Getter
public enum ImageCategoryEnum {
    CONTENT("内容图片", "CONTENT"),
    LOGO("LOGO图片", "LOGO"),
    ILLUSTRATION("插画图片", "ILLUSTRATION"),
    ARCHITECTURE("架构图片", "ARCHITECTURE");

    private final String text;
    private final String value;

    ImageCategoryEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    public static ImageCategoryEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (ImageCategoryEnum anEnum : ImageCategoryEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
```

- [ ] **Step 1.2: 创建 ImageResource.java**

从 `langgraph4j/model/ImageResource.java` 复制内容,修改 package 和 ImageCategoryEnum 的 import:

```java
package com.lyw.appgeneration.ai.image.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageResource implements Serializable {
    private ImageCategoryEnum category;
    private String description;
    private String url;

    @Serial
    private static final long serialVersionUID = 1L;
}
```

注意:`ImageCategoryEnum` 现在**同包**,无需 import。

- [ ] **Step 1.3: 创建 ImageCollectionPlan.java**

从 `langgraph4j/model/ImageCollectionPlan.java` 复制,仅修改 package:

```java
package com.lyw.appgeneration.ai.image.model;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ImageCollectionPlan implements Serializable {
    private List<ImageSearchTask> contentImageTasks;
    private List<IllustrationTask> illustrationTasks;
    private List<DiagramTask> diagramTasks;
    private List<LogoTask> logoTasks;

    public record ImageSearchTask(String query) implements Serializable {}
    public record IllustrationTask(String query) implements Serializable {}
    public record DiagramTask(String mermaidCode, String description) implements Serializable {}
    public record LogoTask(String description) implements Serializable {}
}
```

- [ ] **Step 1.4: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS(此时 langgraph4j/ 下的原始文件还在,旧路径引用未断,应能通过)

- [ ] **Step 1.5: 提交**

```bash
git add src/main/java/com/lyw/appgeneration/ai/image/model/
git commit -m "feat(image): migrate image model classes to ai.image.model package"
```

---

## Task 2: 迁移 4 个图片工具

**目标**: 把 4 个 `@Component` Tool 迁到新包,更新 import 指向新 model 包。

> ⚠️ **必须同时删除旧 Tool + 引用它们的节点**。新旧 Tool 都是 `@Component`,默认 bean name 是小驼峰类名(`imageSearchTool` 等),两份同名 bean 会让 Spring 启动抛 `ConflictingBeanDefinitionException`。同时 `langgraph4j/node/` 下节点类和 `CodeGenConcurrentWorkflow.java` 都 import 旧 Tool,不删掉编译会断。Step 2.5 一次性清理掉它们,剩下的 `state/ model/ demo/`(不依赖 Tool)留到 Task 9 统清。

**Files:**
- Create: `src/main/java/com/lyw/appgeneration/ai/image/tools/ImageSearchTool.java`
- Create: `src/main/java/com/lyw/appgeneration/ai/image/tools/UndrawIllustrationTool.java`
- Create: `src/main/java/com/lyw/appgeneration/ai/image/tools/MermaidDiagramTool.java`
- Create: `src/main/java/com/lyw/appgeneration/ai/image/tools/LogoGeneratorTool.java`
- Delete: `src/main/java/com/lyw/appgeneration/langgraph4j/tools/*.java`(4 个文件)

- [ ] **Step 2.1: 创建 ImageSearchTool.java**

从 `langgraph4j/tools/ImageSearchTool.java` 复制,修改 package 和 model import:

```java
package com.lyw.appgeneration.ai.image.tools;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.image.model.ImageCategoryEnum;
import com.lyw.appgeneration.ai.image.model.ImageResource;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class ImageSearchTool {
    private static final String PEXELS_API_URL = "https://api.pexels.com/v1/search";

    @Value("${pexels.api-key}")
    private String pexelsApiKey;

    @Tool("搜索内容相关的图片，用于网站内容展示")
    public List<ImageResource> searchContentImages(@P("搜索关键词") String query) {
        List<ImageResource> imageList = new ArrayList<>();
        int searchCount = 12;
        try (HttpResponse response = HttpRequest.get(PEXELS_API_URL)
                .header("Authorization", pexelsApiKey)
                .form("query", query)
                .form("per_page", searchCount)
                .form("page", 1)
                .execute()) {
            if (response.isOk()) {
                JSONObject result = JSONUtil.parseObj(response.body());
                JSONArray photos = result.getJSONArray("photos");
                for (int i = 0; i < photos.size(); i++) {
                    JSONObject photo = photos.getJSONObject(i);
                    JSONObject src = photo.getJSONObject("src");
                    imageList.add(ImageResource.builder()
                            .category(ImageCategoryEnum.CONTENT)
                            .description(photo.getStr("alt", query))
                            .url(src.getStr("medium"))
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("Pexels API 调用失败: {}", e.getMessage(), e);
        }
        return imageList;
    }
}
```

- [ ] **Step 2.2: 创建 UndrawIllustrationTool.java**

复制 `langgraph4j/tools/UndrawIllustrationTool.java`,只改 package + 两处 model import(将 `langgraph4j.model.ImageResource` 和 `langgraph4j.model.enums.ImageCategoryEnum` 替换为 `ai.image.model.*`),其余逻辑**完全不动**。

- [ ] **Step 2.3: 创建 MermaidDiagramTool.java**

同上操作。此类还依赖 `com.lyw.appgeneration.manger.CosManager` 和 `com.lyw.appgeneration.exception.BusinessException/ErrorCode`,这些是工程公共包,import 不变。

- [ ] **Step 2.4: 创建 LogoGeneratorTool.java**

同上操作。只改 package + 两处 model import,DashScope 相关 import 不变。

- [ ] **Step 2.5: 一次性删除所有引用旧 Tool 的 langgraph4j 类**

新 Tool 已就位,原 Tool 以及所有引用它们的节点必须同步删掉,否则 Spring 启动会因**同名 bean 冲突**失败(新旧 Tool 都是 `@Component`,小驼峰类名作 bean 名)。一次性清理:

```bash
rm src/main/java/com/lyw/appgeneration/langgraph4j/tools/ImageSearchTool.java
rm src/main/java/com/lyw/appgeneration/langgraph4j/tools/UndrawIllustrationTool.java
rm src/main/java/com/lyw/appgeneration/langgraph4j/tools/MermaidDiagramTool.java
rm src/main/java/com/lyw/appgeneration/langgraph4j/tools/LogoGeneratorTool.java
rm -rf src/main/java/com/lyw/appgeneration/langgraph4j/node/
rm src/main/java/com/lyw/appgeneration/langgraph4j/CodeGenConcurrentWorkflow.java
```

此时 `langgraph4j/` 只剩 `ai/`(Task 3 迁)、`model/`(Task 9 清)、`state/`(Task 9 清)、`demo/`(Task 9 清)、`tools/`(空目录,Task 9 清)。
保留的 `state/WorkflowContext`、`model/QualityResult`、`demo/*` 不依赖刚删掉的 Tool,编译不受影响。

- [ ] **Step 2.6: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS。若失败,执行 `grep -r "import com.lyw.appgeneration.langgraph4j.tools" src/` 找残留引用,进一步删除。

- [ ] **Step 2.7: 提交**

```bash
git add src/main/java/com/lyw/appgeneration/ai/image/tools/
git add -u  # 暂存删除的文件
git commit -m "feat(image): migrate image tools to ai.image.tools; drop langgraph4j nodes"
```

---

## Task 3: 迁移 PlanService + Factory

**目标**: 把 LLM 规划接口和对应工厂迁到新包。

**Files:**
- Create: `src/main/java/com/lyw/appgeneration/ai/image/ImageCollectionPlanService.java`
- Create: `src/main/java/com/lyw/appgeneration/ai/image/ImageCollectionPlanServiceFactory.java`
- Delete: `src/main/java/com/lyw/appgeneration/langgraph4j/ai/*.java`

- [ ] **Step 3.1: 创建 ImageCollectionPlanService.java**

```java
package com.lyw.appgeneration.ai.image;

import com.lyw.appgeneration.ai.image.model.ImageCollectionPlan;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface ImageCollectionPlanService {
    @SystemMessage(fromResource = "prompt/image-collection-plan-system-prompt.txt")
    ImageCollectionPlan planImageCollection(@UserMessage String userPrompt);
}
```

系统提示词资源路径 `prompt/image-collection-plan-system-prompt.txt` **不变**,已在 `src/main/resources/`。

- [ ] **Step 3.2: 创建 ImageCollectionPlanServiceFactory.java**

```java
package com.lyw.appgeneration.ai.image;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ImageCollectionPlanServiceFactory {

    @Resource(name = "openAiChatModel")
    private ChatModel chatModel;

    @Bean
    public ImageCollectionPlanService createImageCollectionPlanService() {
        return AiServices.builder(ImageCollectionPlanService.class)
                .chatModel(chatModel)
                .build();
    }
}
```

⚠️ 依赖 Spring 上下文中存在 name 为 `openAiChatModel` 的 `ChatModel` bean。这个 bean 由 `langchain4j-open-ai-spring-boot-starter` 自动装配,项目 pom 已包含。若启动时抛 NoSuchBeanDefinitionException,检查 `application.yml` 的 `langchain4j.open-ai.chat-model` 配置。

- [ ] **Step 3.3: 删除旧文件**

```bash
rm -rf src/main/java/com/lyw/appgeneration/langgraph4j/ai/
```

这会一并删除 `CodeQualityCheckService.java` 和 `CodeQualityCheckServiceFactory.java`(质检服务,本次不用)。

- [ ] **Step 3.4: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3.5: 提交**

```bash
git add src/main/java/com/lyw/appgeneration/ai/image/ImageCollectionPlanService.java
git add src/main/java/com/lyw/appgeneration/ai/image/ImageCollectionPlanServiceFactory.java
git add -u
git commit -m "feat(image): migrate plan service and factory to ai.image package"
```

---

## Task 4: 新建 ImageCollectionExecutorConfig

**目标**: 暴露一个 Spring 管理的共享线程池 bean 给 `ImageCollectionService` 使用。

**Files:**
- Create: `src/main/java/com/lyw/appgeneration/ai/image/ImageCollectionExecutorConfig.java`

- [ ] **Step 4.1: 创建配置类**

```java
package com.lyw.appgeneration.ai.image;

import cn.hutool.core.thread.ExecutorBuilder;
import cn.hutool.core.thread.ThreadFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;

@Configuration
public class ImageCollectionExecutorConfig {

    @Bean(name = "imageCollectionExecutor", destroyMethod = "shutdown")
    public ExecutorService imageCollectionExecutor() {
        return ExecutorBuilder.create()
                .setCorePoolSize(10)
                .setMaxPoolSize(20)
                .setWorkQueue(new LinkedBlockingQueue<>(100))
                .setThreadFactory(ThreadFactoryBuilder.create()
                        .setNamePrefix("Image-Collect-")
                        .build())
                .build();
    }
}
```

`destroyMethod = "shutdown"` 确保 Spring 关闭时优雅释放线程池。

- [ ] **Step 4.2: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4.3: 提交**

```bash
git add src/main/java/com/lyw/appgeneration/ai/image/ImageCollectionExecutorConfig.java
git commit -m "feat(image): add shared executor config for concurrent image collection"
```

---

## Task 5: TDD - ImageCollectionPromptBuilder

**目标**: 纯拼装逻辑,先写测试,再写实现。**无网络/无 Spring 依赖**,纯 Java 单元测试。

**Files:**
- Create: `src/test/java/com/lyw/appgeneration/ai/image/ImageCollectionPromptBuilderTest.java`
- Create: `src/main/java/com/lyw/appgeneration/ai/image/ImageCollectionPromptBuilder.java`

- [ ] **Step 5.1: 写失败测试**

`src/test/java/com/lyw/appgeneration/ai/image/ImageCollectionPromptBuilderTest.java`:

```java
package com.lyw.appgeneration.ai.image;

import com.lyw.appgeneration.ai.image.model.ImageCategoryEnum;
import com.lyw.appgeneration.ai.image.model.ImageResource;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImageCollectionPromptBuilderTest {

    private final ImageCollectionPromptBuilder builder = new ImageCollectionPromptBuilder();

    @Test
    void build_withEmptyList_returnsOriginalPrompt() {
        String result = builder.build("原始提示", Collections.emptyList());
        assertEquals("原始提示", result);
    }

    @Test
    void build_withNullList_returnsOriginalPrompt() {
        String result = builder.build("原始提示", null);
        assertEquals("原始提示", result);
    }

    @Test
    void build_withImages_appendsResourceSection() {
        List<ImageResource> images = List.of(
                ImageResource.builder()
                        .category(ImageCategoryEnum.CONTENT)
                        .description("风景")
                        .url("https://pexels.com/a.jpg")
                        .build(),
                ImageResource.builder()
                        .category(ImageCategoryEnum.LOGO)
                        .description("品牌 logo")
                        .url("https://cdn.com/logo.png")
                        .build()
        );
        String result = builder.build("原始提示", images);
        assertTrue(result.startsWith("原始提示"));
        assertTrue(result.contains("## 可用素材资源"));
        assertTrue(result.contains("内容图片：风景（https://pexels.com/a.jpg）"));
        assertTrue(result.contains("LOGO图片：品牌 logo（https://cdn.com/logo.png）"));
    }
}
```

- [ ] **Step 5.2: 运行测试确认失败**

Run: `./mvnw test -Dtest=ImageCollectionPromptBuilderTest -q`
Expected: 编译失败,`ImageCollectionPromptBuilder` 类不存在

- [ ] **Step 5.3: 写最小实现**

`src/main/java/com/lyw/appgeneration/ai/image/ImageCollectionPromptBuilder.java`:

```java
package com.lyw.appgeneration.ai.image;

import cn.hutool.core.collection.CollUtil;
import com.lyw.appgeneration.ai.image.model.ImageResource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ImageCollectionPromptBuilder {

    public String build(String originalPrompt, List<ImageResource> images) {
        if (CollUtil.isEmpty(images)) {
            return originalPrompt;
        }
        StringBuilder sb = new StringBuilder(originalPrompt);
        sb.append("\n\n## 可用素材资源\n");
        sb.append("请在生成网站使用以下图片资源，将这些图片合理地嵌入到网站的相应位置中。\n");
        for (ImageResource img : images) {
            sb.append("- ")
                    .append(img.getCategory().getText())
                    .append("：")
                    .append(img.getDescription())
                    .append("（")
                    .append(img.getUrl())
                    .append("）\n");
        }
        return sb.toString();
    }
}
```

逻辑与原 `PromptEnhancerNode` 第 33-49 行一致,去掉了 WorkflowContext 依赖。

- [ ] **Step 5.4: 运行测试确认通过**

Run: `./mvnw test -Dtest=ImageCollectionPromptBuilderTest -q`
Expected: PASS,3 个测试全绿

- [ ] **Step 5.5: 提交**

```bash
git add src/main/java/com/lyw/appgeneration/ai/image/ImageCollectionPromptBuilder.java
git add src/test/java/com/lyw/appgeneration/ai/image/ImageCollectionPromptBuilderTest.java
git commit -m "feat(image): add ImageCollectionPromptBuilder with unit tests"
```

---

## Task 6: TDD - ImageCollectionService(核心编排)

**目标**: 核心并发编排逻辑。Mock 4 个 Tool + PlanService,验证 4 个场景(正常 / Plan 失败 / 部分失败 / 超时)。

**Files:**
- Create: `src/test/java/com/lyw/appgeneration/ai/image/ImageCollectionServiceTest.java`
- Create: `src/main/java/com/lyw/appgeneration/ai/image/ImageCollectionService.java`

- [ ] **Step 6.1: 写失败测试(骨架)**

`src/test/java/com/lyw/appgeneration/ai/image/ImageCollectionServiceTest.java`:

```java
package com.lyw.appgeneration.ai.image;

import com.lyw.appgeneration.ai.image.model.ImageCategoryEnum;
import com.lyw.appgeneration.ai.image.model.ImageCollectionPlan;
import com.lyw.appgeneration.ai.image.model.ImageResource;
import com.lyw.appgeneration.ai.image.tools.ImageSearchTool;
import com.lyw.appgeneration.ai.image.tools.LogoGeneratorTool;
import com.lyw.appgeneration.ai.image.tools.MermaidDiagramTool;
import com.lyw.appgeneration.ai.image.tools.UndrawIllustrationTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageCollectionServiceTest {

    @Mock private ImageCollectionPlanService planService;
    @Mock private ImageSearchTool imageSearchTool;
    @Mock private UndrawIllustrationTool undrawIllustrationTool;
    @Mock private MermaidDiagramTool mermaidDiagramTool;
    @Mock private LogoGeneratorTool logoGeneratorTool;

    private ExecutorService executor;
    private ImageCollectionPromptBuilder promptBuilder;
    private ImageCollectionService service;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(4);
        promptBuilder = new ImageCollectionPromptBuilder();
        service = new ImageCollectionService(
                planService,
                imageSearchTool,
                undrawIllustrationTool,
                mermaidDiagramTool,
                logoGeneratorTool,
                executor,
                promptBuilder
        );
    }

    @Test
    void enhancePrompt_happyPath_returnsEnhancedPrompt() {
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("风景")));
        when(planService.planImageCollection("博客网站")).thenReturn(plan);
        when(imageSearchTool.searchContentImages("风景")).thenReturn(List.of(
                ImageResource.builder()
                        .category(ImageCategoryEnum.CONTENT)
                        .description("山")
                        .url("https://pexels.com/mountain.jpg")
                        .build()
        ));

        String result = service.enhancePrompt("博客网站");

        assertTrue(result.contains("博客网站"));
        assertTrue(result.contains("https://pexels.com/mountain.jpg"));
    }

    @Test
    void enhancePrompt_planThrows_returnsOriginalPrompt() {
        when(planService.planImageCollection(anyString()))
                .thenThrow(new RuntimeException("LLM down"));

        String result = service.enhancePrompt("博客网站");

        assertEquals("博客网站", result);
        verifyNoInteractions(imageSearchTool, undrawIllustrationTool, mermaidDiagramTool, logoGeneratorTool);
    }

    @Test
    void enhancePrompt_oneToolFails_otherResultsAggregated() {
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("风景")));
        plan.setLogoTasks(List.of(new ImageCollectionPlan.LogoTask("科技公司 logo")));
        when(planService.planImageCollection("科技站")).thenReturn(plan);
        when(imageSearchTool.searchContentImages("风景"))
                .thenThrow(new RuntimeException("Pexels down"));
        when(logoGeneratorTool.generateLogos("科技公司 logo")).thenReturn(List.of(
                ImageResource.builder()
                        .category(ImageCategoryEnum.LOGO)
                        .description("科技公司 logo")
                        .url("https://dashscope.com/logo.png")
                        .build()
        ));

        String result = service.enhancePrompt("科技站");

        assertTrue(result.contains("https://dashscope.com/logo.png"));
        assertFalse(result.contains("pexels"));
    }

    @Test
    void enhancePrompt_toolTimeout_treatedAsEmpty() throws InterruptedException {
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setLogoTasks(List.of(new ImageCollectionPlan.LogoTask("slow-logo")));
        when(planService.planImageCollection("站")).thenReturn(plan);
        when(logoGeneratorTool.generateLogos("slow-logo")).thenAnswer(inv -> {
            Thread.sleep(35_000);
            return List.of();
        });

        long start = System.currentTimeMillis();
        String result = service.enhancePrompt("站");
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("站", result); // 降级
        assertTrue(elapsed < 33_000, "应在 30s 超时后返回,实际 " + elapsed + "ms");
    }
}
```

⚠️ Task 6.1 包含了第 4 个超时测试,会让测试耗时 30+s。若本地嫌慢,可在 Service 实现中把超时做成构造注入的参数,测试里传 1s。**推荐做法:Service 内部超时常量化 (`private static final Duration COLLECT_TIMEOUT = Duration.ofSeconds(30);`),测试保留真实场景但做好耐心等待**。或写成 `@Tag("slow")` 按需跑。

- [ ] **Step 6.2: 运行测试确认失败**

Run: `./mvnw test -Dtest=ImageCollectionServiceTest -q`
Expected: 编译失败,`ImageCollectionService` 不存在

- [ ] **Step 6.3: 写实现**

`src/main/java/com/lyw/appgeneration/ai/image/ImageCollectionService.java`:

```java
package com.lyw.appgeneration.ai.image;

import cn.hutool.core.collection.CollUtil;
import com.lyw.appgeneration.ai.image.model.ImageCollectionPlan;
import com.lyw.appgeneration.ai.image.model.ImageResource;
import com.lyw.appgeneration.ai.image.tools.ImageSearchTool;
import com.lyw.appgeneration.ai.image.tools.LogoGeneratorTool;
import com.lyw.appgeneration.ai.image.tools.MermaidDiagramTool;
import com.lyw.appgeneration.ai.image.tools.UndrawIllustrationTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ImageCollectionService {

    private static final int TIMEOUT_SECONDS = 30;

    private final ImageCollectionPlanService planService;
    private final ImageSearchTool imageSearchTool;
    private final UndrawIllustrationTool undrawIllustrationTool;
    private final MermaidDiagramTool mermaidDiagramTool;
    private final LogoGeneratorTool logoGeneratorTool;
    private final ExecutorService executor;
    private final ImageCollectionPromptBuilder promptBuilder;

    public ImageCollectionService(
            ImageCollectionPlanService planService,
            ImageSearchTool imageSearchTool,
            UndrawIllustrationTool undrawIllustrationTool,
            MermaidDiagramTool mermaidDiagramTool,
            LogoGeneratorTool logoGeneratorTool,
            @Qualifier("imageCollectionExecutor") ExecutorService executor,
            ImageCollectionPromptBuilder promptBuilder
    ) {
        this.planService = planService;
        this.imageSearchTool = imageSearchTool;
        this.undrawIllustrationTool = undrawIllustrationTool;
        this.mermaidDiagramTool = mermaidDiagramTool;
        this.logoGeneratorTool = logoGeneratorTool;
        this.executor = executor;
        this.promptBuilder = promptBuilder;
    }

    public String enhancePrompt(String originalPrompt) {
        try {
            ImageCollectionPlan plan = planService.planImageCollection(originalPrompt);
            if (plan == null) {
                log.warn("图片收集计划为 null,跳过收集");
                return originalPrompt;
            }
            List<CompletableFuture<List<ImageResource>>> futures = new ArrayList<>();
            addFutures(plan.getContentImageTasks(), t ->
                    imageSearchTool.searchContentImages(t.query()), futures);
            addFutures(plan.getIllustrationTasks(), t ->
                    undrawIllustrationTool.searchIllustrations(t.query()), futures);
            addFutures(plan.getDiagramTasks(), t ->
                    mermaidDiagramTool.generateMermaidDiagram(t.mermaidCode(), t.description()), futures);
            addFutures(plan.getLogoTasks(), t ->
                    logoGeneratorTool.generateLogos(t.description()), futures);

            List<ImageResource> aggregated = new ArrayList<>();
            for (CompletableFuture<List<ImageResource>> f : futures) {
                try {
                    List<ImageResource> part = f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (part != null) {
                        aggregated.addAll(part);
                    }
                } catch (Exception e) {
                    log.warn("单任务失败或超时,忽略: {}", e.getMessage());
                }
            }
            log.info("图片并发收集完成,共 {} 张", aggregated.size());
            return promptBuilder.build(originalPrompt, aggregated);
        } catch (Exception e) {
            log.error("图片收集整体失败,降级为原始提示词: {}", e.getMessage(), e);
            return originalPrompt;
        }
    }

    private <T> void addFutures(
            List<T> tasks,
            java.util.function.Function<T, List<ImageResource>> invoker,
            List<CompletableFuture<List<ImageResource>>> futures
    ) {
        if (CollUtil.isEmpty(tasks)) {
            return;
        }
        for (T task : tasks) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return invoker.apply(task);
                } catch (Exception e) {
                    log.warn("子任务异常,返回空列表: {}", e.getMessage());
                    return Collections.emptyList();
                }
            }, executor));
        }
    }
}
```

关键点:
- 构造注入(非字段注入),便于单测 new 实例
- `@Qualifier("imageCollectionExecutor")` 锁定 Task 4 创建的 bean
- 每个 Future 通过 `f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)` 实现超时
- 超时/异常都 catch → 空列表,不影响其他 Future
- Plan 抛异常时最外层 catch → 返回 originalPrompt
- 4 类任务用泛型方法 `addFutures` 统一,避免四段重复

- [ ] **Step 6.4: 运行测试确认通过**

Run: `./mvnw test -Dtest=ImageCollectionServiceTest -q`
Expected: 4 个测试全绿(第 4 个耗时 ~30s)

- [ ] **Step 6.5: 提交**

```bash
git add src/main/java/com/lyw/appgeneration/ai/image/ImageCollectionService.java
git add src/test/java/com/lyw/appgeneration/ai/image/ImageCollectionServiceTest.java
git commit -m "feat(image): add ImageCollectionService with concurrent orchestration"
```

---

## Task 7: 集成到 AiCodeGeneratorFacade

**目标**: Facade 两处 case 加 `enhancePrompt` 调用。HTML 不动。

**Files:**
- Modify: `src/main/java/com/lyw/appgeneration/core/AiCodeGeneratorFacade.java`

- [ ] **Step 7.1: 注入 ImageCollectionService**

在 `AiCodeGeneratorFacade` 字段区新增:

```java
@Resource
private com.lyw.appgeneration.ai.image.ImageCollectionService imageCollectionService;
```

(或顶部加 `import com.lyw.appgeneration.ai.image.ImageCollectionService;`)

- [ ] **Step 7.2: 改 MULTI_FILE 分支**

原(generateAndSaveCodeStream 方法内):

```java
case MULTI_FILE -> {
    Flux<String> codeStream = aiCodeGeneratorService.generateMultiFileCodeStream(userMessage);
    yield progressCodeStream(codeStream, CodeGenTypeEnum.MULTI_FILE, appId);
}
```

改为:

```java
case MULTI_FILE -> {
    String enhancedPrompt = imageCollectionService.enhancePrompt(userMessage);
    Flux<String> codeStream = aiCodeGeneratorService.generateMultiFileCodeStream(enhancedPrompt);
    yield progressCodeStream(codeStream, CodeGenTypeEnum.MULTI_FILE, appId);
}
```

- [ ] **Step 7.3: 改 VUE_PROJECT 分支**

原:

```java
case VUE_PROJECT -> {
    TokenStream tokenStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, userMessage);
    yield processTokenStream(tokenStream);
}
```

改为:

```java
case VUE_PROJECT -> {
    String enhancedPrompt = imageCollectionService.enhancePrompt(userMessage);
    TokenStream tokenStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, enhancedPrompt);
    yield processTokenStream(tokenStream);
}
```

- [ ] **Step 7.4: HTML 分支保持不变**

不要动 HTML case,确认未改。

- [ ] **Step 7.5: 编译 + 跑现有测试**

Run: `./mvnw test -q`
Expected: BUILD SUCCESS,原有测试不 regression(注意 `AiCodeGeneratorFacadeTest` 是真实调用 LLM + API,可能因为现在 MULTI_FILE/VUE_PROJECT 会先做图片收集而耗时增加,但应能通过。若外部 API 不可达导致超时,临时 `@Disabled` 两个 stream 测试,不算 regression)

- [ ] **Step 7.6: 提交**

```bash
git add src/main/java/com/lyw/appgeneration/core/AiCodeGeneratorFacade.java
git commit -m "feat(core): integrate concurrent image collection into MULTI_FILE and VUE_PROJECT flows"
```

---

## Task 8: 手动端到端验证

**目标**: 真实运行应用,确认 3 种生成类型的行为符合预期。

- [ ] **Step 8.1: 启动应用**

```bash
./mvnw spring-boot:run
```

等待"Started AiAppGenerationApplication"日志出现。

- [ ] **Step 8.2: 验证 MULTI_FILE 类型**

登录前端 `http://localhost:8080`(或前端端口),新建一个应用,`initPrompt` 写类似"创建一个旅游博客,首页要图片" → AI 路由到 MULTI_FILE → 发起聊天生成。

观察应用日志:
- 看到 `图片并发收集完成,共 N 张` (N > 0)
- 打开生成的 `tmp/code_output/multi_file_<appId>/index.html` 等文件
- 检查是否包含 `pexels.com` 或 `undraw.co` 的 URL

- [ ] **Step 8.3: 验证 VUE_PROJECT 类型**

同样新建应用,`initPrompt` 写类似"简单任务管理工具,需要 logo" → AI 路由到 VUE_PROJECT → 生成。

观察日志 + 产物:
- `图片并发收集完成` 日志出现
- 生成的 Vue 组件中含 `<img :src="..." />` 引用

- [ ] **Step 8.4: 验证 HTML 类型不触发收集**

`initPrompt` 写"单文件 html 倒计时" → AI 路由到 HTML → 生成。

观察日志:**不应**出现 `图片并发收集完成` 相关日志。生成的 html 不含外部 CDN URL(除非 AI 自己凭空写了)。

- [ ] **Step 8.5: 故障场景验证(可选)**

临时把 `application.yml` 的 `pexels.api-key` 改错,再跑一次 MULTI_FILE。预期:`Pexels API 调用失败` 或 `单任务失败或超时,忽略` WARN 日志出现,但代码仍能正常生成(没图片而已)。改回 api-key。

- [ ] **Step 8.6: 无需 commit**

手动验证不产出文件变更。若改了 application.yml,记得 `git checkout` 还原。

---

## Task 9: 清理 langgraph4j/ 残留 + 修 .gitignore

**目标**: 删除实验代码残骸,恢复 `.gitignore` 为生产状态。

**Files:**
- Delete: `src/main/java/com/lyw/appgeneration/langgraph4j/`(若 Task 2/3 已部分删除,把剩下的 demo/ state/ model/ 都删掉)
- Modify: `.gitignore`(删除 `langgraph4j/` 规则,第 37 行)

- [ ] **Step 9.1: 确认 langgraph4j/ 剩余内容**

```bash
find src/main/java/com/lyw/appgeneration/langgraph4j -type f 2>/dev/null
```

Expected: 可能剩余 `demo/GreeterNode.java`、`demo/SimpleGraphApp.java`、`demo/SimpleState.java`、`state/WorkflowContext.java`、`model/QualityResult.java` 等。

- [ ] **Step 9.2: 整体删除**

```bash
rm -rf src/main/java/com/lyw/appgeneration/langgraph4j/
```

- [ ] **Step 9.3: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS。若失败,说明还有地方引用 langgraph4j 旧类,全局搜索修复:

```bash
grep -r "com.lyw.appgeneration.langgraph4j" src/
```

- [ ] **Step 9.4: 修 .gitignore**

打开 `.gitignore`,删除第 37 行 `langgraph4j/`。

- [ ] **Step 9.5: 跑完整测试**

Run: `./mvnw test -q`
Expected: BUILD SUCCESS

- [ ] **Step 9.6: 提交**

```bash
git add -u
git add .gitignore
git commit -m "chore: remove langgraph4j experimental package and restore .gitignore"
```

---

## 验收标准

所有 Task 完成后:

1. `./mvnw clean test -q` 全绿
2. 手动跑 MULTI_FILE → 日志有"图片并发收集完成",产物含图片 URL
3. 手动跑 VUE_PROJECT → 同上
4. 手动跑 HTML → 无图片收集日志,行为与之前一致
5. `git log --oneline` 显示 ~9 个递进提交,可按 Task 粒度回滚
6. 项目目录 `src/main/java/com/lyw/appgeneration/` 下**不再**存在 `langgraph4j/`
7. `.gitignore` 不再有 `langgraph4j/` 规则

---

## 风险与回滚

| 风险 | 缓解 |
|---|---|
| `openAiChatModel` bean 缺失导致 Factory 启动报错 | 事前 grep 确认 bean 存在;实际运行 Task 9.3 会暴露 |
| 并发任务真实 API 配额超限 | Task 8.5 故障演练;线上可通过修 application.yml 临时屏蔽(把 api-key 清空让 Tool 自然失败降级) |
| Task 7 误删 HTML 分支的行为 | Task 7.4 专门保留 HTML 不变;Task 8.4 端到端确认 |
| 重构过程中 langgraph4j 分支上未迁移的文件被误删 | 每个迁移 Task 都有独立 commit,可精确回退 |

---

## 参考资料

- Spec: `docs/superpowers/specs/2026-04-18-concurrent-image-collection-design.md`
- 原 PromptEnhancer 拼装逻辑来源: `langgraph4j/node/PromptEnhancerNode.java`(迁移前)第 33-49 行
- 原并发编排参考: `langgraph4j/node/ImageCollectorNode.java`(迁移前,CompletableFuture 模式)
- Spring @Qualifier 绑定命名 bean: `Qualifier("imageCollectionExecutor")`
- LangChain4j AiServices 构造: `ImageCollectionPlanServiceFactory.createImageCollectionPlanService()`
