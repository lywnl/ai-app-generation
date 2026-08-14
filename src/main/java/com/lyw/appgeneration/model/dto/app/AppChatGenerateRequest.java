package com.lyw.appgeneration.model.dto.app;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;

import java.util.regex.Pattern;

/** 生成代码 POST 请求，应用 ID 保持字符串以避免前端整数精度丢失。 */
public record AppChatGenerateRequest(String appId, String message) {

    public static final int MAX_MESSAGE_CODE_POINTS = 32_000;

    private static final Pattern POSITIVE_DECIMAL =
            Pattern.compile("[0-9]+");

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public static AppChatGenerateRequest fromJson(
            @JsonProperty("appId") JsonNode appId,
            @JsonProperty("message") String message) {
        if (appId == null || !appId.isTextual()) {
            throw paramsError("应用ID必须是字符串");
        }
        return new AppChatGenerateRequest(appId.textValue(), message);
    }

    public long requireAppId() {
        if (appId == null || !POSITIVE_DECIMAL.matcher(appId).matches()) {
            throw paramsError("应用ID无效");
        }
        try {
            long parsed = Long.parseLong(appId);
            if (parsed <= 0L) {
                throw paramsError("应用ID无效");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR, "应用ID超出有效范围");
        }
    }

    public String requireMessage() {
        if (message == null || message.isBlank()) {
            throw paramsError("用户消息不能为空");
        }
        int codePoints = message.codePointCount(0, message.length());
        if (codePoints > MAX_MESSAGE_CODE_POINTS) {
            throw paramsError("用户消息不能超过32000个字符");
        }
        return message;
    }

    private static BusinessException paramsError(String message) {
        return new BusinessException(ErrorCode.PARAMS_ERROR, message);
    }
}
