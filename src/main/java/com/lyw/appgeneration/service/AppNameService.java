package com.lyw.appgeneration.service;

import com.lyw.appgeneration.ai.AiAppNameService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** 命名属于可降级的创建辅助步骤，失败不能阻断应用保存。 */
@Service
@Slf4j
public class AppNameService {
    private static final int MAX_NAME_CODE_POINTS = 20;
    private static final int FALLBACK_CODE_POINTS = 12;
    private static final Pattern LABEL_PREFIX = Pattern.compile(
            "(?i)^(?:应用名称|应用名|名称|名字|标题|app\\s*name|name|title)\\s*[:：].*");
    private static final String[][] QUOTES = {{"\"", "\""}, {"'", "'"}, {"“", "”"}, {"‘", "’"}};

    private final AiAppNameService aiAppNameService;

    public AppNameService(AiAppNameService aiAppNameService) {
        this.aiAppNameService = Objects.requireNonNull(aiAppNameService);
    }

    public String generateName(String initPrompt) {
        Objects.requireNonNull(initPrompt, "首次提示词不能为空");
        long started = System.nanoTime();
        String failure = "INTERRUPTED";
        if (!Thread.currentThread().isInterrupted()) {
            try {
                String name = normalizeName(aiAppNameService.generateAppName(initPrompt));
                if (name != null) {
                    recordResult("AI", "NONE", started);
                    return name;
                }
                failure = "INVALID_OUTPUT";
            } catch (RuntimeException exception) {
                // SDK 异常可能含用户输入或响应正文，只记录受控分类。
                failure = classifyFailure(exception);
            }
        }
        recordResult("FALLBACK", failure, started);
        int count = Math.min(FALLBACK_CODE_POINTS, initPrompt.codePointCount(0, initPrompt.length()));
        return initPrompt.substring(0, initPrompt.offsetByCodePoints(0, count));
    }

    private String normalizeName(String raw) {
        if (raw == null || raw.codePoints().anyMatch(this::isForbiddenCharacter)) {
            return null;
        }
        String name = raw.strip();
        for (String[] quote : QUOTES) {
            if (name.length() >= 2 && name.startsWith(quote[0]) && name.endsWith(quote[1])) {
                name = name.substring(1, name.length() - 1).strip();
                break;
            }
        }
        int length = name.codePointCount(0, name.length());
        if (length < 2 || length > MAX_NAME_CODE_POINTS || LABEL_PREFIX.matcher(name).matches()
                || name.matches("^[#*].*") || name.startsWith("- ")) {
            return null;
        }
        return name;
    }

    private boolean isForbiddenCharacter(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint) || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR || type == Character.FORMAT
                || type == Character.SURROGATE || codePoint == '`' || codePoint == '<'
                || codePoint == '>' || codePoint == '{' || codePoint == '}';
    }

    private String classifyFailure(RuntimeException exception) {
        boolean timedOut = false;
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return "INTERRUPTED";
            }
            timedOut |= cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException
                    || cause instanceof TimeoutException;
        }
        return Thread.currentThread().isInterrupted() ? "INTERRUPTED" : timedOut ? "TIMEOUT" : "MODEL_ERROR";
    }

    private void recordResult(String source, String failure, long started) {
        log.info("[应用命名] source={},failureKind={},durationMs={}", source, failure,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
}
