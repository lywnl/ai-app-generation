package com.lyw.appgeneration.config;

import cn.hutool.core.thread.ExecutorBuilder;
import cn.hutool.core.thread.ThreadFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * L1 后台滚动摘要的有界线程池配置。
 *
 * <p>L1 摘要是 best-effort。队列满时拒绝策略<b>必须抛异常</b>(AbortPolicy):
 * 调用方在 catch 中清理 single-flight 的 inFlight 锁并记录安全日志，
 * 本次跳过、下一轮对话结束钩子再触发,绝不阻塞或拖慢主对话流。
 *
 * <p>⚠ 切勿改用 DiscardPolicy / DiscardOldestPolicy:它们静默丢弃被拒任务,
 * 任务体 {@code finally} 不执行 → inFlight 锁无法释放 → 该 appId 的 single-flight
 * 永久卡死(直到重启)、L1 静默停更且无日志。也勿用 CallerRunsPolicy(会在调用线程
 * 同步跑 LLM,阻塞主对话)。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
@Configuration
public class MemorySummarizationExecutorConfig {

    @Bean(name = "memorySummarizationExecutor", destroyMethod = "shutdown")
    public ExecutorService memorySummarizationExecutor() {
        return ExecutorBuilder.create()
                .setCorePoolSize(5)
                .setMaxPoolSize(10)
                .setWorkQueue(new LinkedBlockingQueue<>(200))
                .setThreadFactory(ThreadFactoryBuilder.create()
                        .setNamePrefix("Memory-Sum-")
                        .build())
                // 队列满 → 抛 RejectedExecutionException,由 triggerXxxAsync 的 catch 清理 inFlight 锁;
                // 切勿用 Discard 系(静默丢弃会致 L1 single-flight 永久泄漏),亦勿用 CallerRuns(阻塞主对话)
                .setHandler(new ThreadPoolExecutor.AbortPolicy())
                .build();
    }
}
