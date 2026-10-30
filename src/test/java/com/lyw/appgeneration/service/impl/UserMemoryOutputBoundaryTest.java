package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.ConservativeChatTokenEstimator;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.mapper.AppMemoryExtractCursorMapper;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.AppMemoryExtractCursor;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector;
import com.lyw.appgeneration.service.ChatHistoryService;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

import static com.lyw.appgeneration.service.impl.UserMemoryState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserMemoryOutputBoundaryTest {
    private final VersionedUserMemoryRepository repository = mock(VersionedUserMemoryRepository.class);
    private final ChatHistoryService history = mock(ChatHistoryService.class);
    private final AppMemoryExtractCursorMapper cursor = mock(AppMemoryExtractCursorMapper.class);
    private final AppMapper apps = mock(AppMapper.class);
    private final ChatModel model = mock(ChatModel.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final MemoryTokenProperties properties = new MemoryTokenProperties();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private UserMemoryServiceImpl service;
    private static final String ADD = """
            {"operations":[{"action":"ADD","category":"视觉风格","attribute":"主色","content":"新应用主色使用紫色",
            "scope":"USER","evidenceType":"EXPLICIT","turnIds":["11"]}]}
            """;

    @BeforeEach void 初始化() {
        when(repository.load(7)).thenReturn(Snapshot.of(List.of()));
        when(repository.loadCurrent(7)).thenAnswer(inv -> repository.load(7));
        when(repository.nextId()).thenAnswer(inv -> ids.incrementAndGet());
        when(repository.evidence(any())).thenReturn(java.util.Map.of());
        when(apps.selectOneById(100L)).thenReturn(App.builder().id(100L).userId(7L).build());
        when(cursor.insert(any())).thenReturn(1);
        when(cursor.update(any())).thenReturn(1);
        when(history.listMessagesAfterCursor(eq(100L), anyLong(), anyInt())).thenReturn(List.of(
                ChatHistory.builder().id(11L).messageType("user").message("以后所有应用主色用紫色").build(),
                ChatHistory.builder().id(12L).messageType("ai").message("已收到").memoryMessage("已收到")
                        .memoryOutcome(ChatMemoryOutcome.SUCCEEDED).build()));
        service = new UserMemoryServiceImpl(history, repository, cursor, apps, model,
                mock(ExecutorService.class), mock(TaskScheduler.class), redis, new AppDataLifecycleFence(),
                new ConservativeChatTokenEstimator(properties), properties, TransactionOperations.withoutTransaction(),
                new MemoryCompressionMetricsCollector(registry));
    }
    private final AtomicLong ids = new AtomicLong(10000);
    @AfterEach void 关闭() { registry.close(); }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{}", "not-json", "{\"operations\":[{}]}", "{\"operations\":[],\"sql\":\"DELETE\"}"})
    void 非法输出不写记忆也不推进游标(String raw) {
        when(model.chat(anyString())).thenReturn(raw);
        service.extractNow(7L, 100L);
        verify(repository, never()).insert(any());
        verify(repository, never()).update(any(), any());
        assertCursor(0, 1);
    }

    @Test void 未展示目标不能被写入() {
        when(model.chat(anyString())).thenReturn("""
                {"operations":[{"action":"RETRACT","targetId":"999","scope":"USER","evidenceType":"EXPLICIT","turnIds":["11"]}]}
                """);
        service.extractNow(7L, 100L);
        verify(repository, never()).update(any(), any());
        assertCursor(0, 1);
    }

    @Test void 非所属用户不能抽取应用证据() {
        service.extractNow(8L, 100L);
        verify(model, never()).chat(anyString());
        verify(repository, never()).insert(any());
    }

    @Test void 只有当前应用的要求正常推进且不动正式缓存() {
        when(model.chat(anyString())).thenReturn(ADD.replace("\"USER\"", "\"APP\""));
        service.extractNow(7L, 100L);
        verify(repository, never()).insert(any());
        verifyNoInteractions(redis);
        assertCursor(12, 0);
    }

    @Test void 纯隐式候选新增不读写或删除正式缓存() {
        when(model.chat(anyString())).thenReturn(ADD.replace("EXPLICIT", "IMPLICIT"));
        service.extractNow(7L, 100L);
        ArgumentCaptor<Item> saved = ArgumentCaptor.forClass(Item.class);
        verify(repository).insert(saved.capture());
        assertEquals(Kind.CANDIDATE, saved.getValue().kind());
        assertEquals("CANDIDATE", saved.getValue().status());
        verifyNoInteractions(redis);
        assertCursor(12, 0);
    }

    @Test void 候选写入在Redis不可用时仍能提交() {
        doThrow(new IllegalStateException("缓存不可用")).when(redis).delete(anyString());
        when(model.chat(anyString())).thenReturn(ADD.replace("EXPLICIT", "IMPLICIT"));
        service.extractNow(7L, 100L);
        verify(repository).insert(any());
        verifyNoInteractions(redis);
        assertCursor(12, 0);
    }

    @Test void 模型原始输出超过预算时整批失败() {
        properties.setMaxOutputTokens(20);
        when(model.chat(anyString())).thenReturn(ADD);
        service.extractNow(7L, 100L);
        verify(repository, never()).insert(any());
        assertCursor(0, 1);
    }

    @Test void 分页覆盖旧目标后才一次性提交() {
        List<Item> rows = LongStream.rangeClosed(1, 300).mapToObj(this::formal).toList();
        when(repository.load(7)).thenReturn(Snapshot.of(rows));
        when(model.chat(anyString())).thenAnswer(inv -> {
            String prompt = inv.getArgument(0);
            assertTrue(new ConservativeChatTokenEstimator(properties).estimateText(prompt)
                    <= properties.getAsyncCompressionThreshold());
            return prompt.contains("\"id\":\"1\"") ? """
                    {"operations":[{"action":"RETRACT","targetId":"1","scope":"USER","evidenceType":"EXPLICIT","turnIds":["11"]}]}
                    """ : "{\"operations\":[]}";
        });
        service.extractNow(7L, 100L);
        verify(model, atLeast(2)).chat(anyString());
        ArgumentCaptor<Item> next = ArgumentCaptor.forClass(Item.class);
        verify(repository).update(eq(rows.getFirst()), next.capture());
        assertEquals("REVOKED", next.getValue().status());
        assertCursor(12, 0);
    }

    @Test void 后续分页失败不提交前一页结果() {
        when(repository.load(7)).thenReturn(Snapshot.of(LongStream.rangeClosed(1, 300).mapToObj(this::formal).toList()));
        when(model.chat(anyString())).thenReturn(ADD).thenThrow(new IllegalStateException("模型超时"));
        service.extractNow(7L, 100L);
        verify(repository, never()).insert(any());
        verify(repository, never()).update(any(), any());
        assertCursor(0, 1);
    }

    @Test void 同类别多条正式缓存可召回且不接受注入文本() {
        UserPreferenceContract contract = new UserPreferenceContract(new ConservativeChatTokenEstimator(properties), properties);
        assertTrue(contract.isValidRecallCache("- 语言偏好:页面默认语言：中文\n- 语言偏好:代码注释语言：日语"));
        assertFalse(contract.isValidRecallCache("- 语言偏好:中文\n- 语言偏好:中文"));
        assertFalse(contract.isValidRecallCache("- 非法类别:中文"));
    }

    private Item formal(long id) {
        return Item.builder().id(id).userId(7).appId(100L).kind(Kind.FORMAL).category("视觉风格")
                .memoryKey("test." + id).attributeName("布局属性" + id).content("偏好规则" + id)
                .status("ACTIVE").evidenceType("EXPLICIT").evidenceCount(1).legacyEvidenceCount(1)
                .legacyEvidenceTurnId(1L).lastEvidenceTurnId(1).revision(1)
                .createTime(LocalDateTime.of(2026, 10, 30, 10, 0)).updateTime(LocalDateTime.of(2026, 10, 30, 10, 0)).build();
    }
    private void assertCursor(long id, int failCount) {
        ArgumentCaptor<AppMemoryExtractCursor> captured = ArgumentCaptor.forClass(AppMemoryExtractCursor.class);
        verify(cursor).insert(captured.capture());
        assertEquals(id, captured.getValue().getLastExtractedId());
        assertEquals(failCount, captured.getValue().getFailCount());
    }
}
