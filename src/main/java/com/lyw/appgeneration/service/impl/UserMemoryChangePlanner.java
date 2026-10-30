package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.UserMemoryOperation;
import com.lyw.appgeneration.ai.memory.UserMemoryOperation.Action;
import com.lyw.appgeneration.ai.memory.UserPreferenceAttributes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.lyw.appgeneration.service.impl.UserMemoryState.*;

/** 在用户行锁内生成完整变更计划；规划阶段只读，校验通过后才执行固定的仓储写入。 */
final class UserMemoryChangePlanner {
    private final VersionedUserMemoryRepository repository;
    private final long userId;
    private final Long appId;
    private final Snapshot before;
    private final Map<Long, Item> state;
    private final Map<Long, Map<Long, Evidence>> ledgers = new HashMap<>();
    private final Map<Long, Map<Long, Evidence>> addedEvidence = new LinkedHashMap<>();
    private final Set<Long> touchedCandidates = new HashSet<>();
    private final LocalDateTime now;

    UserMemoryChangePlanner(VersionedUserMemoryRepository repository, long userId,
                           Long appId, Snapshot before, LocalDateTime now) {
        this.repository = repository;
        this.userId = userId;
        this.appId = appId;
        this.before = before;
        this.state = new LinkedHashMap<>(before.items());
        this.now = now;
    }

    Plan plan(List<UserMemoryOperation> operations, Snapshot shown) {
        validateSnapshot(operations, shown);
        validateIntents(operations);
        // 先校验整批意图，再撤销、写证据，最后统一判断自动晋升，避免数组顺序决定胜者。
        operations.stream().filter(op -> op.action() == Action.RETRACT).forEach(this::retract);
        operations.stream().filter(op -> op.action() != Action.RETRACT).forEach(this::propose);
        promoteUncontestedCandidates();
        List<Change> changes = new ArrayList<>();
        for (Item item : state.values()) {
            Item original = before.items().get(item.id());
            if (!Objects.equals(original, item)) changes.add(new Change(original, item));
        }
        List<EvidenceWrite> evidence = new ArrayList<>();
        addedEvidence.forEach((id, rows) -> rows.values().forEach(row ->
                evidence.add(new EvidenceWrite(state.get(id), row))));
        return new Plan(List.copyOf(changes), List.copyOf(evidence), now);
    }

    private void validateSnapshot(List<UserMemoryOperation> operations, Snapshot shown) {
        Set<String> keys = new HashSet<>();
        for (UserMemoryOperation op : operations) {
            if (op.scope() != UserMemoryOperation.Scope.USER || op.turnIds().isEmpty()) {
                throw new IllegalArgumentException("操作范围或证据无效");
            }
            if (op.targetId() != null) {
                Item target = shown.items().get(op.targetId());
                if (target == null || target.userId() != userId) {
                    throw new IllegalArgumentException("目标未展示或不属于当前用户");
                }
                if (!target.active() && !target.pending() && !target.revoked()) {
                    throw new IllegalArgumentException("目标已归档，不能继续修改");
                }
                if (op.category() != null && !target.category().equals(op.category())) {
                    throw new IllegalArgumentException("不能修改目标所属类别");
                }
                if (op.attribute() != null && !target.memoryKey().equals(
                        UserPreferenceAttributes.resolve(target.category(), op.attribute()).key())) {
                    throw new IllegalArgumentException("不能修改目标属性身份");
                }
                if (target.revoked() && op.action() != Action.RETRACT) {
                    throw new IllegalArgumentException("历史和撤销目标不能原地复活");
                }
                keys.add(target.memoryKey());
            } else {
                keys.add(UserPreferenceAttributes.resolve(op.category(), op.attribute()).key());
            }
        }
        for (String key : keys) {
            Map<Long, Item> expected = forKey(shown, key);
            if (!expected.equals(forKey(before, key))) {
                throw new IllegalStateException("关联记忆在模型调用期间已变化，需重新抽取");
            }
        }
    }

