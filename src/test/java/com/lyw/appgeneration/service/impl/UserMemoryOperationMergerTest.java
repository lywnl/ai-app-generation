package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.ConservativeChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.UserMemoryOperation;
import com.lyw.appgeneration.ai.memory.UserMemoryOperation.Action;
import com.lyw.appgeneration.ai.memory.UserMemoryOperation.Scope;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static com.lyw.appgeneration.service.impl.UserMemoryState.*;
import static org.junit.jupiter.api.Assertions.*;

class UserMemoryOperationMergerTest {
    private final Item formal = item(1, "中文", Kind.FORMAL, "ui.default_language");
    private final Item candidate = item(2, "日语", Kind.CANDIDATE, "ui.default_language");
    private final Snapshot snapshot = Snapshot.of(List.of(formal, candidate));

    @Test void 相同目标跨页重复合并证据() {
        var one = op(Action.REPLACE, 1L, "日语", 10L);
        var two = op(Action.REPLACE, 1L, "日语", 20L);
        var merged = UserMemoryOperationMerger.merge(List.of(one, two), snapshot);
        assertEquals(1, merged.size());
        assertEquals(List.of(10L, 20L), merged.getFirst().turnIds());
    }
    @Test void 新增页和已知目标页指向同一选择时使用真实目标() {
        var add = new UserMemoryOperation(Action.ADD, null, "语言偏好", "页面默认语言", "日语", Scope.USER, "EXPLICIT", List.of(10L));
        var replace = op(Action.REPLACE, 1L, "日语", 10L);
        assertEquals(List.of(replace), UserMemoryOperationMerger.merge(List.of(add, replace), snapshot));
    }
    @Test void 正式替换与候选确认的重复证据只处理一次() {
        var replace = op(Action.REPLACE, 1L, "日语", 10L);
        var reinforce = op(Action.REINFORCE, 2L, null, 10L);
        assertEquals(List.of(replace), UserMemoryOperationMerger.merge(List.of(reinforce, replace), snapshot));
    }
    @Test void 分页互相矛盾或未知目标整批拒绝() {
        assertThrows(IllegalArgumentException.class, () -> UserMemoryOperationMerger.merge(List.of(
                op(Action.REPLACE, 1L, "日语", 10L), op(Action.REPLACE, 1L, "英文", 10L)), snapshot));
        assertThrows(IllegalArgumentException.class, () -> UserMemoryOperationMerger.merge(List.of(
                op(Action.REPLACE, 999L, "日语", 10L), new UserMemoryOperation(Action.ADD, null,
                        "语言偏好", "页面默认语言", "日语", Scope.USER, "EXPLICIT", List.of(10L))), snapshot));
    }
    @Test void 大快照分页完整覆盖且同属性正式候选尽量同页() {
        List<Item> rows = LongStream.rangeClosed(1, 200).mapToObj(id -> item(id, "偏好" + id,
                id % 2 == 0 ? Kind.CANDIDATE : Kind.FORMAL, "test." + ((id-1)/2))).toList();
        var estimator = new ConservativeChatTokenEstimator(new MemoryTokenProperties());
        List<Window> windows = Snapshot.of(rows).windows(estimator, 1024);
        assertTrue(windows.size() > 1);
        Set<Long> covered = new HashSet<>();
        for (Window window : windows) {
            assertTrue(estimator.estimateText(window.text()) <= 1024);
            for (long id : window.visibleIds()) {
                assertTrue(covered.add(id), "目标不能跨页重复出现");
                assertTrue(window.visibleIds().contains(id % 2 == 0 ? id - 1 : id + 1));
            }
            assertTrue(window.text().contains("\"totalRecords\":200"));
        }
        assertEquals(200, covered.size());
    }
    private UserMemoryOperation op(Action action, Long id, String content, long turn) {
        return new UserMemoryOperation(action, id, action == Action.REPLACE ? "语言偏好" : null,
                null, content, Scope.USER, "EXPLICIT", List.of(turn));
    }
    private Item item(long id, String content, Kind kind, String key) {
        return Item.builder().id(id).userId(7).kind(kind).category("语言偏好").memoryKey(key)
                .attributeName("页面默认语言").content(content).status(kind == Kind.FORMAL ? "ACTIVE" : "CANDIDATE")
                .evidenceType("IMPLICIT").evidenceCount(1).revision(1).build();
    }
}
