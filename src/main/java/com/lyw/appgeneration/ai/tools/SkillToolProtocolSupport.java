package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/** readSkill 协议序列化、严格解析和客户端脱敏。 */
public final class SkillToolProtocolSupport {

    private static final Set<String> FIELDS = Set.of(
            "protocol", "operation", "status", "skillName",
            "message", "failureReason", "content");

    private SkillToolProtocolSupport() {
    }

    public static String json(SkillToolResult result) {
        JSONObject json = new JSONObject(
                JSONConfig.create().setIgnoreNullValue(false));
        json.set("protocol", result.protocol());
        json.set("operation", result.operation());
        json.set("status", result.status().name());
        json.set("skillName", result.skillName());
        json.set("message", result.message());
        json.set("failureReason", result.failureReason());
        json.set("content", result.content());
        return JSONUtil.toJsonStr(json);
    }

    public static SkillToolResult parse(
            String rawResult, String operation, String skillName) {
        ObjectNode json = StrictToolJsonSupport.parseObject(rawResult);
        Set<String> fields = new java.util.HashSet<>();
        json.fieldNames().forEachRemaining(fields::add);
        if (!FIELDS.equals(fields)) {
            throw new IllegalArgumentException("Skill 工具协议字段不完整或包含未知字段");
        }
        String protocol = requiredString(json, "protocol");
        String actualOperation = requiredString(json, "operation");
        String actualSkillName = requiredString(json, "skillName");
        if (!operation.equals(actualOperation) || !skillName.equals(actualSkillName)) {
            throw new IllegalArgumentException("Skill 工具调用标识不匹配");
        }
        return new SkillToolResult(protocol, actualOperation,
                SkillToolResult.Status.valueOf(requiredString(json, "status")),
                actualSkillName, requiredString(json, "message"),
                nullableString(json, "failureReason"), nullableString(json, "content"));
    }

    public static SkillToolResult parse(String rawResult) {
        ObjectNode json = StrictToolJsonSupport.parseObject(rawResult);
        return parse(rawResult, requiredString(json, "operation"),
                requiredString(json, "skillName"));
    }

    public static String clientSafeResult(String rawResult) {
        ObjectNode json = StrictToolJsonSupport.parseObject(rawResult);
        String operation = requiredString(json, "operation");
        String skillName = requiredString(json, "skillName");
        SkillToolResult result = parse(rawResult, operation, skillName);
        return json(new SkillToolResult(
                result.protocol(), result.operation(), result.status(),
                result.skillName(), result.message(), result.failureReason(), null));
    }

    /**
     * 生成不包含 Skill 正文的观测摘要，供服务端日志使用。
     */
    public static String observabilitySummary(SkillToolResult result) {
        String content = result.content();
        int bodyCodePoints = content == null
                ? 0 : content.codePointCount(0, content.length());
        String bodySha256 = content == null ? "-" : sha256(content);
        String failureReason = result.failureReason() == null
                ? "-" : result.failureReason();
        return "status=" + result.status()
                + ",skillName=" + sanitizeLogValue(result.skillName())
                + ",bodyCodePoints=" + bodyCodePoints
                + ",bodySha256=" + bodySha256
                + ",failureReason=" + sanitizeLogValue(failureReason);
    }

    public static String stableSummary(BaseTool tool, String rawResult, String skillName) {
        SkillToolResult result;
        try {
            result = parse(rawResult, tool.getToolName(), skillName);
        } catch (RuntimeException exception) {
            result = SkillToolResult.failed(skillName);
        }
        return "[工具调用] " + tool.getDisplayName() + " " + result.skillName()
                + "（" + statusText(result.status()) + "）";
    }

    private static String requiredString(ObjectNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Skill 工具协议字段必须是非空字符串: " + field);
        }
        return value.textValue();
    }

    private static String nullableString(ObjectNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("Skill 工具协议字段必须是字符串或 null: " + field);
        }
        return value.textValue();
    }

    private static String statusText(SkillToolResult.Status status) {
        return switch (status) {
            case APPLIED -> "已加载";
            case NOT_FOUND -> "未找到";
            case REJECTED -> "已拒绝";
            case FAILED -> "失败";
        };
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private static String sanitizeLogValue(String value) {
        return value == null ? "-" : value.replaceAll("[\\r\\n\\t]", "_");
    }
}