    private Map<Long, Item> forKey(Snapshot snapshot, String key) {
        Map<Long, Item> result = new HashMap<>();
        snapshot.items().values().stream().filter(item -> item.memoryKey().equals(key))
                .forEach(item -> result.put(item.id(), item));
        return result;
    }

    private void validateIntents(List<UserMemoryOperation> operations) {
        Map<Long, UserMemoryOperation> targets = new HashMap<>();
        Map<String, String> explicitValues = new HashMap<>();
        Set<String> retractKeys = new HashSet<>();
        for (UserMemoryOperation op : operations) {
            Item target = op.targetId() == null ? null : state.get(op.targetId());
            String key = target == null
                    ? UserPreferenceAttributes.resolve(op.category(), op.attribute()).key() : target.memoryKey();
            if (target != null && targets.putIfAbsent(target.id(), op) != null) {
                throw new IllegalArgumentException("同一目标有重复或矛盾操作");
            }
            if (op.action() == Action.RETRACT) {
                if (!"EXPLICIT".equals(op.evidenceType())) throw new IllegalArgumentException("撤销需要明确证据");
                if (target.active()) retractKeys.add(key);
            } else if ("EXPLICIT".equals(op.evidenceType())) {
                String content = op.action() == Action.REINFORCE ? target.content() : op.content();
                String previous = explicitValues.putIfAbsent(key, content);
                if (previous != null && !previous.equals(content)) {
                    throw new IllegalArgumentException("同批出现互斥的明确偏好");
                }
            }
        }
        if (retractKeys.stream().anyMatch(explicitValues::containsKey)) {
            throw new IllegalArgumentException("同批撤销和重新设置同一正式属性，需抽取最终一致意图");
        }
    }

    private void retract(UserMemoryOperation op) {
        Item target = state.get(op.targetId());
        long turn = latest(op);
        if (target.revoked() || turn <= target.lastEvidenceTurnId()) return;
        putChanged(target.toBuilder().status("REVOKED").revokedByTurnId(turn).build());
    }

    private void propose(UserMemoryOperation op) {
        Item target = op.targetId() == null ? null : state.get(op.targetId());
        String key;
        String attribute;
        String category;
        String content;
        if (target == null) {
            var resolved = UserPreferenceAttributes.resolve(op.category(), op.attribute());
            key = resolved.key();
            attribute = resolved.name();
            category = op.category();
            content = op.content();
        } else {
            key = target.memoryKey();
            attribute = target.attributeName();
            category = target.category();
            content = op.action() == Action.REINFORCE ? target.content() : op.content();
        }
        Item active = active(key);
        boolean explicit = "EXPLICIT".equals(op.evidenceType());
        long barrier = revocationBarrier(key, content);
        if (latest(op) <= barrier) return;
        if (active != null && active.content().equals(content)) {
            // 候选只维护自己的证据；不能借候选操作给正式行加分或刷新排序时间。
            if (target == null && !explicit) {
                Item separate = pending(key, content);
                if (separate == null) {
                    separate = newItem(Kind.CANDIDATE, key, attribute, category, content, "CANDIDATE");
                    state.put(separate.id(), separate);
                }
                reinforce(separate, op);
            } else if (target == null || target.kind() == Kind.FORMAL) {
                reinforce(active, op);
            } else {
                reinforce(target, op);
            }
            return;
        }
        if (active != null && explicit && latest(op) <= active.lastEvidenceTurnId()) return;
        if (active != null && target == null && explicit) {
            throw new IllegalArgumentException("新增与现有正式属性冲突，必须引用已展示目标");
        }
        Item pending = pending(key, content);
        if (target != null && target.pending() && op.action() == Action.REPLACE
                && !target.content().equals(content)) {
            // 修改候选仍保留旧候选和证据，只结束它的待确认状态。
            if (explicit && latest(op) > target.lastEvidenceTurnId()) {
                putChanged(target.toBuilder().status("REVOKED").revokedByTurnId(latest(op)).build());
            }
        }
        if (pending != null) pending = reinforce(pending, op);
        if (explicit) {
            activate(active, pending, key, attribute, category, content, op, barrier);
        } else {
            if (pending == null) {
                pending = newItem(Kind.CANDIDATE, key, attribute, category, content, "CANDIDATE");
                state.put(pending.id(), pending);
                pending = reinforce(pending, op);
            }
            touchedCandidates.add(pending.id());
        }
    }

