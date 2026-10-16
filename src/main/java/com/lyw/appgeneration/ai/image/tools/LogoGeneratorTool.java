package com.lyw.appgeneration.ai.image.tools;

import cn.hutool.core.util.StrUtil;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.lyw.appgeneration.ai.image.model.ImageCategoryEnum;
import com.lyw.appgeneration.ai.image.model.ImageResource;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Logo 图片生成工具
 */
@Slf4j
@Component
public class LogoGeneratorTool {

    @Value("${dashscope.api-key:}")
    private String dashScopeApiKey;

    @Value("${dashscope.image-model:wan2.2-t2i-flash}")
    private String imageModel;

    private final long pollIntervalMs;
    private final long maxErrorBackoffMs;
    private final LogoPollingRateLimiter rateLimiter;
    private final Supplier<LogoImageClient> clientFactory;
    private final LongSupplier nanoTime;
    private final LogoPollingRateLimiter.Sleeper sleeper;

    public LogoGeneratorTool() {
        this(1000, 10);
    }

    @Autowired
    public LogoGeneratorTool(
            @Value("${image.logo.poll-interval-ms:1000}") long pollIntervalMs,
            @Value("${image.logo.max-poll-qps:10}") int maxPollQps) {
        this(pollIntervalMs, maxPollQps, LogoImageClient::dashScope,
                System::nanoTime, TimeUnit.NANOSECONDS::sleep);
    }

    LogoGeneratorTool(long pollIntervalMs, int maxPollQps, Supplier<LogoImageClient> clientFactory,
                      LongSupplier nanoTime, LogoPollingRateLimiter.Sleeper sleeper) {
        if (pollIntervalMs <= 0 || pollIntervalMs > 60_000) {
            throw new IllegalArgumentException("Logo 查询间隔必须在 1 至 60000 毫秒之间");
        }
        this.pollIntervalMs = pollIntervalMs;
        this.maxErrorBackoffMs = Math.max(5000, pollIntervalMs);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.sleeper = Objects.requireNonNull(sleeper);
        this.clientFactory = Objects.requireNonNull(clientFactory);
        this.rateLimiter = new LogoPollingRateLimiter(maxPollQps, nanoTime, sleeper);
    }

    @Tool("根据描述生成 Logo 设计图片，用于网站品牌标识")
    public List<ImageResource> generateLogos(@P("Logo 设计描述，如名称、行业、风格等，尽量详细") String description) {
        return generateLogos(description, () -> false);
    }

