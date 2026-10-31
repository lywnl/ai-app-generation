package com.lyw.appgeneration.ai.memory;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 回合结束时同步 L0。Vue 合法原文通过来源关闭接口保留，并单独关联后端终态；
 * 旧的折叠与丢弃入口仍用于兼容调用，不再作为 Vue 正常收尾的无条件操作。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
@Slf4j
@Component
public class ToolMessageCollapser {

    private final AtomicChatMemoryStore store;

    public ToolMessageCollapser(ChatMemoryStore store) {
        this.store = store instanceof AtomicChatMemoryStore atomicStore
                ? atomicStore
                : new AtomicChatMemoryStore(store);
    }

    /** Vue 正常收尾保留合法原文；旧折叠接口仅保留给既有兼容调用。 */
    public CollapseResult finishLastTurn(com.lyw.appgeneration.core.handler.VueTurnContext context,
                                         com.lyw.appgeneration.model.entity.ChatHistory saved,
                                         com.lyw.appgeneration.service.MemorySummarySnapshot summary) {
        try {
            if (saved == null || !java.util.Objects.equals(saved.getAppId(), context.appId())
                    || !java.util.Objects.equals(saved.getUserId(), context.userId()) || summary == null
                    || context.turnState().stage() != com.lyw.appgeneration.core.handler.VueTurnContext.TurnStage.FINALIZING) {
                return new CollapseResult(CollapseStatus.STORE_FAILED, List.of());
            }
            AdmissionDeadline deadline = AdmissionDeadline.start(java.time.Duration.ofSeconds(60));
            return store.withMemoryLock(context.appId(), deadline, () -> {
                if (!store.supportsSourceSnapshots()) return new CollapseResult(CollapseStatus.STORE_FAILED, List.of());
                L0Snapshot source = store.getSnapshot(context.appId());
                if (!source.hasKnownSource()) return new CollapseResult(CollapseStatus.STORE_FAILED, source.messages());
                L0Snapshot closed = L0SourceOperations.finishTurn(source, context.userHistoryId(), context.turnId(),
                        saved.getId(), saved.getMemoryOutcome(), saved.getMemoryMessage(), summary.summaryRevision());
                if (closed.equals(source)) return new CollapseResult(CollapseStatus.COLLAPSED, source.messages());
                if (store.replaceSnapshotIfMatches(context.appId(), source, closed, deadline) != DeadlineAwareReplaceResult.REPLACED) {
                    return new CollapseResult(CollapseStatus.STORE_FAILED, source.messages());
                }
                // 保持原同步结果类型及指标标签，COLLAPSED 在此表示已完成来源同步。
                return new CollapseResult(CollapseStatus.COLLAPSED, closed.messages());
            });
        } catch (RuntimeException exception) {
            log.warn("L0 回合来源关闭失败 appId={} type={}", context.appId(), exception.getClass().getSimpleName());
            return new CollapseResult(CollapseStatus.STORE_FAILED, List.of());
        }
    }

    /**
     * 把一轮 AI 工具调用折叠为恒 1 条 {@link AiMessage}:定位最后一条 {@link UserMessage},砍掉其后的全部
     * 原始工具消息,贴上 1 条调用方提供的可信记忆投影 {@code AiMessage}。<b>与原始工具次数 N 无关</b>。
     *
     * <p>降级(best-effort,均原样返回新副本,不抛异常):
     * <ul>
     *   <li>{@code memoryAiText} 空白:不折叠 —— 否则会折成 {@code [..., User]} 丢掉 AI 轮,使窗口以
     *       {@code UserMessage} 结尾,下一轮再追加 User → 连续同角色被 API 拒绝;</li>
     *   <li>无 {@link UserMessage}:无法定界,不折叠。</li>
     * </ul>
     * 幂等:对 {@code [..., User, Ai(memoryProjection)]} 再跑一次结果不变。System 前缀被保留。
     */
    static List<ChatMessage> mergeLastTurn(
            List<ChatMessage> raw, String memoryAiText) {
        if (raw == null) {
            return new ArrayList<>();
        }
        if (StrUtil.isBlank(memoryAiText)) {
            return new ArrayList<>(raw);
        }
        int lastUser = -1;
        for (int i = raw.size() - 1; i >= 0; i--) {
            if (raw.get(i) instanceof UserMessage) {
                lastUser = i;
                break;
            }
        }
        if (lastUser == -1) {
            return new ArrayList<>(raw);
        }
        // 前缀:System? + 既往已合并轮(User/Ai) + 本轮 User;其后原始工具消息全部丢弃,替换为 1 条
        List<ChatMessage> result = new ArrayList<>(raw.subList(0, lastUser + 1));
        result.add(AiMessage.from(memoryAiText));
        return result;
    }

