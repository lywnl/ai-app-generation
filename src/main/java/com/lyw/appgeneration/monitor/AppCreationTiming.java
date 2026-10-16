package com.lyw.appgeneration.monitor;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 创建应用的阶段计时，只记录请求标识、固定阶段和异常类型。 */
@Slf4j
public final class AppCreationTiming {

    public static final String REQUEST_ID_KEY = "appCreationRequestId";
    public static final String APP_ID_ATTRIBUTE = AppCreationTiming.class.getName() + ".appId";

    private AppCreationTiming() {
    }

    public static String requestId() {
        String requestId = MDC.get(REQUEST_ID_KEY);
        return requestId == null ? "-" : requestId;
    }

    public static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    public static <T> T measure(String stage, Supplier<T> operation) {
        String requestId = requestId();
        long startedAt = System.nanoTime();
        String status = "FAILED";
        String errorType = "NONE";
        log.info("[应用创建耗时] 阶段开始,requestId={},stage={}", requestId, stage);
        try {
            T result = operation.get();
            status = "RETURNED";
            return result;
        } catch (RuntimeException | Error error) {
            errorType = error.getClass().getSimpleName();
            throw error;
        } finally {
            log.info("[应用创建耗时] 阶段结束,requestId={},stage={},elapsedMs={},status={},errorType={}",
                    requestId, stage, elapsedMillis(startedAt), status, errorType);
        }
    }
}