    /** 外层取消时停止本地轮询；已提交的云端生成任务不重复创建。 */
    public List<ImageResource> generateLogos(String description, BooleanSupplier cancelled) {
        List<ImageResource> logoList = new ArrayList<>();
        CallTiming timing = new CallTiming();
        long startedAt = nanoTime.getAsLong();
        try {
            checkStopped(cancelled);
            String logoPrompt = String.format("生成 Logo，Logo 中禁止包含任何文字！Logo 介绍：%s", description);
            ImageSynthesisParam param = ImageSynthesisParam.builder()
                    .apiKey(dashScopeApiKey)
                    .model(imageModel)
                    .prompt(logoPrompt)
                    .size("512*512")
                    .n(1)
                    .build();
            LogoImageClient imageSynthesis = clientFactory.get();
            checkStopped(cancelled);
            ImageSynthesisResult task = submitLogo(imageSynthesis, param, timing);
            checkStopped(cancelled);
            ImageSynthesisResult result = awaitLogoResult(imageSynthesis, task, cancelled, timing);
            if (result != null && result.getOutput() != null && result.getOutput().getResults() != null) {
                List<Map<String, String>> results = result.getOutput().getResults();
                for (Map<String, String> imageResult : results) {
                    String imageUrl = imageResult.get("url");
                    if (StrUtil.isNotBlank(imageUrl)) {
                        logoList.add(ImageResource.builder()
                                .category(ImageCategoryEnum.LOGO)
                                .description(description)
                                .url(imageUrl)
                                .build());
                    }
                }
            }
            if (result != null) {
                timing.status = logoList.isEmpty() ? "EMPTY" : "RETURNED";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            timing.status = "CANCELED";
        } catch (CancellationException e) {
            timing.status = "CANCELED";
        } catch (Exception e) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                timing.status = "CANCELED";
            } else {
                timing.status = "FAILED";
                // SDK 异常正文可能含请求内容，只记录固定错误类型和 HTTP 状态。
                log.warn("[Logo耗时] 调用失败,requestId={},taskId={},errorType={},httpStatus={}",
                        timing.requestId, timing.taskId, e.getClass().getSimpleName(), httpStatus(e));
            }
        } finally {
            log.info("[Logo耗时] 生成结束,requestId={},taskId={},submitMs={},pollCount={},retryCount={},"
                            + "queryMs={},intervalWaitMs={},rateWaitMs={},totalMs={},imageCount={},status={}",
                    timing.requestId, timing.taskId, timing.submitMs, timing.pollCount, timing.retryCount,
                    millis(timing.queryNanos), millis(timing.intervalWaitNanos), millis(timing.rateWaitNanos),
                    millis(nanoTime.getAsLong() - startedAt), logoList.size(), timing.status);
        }
        return logoList;
    }

    private ImageSynthesisResult submitLogo(LogoImageClient client, ImageSynthesisParam param, CallTiming timing)
            throws NoApiKeyException {
        long startedAt = nanoTime.getAsLong();
        String status = "FAILED";
        try {
            // 提交阶段不自动重试，避免网络响应丢失时重复创建、重复计费。
            ImageSynthesisResult task = client.asyncCall(param);
            if (task == null || task.getOutput() == null || StrUtil.isBlank(task.getOutput().getTaskId())) {
                throw new IllegalStateException("Logo 提交响应缺少任务 ID");
            }
            timing.taskId = task.getOutput().getTaskId();
            status = taskStatus(task);
            return task;
        } finally {
            timing.submitMs = millis(nanoTime.getAsLong() - startedAt);
            log.info("[Logo耗时] 提交完成,requestId={},taskId={},elapsedMs={},status={}",
                    timing.requestId, timing.taskId, timing.submitMs, status);
        }
    }

    private ImageSynthesisResult awaitLogoResult(LogoImageClient client, ImageSynthesisResult task,
                                                 BooleanSupplier cancelled, CallTiming timing)
            throws InterruptedException, NoApiKeyException {
        ImageSynthesisResult current = task;
        long errorBackoffMs = pollIntervalMs;
        while (true) {
            checkStopped(cancelled);
            String status = taskStatus(current);
            switch (status) {
                case "SUCCEEDED":
                    return current;
                case "PENDING", "RUNNING":
                    break;
                default:
                    timing.status = status;
                    return null;
            }

            long rateStartedAt = nanoTime.getAsLong();
            try {
                rateLimiter.acquire();
            } finally {
                timing.rateWaitNanos += nanoTime.getAsLong() - rateStartedAt;
            }
            checkStopped(cancelled);
            long queryStartedAt = nanoTime.getAsLong();
            timing.pollCount++;
            String queryStatus = "FAILED";
            int responseCode = 0;
            long nextWaitMs = pollIntervalMs;
            boolean retry = false;
            try {
                current = client.fetch(timing.taskId, dashScopeApiKey);
                queryStatus = taskStatus(current);
                responseCode = 200;
                errorBackoffMs = pollIntervalMs;
            } catch (ApiException e) {
                responseCode = httpStatus(e);
                if (!isRetryable(responseCode)) {
                    throw e;
                }
                checkStopped(cancelled);
                retry = true;
                timing.retryCount++;
                errorBackoffMs = Math.min(maxErrorBackoffMs, errorBackoffMs * 2);
                nextWaitMs = errorBackoffMs;
                queryStatus = "RETRYABLE_ERROR";
            } finally {
                long queryNanos = nanoTime.getAsLong() - queryStartedAt;
                timing.queryNanos += queryNanos;
                log.info("[Logo耗时] 查询完成,requestId={},taskId={},pollIndex={},elapsedMs={},"
                                + "httpStatus={},status={}",
                        timing.requestId, timing.taskId, timing.pollCount, millis(queryNanos),
                        responseCode, queryStatus);
            }
            checkStopped(cancelled);
            if (retry || "PENDING".equals(queryStatus) || "RUNNING".equals(queryStatus)) {
                if (retry) {
                    log.info("[Logo耗时] 查询退避,requestId={},taskId={},backoffMs={},httpStatus={}",
                            timing.requestId, timing.taskId, nextWaitMs, responseCode);
                }
                // 正常间隔从上次发起查询时计算，避免每轮额外叠加 HTTP 耗时。
                // 慢查询必须先返回；错误退避则从错误返回后完整等待。
                long waitNanos = TimeUnit.MILLISECONDS.toNanos(nextWaitMs);
                if (!retry) {
                    waitNanos = Math.max(0, waitNanos - (nanoTime.getAsLong() - queryStartedAt));
                }
                long waitStartedAt = nanoTime.getAsLong();
                try {
                    if (waitNanos > 0) {
                        sleeper.sleep(waitNanos);
                    }
                } finally {
                    timing.intervalWaitNanos += nanoTime.getAsLong() - waitStartedAt;
                }
            }
        }
    }

    private void checkStopped(BooleanSupplier cancelled) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Logo 生成等待已中断");
        }
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Logo 生成等待已取消");
        }
    }

    private String taskStatus(ImageSynthesisResult result) {
        if (result == null || result.getOutput() == null || result.getOutput().getTaskStatus() == null) {
            return "INVALID_RESPONSE";
        }
        return switch (result.getOutput().getTaskStatus()) {
            case "PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED", "UNKNOWN" ->
                    result.getOutput().getTaskStatus();
            default -> "INVALID_RESPONSE";
        };
    }

    private int httpStatus(Exception exception) {
        return exception instanceof ApiException apiException && apiException.getStatus() != null
                ? apiException.getStatus().getStatusCode() : 0;
    }

    private boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode == 503 || statusCode == 504;
    }

    private long millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private static final class CallTiming {
        private final String requestId = StrUtil.blankToDefault(
                MDC.get("imageCollectionRequestId"), UUID.randomUUID().toString());
        private String taskId = "-";
        private String status = "FAILED";
        private long submitMs;
        private int pollCount;
        private int retryCount;
        private long queryNanos;
        private long intervalWaitNanos;
        private long rateWaitNanos;
    }
}
