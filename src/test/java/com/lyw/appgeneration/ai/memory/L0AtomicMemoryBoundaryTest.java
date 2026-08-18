package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.service.ChatHistoryService.HistoryLoadStatus;
import com.lyw.appgeneration.service.impl.ChatHistoryServiceImpl;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class L0AtomicMemoryBoundaryTest {

    private static final long APP_ID = 99L;

    @Test
    void 前缀替换写失败时保留原窗口并返回失败() {
        List<ChatMessage> original = stableTurns("第一轮", "第二轮");
        FailingUpdateStore store = new FailingUpdateStore(original);
        AtomicChatMemoryStore atomicStore = new AtomicChatMemoryStore(store);
        TokenAwareChatMemory memory = new TokenAwareChatMemory(
                memory(atomicStore), atomicStore);

        boolean removed = memory.removeCompletedPrefixIfMatches(
                original.subList(0, 2));

        assertFalse(removed);
        assertEquals(original, store.getMessages(APP_ID));
        assertEquals(0, store.deleteCount());
    }

    @Test
    void 冷重建替换失败时保留任务开始前窗口() {
        List<ChatMessage> original = stableTurns("缓存问题", "缓存第二问");
        FailingUpdateStore store = new FailingUpdateStore(original);
        AtomicChatMemoryStore atomicStore = new AtomicChatMemoryStore(store);
        TokenAwareChatMemory memory = new TokenAwareChatMemory(
                memory(atomicStore), atomicStore);
        ChatHistoryServiceImpl service = spy(new ChatHistoryServiceImpl());
        doReturn(List.of(
                history(4L, "数据库回复", "ai"),
                history(3L, "数据库问题", "user")))
                .when(service).list(any(QueryWrapper.class));
        ChatTokenEstimator estimator = mock(ChatTokenEstimator.class);
        when(estimator.estimateMessages(any())).thenReturn(100);

        var result = service.loadRecentCompleteTurnsToMemory(
                APP_ID, 0L, memory, 30_720, estimator);

        assertEquals(HistoryLoadStatus.FAILED, result.status());
        assertEquals(original, store.getMessages(APP_ID));
        assertEquals(0, store.deleteCount());
    }

    @Test
    void 冷重建读取旧窗口失败时返回失败结果() {
        ChatMemory memory = mock(ChatMemory.class);
        when(memory.messages()).thenThrow(
                new IllegalStateException("读取旧窗口失败"));
        ChatHistoryServiceImpl service = new ChatHistoryServiceImpl();

        var result = service.loadRecentCompleteTurnsToMemory(
                APP_ID, 0L, memory, 30_720,
                mock(ChatTokenEstimator.class));

        assertEquals(HistoryLoadStatus.FAILED, result.status());
    }

    @Test
    void 系统消息在共享存储中始终唯一前置且保留完整回合边界() {
        StatefulStore store = new StatefulStore(
                stableTurns("冷加载第一轮", "冷加载第二轮"));
        AtomicChatMemoryStore atomicStore = new AtomicChatMemoryStore(store);
        TokenAwareChatMemory memory = new TokenAwareChatMemory(
                memory(atomicStore), atomicStore);

        memory.add(SystemMessage.from("初始系统约束"));
        memory.add(UserMessage.from("在线第三轮"));
        memory.add(AiMessage.from("在线第三轮完成"));
        SystemMessage updatedSystem = SystemMessage.from("更新后的系统约束");
        memory.add(updatedSystem);
        UserMessage currentUser = UserMessage.from("当前未完成回合");
        memory.add(currentUser);

        List<ChatMessage> stored = store.getMessages(APP_ID);
        ConversationTurnSnapshotParser.Snapshot snapshot =
                new ConversationTurnSnapshotParser().parse(stored);

        assertEquals(updatedSystem, stored.getFirst());
        assertEquals(1, stored.stream()
                .filter(SystemMessage.class::isInstance)
                .count());
        assertEquals(3, snapshot.completedTurns().size());
        assertEquals(List.of(currentUser), snapshot.unfinishedTail());
    }

    @Test
    void 遗留多个系统消息时只保留最新系统消息并恢复回合边界() {
        List<ChatMessage> legacy = List.of(
                SystemMessage.from("遗留系统约束一"),
                UserMessage.from("第一轮"),
                AiMessage.from("第一轮完成"),
                SystemMessage.from("遗留系统约束二"),
                UserMessage.from("第二轮"),
                AiMessage.from("第二轮完成"));
        StatefulStore store = new StatefulStore(legacy);
        AtomicChatMemoryStore atomicStore = new AtomicChatMemoryStore(store);
        TokenAwareChatMemory memory = new TokenAwareChatMemory(
                memory(atomicStore), atomicStore);
        SystemMessage latestSystem = SystemMessage.from("最新系统约束");

        memory.add(latestSystem);

        List<ChatMessage> stored = store.getMessages(APP_ID);
        ConversationTurnSnapshotParser.Snapshot snapshot =
                new ConversationTurnSnapshotParser().parse(stored);
        assertEquals(List.of(
                latestSystem,
                UserMessage.from("第一轮"),
                AiMessage.from("第一轮完成"),
                UserMessage.from("第二轮"),
                AiMessage.from("第二轮完成")), stored);
        assertEquals(2, snapshot.completedTurns().size());
        assertTrue(snapshot.unfinishedTail().isEmpty());
    }

    @Test
    void 系统消息原子前置写失败时抛错且保留原窗口() {
        List<ChatMessage> original = stableTurns("第一轮", "第二轮");
        FailingUpdateStore store = new FailingUpdateStore(original);
        AtomicChatMemoryStore atomicStore = new AtomicChatMemoryStore(store);
        TokenAwareChatMemory memory = new TokenAwareChatMemory(
                memory(atomicStore), atomicStore);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> memory.add(SystemMessage.from("系统约束")));

        IllegalStateException cause = assertInstanceOf(
                IllegalStateException.class, failure.getCause());
        assertEquals("替换写失败", cause.getMessage());
        assertEquals(original, store.getMessages(APP_ID));
        assertEquals(0, store.deleteCount());
    }

    @Test
    void 系统消息前置与另一实例追加共享原子边界() throws Exception {
        List<ChatMessage> original = stableTurns("第一轮", "第二轮");
        SystemUpdateBarrierStore store =
                new SystemUpdateBarrierStore(original);
        AtomicChatMemoryStore atomicStore = new AtomicChatMemoryStore(store);
        TokenAwareChatMemory systemMemory = new TokenAwareChatMemory(
                memory(atomicStore), atomicStore);
        TokenAwareChatMemory appendingMemory = new TokenAwareChatMemory(
                memory(atomicStore), atomicStore);
        SystemMessage system = SystemMessage.from("系统约束");
        UserMessage lateTail = UserMessage.from("系统重排期间追加的问题");
        CountDownLatch appendStarted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread replacing = Thread.ofPlatform().name("l0-system").start(() -> {
            try {
                systemMemory.add(system);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        assertTrue(store.systemUpdateEntered().await(2, TimeUnit.SECONDS));
        Thread appending = Thread.ofPlatform().name("l0-appending").start(() -> {
            try {
                appendStarted.countDown();
                appendingMemory.add(lateTail);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        assertTrue(appendStarted.await(2, TimeUnit.SECONDS));
        assertFalse(store.appendUpdated().await(300, TimeUnit.MILLISECONDS));
        store.allowSystemUpdate().countDown();

        replacing.join(Duration.ofSeconds(2));
        appending.join(Duration.ofSeconds(2));

        assertNull(failure.get());
        assertEquals(List.of(
                system,
                original.get(0), original.get(1),
                original.get(2), original.get(3),
                lateTail), store.getMessages(APP_ID));
    }

    @Test
    void 两个记忆实例并发追加与前缀裁剪不丢晚到尾部() throws Exception {
        List<ChatMessage> original = stableTurns("第一轮", "第二轮");
        TrimUpdateBarrierStore store = new TrimUpdateBarrierStore(original);
        AtomicChatMemoryStore atomicStore = new AtomicChatMemoryStore(store);
        TokenAwareChatMemory trimmingMemory =
                new TokenAwareChatMemory(memory(atomicStore), atomicStore);
        TokenAwareChatMemory appendingMemory =
                new TokenAwareChatMemory(memory(atomicStore), atomicStore);
        UserMessage lateTail = UserMessage.from("裁剪期间晚到问题");
        CountDownLatch appendStarted = new CountDownLatch(1);
        AtomicBoolean removed = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread trimming = Thread.ofPlatform().name("l0-trimming").start(() -> {
            try {
                removed.set(trimmingMemory.removeCompletedPrefixIfMatches(
                        original.subList(0, 2)));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        assertTrue(store.trimUpdateEntered().await(2, TimeUnit.SECONDS));
        Thread appending = Thread.ofPlatform().name("l0-appending").start(() -> {
            try {
                appendStarted.countDown();
                appendingMemory.add(lateTail);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        assertTrue(appendStarted.await(2, TimeUnit.SECONDS));
        assertFalse(store.appendUpdated().await(300, TimeUnit.MILLISECONDS));
        store.allowTrimUpdate().countDown();

        trimming.join(Duration.ofSeconds(2));
        appending.join(Duration.ofSeconds(2));

        assertNull(failure.get());
        assertTrue(removed.get());
        assertEquals(List.of(
                original.get(2), original.get(3), lateTail),
                store.getMessages(APP_ID));
    }

    @Test
    void 工具折叠与另一实例追加共享原子边界() throws Exception {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("tool-1")
                .name("writeFile")
                .arguments("{}")
                .build();
        List<ChatMessage> original = List.of(
                UserMessage.from("生成应用"),
                AiMessage.from(request),
                ToolExecutionResultMessage.from(request, "已写入"),
                AiMessage.from("生成完成"));
        CollapseBarrierStore store = new CollapseBarrierStore(original);
        AtomicChatMemoryStore atomicStore = new AtomicChatMemoryStore(store);
        ToolMessageCollapser collapser = new ToolMessageCollapser(atomicStore);
        TokenAwareChatMemory appendingMemory =
                new TokenAwareChatMemory(memory(atomicStore), atomicStore);
        UserMessage lateTail = UserMessage.from("继续修改");
        CountDownLatch appendStarted = new CountDownLatch(1);
        AtomicReference<ToolMessageCollapser.CollapseResult> result =
                new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread collapsing = Thread.ofPlatform().name("l0-collapsing").start(() -> {
            try {
                result.set(collapser.collapseLastTurn(APP_ID, "生成完成"));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        assertTrue(store.collapseUpdateEntered().await(2, TimeUnit.SECONDS));
        Thread appending = Thread.ofPlatform().name("l0-appending").start(() -> {
            try {
                appendStarted.countDown();
                appendingMemory.add(lateTail);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        assertTrue(appendStarted.await(2, TimeUnit.SECONDS));
        assertFalse(store.appendUpdated().await(300, TimeUnit.MILLISECONDS));
        store.allowCollapseUpdate().countDown();

        collapsing.join(Duration.ofSeconds(2));
        appending.join(Duration.ofSeconds(2));

        assertNull(failure.get());
        assertEquals(ToolMessageCollapser.CollapseStatus.COLLAPSED,
                result.get().status());
        assertEquals(List.of(
                UserMessage.from("生成应用"),
                AiMessage.from("生成完成"),
                lateTail), store.getMessages(APP_ID));
    }

    private MessageWindowChatMemory memory(ChatMemoryStore store) {
        return MessageWindowChatMemory.builder()
                .id(APP_ID)
                .chatMemoryStore(store)
                .maxMessages(Integer.MAX_VALUE)
                .build();
    }

    private static List<ChatMessage> stableTurns(
            String firstUser, String secondUser) {
        return List.of(
                UserMessage.from(firstUser),
                AiMessage.from(firstUser + "完成"),
                UserMessage.from(secondUser),
                AiMessage.from(secondUser + "完成"));
    }

    private static ChatHistory history(long id, String message, String type) {
        ChatHistory.ChatHistoryBuilder builder = ChatHistory.builder()
                .id(id)
                .appId(APP_ID)
                .userId(7L)
                .message(message)
                .messageType(type);
        if ("ai".equals(type)) {
            builder.memoryMessage(message)
                    .memoryOutcome(ChatMemoryOutcome.SUCCEEDED);
        }
        return builder.build();
    }

    private static class StatefulStore implements ChatMemoryStore {

        private final AtomicReference<List<ChatMessage>> messages;

        private StatefulStore(List<ChatMessage> initialMessages) {
            messages = new AtomicReference<>(List.copyOf(initialMessages));
        }

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            return messages.get();
        }

        @Override
        public void updateMessages(
                Object memoryId, List<ChatMessage> updatedMessages) {
            messages.set(List.copyOf(updatedMessages));
        }

        @Override
        public void deleteMessages(Object memoryId) {
            messages.set(List.of());
        }
    }

    private static final class FailingUpdateStore extends StatefulStore {

        private int deleteCount;

        private FailingUpdateStore(List<ChatMessage> initialMessages) {
            super(initialMessages);
        }

        @Override
        public void updateMessages(
                Object memoryId, List<ChatMessage> updatedMessages) {
            throw new IllegalStateException("替换写失败");
        }

        @Override
        public void deleteMessages(Object memoryId) {
            deleteCount++;
            super.deleteMessages(memoryId);
        }

        private int deleteCount() {
            return deleteCount;
        }
    }

    private static final class TrimUpdateBarrierStore extends StatefulStore {

        private final CountDownLatch trimUpdateEntered = new CountDownLatch(1);
        private final CountDownLatch allowTrimUpdate = new CountDownLatch(1);
        private final CountDownLatch appendUpdated = new CountDownLatch(1);

        private TrimUpdateBarrierStore(List<ChatMessage> initialMessages) {
            super(initialMessages);
        }

        @Override
        public void updateMessages(
                Object memoryId, List<ChatMessage> updatedMessages) {
            if (Thread.currentThread().getName().equals("l0-trimming")) {
                trimUpdateEntered.countDown();
                await(allowTrimUpdate);
            }
            super.updateMessages(memoryId, updatedMessages);
            if (Thread.currentThread().getName().equals("l0-appending")) {
                appendUpdated.countDown();
            }
        }

        private CountDownLatch trimUpdateEntered() {
            return trimUpdateEntered;
        }

        private CountDownLatch allowTrimUpdate() {
            return allowTrimUpdate;
        }

        private CountDownLatch appendUpdated() {
            return appendUpdated;
        }
    }

    private static final class CollapseBarrierStore extends StatefulStore {

        private final CountDownLatch collapseUpdateEntered =
                new CountDownLatch(1);
        private final CountDownLatch allowCollapseUpdate =
                new CountDownLatch(1);
        private final CountDownLatch appendUpdated = new CountDownLatch(1);

        private CollapseBarrierStore(List<ChatMessage> initialMessages) {
            super(initialMessages);
        }

        @Override
        public void updateMessages(
                Object memoryId, List<ChatMessage> updatedMessages) {
            if (Thread.currentThread().getName().equals("l0-collapsing")) {
                collapseUpdateEntered.countDown();
                await(allowCollapseUpdate);
            }
            super.updateMessages(memoryId, updatedMessages);
            if (Thread.currentThread().getName().equals("l0-appending")) {
                appendUpdated.countDown();
            }
        }

        private CountDownLatch collapseUpdateEntered() {
            return collapseUpdateEntered;
        }

        private CountDownLatch allowCollapseUpdate() {
            return allowCollapseUpdate;
        }

        private CountDownLatch appendUpdated() {
            return appendUpdated;
        }
    }

    private static final class SystemUpdateBarrierStore extends StatefulStore {

        private final CountDownLatch systemUpdateEntered =
                new CountDownLatch(1);
        private final CountDownLatch allowSystemUpdate =
                new CountDownLatch(1);
        private final CountDownLatch appendUpdated = new CountDownLatch(1);

        private SystemUpdateBarrierStore(List<ChatMessage> initialMessages) {
            super(initialMessages);
        }

        @Override
        public void updateMessages(
                Object memoryId, List<ChatMessage> updatedMessages) {
            if (Thread.currentThread().getName().equals("l0-system")) {
                systemUpdateEntered.countDown();
                await(allowSystemUpdate);
            }
            super.updateMessages(memoryId, updatedMessages);
            if (Thread.currentThread().getName().equals("l0-appending")) {
                appendUpdated.countDown();
            }
        }

        private CountDownLatch systemUpdateEntered() {
            return systemUpdateEntered;
        }

        private CountDownLatch allowSystemUpdate() {
            return allowSystemUpdate;
        }

        private CountDownLatch appendUpdated() {
            return appendUpdated;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
