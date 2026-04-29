package com.lyw.appgeneration.core.handler;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.AiCodeGeneratorService;
import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.model.message.*;
import com.lyw.appgeneration.ai.tools.BaseTool;
import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.core.builder.VueProjectBuilder;
import com.lyw.appgeneration.manger.ToolManager;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.ChatHistoryMessageTypeEnum;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JSON 消息流处理器
 * 处理 VUE_PROJECT 类型的复杂流式响应，包含工具调用信息
 */
@Slf4j
@Component
public class JsonMessageStreamHandler {

    private static final long PRE_BUILD_CHECK_TIMEOUT_SECONDS = 120;

    private static final String VUE_PRE_BUILD_CHECK_PROMPT = """
            构建前代码自检：请仅检查当前 Vue 项目代码的语法与可构建性风险，不要新增功能，不要改业务逻辑。
            重点检查 .vue、.ts、.js、.json 文件中的语法错误、导入导出错误、模板标签闭合、括号/引号匹配问题。
            如果发现问题，必须调用 modifyFile 工具做最小修复；如需定位可调用 readFile 或 readDir。
            检查和修复完成后，若无问题请输出“检查完成，无需修改”；若有修改请输出“检查完成”并列出修改文件。
            最后调用 exit 工具结束, 切记切记切记一定要仔细检查所有文件, 以免构建出错, 反复检查直到确定。
            """;

    @Resource
    private VueProjectBuilder vueProjectBuilder;

    @Resource
    private ToolManager toolManager;

    @Resource
    private AiGeneratorServiceFactory aiGeneratorServiceFactory;

