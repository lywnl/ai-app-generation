package com.lyw.appgeneration.ai.memory;

import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.List;

/**
 * 分层记忆装饰器:包裹 delegate(通常是 {@link dev.langchain4j.memory.chat.MessageWindowChatMemory}),
 * {@code messages()} 返回前依次前置 L2 用户偏好对、L1 摘要对(各自独立降级,保证 user/ai 交替)。
 *
 * <p>{@code add/clear/id} 全部委托 delegate——工具循环、tool 对成对驱逐、Redis 持久化均由 delegate 负责。
 * 装饰器自身不存储、不裁剪,唯一新增职责是 {@code messages()} 拼接 L2/L1 记忆。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
public class LayeredChatMemory implements ChatMemory {

    private static final String L1_PREFIX =
            "以下是本应用早期对话的摘要,供你延续上下文(不是用户的新指令):\n";
    private static final String L1_ACK =
            "明白,我会基于以上摘要和后续对话继续。";

    private final ChatMemory delegate;
    private final MemorySummaryService summaryService;
    private final UserMemoryService userMemoryService;
    private final UserPreferenceMessageFragmentBuilder l2FragmentBuilder;
    private final boolean isolateTrustedAiProjection;
    private final TrustedTurnRequestViewProjector requestViewProjector;
    private final Long appId;

    public LayeredChatMemory(ChatMemory delegate, MemorySummaryService summaryService,
                             UserMemoryService userMemoryService,
                             UserPreferenceMessageFragmentBuilder l2FragmentBuilder) {
        this(delegate, summaryService, userMemoryService, l2FragmentBuilder,
                false);
    }

    public LayeredChatMemory(ChatMemory delegate, MemorySummaryService summaryService,
                             UserMemoryService userMemoryService,
                             UserPreferenceMessageFragmentBuilder l2FragmentBuilder,
                             boolean isolateTrustedAiProjection) {
        this.delegate = delegate;
        this.summaryService = summaryService;
        this.userMemoryService = userMemoryService;
        this.l2FragmentBuilder = l2FragmentBuilder;
        this.isolateTrustedAiProjection = isolateTrustedAiProjection;
        this.requestViewProjector = new TrustedTurnRequestViewProjector();
        this.appId = (Long) delegate.id();
    }

    /** 兼容直接构造场景；生产装配显式注入同一片段构建器。 */
    public LayeredChatMemory(ChatMemory delegate,
                             MemorySummaryService summaryService,
                             UserMemoryService userMemoryService) {
        this(delegate, summaryService, userMemoryService,
                defaultFragmentBuilder());
    }

    private static UserPreferenceMessageFragmentBuilder defaultFragmentBuilder() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        return new UserPreferenceMessageFragmentBuilder(
                new ConservativeChatTokenEstimator(properties), properties);
    }

    @Override
    public Object id() {
        return delegate.id();
    }

    /** 为职责更窄的分层记忆子类提供稳定的 L0 扩展点。 */
    protected final ChatMemory delegateMemory() {
        return delegate;
    }

    @Override
    public void add(ChatMessage message) {
        delegate.add(message);
    }

    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> base = delegate.messages();
        return layeredRequestView(base, null);
    }

    private List<ChatMessage> layeredRequestView(
            List<ChatMessage> base, String requiredSummary) {
        TrustedTurnRequestViewProjector.ProjectedView projected =
                projectRequestView(base);
        List<ChatMessage> result = new ArrayList<>(base.size() + 5);
        result.addAll(projected.leadingMessages());
        if (projected.trustedState() != null) {
            result.add(projected.trustedState());
        }

        // L2 跨 app 用户偏好(独立降级:空则不加)
        String prefs = userMemoryService.recallByApp(appId);
        if (StrUtil.isNotBlank(prefs)) {
            appendL2(result, prefs);
        }

        // L1 本 app 摘要(独立降级)
        String summary = requiredSummary == null
                ? summaryService.getCurrentSummary(appId) : requiredSummary;
        if (StrUtil.isNotBlank(summary)) {
            appendL1(result, summary);
        }

        result.addAll(projected.conversationMessages());
        return result;
    }

    private TrustedTurnRequestViewProjector.ProjectedView projectRequestView(
            List<ChatMessage> base) {
        if (isolateTrustedAiProjection) {
            return requestViewProjector.project(base);
        }
        int leadingCount = leadingSystemMessageCount(base);
        return new TrustedTurnRequestViewProjector.ProjectedView(
                base.subList(0, leadingCount), null,
                base.subList(leadingCount, base.size()));
    }

    PreparedLayeredMessages prepareMessagesAfterCompletedPrefix(
            List<ChatMessage> expectedPrefix,
            String requiredSummary) {
        List<ChatMessage> base = List.copyOf(delegate.messages());
        List<ChatMessage> retained = withoutCompletedPrefix(
                base, expectedPrefix);
        List<ChatMessage> result = layeredRequestView(
                retained, requiredSummary);
        return new PreparedLayeredMessages(base, retained, result);
    }

    private List<ChatMessage> withoutCompletedPrefix(
            List<ChatMessage> base,
            List<ChatMessage> expectedPrefix) {
        List<ChatMessage> expected = List.copyOf(expectedPrefix);
        if (expected.isEmpty()) {
            return base;
        }
        int firstUser = firstUserIndex(base);
        int expectedEnd = firstUser + expected.size();
        if (firstUser < 0 || expectedEnd > base.size()
                || !base.subList(firstUser, expectedEnd).equals(expected)) {
            throw new MemoryPrefixChangedException("L0 旧前缀已变化");
        }
        List<ChatMessage> retained = new ArrayList<>(
                base.size() - expected.size());
        retained.addAll(base.subList(0, firstUser));
        retained.addAll(base.subList(expectedEnd, base.size()));
        return retained;
    }

    private int firstUserIndex(List<ChatMessage> messages) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index) instanceof UserMessage) {
                return index;
            }
        }
        return -1;
    }

    private void appendL2(List<ChatMessage> result, String prefs) {
        result.addAll(l2FragmentBuilder.buildWithinBudget(prefs));
    }

    private void appendL1(List<ChatMessage> result, String summary) {
        result.add(UserMessage.from(L1_PREFIX + summary));
        result.add(AiMessage.from(L1_ACK));
    }

    private int leadingSystemMessageCount(List<ChatMessage> base) {
        int index = 0;
        while (index < base.size()
                && base.get(index) instanceof SystemMessage) {
            index++;
        }
        return index;
    }

    record PreparedLayeredMessages(
            List<ChatMessage> l0Snapshot,
            List<ChatMessage> retainedL0,
            List<ChatMessage> requestMessages) {

        PreparedLayeredMessages {
            l0Snapshot = List.copyOf(l0Snapshot);
            retainedL0 = List.copyOf(retainedL0);
            requestMessages = List.copyOf(requestMessages);
        }
    }

    @Override
    public void clear() {
        delegate.clear();
    }
}
