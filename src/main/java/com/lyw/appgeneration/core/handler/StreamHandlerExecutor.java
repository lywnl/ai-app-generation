package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 流处理器执行器
 * 根据代码生成类型创建合适的流处理器：
 * 1. 传统的 Flux<String> 流（HTML、MULTI_FILE） -> SimpleTextStreamHandler
 * 2. TokenStream 格式的复杂流（VUE_PROJECT） -> JsonMessageStreamHandler
 */
@Slf4j
@Component
public class StreamHandlerExecutor {

    @Resource
    private JsonMessageStreamHandler jsonMessageStreamHandler;

    @Resource
    private MemorySummaryService memorySummaryService;

    @Resource
    private UserMemoryService userMemoryService;

    /**
     * 创建流处理器并处理聊天历史记录
     *
     * @param originFlux         原始流
     * @param chatHistoryService 聊天历史服务
     * @param appId              应用ID
     * @param loginUser          登录用户
     * @param codeGenType        代码生成类型
     * @return 处理后的流
     */
    public Flux<String> doExecute(Flux<String> originFlux,
                                  ChatHistoryService chatHistoryService,
                                  long appId, User loginUser, CodeGenTypeEnum codeGenType) {
        return switch (codeGenType) {
            case VUE_PROJECT -> throw new IllegalArgumentException(
                    "Vue 流必须使用绑定精确回合上下文的 doExecuteVue");
            case HTML, MULTI_FILE -> // 简单文本处理器不需要依赖注入
                    new SimpleTextStreamHandler().handle(originFlux, chatHistoryService, appId, loginUser, memorySummaryService, userMemoryService);
        };
    }

    /** Vue 回合专用执行入口，稳定记忆由 {@link VueTurnFinalizer} 唯一持有。 */
    public Flux<String> doExecuteVue(
            Flux<String> originFlux, VueTurnContext turnContext) {
        return jsonMessageStreamHandler.handle(originFlux, turnContext);
    }
}
