package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.UserMemoryOperation;
import com.lyw.appgeneration.ai.memory.UserMemoryOperation.Action;
import com.lyw.appgeneration.ai.memory.UserPreferenceAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import static com.lyw.appgeneration.service.impl.UserMemoryState.*;

/** 合并同一批用户证据在多个记忆窗口中的重复结果，不以窗口顺序裁决冲突。 */
final class UserMemoryOperationMerger {
    private UserMemoryOperationMerger() { }

    static List<UserMemoryOperation> merge(List<UserMemoryOperation> input, Snapshot snapshot) {
        Map<String, UserMemoryOperation> unique = new LinkedHashMap<>();
        for (UserMemoryOperation op : input) {
            String identity = op.targetId() == null ? "ADD:" + key(op, snapshot) : "TARGET:" + op.targetId();
            UserMemoryOperation old = unique.get(identity);
            if (old != null) {
                if (old.action() != op.action() || !Objects.equals(old.content(), op.content())
                        || !Objects.equals(old.category(), op.category())
                        || !old.evidenceType().equals(op.evidenceType())) {
                    throw new IllegalArgumentException("分页抽取对同一目标返回矛盾操作");
                }
                op = withTurns(op, old);
            }
            unique.put(identity, op);
        }
        List<UserMemoryOperation> result = new ArrayList<>(unique.values());
        for (UserMemoryOperation op : List.copyOf(result)) {
            if (op.action() != Action.ADD) continue;
            UserMemoryOperation existing = result.stream().filter(other -> other.targetId() != null
                    && other.action() != Action.RETRACT && key(other, snapshot).equals(key(op, snapshot)))
                    .findFirst().orElse(null);
            if (existing == null) continue;
            if (!desired(existing, snapshot).equals(op.content())
                    || !existing.evidenceType().equals(op.evidenceType())) {
                throw new IllegalArgumentException("新增属性与分页中的已有目标判断冲突");
            }
            result.remove(op);
            result.set(result.indexOf(existing), withTurns(existing, op));
        }
        // 正式替换会采用同内容候选；另一页对该候选的重复确认不能再次执行晋升。
        for (UserMemoryOperation op : List.copyOf(result)) {
            if (op.action() != Action.REINFORCE || snapshot.items().get(op.targetId()).kind() != Kind.CANDIDATE) continue;
            UserMemoryOperation replacement = result.stream().filter(other -> other.action() == Action.REPLACE
                    && snapshot.items().get(other.targetId()).kind() == Kind.FORMAL
                    && key(other, snapshot).equals(key(op, snapshot))
                    && other.content().equals(desired(op, snapshot))
                    && other.evidenceType().equals(op.evidenceType())).findFirst().orElse(null);
            if (replacement != null) {
                result.remove(op);
                result.set(result.indexOf(replacement), withTurns(replacement, op));
            }
        }
        return List.copyOf(result);
    }

    private static String key(UserMemoryOperation op, Snapshot snapshot) {
        if (op.targetId() == null) return UserPreferenceAttributes.resolve(op.category(), op.attribute()).key();
        Item item = snapshot.items().get(op.targetId());
        if (item == null) throw new IllegalArgumentException("模型返回未展示的目标");
        return item.memoryKey();
    }

    private static String desired(UserMemoryOperation op, Snapshot snapshot) {
        return op.action() == Action.REINFORCE ? snapshot.items().get(op.targetId()).content() : op.content();
    }

    private static UserMemoryOperation withTurns(UserMemoryOperation op, UserMemoryOperation other) {
        TreeSet<Long> turns = new TreeSet<>(op.turnIds());
        turns.addAll(other.turnIds());
        return new UserMemoryOperation(op.action(), op.targetId(), op.category(), op.attribute(),
                op.content(), op.scope(), op.evidenceType(), List.copyOf(turns));
    }
}
