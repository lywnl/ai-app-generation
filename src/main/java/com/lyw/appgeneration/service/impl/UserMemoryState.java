package com.lyw.appgeneration.service.impl;

import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 抽取快照使用不可变条目，准备新状态时不会污染缓存中的旧对象。 */
final class UserMemoryState {
    private UserMemoryState() { }

    enum Kind { FORMAL, CANDIDATE }

    record Evidence(long turnId, Long appId, String type) { }

    @Builder(toBuilder = true)
    record Item(Kind kind, long id, long userId, Long appId,
                String category, String memoryKey, String attributeName, String content,
                String status, String evidenceType, int evidenceCount, long lastEvidenceTurnId,
                int legacyEvidenceCount, Long legacyEvidenceTurnId, long revision,
                Long supersededBy, Long originCandidateId, Long promotedMemoryId,
                Long revokedByTurnId, LocalDateTime createTime, LocalDateTime updateTime) {
        boolean active() { return kind == Kind.FORMAL && "ACTIVE".equals(status); }
        boolean pending() { return kind == Kind.CANDIDATE && "CANDIDATE".equals(status); }
        boolean revoked() { return "REVOKED".equals(status); }
    }

    record Snapshot(Map<Long, Item> items) {
        Snapshot { items = Map.copyOf(items); }

        static Snapshot of(Collection<Item> entries) {
            Map<Long, Item> map = new LinkedHashMap<>();
            for (Item item : entries) {
                if (map.putIfAbsent(item.id(), item) != null) {
                    throw new IllegalStateException("正式与候选记录 ID 发生冲突");
                }
                if (item.memoryKey() == null || item.attributeName() == null) {
                    throw new IllegalStateException("长期记忆尚未完成属性迁移");
                }
            }
            return new Snapshot(map);
        }

        /** 每条当前记录都进入一个窗口；没有展示的记录不能被当成不存在。 */
        List<Window> windows(ChatTokenEstimator estimator, int maxTokens) {
            int pageBudget = Math.max(1, maxTokens - 96);
            List<Window> result = new ArrayList<>();
            Map<String, List<Item>> groups = new LinkedHashMap<>();
            items.values().stream().sorted(Comparator.comparing(Item::memoryKey).thenComparingLong(Item::id))
                    .forEach(item -> groups.computeIfAbsent(item.memoryKey(), ignored -> new ArrayList<>()).add(item));
            List<Item> page = new ArrayList<>();
            for (List<Item> group : groups.values()) {
                List<Item> tentative = new ArrayList<>(page);
                tentative.addAll(group);
                if (fits(tentative, estimator, pageBudget)) {
                    page = tentative;
                    continue;
                }
                if (!page.isEmpty()) result.add(Snapshot.of(page).window(estimator, pageBudget));
                page = new ArrayList<>();
                // 一般将同属性正式、候选及撤销一起提供；异常大的单属性也逐页覆盖，不丢目标。
                List<Item> remaining = new ArrayList<>(group);
                while (!remaining.isEmpty()) {
                    if (fits(remaining, estimator, pageBudget)) {
                        page.addAll(remaining);
                        break;
                    }
                    Window window = Snapshot.of(remaining).window(estimator, pageBudget);
                    if (window.visibleIds().isEmpty()) {
                        throw new IllegalStateException("单条已有记忆超过抽取窗口，请先检查迁移数据");
                    }
                    result.add(window);
                    remaining.removeIf(item -> window.visibleIds().contains(item.id()));
                }
            }
            if (!page.isEmpty()) result.add(Snapshot.of(page).window(estimator, pageBudget));
            if (result.isEmpty()) return List.of(new Window("", Set.of()));
            List<Window> numbered = new ArrayList<>();
            for (Window window : result) {
                var json = JSONUtil.parseObj(window.text());
                json.set("omittedCount", items.size() - window.visibleIds().size());
                json.set("pageIndex", numbered.size() + 1);
                json.set("pageCount", result.size());
                json.set("totalRecords", items.size());
                String text = json.toString();
                if (estimator.estimateText(text) > maxTokens) throw new IllegalStateException("记忆分页元数据超过预算");
                numbered.add(new Window(text, window.visibleIds()));
            }
            return List.copyOf(numbered);
        }

        private boolean fits(List<Item> entries, ChatTokenEstimator estimator, int maxTokens) {
            return Snapshot.of(entries).window(estimator, maxTokens).visibleIds().size() == entries.size();
        }

        Window window(ChatTokenEstimator estimator, int maxTokens) {
            List<Item> sorted = items.values().stream().sorted(Comparator
                    .comparingInt((Item item) -> item.active() ? 0 : item.pending() ? 1 : 2)
                    .thenComparing(Comparator.comparingLong(Item::id).reversed())).toList();
            List<Map<String, Object>> rows = new ArrayList<>();
            Set<Long> visible = new LinkedHashSet<>();
            for (Item item : sorted) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", Long.toString(item.id()));
                row.put("region", item.kind().name());
                row.put("category", item.category());
                row.put("attributeName", item.attributeName());
                row.put("memoryKey", item.memoryKey());
                row.put("content", item.content());
                row.put("status", item.status());
                row.put("evidenceType", item.evidenceType());
                row.put("evidenceCount", item.evidenceCount());
                if (item.revokedByTurnId() != null) {
                    row.put("revokedByTurnId", Long.toString(item.revokedByTurnId()));
                }
                rows.add(row);
                if (estimator.estimateText(render(rows, items.size() - rows.size())) > maxTokens) {
                    rows.removeLast();
                    break;
                }
                visible.add(item.id());
            }
            return new Window(render(rows, items.size() - visible.size()), Set.copyOf(visible));
        }

        private String render(List<Map<String, Object>> rows, int omitted) {
            return JSONUtil.toJsonStr(Map.of("records", rows, "omittedCount", omitted));
        }
    }

    record Window(String text, Set<Long> visibleIds) { }
}
