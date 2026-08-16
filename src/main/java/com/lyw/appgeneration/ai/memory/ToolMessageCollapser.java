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
 * 把"一轮 AI 工具调用"在 L0 热窗口里折叠为恒 1 条 {@link dev.langchain4j.data.message.AiMessage},
 * 与 MySQL / 冷启动的合并态对齐 —— 一轮 AI 输出无论调用了多少次工具,折叠后恒占 1 个窗口槽位。
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

    /**
     * 把一轮 AI 工具调用折叠为恒 1 条 {@link AiMessage}:定位最后一条 {@link UserMessage},砍掉其后的全部
     * 原始工具消息,贴上 1 条复用 MySQL 同款合并文本的 {@code AiMessage}。<b>与原始工具次数 N 无关</b>。
     *
     * <p>降级(best-effort,均原样返回新副本,不抛异常):
     * <ul>
     *   <li>{@code mergedAiText} 空白:不折叠 —— 否则会折成 {@code [..., User]} 丢掉 AI 轮,使窗口以
     *       {@code UserMessage} 结尾,下一轮再追加 User → 连续同角色被 API 拒绝;</li>
     *   <li>无 {@link UserMessage}:无法定界,不折叠。</li>
     * </ul>
     * 幂等:对 {@code [..., User, Ai(merged)]} 再跑一次结果不变。System 前缀(若 {@code raw[0]} 为系统消息)被保留。
     */
    static List<ChatMessage> mergeLastTurn(List<ChatMessage> raw, String mergedAiText) {
        if (raw == null) {
            return new ArrayList<>();
        }
        if (StrUtil.isBlank(mergedAiText)) {
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
        result.add(AiMessage.from(mergedAiText));
        return result;
    }

    /**
     * 读 store → {@link #mergeLastTurn} → 写回 store,把本轮工具消息折叠为恒 1 条。
     * {@code MessageWindowChatMemory} 无状态(每次 {@code messages()} 直读 store),故写回后工厂缓存的
     * delegate 下次读取自动反映,无需 clear()+重灌。
     *
     * @return 结构化折叠结果；调用方可区分空窗口、无 User 边界、非法文本和存储失败
     */
    public CollapseResult collapseLastTurn(long appId, String mergedAiText) {
        try {
            return store.withMemoryLock(appId, () -> {
                List<ChatMessage> raw = store.getMessages(appId);
                if (raw.isEmpty()) {
                    return new CollapseResult(
                            CollapseStatus.NO_MESSAGES, List.of());
                }
                if (StrUtil.isBlank(mergedAiText)) {
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
                        raw, mergedAiText);
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