    /**
     * 读 store → {@link #mergeLastTurn} → 写回 store,把本轮工具消息折叠为恒 1 条。
     * {@code MessageWindowChatMemory} 无状态(每次 {@code messages()} 直读 store),故写回后工厂缓存的
     * delegate 下次读取自动反映,无需 clear()+重灌。
     *
     * @return 结构化折叠结果；调用方可区分空窗口、无 User 边界、非法文本和存储失败
     */
    public CollapseResult collapseLastTurn(long appId, String memoryAiText) {
        try {
            return store.withMemoryLock(appId, () -> {
                List<ChatMessage> raw = store.getMessages(appId);
                if (raw.isEmpty()) {
                    return new CollapseResult(
                            CollapseStatus.NO_MESSAGES, List.of());
                }
                if (StrUtil.isBlank(memoryAiText)) {
                    return new CollapseResult(
                            CollapseStatus.INVALID_TEXT, raw);
                }
                boolean hasUserBoundary = raw.stream()
                        .anyMatch(UserMessage.class::isInstance);
                if (!hasUserBoundary) {
                    return new CollapseResult(
                            CollapseStatus.NO_USER_BOUNDARY, raw);
                }
                List<ChatMessage> merged = mergeLastTurn(
                        raw, memoryAiText);
                if (!store.replaceMessagesIfMatches(appId, raw, merged)) {
                    return new CollapseResult(
                            CollapseStatus.STORE_FAILED, raw);
                }
                return new CollapseResult(
                        CollapseStatus.COLLAPSED, merged);
            });
        } catch (Exception e) {
            log.warn("L0 窗口工具消息折叠失败,降级保留原始多条: appId={}, error={}", appId, e.getMessage());
            return new CollapseResult(CollapseStatus.STORE_FAILED, List.of());
        }
    }

    /**
     * 删除最后一条用户消息及其后的全部工具链尾部。
     *
     * <p>未完成工具链没有可供后续模型信任的稳定 AI 结果；保留用户消息会形成
     * 孤立尾部，追加下一轮用户消息后又会破坏角色交替，因此必须整轮删除。</p>
     */
    public CollapseResult discardLastTurn(long appId) {
        try {
            return store.withMemoryLock(appId, () -> {
                List<ChatMessage> raw = store.getMessages(appId);
                if (raw.isEmpty()) {
                    return new CollapseResult(
                            CollapseStatus.NO_MESSAGES, List.of());
                }
                int lastUser = lastUserIndex(raw);
                if (lastUser < 0) {
                    return new CollapseResult(
                            CollapseStatus.NO_USER_BOUNDARY, raw);
                }
                List<ChatMessage> retained = List.copyOf(
                        raw.subList(0, lastUser));
                if (!store.replaceMessagesIfMatches(
                        appId, raw, retained)) {
                    return new CollapseResult(
                            CollapseStatus.STORE_FAILED, raw);
                }
                return new CollapseResult(
                        CollapseStatus.COLLAPSED, retained);
            });
        } catch (Exception exception) {
            log.warn("L0 未完成工具链整轮删除失败,appId={},errorType={}",
                    appId, exception.getClass().getSimpleName());
            return new CollapseResult(
                    CollapseStatus.STORE_FAILED, List.of());
        }
    }

    private int lastUserIndex(List<ChatMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessage) {
                return index;
            }
        }
        return -1;
    }

    public enum CollapseStatus {
        COLLAPSED,
        NO_MESSAGES,
        NO_USER_BOUNDARY,
        INVALID_TEXT,
        STORE_FAILED
    }

    public record CollapseResult(CollapseStatus status, List<ChatMessage> messages) {

        public CollapseResult {
            status = java.util.Objects.requireNonNull(status, "status 不能为空");
            messages = List.copyOf(java.util.Objects.requireNonNull(messages,
                    "messages 不能为空"));
        }
    }

}
