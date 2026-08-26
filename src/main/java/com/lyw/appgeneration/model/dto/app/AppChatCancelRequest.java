package com.lyw.appgeneration.model.dto.app;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;

import java.util.regex.Pattern;

/** 停止生成请求；ID 均使用字符串承载，避免前端整数精度丢失。 */
public record AppChatCancelRequest(String appId, String generationId) {

    private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[0-9]+");

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public static AppChatCancelRequest fromJson(
            @JsonProperty("appId") JsonNode appId,
            @JsonProperty("generationId") String generationId) {
        if (appId == null || !appId.isTextual()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "应用ID必须是字符串");
        }
        return new AppChatCancelRequest(appId.textValue(), generationId);
    }

    public long requireAppId() {
        if (appId == null || !POSITIVE_DECIMAL.matcher(appId).matches()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用ID无效");
        }
        try {
            long parsed = Long.parseLong(appId);
            if (parsed <= 0L) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用ID无效");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "应用ID超出有效范围");
        }
    }

    public String requireGenerationId() {
        if (generationId == null || !generationId.matches(
                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "生成任务ID格式无效");
        }
        return generationId;
    }
}
