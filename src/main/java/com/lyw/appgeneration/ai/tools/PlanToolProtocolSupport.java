package com.lyw.appgeneration.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lyw.appgeneration.ai.plan.PlanFile;
import com.lyw.appgeneration.ai.plan.PlanFileAction;
import com.lyw.appgeneration.ai.plan.PlanFileState;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** 计划工具的严格协议序列化、解析和模型输入转换。 */
public final class PlanToolProtocolSupport {

    private static final Set<String> FIELDS = Set.of(
            "protocol", "operation", "status", "planId",
            "version", "message", "summary", "files");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlanToolProtocolSupport() {
    }

    public static String json(PlanToolResult result) {
        try {
            return MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化计划工具结果失败", exception);
        }
    }

    public static PlanToolResult parse(String rawResult) {
        ObjectNode json = StrictToolJsonSupport.parseObject(rawResult);
        Set<String> fields = new java.util.HashSet<>();
        json.fieldNames().forEachRemaining(fields::add);
        if (!FIELDS.equals(fields)) {
            throw new IllegalArgumentException("计划工具协议字段不完整或包含未知字段");
        }
        String operation = requiredString(json, "operation");
        String protocol = requiredString(json, "protocol");
        PlanToolResult.Status status = PlanToolResult.Status.valueOf(
                requiredString(json, "status"));
        return new PlanToolResult(
                protocol,
                operation,
                status,
                nullableString(json, "planId"),
                nullableInteger(json, "version"),
                requiredString(json, "message"),
                nullableString(json, "summary"),
                files(json.get("files")));
    }

    public static List<PlanFile> parseInputFiles(String rawFiles) {
        if (rawFiles == null || rawFiles.isBlank()) {
            return List.of();
        }
        try {
            PlanFileInput[] inputs = MAPPER.readValue(rawFiles, PlanFileInput[].class);
            return Arrays.stream(inputs)
                    .map(input -> new PlanFile(
                            input.path(), input.purpose(), input.action(),
                            input.dependsOn(), PlanFileState.PENDING))
                    .toList();
        } catch (Exception exception) {
            throw new IllegalArgumentException("计划文件列表不是合法 JSON 数组", exception);
        }
    }

    public static List<String> parseInputPaths(String rawPaths) {
        if (rawPaths == null || rawPaths.isBlank()) {
            return List.of();
        }
        try {
            String[] paths = MAPPER.readValue(rawPaths, String[].class);
            return Arrays.stream(paths).map(String::strip).toList();
        } catch (Exception exception) {
            throw new IllegalArgumentException("计划路径列表不是合法 JSON 数组", exception);
        }
    }

    public static String stableSummary(BaseTool tool, String rawResult) {
        PlanToolResult result;
        try {
            result = parse(rawResult);
        } catch (RuntimeException exception) {
            return "[工具调用] " + tool.getDisplayName() + "（协议错误）";
        }
        StringBuilder summary = new StringBuilder("[工具调用] ")
                .append(tool.getDisplayName()).append("（")
                .append(statusText(result.status())).append("）");
        if (result.status() == PlanToolResult.Status.APPLIED) {
            summary.append("\n计划 version=").append(result.version())
                    .append("：").append(result.summary());
            if (!result.files().isEmpty()) {
                summary.append("\n文件：")
                        .append(result.files().stream()
                                .map(PlanFile::path)
                                .collect(java.util.stream.Collectors.joining(", ")));
            }
        }
        return summary.toString();
    }

    private static List<PlanFile> files(JsonNode value) {
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException("计划工具 files 必须是数组");
        }
        return java.util.stream.StreamSupport.stream(
                        value.spliterator(), false)
                .map(node -> MAPPER.convertValue(node, PlanFile.class))
                .toList();
    }

    private static String requiredString(ObjectNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("计划工具协议字段必须是非空字符串: " + field);
        }
        return value.textValue();
    }

    private static String nullableString(ObjectNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("计划工具协议字段必须是字符串或 null: " + field);
        }
        return value.textValue();
    }

    private static Integer nullableInteger(ObjectNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException("计划工具协议字段必须是整数或 null: " + field);
        }
        return value.intValue();
    }

    private static String statusText(PlanToolResult.Status status) {
        return switch (status) {
            case APPLIED -> "已应用";
            case REJECTED -> "已拒绝";
            case CONFLICT -> "版本冲突";
            case FAILED -> "失败";
        };
    }

    public record PlanFileInput(
            String path,
            String purpose,
            PlanFileAction action,
            List<String> dependsOn) {
    }
}
