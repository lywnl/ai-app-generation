package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link ToolMessageCollapser} 测试:
 * <ul>
 *   <li><b>纯函数单测</b>({@link ToolMessageCollapser#mergeLastTurn}):把"最后一条 UserMessage 之后的全部
 *       原始工具消息"折叠为恒 1 条 {@link AiMessage}(复用 MySQL 同款合并文本),与原始工具次数 N 无关;
 *       降级类用例(无 User / 空文本)验证 best-effort 原样返回,绝不破坏 user/ai 交替。</li>
 *   <li><b>集成测</b>(真实 {@link MessageWindowChatMemory} + 共享 {@link InMemoryChatMemoryStore}):
 *       验证 {@code collapseLastTurn} 真正释放窗口槽位(2N+1 → 2)、{@code restore} 丢弃自检残留。</li>
 * </ul>
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
class ToolMessageCollapserTest {

    private static ToolExecutionRequest req(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    /** 普通工具轮:[User, Ai(toolReq), ToolResult, Ai(final)] + "merged" → [User, Ai("merged")]。 */
    @Test
    void mergeCollapsesNormalToolTurnToSingleAiMessage() {
        ToolExecutionRequest r = req("t1", "writeFile");
        List<ChatMessage> raw = List.of(
                UserMessage.from("做一个待办App"),
                AiMessage.from(r),
                ToolExecutionResultMessage.from(r, "[工具调用] 写入文件 App.vue\n```vue\n...\n```"),
                AiMessage.from("已生成待办App"));

        List<ChatMessage> merged = ToolMessageCollapser.mergeLastTurn(raw, "已生成待办App(合并文本)");

        assertEquals(2, merged.size(), "一轮工具消息应折叠为 User + 恒 1 条 Ai");
        assertInstanceOf(UserMessage.class, merged.get(0));
        assertInstanceOf(AiMessage.class, merged.get(1));
        assertEquals("已生成待办App(合并文本)", ((AiMessage) merged.get(1)).text(), "折叠那条文本应逐字复用合并文本");
        assertFalse(((AiMessage) merged.get(1)).hasToolExecutionRequests(), "折叠后那条 Ai 不应再含 toolReq");
        assertTrue(merged.stream().noneMatch(m -> m instanceof ToolExecutionResultMessage), "不应残留 ToolExecutionResultMessage");
    }

    /** 含既往已合并轮:只折叠最后一轮,前缀的 User/Ai 合并态原样穿过。 */
    @Test
    void mergeOnlyFoldsLastTurnKeepingEarlierMergedPrefix() {
        ToolExecutionRequest r = req("t2", "modifyFile");
        List<ChatMessage> raw = List.of(
                UserMessage.from("第1轮:做待办App"),
                AiMessage.from("已生成待办App"),                 // 既往已合并轮
                UserMessage.from("第2轮:加搜索框"),
                AiMessage.from(r),                                // 本轮原始工具消息(应被折叠)
                ToolExecutionResultMessage.from(r, "[工具调用] 修改文件 App.vue"),
                AiMessage.from("已加搜索框"));

        List<ChatMessage> merged = ToolMessageCollapser.mergeLastTurn(raw, "已加搜索框(合并)");

        assertEquals(4, merged.size(), "只折叠最后一轮,前缀既往轮原样保留");
        assertEquals("已生成待办App", ((AiMessage) merged.get(1)).text(), "既往合并轮不动");
        assertEquals("第2轮:加搜索框", ((UserMessage) merged.get(2)).singleText(), "本轮 User 定界点保留");
        assertEquals("已加搜索框(合并)", ((AiMessage) merged.get(3)).text(), "本轮折叠为合并文本");
    }

    /** messages[0] 为 SystemMessage 时,system 前缀在折叠后被保留。 */
    @Test
    void mergeKeepsSystemMessagePrefix() {
        ToolExecutionRequest r = req("t3", "readFile");
        List<ChatMessage> raw = List.of(
                SystemMessage.from("你是 Vue 代码生成助手"),
                UserMessage.from("做App"),
                AiMessage.from(r),
                ToolExecutionResultMessage.from(r, "[工具调用] 读取文件 App.vue"),
                AiMessage.from("完成"));

        List<ChatMessage> merged = ToolMessageCollapser.mergeLastTurn(raw, "完成(合并)");

        assertEquals(3, merged.size(), "System + User + 折叠 Ai");
        assertInstanceOf(SystemMessage.class, merged.get(0), "System 前缀应保留");
        assertInstanceOf(UserMessage.class, merged.get(1));
        assertEquals("完成(合并)", ((AiMessage) merged.get(2)).text());
    }

    /** 幂等:对已是 [User, Ai("merged")] 的序列再折叠,结果不变。 */
    @Test
    void mergeIsIdempotentOnAlreadyMerged() {
        List<ChatMessage> raw = List.of(
                UserMessage.from("做App"),
                AiMessage.from("已生成"));

        List<ChatMessage> merged = ToolMessageCollapser.mergeLastTurn(raw, "已生成");

        assertEquals(2, merged.size(), "已合并态再折叠应不变");
        assertInstanceOf(UserMessage.class, merged.get(0));
        assertEquals("已生成", ((AiMessage) merged.get(1)).text());
    }

    /** 无 UserMessage 无法定界 → 原样返回,不抛异常。 */
    @Test
    void mergeReturnsRawWhenNoUserMessage() {
        List<ChatMessage> raw = List.of(AiMessage.from("孤儿AI消息,无User定界"));

        List<ChatMessage> merged = ToolMessageCollapser.mergeLastTurn(raw, "merged");

        assertEquals(1, merged.size(), "无 UserMessage 原样返回");
        assertEquals("孤儿AI消息,无User定界", ((AiMessage) merged.get(0)).text());
    }

    /** 空白/ null 文本 → 原样返回(绝不折成 [..., User] 丢掉 AI 轮,否则破坏 user/ai 交替)。 */
    @Test
    void mergeReturnsRawWhenTextBlank() {
        ToolExecutionRequest r = req("t4", "readFile");
        List<ChatMessage> raw = List.of(
                UserMessage.from("做App"),
                AiMessage.from(r),
                ToolExecutionResultMessage.from(r, "result"),
                AiMessage.from("done"));

        assertEquals(raw.size(), ToolMessageCollapser.mergeLastTurn(raw, "").size(), "空字符串原样返回");
        assertEquals(raw.size(), ToolMessageCollapser.mergeLastTurn(raw, "   ").size(), "纯空白原样返回");
        assertEquals(raw.size(), ToolMessageCollapser.mergeLastTurn(raw, null).size(), "null 文本原样返回");
    }

    // ---------- 集成测:真实 MessageWindowChatMemory + 共享 InMemoryChatMemoryStore ----------

    /** 灌入"User + N=7 工具轮"(2N+1=15 条原始消息)→ collapseLastTurn 后真实窗口恒降到 2 条,且经 LayeredChatMemory 仍 user/ai 交替。 */
    @Test
    void collapseLastTurnReducesRealWindowToSinglePairAndKeepsAlternation() {
        long appId = 555L;
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .id(appId).chatMemoryStore(store)
                .maxMessages(Integer.MAX_VALUE).build();

        memory.add(UserMessage.from("做一个待办App"));
        for (int i = 1; i <= 7; i++) {
            ToolExecutionRequest r = req("t" + i, "writeFile");
            memory.add(AiMessage.from(r));
            memory.add(ToolExecutionResultMessage.from(r, "[工具调用] 写入文件 f" + i + ".vue"));
        }
        assertEquals(15, memory.messages().size(), "折叠前应为 2N+1=15 条原始消息");

        ToolMessageCollapser collapser = new ToolMessageCollapser(store);
        ToolMessageCollapser.CollapseResult result =
                collapser.collapseLastTurn(appId, "已生成待办App(合并文本)");

        assertEquals(ToolMessageCollapser.CollapseStatus.COLLAPSED, result.status());
        assertEquals(2, result.messages().size());
        assertEquals(2, memory.messages().size(), "折叠后窗口恒降到 User + 1 条 Ai(与 N 无关)");
        assertInstanceOf(UserMessage.class, memory.messages().get(0));
        assertInstanceOf(AiMessage.class, memory.messages().get(1));
        assertEquals("已生成待办App(合并文本)", ((AiMessage) memory.messages().get(1)).text());

        // 经 LayeredChatMemory 包裹(L1/L2 未表态→只剩 L0)后仍满足 user/ai 严格交替
        MemorySummaryService summaryService = mock(MemorySummaryService.class);
        when(summaryService.getCurrentSummary(appId)).thenReturn("");
        UserMemoryService userMemoryService = mock(UserMemoryService.class);
        when(userMemoryService.recallByApp(appId)).thenReturn("");
        LayeredChatMemory layered = new LayeredChatMemory(memory, summaryService, userMemoryService);
        List<ChatMessage> layeredMsgs = layered.messages();
        for (int i = 1; i < layeredMsgs.size(); i++) {
            assertNotEquals(layeredMsgs.get(i - 1).type(), layeredMsgs.get(i).type(), "位置 " + i + " 连续同角色");
        }
    }

    @Test
    void collapseDistinguishesEmptyBoundaryAndStoreFailure() {
        long appId = 557L;
        ChatMemoryStore store = mock(ChatMemoryStore.class);
        ToolMessageCollapser collapser = new ToolMessageCollapser(store);

        when(store.getMessages(appId)).thenReturn(List.of());
        assertEquals(ToolMessageCollapser.CollapseStatus.NO_MESSAGES,
                collapser.collapseLastTurn(appId, "正文").status());

        when(store.getMessages(appId)).thenReturn(List.of(AiMessage.from("孤立 AI")));
        assertEquals(ToolMessageCollapser.CollapseStatus.NO_USER_BOUNDARY,
                collapser.collapseLastTurn(appId, "正文").status());

        when(store.getMessages(appId)).thenThrow(new IllegalStateException("redis down"));
        assertEquals(ToolMessageCollapser.CollapseStatus.STORE_FAILED,
                collapser.collapseLastTurn(appId, "正文").status());
    }

}
