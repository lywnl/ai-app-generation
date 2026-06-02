package com.lyw.appgeneration.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@link MemorySummarizationExecutorConfig} 单测:守护拒绝策略必须"抛异常"。
 *
 * <p>背景:L1/L2 的 {@code triggerXxxAsync} 采用 single-flight——先 {@code inFlight.add(key)}
 * 再 {@code executor.submit(task)},任务体 {@code finally} 里 {@code inFlight.remove(key)}。
 * 若拒绝策略静默丢弃(DiscardPolicy / DiscardOldestPolicy),被拒任务的 {@code finally} 永不执行,
 * {@code inFlight} 锁永久泄漏 → 该 appId/userId 的提炼永久停更(直到重启)且无日志。
 *
 * <p>故拒绝策略必须抛 {@code RejectedExecutionException},交由调用方 catch 清理锁;
 * 也不得用 CallerRunsPolicy(会在对话结束钩子线程同步跑 LLM,阻塞主对话)。本测试防回归。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
class MemorySummarizationExecutorConfigTest {

    @Test
    void rejectionPolicyMustThrowNotSilentlyDiscardNorBlockCaller() {
        ExecutorService executor = new MemorySummarizationExecutorConfig().memorySummarizationExecutor();
        try {
            ThreadPoolExecutor tpe = assertInstanceOf(ThreadPoolExecutor.class, executor,
                    "Hutool ExecutorBuilder 应产出 ThreadPoolExecutor");
            var handler = tpe.getRejectedExecutionHandler();
            // 严禁 Discard 系静默策略:会导致 triggerXxxAsync 的 inFlight 锁无法释放、L1/L2 永久停更
            assertFalse(handler instanceof ThreadPoolExecutor.DiscardPolicy
                            || handler instanceof ThreadPoolExecutor.DiscardOldestPolicy,
                    "拒绝策略不得静默丢弃:被拒任务 finally 不执行 → inFlight 锁永久泄漏");
            // 严禁 CallerRunsPolicy:会在调用线程(对话结束钩子)同步执行提炼,阻塞主对话
            assertFalse(handler instanceof ThreadPoolExecutor.CallerRunsPolicy,
                    "拒绝策略不得用 CallerRunsPolicy:会同步执行提炼,阻塞主对话流");
        } finally {
            executor.shutdownNow();
        }
    }
}
