package com.lyw.appgeneration.service.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.ai.memory.UserMemoryOperation;
import com.lyw.appgeneration.ai.memory.UserMemoryOperation.Action;
import com.lyw.appgeneration.ai.memory.UserMemoryOperation.Scope;
import com.lyw.appgeneration.ai.memory.UserPreferenceAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 只解析操作请求；最终归属、状态和版本必须在事务内重新校验。 */
final class UserMemoryOperationParser {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> FIELDS = Set.of("action", "targetId", "category",
            "attribute", "content", "scope", "evidenceType", "turnIds");
    private final UserPreferenceContract contract;

    UserMemoryOperationParser(UserPreferenceContract contract) {
        this.contract = contract;
    }

    /** 非法整批返回 null；合法的无操作批次返回空列表。 */
    List<UserMemoryOperation> parse(String raw, List<Long> allowedTurns) {
        if (raw == null || raw.isBlank() || !contract.isRawOutputWithinBudget(raw)) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(raw);
            if (!root.isObject() || root.size() != 1 || !root.path("operations").isArray()) {
                return null;
            }
            Set<Long> whitelist = Set.copyOf(allowedTurns);
            Map<String, UserMemoryOperation> operations = new LinkedHashMap<>();
            for (JsonNode node : root.get("operations")) {
                if (!node.isObject()) {
                    return null;
                }
                for (var names = node.fieldNames(); names.hasNext();) {
                    if (!FIELDS.contains(names.next())) {
                        return null;
                    }
                }
                Action action = Action.valueOf(requiredText(node, "action"));
                Scope scope = Scope.valueOf(requiredText(node, "scope"));
                String evidenceType = requiredText(node, "evidenceType");
                if (!Set.of("EXPLICIT", "IMPLICIT").contains(evidenceType)) {
                    return null;
                }
                JsonNode evidence = node.get("turnIds");
                if (evidence == null || !evidence.isArray() || evidence.isEmpty()) {
                    return null;
                }
                TreeSet<Long> turns = new TreeSet<>();
                for (JsonNode turn : evidence) {
                    long id = positiveId(turn);
                    if (!whitelist.contains(id)) {
                        return null;
                    }
                    turns.add(id);
                }
                Long target = node.hasNonNull("targetId") ? positiveId(node.get("targetId")) : null;
                String category = optionalText(node, "category");
                String attribute = optionalText(node, "attribute");
                String content = optionalText(node, "content");
                if (action == Action.ADD) {
                    if (node.has("targetId") || category == null || attribute == null || content == null) {
                        return null;
                    }
                    UserPreferenceAttributes.resolve(category, attribute);
                } else if (target == null) {
                    return null;
                }
                if (category != null && !contract.isAllowedName(category)) {
                    return null;
                }
                if (action == Action.REPLACE && (content == null || category == null)) {
                    return null;
                }
                if (action == Action.REINFORCE || action == Action.RETRACT) {
                    // 模型可回显目标元数据；属性必须在事务内与目标完全对齐，不能拿来改身份。
                    if (content != null) {
                        return null;
                    }
                }
                if (action == Action.RETRACT && !"EXPLICIT".equals(evidenceType)) {
                    return null;
                }
                if (content != null) {
                    content = UserPreferenceAttributes.normalizeContent(content);
                    if (!contract.isPreferenceWithinBudget(category, content)) {
                        return null;
                    }
                    if (action == Action.ADD && !contract.isPreferenceWithinBudget(category,
                            UserPreferenceAttributes.resolve(category, attribute).name(), content)) {
                        return null;
                    }
                }
                // 本应用和临时信息仍推进已处理回合，但不产生全局记忆操作。
                if (scope != Scope.USER) {
                    continue;
                }
                UserMemoryOperation operation = new UserMemoryOperation(action, target,
                        category, attribute, content, scope, evidenceType, List.copyOf(turns));
                String key = target == null
                        ? "ADD:" + UserPreferenceAttributes.resolve(category, attribute).key()
                        : "TARGET:" + target;
                UserMemoryOperation previous = operations.get(key);
                if (previous != null) {
                    if (previous.action() != action
                            || !java.util.Objects.equals(previous.content(), content)
                            || !java.util.Objects.equals(previous.category(), category)
                            || !previous.evidenceType().equals(evidenceType)) {
                        return null;
                    }
                    TreeSet<Long> merged = new TreeSet<>(previous.turnIds());
                    merged.addAll(turns);
                    operation = new UserMemoryOperation(action, target, category, attribute,
                            content, scope, evidenceType, List.copyOf(merged));
                }
                operations.put(key, operation);
            }
            return new ArrayList<>(operations.values());
        } catch (Exception exception) {
            return null;
        }
    }

    private static long positiveId(JsonNode node) {
        if (!node.isTextual() || !node.textValue().matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("ID 必须为正整数字符串");
        }
        return Long.parseLong(node.textValue());
    }

    private static String requiredText(JsonNode node, String name) {
        String value = optionalText(node, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少操作字段：" + name);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("操作字段类型无效：" + name);
        }
        return value.textValue().strip();
    }
}