    private Item reinforce(Item item, UserMemoryOperation op) {
        Map<Long, Evidence> ledger = ledger(item);
        long barrier = revocationBarrier(item.memoryKey(), item.content());
        List<Long> newTurns = op.turnIds().stream()
                .filter(turn -> turn > barrier)
                .filter(turn -> !Objects.equals(turn, item.legacyEvidenceTurnId()))
                .filter(turn -> !ledger.containsKey(turn)).toList();
        if (newTurns.isEmpty()) return item;
        for (long turn : newTurns) {
            addEvidence(item, new Evidence(turn, appId, op.evidenceType()));
        }
        int count = Math.addExact(item.legacyEvidenceCount(), ledger.size());
        String type = "EXPLICIT".equals(item.evidenceType()) || "EXPLICIT".equals(op.evidenceType())
                ? "EXPLICIT" : "IMPLICIT";
        Item next = item.toBuilder().evidenceType(type).evidenceCount(count)
                .lastEvidenceTurnId(Math.max(item.lastEvidenceTurnId(), latest(op))).build();
        putChanged(next);
        if (next.pending()) touchedCandidates.add(next.id());
        return state.get(next.id());
    }

    private void activate(Item active, Item pending, String key, String attribute, String category,
                          String content, UserMemoryOperation explicit, long barrier) {
        Item next = newItem(Kind.FORMAL, key, attribute, category, content, "ACTIVE");
        if (pending != null) {
            next = next.toBuilder().originCandidateId(pending.id()).build();
            if (barrier == 0L) {
                next = next.toBuilder().legacyEvidenceCount(pending.legacyEvidenceCount())
                        .legacyEvidenceTurnId(pending.legacyEvidenceTurnId())
                        .lastEvidenceTurnId(pending.lastEvidenceTurnId()).build();
            }
            state.put(next.id(), next);
            for (Evidence evidence : List.copyOf(ledger(pending).values())) {
                if (evidence.turnId() > barrier) addEvidence(next, evidence);
            }
        } else {
            state.put(next.id(), next);
        }
        if (explicit != null) {
            for (long turn : explicit.turnIds()) {
                if (turn > barrier && !Objects.equals(turn, next.legacyEvidenceTurnId())) {
                    addEvidence(next, new Evidence(turn, appId, explicit.evidenceType()));
                }
            }
        }
        Map<Long, Evidence> evidence = ledger(next);
        String type = explicit != null ? "EXPLICIT" : pending.evidenceType();
        next = next.toBuilder().evidenceType(type)
                .evidenceCount(Math.addExact(next.legacyEvidenceCount(), evidence.size()))
                .lastEvidenceTurnId(Math.max(next.lastEvidenceTurnId(), evidence.keySet().stream()
                        .mapToLong(Long::longValue).max().orElse(0L))).build();
        state.put(next.id(), next);
        if (active != null) putChanged(active.toBuilder().status("SUPERSEDED").supersededBy(next.id()).build());
        if (pending != null) putChanged(pending.toBuilder().status("PROMOTED").promotedMemoryId(next.id()).build());
    }

