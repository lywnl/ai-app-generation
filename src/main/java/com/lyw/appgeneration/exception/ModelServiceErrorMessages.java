package com.lyw.appgeneration.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.LangChain4jException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

/** 将明确的模型服务错误转换为固定提示，不把提供方响应透传给用户。 */
public final class ModelServiceErrorMessages {

    public static final String INSUFFICIENT_BALANCE_MESSAGE =
            "模型服务余额不足，请充值后重试。";

    private static final int MAX_CAUSE_DEPTH = 32;
    private static final int MAX_PROVIDER_MESSAGE_LENGTH = 16_384;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> BALANCE_CODES = Set.of(
            "insufficientbalance", "insufficientbalanceerror", "balanceinsufficient", "arrearage");
    private static final Set<String> BALANCE_MESSAGES = Set.of(
            "insufficient balance", "insufficient account balance",
            "余额不足", "账户余额不足", "账号余额不足");

    private ModelServiceErrorMessages() {
    }

    public static String messageOrDefault(Throwable failure, String fallback) {
        return isInsufficientBalance(failure) ? INSUFFICIENT_BALANCE_MESSAGE : fallback;
    }

    public static boolean isInsufficientBalance(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH && seen.add(current); depth++) {
            // DeepSeek 的 402 在 SDK 中可能被 InvalidRequestException 等包装。
            if (current instanceof HttpException http && http.statusCode() == 402) {
                return true;
            }
            if (current instanceof LangChain4jException && hasBalanceEvidence(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasBalanceEvidence(String message) {
        if (message == null || message.length() > MAX_PROVIDER_MESSAGE_LENGTH) {
            return false;
        }
        if (isBalanceMessage(message)) {
            return true;
        }
        int objectStart = message.indexOf('{');
        if (objectStart < 0) {
            return false;
        }
        try {
            JsonNode response = JSON.readTree(message.substring(objectStart));
            return response != null && (isBalanceError(response.path("error")) || isBalanceError(response));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isBalanceError(JsonNode error) {
        return error.isObject() && (isBalanceCode(error.path("code").asText())
                || isBalanceCode(error.path("type").asText())
                || isBalanceMessage(error.path("message").asText()));
    }

    private static boolean isBalanceCode(String code) {
        return BALANCE_CODES.contains(normalize(code).replace("_", "").replace("-", ""));
    }

    private static boolean isBalanceMessage(String message) {
        String normalized = normalize(message);
        if (normalized.endsWith(".") || normalized.endsWith("。")
                || normalized.endsWith("!") || normalized.endsWith("！")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return BALANCE_MESSAGES.contains(normalized);
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }
}
