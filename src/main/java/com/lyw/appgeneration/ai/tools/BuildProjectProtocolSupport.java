package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONUtil;
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
        StrictToolJsonSupport.requireObject(rawResult);
        JSONObject json = JSONUtil.parseObj(
                rawResult, JSONConfig.create()
                        .setCheckDuplicate(true)
                        .setIgnoreNullValue(false));
        if (!PROTOCOL_FIELDS.equals(json.keySet())) {
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

    private static String requiredString(JSONObject json, String field) {
        Object value = json.get(field);
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException("构建工具协议字段必须是字符串: " + field);
    }

    private static String nullableString(JSONObject json, String field) {
        Object value = json.get(field);
        if (value == null || value == JSONNull.NULL) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException("构建工具协议字段必须是字符串或 null: " + field);
    }

    private static boolean requiredBoolean(JSONObject json, String field) {
        Object value = json.get(field);
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalArgumentException("构建工具协议字段必须是布尔值: " + field);
    }

    private static Boolean nullableBoolean(JSONObject json, String field) {
        Object value = json.get(field);
        if (value == null || value == JSONNull.NULL) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalArgumentException("构建工具协议字段必须是布尔值或 null: " + field);
    }

    private static int requiredInteger(JSONObject json, String field) {
        Integer value = nullableInteger(json, field);
        if (value == null) {
            throw new IllegalArgumentException("构建工具协议字段不能为空: " + field);
        }
        return value;
    }

    private static Integer nullableInteger(JSONObject json, String field) {
        Object value = json.get(field);
        if (value == null || value == JSONNull.NULL) {
            return null;
        }
        if (value instanceof Number number
                && number.longValue() == number.doubleValue()
                && number.longValue() >= Integer.MIN_VALUE
                && number.longValue() <= Integer.MAX_VALUE) {
            return number.intValue();
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
