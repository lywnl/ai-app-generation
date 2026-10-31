package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.ConservativeChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.UserMemoryOperation;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.model.dto.memory.UserMemoryMutationRequest;
import com.lyw.appgeneration.model.dto.memory.UserMemoryMutationRequest.VersionReference;
import com.lyw.appgeneration.model.entity.AppMemoryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.lyw.appgeneration.service.impl.UserMemoryState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserMemoryManagementServiceImplTest {
    private static final long USER = 7;
    private final ManagementStore store = new ManagementStore();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final UserMemoryConsistencyCoordinator coordinator = new UserMemoryConsistencyCoordinator();
    private UserMemoryManagementServiceImpl service;

    @BeforeEach void 准备用户级管理服务() {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        service = new UserMemoryManagementServiceImpl(store, coordinator, redis,
                new ConservativeChatTokenEstimator(properties), properties, store.transactions(),
                Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test void 手动新增不伪造聊天回合且操作和证据可以互相追溯() {
        var result = service.change(USER, create("中文", "ACTIVE"));
        Item item = store.rows.get(Long.parseLong(result.targetId()));
        assertTrue(item.active());
        assertEquals(1, item.evidenceCount());
        assertNull(item.appId());
        Evidence evidence = store.ledgers.get(item.id()).values().iterator().next();
        assertEquals("MANUAL", evidence.sourceType());
        assertNull(evidence.appId());
        assertEquals("MANUAL", store.events.get(evidence.turnId()).getSourceType());
        assertEquals(0, coordinator.registeredUserCount());
        verify(redis, times(2)).delete("mem:pref:v3:7");
    }

    @Test void 手动候选在没有正式记忆时也不能被后续弱证据自动激活() {
        var result = service.change(USER, create("日语", "CANDIDATE"));
        Item candidate = store.rows.get(Long.parseLong(result.targetId()));
        assertTrue(candidate.requiresConfirmation());
        chat(new UserMemoryOperation(UserMemoryOperation.Action.REINFORCE, candidate.id(), null, null, null,
                UserMemoryOperation.Scope.USER, "IMPLICIT", List.of(store.nextId())));
        assertTrue(store.rows.values().stream().noneMatch(Item::active));
        assertEquals(2, store.rows.get(candidate.id()).evidenceCount());
        var adopted = service.change(USER, target("ADOPT", store.rows.get(candidate.id())));
        Item active = store.rows.get(Long.parseLong(adopted.targetId()));
        assertTrue(active.active());
        assertEquals(candidate.id(), active.originCandidateId());
        assertEquals("PROMOTED", store.rows.get(candidate.id()).status());
    }

    @Test void 候选新增和取消不触碰正式内容证据时间或缓存() {
        service.change(USER, create("中文", "ACTIVE"));
        Item original = active();
        clearInvocations(redis);
        var pending = service.change(USER, create("日语", "CANDIDATE"));
        Item candidate = store.rows.get(Long.parseLong(pending.targetId()));
        service.change(USER, target("CANCEL", candidate));
        assertEquals(original, store.rows.get(original.id()));
        assertEquals("REVOKED", store.rows.get(candidate.id()).status());
        verifyNoInteractions(redis);
    }

    @Test void 采用冲突候选保留正式历史和双向关联() {
        service.change(USER, create("中文", "ACTIVE"));
        Item chinese = active();
        var pending = service.change(USER, create("日语", "CANDIDATE"));
        Item candidate = store.rows.get(Long.parseLong(pending.targetId()));
        var request = target("ADOPT", candidate);
        request.setExpectedActive(ref(chinese));
        service.change(USER, request);
        assertEquals("日语", active().content());
        assertEquals(active().id(), store.rows.get(chinese.id()).supersededBy());
        assertEquals(candidate.id(), active().originCandidateId());
        assertEquals(active().id(), store.rows.get(candidate.id()).promotedMemoryId());
    }

    @Test void 与正式内容相同的候选被采用后也结束待确认() {
        service.change(USER, create("中文", "ACTIVE"));
        Item before = active();
        var pending = service.change(USER, create("中文", "CANDIDATE"));
        var request = target("ADOPT", store.rows.get(Long.parseLong(pending.targetId())));
        request.setExpectedActive(ref(before));
        service.change(USER, request);
        assertNotEquals(before.id(), active().id());
        assertEquals("中文", active().content());
        assertEquals("PROMOTED", store.rows.get(Long.parseLong(pending.targetId())).status());
    }

    @Test void 中文日语中文通过新版本保留完整历史() {
        service.change(USER, create("中文", "ACTIVE"));
        long first = active().id();
        edit("日语"); long second = active().id();
        edit("中文");
        assertNotEquals(first, active().id());
        assertEquals(3, store.rows.size());
        assertEquals("SUPERSEDED", store.rows.get(first).status());
        assertEquals("SUPERSEDED", store.rows.get(second).status());
        assertTrue(store.events.values().stream().anyMatch(event -> event.getChangesJson().contains("EDIT")));
    }

    @Test void 请求超时后用同一标识重试不会重复记忆证据与缓存操作() {
        var request = create("中文", "ACTIVE");
        var first = service.change(USER, request);
        clearInvocations(redis);
        var retry = service.change(USER, request);
        assertEquals(first, retry);
        assertEquals(1, store.rows.size());
        assertEquals(1, store.events.size());
        assertEquals(1, active().evidenceCount());
        verifyNoInteractions(redis);
    }

    @Test void 提交结果不确定时已提交的请求仍能安全重试() {
        var request = create("中文", "ACTIVE");
        store.commitUncertain = true;
        assertEquals(50001, assertThrows(BusinessException.class, () -> service.change(USER, request)).getCode());
        assertEquals(1, store.rows.size());
        var retry = service.change(USER, request);
        assertEquals(active().id(), Long.parseLong(retry.targetId()));
        assertEquals(1, store.rows.size());
        assertEquals(1, store.events.size());
    }

    @Test void 同一请求标识不能被复用于不同内容() {
        var request = create("中文", "ACTIVE");
        service.change(USER, request);
        request.setContent("日语");
        assertEquals(40900, assertThrows(BusinessException.class, () -> service.change(USER, request)).getCode());
        assertEquals("中文", active().content());
    }

    @Test void 页面旧版本不能覆盖聊天更新() {
        service.change(USER, create("中文", "ACTIVE"));
        Item original = active();
        var request = target("EDIT", original); request.setContent("日语");
        chat(new UserMemoryOperation(UserMemoryOperation.Action.REINFORCE, original.id(), null, null, null,
                UserMemoryOperation.Scope.USER, "EXPLICIT", List.of(store.nextId())));
        clearInvocations(redis);
        assertEquals(40900, assertThrows(BusinessException.class, () -> service.change(USER, request)).getCode());
        assertEquals("中文", active().content());
        verifyNoInteractions(redis);
    }

    @Test void 候选确认前正式值改变时必须重新确认() {
        service.change(USER, create("中文", "ACTIVE"));
        Item original = active();
        var pending = service.change(USER, create("日语", "CANDIDATE"));
        var adopt = target("ADOPT", store.rows.get(Long.parseLong(pending.targetId())));
        adopt.setExpectedActive(ref(original));
        edit("英文");
        assertEquals(40900, assertThrows(BusinessException.class, () -> service.change(USER, adopt)).getCode());
        assertEquals("英文", active().content());
        assertTrue(store.rows.get(Long.parseLong(pending.targetId())).pending());
    }

    @Test void 未展示正式冲突时不能直接覆盖() {
        service.change(USER, create("中文", "ACTIVE"));
        assertEquals(40900, assertThrows(BusinessException.class,
                () -> service.change(USER, create("日语", "ACTIVE"))).getCode());
        assertEquals("中文", active().content());
    }

    @Test void 手动撤销后的旧聊天证据不能复活偏好() {
        service.change(USER, create("中文", "ACTIVE"));
        Item original = active();
        long oldTurn = store.nextId();
        service.change(USER, target("REVOKE", original));
        chat(new UserMemoryOperation(UserMemoryOperation.Action.ADD, null, "语言偏好", "页面默认语言", "日语",
                UserMemoryOperation.Scope.USER, "EXPLICIT", List.of(oldTurn)));
        assertTrue(store.rows.values().stream().noneMatch(Item::active));
        service.change(USER, create("中文", "ACTIVE"));
        assertNotEquals(original.id(), active().id());
    }

    @Test void 越权目标不可读取或修改() {
        service.change(8, create("日语", "ACTIVE"));
        Item foreign = store.rows.values().stream().filter(item -> item.userId() == 8).findFirst().orElseThrow();
        var request = target("EDIT", foreign); request.setContent("中文");
        assertEquals(40400, assertThrows(BusinessException.class, () -> service.change(USER, request)).getCode());
        assertEquals(foreign, store.rows.get(foreign.id()));
    }

    @Test void 历史写入失败时正式版本证据和请求一起回滚() {
        service.change(USER, create("中文", "ACTIVE"));
        Item original = active(); int events = store.events.size();
        store.failEvent = true;
        assertThrows(BusinessException.class, () -> edit("日语"));
        assertEquals(original, active());
        assertEquals(1, store.rows.size());
        assertEquals(events, store.events.size());
    }

    @Test void 缓存失效失败拒绝正式写入但不阻止独立候选() {
        doThrow(new IllegalStateException("模拟缓存不可用")).when(redis).delete("mem:pref:v3:7");
        assertThrows(BusinessException.class, () -> service.change(USER, create("中文", "ACTIVE")));
        assertTrue(store.rows.isEmpty());
        clearInvocations(redis);
        service.change(USER, create("日语", "CANDIDATE"));
        assertEquals(1, store.rows.size());
        verifyNoInteractions(redis);
    }

    @Test void 超预算文本被拒绝且不留下操作记录() {
        assertEquals(40000, assertThrows(BusinessException.class,
                () -> service.change(USER, create("长".repeat(600), "ACTIVE"))).getCode());
        assertTrue(store.rows.isEmpty());
        assertTrue(store.events.isEmpty());
    }

    @Test void 原值保存不新增版本或证据() {
        service.change(USER, create("中文", "ACTIVE"));
        Item original = active();
        var request = target("EDIT", original); request.setContent("中文");
        clearInvocations(redis);
        assertFalse(service.change(USER, request).changed());
        assertEquals(original, active());
        assertEquals(1, store.rows.size());
        verifyNoInteractions(redis);
    }

    private void edit(String content) { var request = target("EDIT", active()); request.setContent(content); service.change(USER, request); }
    private Item active() { return store.rows.values().stream().filter(item -> item.userId() == USER && item.active()).findFirst().orElseThrow(); }
    private static VersionReference ref(Item item) { return new VersionReference(Long.toString(item.id()), Long.toString(item.revision())); }
    private static UserMemoryMutationRequest create(String content, String mode) {
        var request = new UserMemoryMutationRequest(); request.setRequestId(UUID.randomUUID().toString());
        request.setAction("CREATE"); request.setCategory("语言偏好"); request.setAttributeName("页面默认语言");
        request.setContent(content); request.setSaveMode(mode); return request;
    }
    private static UserMemoryMutationRequest target(String action, Item item) {
        var request = new UserMemoryMutationRequest(); request.setRequestId(UUID.randomUUID().toString());
        request.setAction(action); request.setTarget(ref(item)); return request;
    }
    private void chat(UserMemoryOperation operation) {
        Snapshot snapshot = store.load(USER);
        new UserMemoryChangePlanner(store, USER, 100L, snapshot, LocalDateTime.now()).plan(List.of(operation), snapshot).apply(store);
    }

    private static class ManagementStore extends UserMemoryChangePlannerTest.Store {
        final Map<Long, AppMemoryEvent> events = new LinkedHashMap<>();
        boolean failEvent;
        boolean commitUncertain;
        @Override void lockUser(long userId) { }
        @Override Snapshot loadKeyCurrent(long userId, String key) { return Snapshot.of(load(userId).items().values().stream().filter(item -> item.memoryKey().equals(key)).toList()); }
        @Override Item findCurrentTarget(long userId, Kind kind, long id) {
            Item row = rows.get(id); return row != null && row.userId() == userId && row.kind() == kind ? row : null;
        }
        @Override AppMemoryEvent findRequest(long userId, String requestId) {
            return events.values().stream().filter(event -> event.getUserId() == userId && requestId.equals(event.getRequestId())).findFirst().orElse(null);
        }
        @Override void insertEvent(AppMemoryEvent event) {
            if (failEvent) throw new IllegalStateException("模拟历史写入失败");
            assertNull(events.putIfAbsent(event.getId(), event));
        }
        TransactionOperations transactions() {
            return new TransactionOperations() {
                @Override public <T> T execute(TransactionCallback<T> callback) {
                    var oldRows = new LinkedHashMap<>(rows);
                    Map<Long, Map<Long, Evidence>> oldEvidence = new HashMap<>();
                    ledgers.forEach((key, value) -> oldEvidence.put(key, new LinkedHashMap<>(value)));
                    var oldEvents = new LinkedHashMap<>(events);
                    T result;
                    try { result = callback.doInTransaction(new SimpleTransactionStatus()); }
                    catch (RuntimeException exception) {
                        rows.clear(); rows.putAll(oldRows); ledgers.clear(); ledgers.putAll(oldEvidence);
                        events.clear(); events.putAll(oldEvents); throw exception;
                    }
                    if (commitUncertain) { commitUncertain = false; throw new IllegalStateException("模拟提交响应丢失"); }
                    return result;
                }
            };
        }
    }
}
