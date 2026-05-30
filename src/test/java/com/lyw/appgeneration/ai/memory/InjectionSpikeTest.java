package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 分层记忆架构一期 —— Task0 Spike。
 * <p>
 * 目的:在编写真正的 {@code LayeredChatMemory} 装饰器之前,用最小测试钉死两个关键假设,
 * 这两个假设决定了"前置 L1 摘要 + 依赖 MessageWindowChatMemory 内置 tool 对保护"方案是否成立:
 * <ol>
 *   <li>在 {@code messages()} 头部前置一对 [UserMessage 摘要 + AiMessage 确认] 后,
 *       整个消息序列仍满足 user/ai 严格交替(DeepSeek/OpenAI 兼容 API 不接受连续同角色)。</li>
 *   <li>LangChain4j 的 {@link MessageWindowChatMemory} 在驱逐含 {@code ToolExecutionRequest} 的
 *       {@link AiMessage} 时,会内置地连带驱逐对应的 {@link ToolExecutionResultMessage}
 *       (否则会留下 orphan tool result,API 报错)。</li>
 * </ol>
 * 这是 spike:仅验证现有库行为,不引入任何生产代码。
 */
class InjectionSpikeTest {

    /** 假设1:前置一对 (User摘要, AI确认) 后,整个序列不出现连续同角色。 */
    @Test
    void prependSummaryPairKeepsAlternation() {
        MessageWindowChatMemory delegate = MessageWindowChatMemory.withMaxMessages(100);
        delegate.add(UserMessage.from("第1轮:做一个待办App"));
        delegate.add(AiMessage.from("好的,已生成"));

        String summary = "# 应用目标\n待办清单App";
        List<ChatMessage> result = new ArrayList<>();
        result.add(UserMessage.from("以下是早期对话摘要,供参考:\n" + summary));
        result.add(AiMessage.from("明白,我会基于摘要继续。"));
        result.addAll(delegate.messages());

        for (int i = 1; i < result.size(); i++) {
            assertNotEquals(result.get(i - 1).type(), result.get(i).type(),
                "位置 " + i + " 出现连续同角色,会被 API 拒绝");
        }
    }

    /**
     * 假设2:MessageWindowChatMemory 在驱逐含 {@link ToolExecutionRequest} 的 {@link AiMessage} 时,
     * 会内置地连带驱逐其后紧随的 {@link ToolExecutionResultMessage}(成对驱逐)。
     * <p>
     * 关键:场景必须让那条含 toolReq 的 AiMessage 成为"必被淘汰的最老消息",否则它若仍留在窗口里,
     * 断言会因 toolReq 仍在而假性通过——这正是原 spike 的判别力缺陷。
     * <p>
     * 构造序列(maxMessages=2,按加入顺序,AiMessage(toolReq) 即处于最老端):
     * <pre>
     *   AiMessage(toolReq) -&gt; ToolExecutionResultMessage -&gt; u2(User) -&gt; AiMessage(done) -&gt; u3(User)
     * </pre>
     * LangChain4j 的 ensureCapacity 从最老端循环驱逐:
     * <ol>
     *   <li>驱逐 AiMessage(toolReq)(命中 hasToolExecutionRequests)-&gt; 连带驱逐紧随的 result;</li>
     *   <li>仍超容,再驱逐 u2(User);</li>
     *   <li>终态 = [AiMessage(done), u3(User)],size=2 停止。</li>
     * </ol>
     * 终态特意保留一条<b>不含 toolReq 的</b> AiMessage(done),使两条断言都在实打实地检验成对驱逐,
     * 而不会因窗口恰好不含任何 AiMessage 而空洞通过。
     */
    @Test
    void messageWindowEvictsToolPairTogether() {
        MessageWindowChatMemory mem = MessageWindowChatMemory.withMaxMessages(2);

        // AiMessage(toolReq) 置于最老端,使其成为必被淘汰的消息
        ToolExecutionRequest req = ToolExecutionRequest.builder().id("t1").name("read").arguments("{}").build();
        mem.add(AiMessage.from(req));
        mem.add(ToolExecutionResultMessage.from(req, "result"));
        mem.add(UserMessage.from("u2"));
        mem.add(AiMessage.from("done"));
        mem.add(UserMessage.from("u3")); // 第5条,触发循环驱逐至窗口剩 2 条

        List<ChatMessage> remaining = mem.messages();

        // 断言1:含 toolReq 的 AiMessage 确实已被驱逐(核心:证明真的触发了"驱逐 toolReq-AiMessage"这条路径)
        boolean toolReqAiStillPresent = remaining.stream()
            .anyMatch(m -> m instanceof AiMessage am && am.hasToolExecutionRequests());
        assertFalse(toolReqAiStillPresent,
            "含 ToolExecutionRequest 的 AiMessage 未被驱逐,本场景没测到成对驱逐路径");

        // 断言2:窗口内不存在任何 ToolExecutionResultMessage(即 result 已被连带删,无 orphan)
        boolean anyToolResultPresent = remaining.stream()
            .anyMatch(m -> m instanceof ToolExecutionResultMessage);
        assertFalse(anyToolResultPresent,
            "存在 orphan ToolExecutionResultMessage,LangChain4j 未成对驱逐");
    }
}