    private void promoteUncontestedCandidates() {
        Map<String, List<Item>> byKey = new LinkedHashMap<>();
        for (long id : touchedCandidates) {
            Item item = state.get(id);
            if (item.pending() && item.evidenceCount() >= 2 && active(item.memoryKey()) == null
                    && revocationBarrier(item.memoryKey(), item.content()) == 0L) {
                byKey.computeIfAbsent(item.memoryKey(), ignored -> new ArrayList<>()).add(item);
            }
        }
        for (List<Item> candidates : byKey.values()) {
            // 两个相互冲突的候选都满足次数时，仍需要用户明确选择。
            if (candidates.size() != 1) continue;
            Item item = candidates.getFirst();
            long alternatives = state.values().stream().filter(other -> other.pending()
                    && other.memoryKey().equals(item.memoryKey()) && other.evidenceCount() >= 2).count();
            if (alternatives != 1) continue;
            activate(null, item, item.memoryKey(), item.attributeName(), item.category(), item.content(), null, 0L);
        }
    }

    private Item newItem(Kind kind, String key, String attribute, String category, String content, String status) {
        long id = repository.nextId();
        if (id <= 0L || state.containsKey(id)) throw new IllegalStateException("新记忆 ID 无效或重复");
        return Item.builder().id(id).userId(userId).appId(appId).kind(kind)
                .memoryKey(key).attributeName(attribute).category(category).content(content)
                .status(status).evidenceType("IMPLICIT").revision(1L).createTime(now).updateTime(now).build();
    }

    private void putChanged(Item item) {
        Item original = before.items().get(item.id());
        state.put(item.id(), item.toBuilder().revision(original == null ? 1L : original.revision() + 1L)
                .updateTime(now).build());
    }

    private Map<Long, Evidence> ledger(Item item) {
        return ledgers.computeIfAbsent(item.id(), ignored -> new LinkedHashMap<>(
                before.items().containsKey(item.id()) ? repository.evidence(item) : Map.of()));
    }

    private void addEvidence(Item item, Evidence evidence) {
        if (ledger(item).putIfAbsent(evidence.turnId(), evidence) == null) {
            addedEvidence.computeIfAbsent(item.id(), ignored -> new LinkedHashMap<>())
                    .put(evidence.turnId(), evidence);
        }
    }

    private Item active(String key) {
        return state.values().stream().filter(item -> item.active() && item.memoryKey().equals(key))
                .findFirst().orElse(null);
    }

    private Item pending(String key, String content) {
        return state.values().stream().filter(item -> item.pending() && item.memoryKey().equals(key)
                && item.content().equals(content)).findFirst().orElse(null);
    }

    private long revocationBarrier(String key, String content) {
        return state.values().stream().filter(item -> item.revoked() && item.memoryKey().equals(key)
                        && (item.kind() == Kind.FORMAL || item.content().equals(content)))
                .map(Item::revokedByTurnId).filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L);
    }

    private static long latest(UserMemoryOperation op) { return op.turnIds().stream().mapToLong(Long::longValue).max().orElseThrow(); }

    record Change(Item before, Item after) { }
    record EvidenceWrite(Item target, Evidence evidence) { }

    record Plan(List<Change> changes, List<EvidenceWrite> evidence, LocalDateTime now) {
        boolean formalChanged() { return changes.stream().anyMatch(change -> change.after().kind() == Kind.FORMAL); }
        boolean candidateChanged() { return changes.stream().anyMatch(change -> change.after().kind() == Kind.CANDIDATE); }

        void apply(VersionedUserMemoryRepository repository) {
            // 先释放正式/候选唯一槽，再插入新版本；任一步失败由批次事务完整回滚。
            changes.stream().filter(change -> change.before() != null)
                    .sorted(Comparator.comparingInt(change -> change.after().active() ? 1 : 0))
                    .forEach(change -> repository.update(change.before(), change.after()));
            changes.stream().filter(change -> change.before() == null)
                    .forEach(change -> repository.insert(change.after()));
            evidence.forEach(write -> repository.addEvidence(write.target(), write.evidence().appId(),
                    write.evidence().turnId(), write.evidence().type(), now));
        }
    }
}
