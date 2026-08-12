package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.core.builder.BuildStage;
import com.lyw.appgeneration.core.builder.VueBuildFailureKind;

/** Vue 构建工具协议的显式 JSON 编解码，避免依赖 record Bean 推断。 */
final class BuildProjectProtocolSupport {

    private BuildProjectProtocolSupport() {
    }

    static String json(BuildProjectToolResult result) {
        JSONObject json = new JSONObject();
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
        JSONObject json = JSONUtil.parseObj(rawResult);
        return new BuildProjectToolResult(
                json.getStr("protocol"),
                enumValue(BuildProjectToolResult.BuildInvocationStatus.class,
                        json.getStr("invocationStatus")),
                json.getBool("success"),
                json.getInt("attempt"),
                json.getInt("maxAttempts", 0),
                enumValue(BuildStage.class, json.getStr("stage")),
                enumValue(VueBuildFailureKind.class, json.getStr("failureKind")),
                json.getBool("timedOut"),
                Boolean.TRUE.equals(json.getBool("repairable")),
                Boolean.TRUE.equals(json.getBool("reflectionRequired")),
                enumValue(BuildProjectToolResult.BuildNextAction.class,
                        json.getStr("nextAction")),
                json.getStr("message"),
                json.getStr("errorSummary"),
                Boolean.TRUE.equals(json.getBool("terminateToolLoop")),
                json.getStr("finalResponse"));
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
