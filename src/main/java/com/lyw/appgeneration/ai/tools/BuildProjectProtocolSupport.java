package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lyw.appgeneration.core.builder.BuildStage;
import com.lyw.appgeneration.core.builder.VueBuildFailureKind;

import java.util.Set;

/** Vue 构建工具协议的显式 JSON 编解码，避免依赖 record Bean 推断。 */
final class BuildProjectProtocolSupport {

    private static final Set<String> PROTOCOL_FIELDS = Set.of(
            "protocol", "invocationStatus", "success", "attempt",
            "maxAttempts", "stage", "failureKind", "timedOut", "repairable",
            "reflectionRequired", "nextAction", "message", "errorSummary",
            "terminateToolLoop", "finalResponse");

    private BuildProjectProtocolSupport() {
    }

    static String json(BuildProjectToolResult result) {
        JSONObject json = new JSONObject(
                JSONConfig.create().setIgnoreNullValue(false));
        json.set("protocol", result.protocol());
        json.set("invocationStatus", enumName(result.invocationStatus()));
        json.set("success", result.success());
        json.set("attempt", result.attempt());
        json.set("maxAttempts", result.maxAttempts());
        json.set("stage", enumName(result.stage()));
        json.set("failureKind", enumName(result.failureKind()));
        json.set("timedOut", result.timedOut());
        json.set("repairable", result.repairable());
        json.set("reflectionRequired", result.reflectionRequired());
        json.set("nextAction", enumName(result.nextAction()));
        json.set("message", result.message());
        json.set("errorSummary", result.errorSummary());
        json.set("terminateToolLoop", result.terminateToolLoop());
        json.set("finalResponse", result.finalResponse());
        return JSONUtil.toJsonStr(json);
    }

    static BuildProjectToolResult parse(String rawResult) {
        ObjectNode json = StrictToolJsonSupport.parseObject(rawResult);
        if (!PROTOCOL_FIELDS.equals(json.properties().stream()
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalArgumentException("构建工具协议字段不完整或包含未知字段");
        }
        return new BuildProjectToolResult(
                requiredString(json, "protocol"),
                enumValue(BuildProjectToolResult.BuildInvocationStatus.class,
                        requiredString(json, "invocationStatus")),
                nullableBoolean(json, "success"),
                nullableInteger(json, "attempt"),
                requiredInteger(json, "maxAttempts"),
                enumValue(BuildStage.class, nullableString(json, "stage")),
                enumValue(VueBuildFailureKind.class,
                        nullableString(json, "failureKind")),
                nullableBoolean(json, "timedOut"),
                requiredBoolean(json, "repairable"),
                requiredBoolean(json, "reflectionRequired"),
                enumValue(BuildProjectToolResult.BuildNextAction.class,
                        nullableString(json, "nextAction")),
                requiredString(json, "message"),
                nullableString(json, "errorSummary"),
                requiredBoolean(json, "terminateToolLoop"),
                nullableString(json, "finalResponse"));
    }

    private static String requiredString(ObjectNode json, String field) {
        JsonNode value = json.get(field);
        if (value != null && value.isTextual()) {
            return value.textValue();
        }
        throw new IllegalArgumentException("构建工具协议字段必须是字符串: " + field);
    }

    private static String nullableString(ObjectNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.textValue();
        }
        throw new IllegalArgumentException("构建工具协议字段必须是字符串或 null: " + field);
    }

    private static boolean requiredBoolean(ObjectNode json, String field) {
        JsonNode value = json.get(field);
        if (value != null && value.isBoolean()) {
            return value.booleanValue();
        }
        throw new IllegalArgumentException("构建工具协议字段必须是布尔值: " + field);
    }

    private static Boolean nullableBoolean(ObjectNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        throw new IllegalArgumentException("构建工具协议字段必须是布尔值或 null: " + field);
    }

    private static int requiredInteger(ObjectNode json, String field) {
        Integer value = nullableInteger(json, field);
        if (value == null) {
            throw new IllegalArgumentException("构建工具协议字段不能为空: " + field);
        }
        return value;
    }

    private static Integer nullableInteger(ObjectNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber() && value.canConvertToInt()) {
            return value.intValue();
        }
        throw new IllegalArgumentException("构建工具协议字段必须是整数或 null: " + field);
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