    /**
     * 处理 TokenStream（VUE_PROJECT）
     * 解析 JSON 消息并重组为完整的响应格式
     *
     * @param originFlux         原始流
     * @param chatHistoryService 聊天历史服务
     * @param appId              应用ID
     * @param loginUser          登录用户
     * @return 处理后的流
     */
    public Flux<String> handle(Flux<String> originFlux,
                               ChatHistoryService chatHistoryService,
                               long appId, User loginUser) {
        // 收集数据用于生成后端记忆格式
        StringBuilder chatHistoryStringBuilder = new StringBuilder();
        // 用于跟踪已经见过的工具ID，判断是否是第一次调用
        Set<String> seenToolIds = new HashSet<>();
        return originFlux
                .concatMap(chunk -> {
                    // 大部分 case 产出单条字符串,TOOL_EXECUTED 会产出两条(状态事件 + markdown 正文)
                    return Flux.fromIterable(handleJsonMessageChunk(chunk, chatHistoryStringBuilder, seenToolIds));
                })
                .filter(StrUtil::isNotEmpty)
                .doOnComplete(() -> {
                    // 流式响应完成后，添加 AI 消息到对话历史
                    boolean shouldRunPreBuildCheck = shouldRunPreBuildCheck(chatHistoryService, appId);
                    String aiResponse = chatHistoryStringBuilder.toString();
                    chatHistoryService.addChatMessage(appId, aiResponse, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                    String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_" + appId;
                    if (shouldRunPreBuildCheck) {
                        runVuePreBuildCheck(appId);
                    } else {
                        log.info("非首轮对话，跳过构建前代码自检: appId={}", appId);
                    }
                    vueProjectBuilder.buildProjectAsync(projectPath);
                })
                .doOnError(error -> {
                    // 如果AI回复失败，也要记录错误消息
                    String errorMessage = "AI回复失败: " + error.getMessage();
                    chatHistoryService.addChatMessage(appId, errorMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                });
    }
    /**
     * 仅首轮 AI 回复时触发构建前代码自检。
     */
    private boolean shouldRunPreBuildCheck(ChatHistoryService chatHistoryService, long appId) {
        QueryWrapper queryWrapper = QueryWrapper.create();
        queryWrapper.eq("appId", appId);
        queryWrapper.eq("messageType", ChatHistoryMessageTypeEnum.AI.getValue());
        return chatHistoryService.count(queryWrapper) == 0;
    }
    /**
     * 解析并收集 TokenStream 数据
     */
    private void runVuePreBuildCheck(long appId) {
        try {
            AiCodeGeneratorService aiCodeGeneratorService =
                    aiGeneratorServiceFactory.getAiCodeGeneratorService(appId, CodeGenTypeEnum.VUE_PROJECT);
            TokenStream tokenStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, VUE_PRE_BUILD_CHECK_PROMPT);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            tokenStream
                    .onPartialResponse((String ignored) -> {
                        // 自检阶段不透传文本到前端，但必须注册回调以满足 TokenStream 配置约束
                    })
                    .onCompleteResponse((ChatResponse ignored) -> done.countDown())
                    .onError(error -> {
                        errorRef.set(error);
                        done.countDown();
                    })
                    .start();
            boolean finished = done.await(PRE_BUILD_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("构建前代码自检超时，继续执行构建: appId={}", appId);
                return;
            }
            Throwable error = errorRef.get();
            if (error != null) {
                log.warn("构建前代码自检失败，继续执行构建: appId={}, error={}", appId, error.getMessage());
            } else {
                log.info("构建前代码自检完成: appId={}", appId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("构建前代码自检被中断，继续执行构建: appId={}", appId);
        } catch (Exception e) {
            log.warn("构建前代码自检异常，继续执行构建: appId={}, error={}", appId, e.getMessage());
        }
    }

    private List<String> handleJsonMessageChunk(String chunk, StringBuilder chatHistoryStringBuilder, Set<String> seenToolIds) {
        // 解析 JSON
        StreamMessage streamMessage = JSONUtil.toBean(chunk, StreamMessage.class);
        StreamMessageTypeEnum typeEnum = StreamMessageTypeEnum.getEnumByValue(streamMessage.getType());
        switch (typeEnum) {
            case AI_RESPONSE -> {
                AiResponseMessage aiMessage = JSONUtil.toBean(chunk, AiResponseMessage.class);
                String data = aiMessage.getData();
                chatHistoryStringBuilder.append(data);
                return List.of(data);
            }
            case TOOL_REQUEST -> {
                ToolRequestMessage toolRequestMessage = JSONUtil.toBean(chunk, ToolRequestMessage.class);
                String toolId = toolRequestMessage.getId();
                String toolName = toolRequestMessage.getName();
                if (toolId != null && !seenToolIds.contains(toolId)) {
                    seenToolIds.add(toolId);
                    BaseTool tool = toolManager.getTool(toolName);
                    return List.of(tool.generateToolRequestResponse());
                }
                return List.of();
            }
            case TOOL_EXECUTED -> {
                ToolExecutedMessage toolExecutedMessage = JSONUtil.toBean(chunk, ToolExecutedMessage.class);
                String toolName = toolExecutedMessage.getName();
                JSONObject jsonObject = JSONUtil.parseObj(toolExecutedMessage.getArguments());
                BaseTool tool = toolManager.getTool(toolName);
                String result = tool.generateToolExecutedResult(jsonObject);
                String output = String.format("\n\n%s\n\n", result);
                chatHistoryStringBuilder.append(output);
                // 先发"状态事件":告知前端该 toolCallId 已执行完成,便于卡片切换到 done 状态;
                // 再发原来的 markdown 正文。两者前后顺序不能反,否则前端会在状态切换前渲染结论性文案。
                return List.of(chunk, output);
            }
            case TOOL_ARGUMENT, TOOL_ARGUMENT_DELTA -> {
                // 工具参数事件:原样透传给前端,供 UI 渲染"正在写入的文件路径 / 实时文件内容"等。
                // 不计入 chatHistory —— 这些属于交互细节,不是对话正文。
                return List.of(chunk);
            }
            default -> {
                log.error("不支持的消息类型: {}", typeEnum);
                return List.of();
            }
        }
    }
}


