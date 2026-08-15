package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.service.ModelRequestGate;

import java.util.Objects;

/**
 * 将上下文门禁的关键提交与回合终态认领排成可判定的先后顺序。
 *
 * <p>实现必须保证：返回 {@code true} 时 {@code action} 已完整执行，且该执行
 * 原子地先于终态关门；返回 {@code false} 时不得执行 action。调用方可把
 * {@code VueTurnContext.tryRunCallback} 作为实现传入。</p>
 *
 * <p>action 只承载快速的启动或结果提交线性化点，压缩模型的长时间等待必须
 * 留在票据外。</p>
 *
 * <p>一次成功提交只证明本次动作先于终态获胜，不是永久通行证。任务 5 的
 * 实际模型请求与任务 6 的完成事件仍必须再次通过同一个真实回调门。</p>
 */
@FunctionalInterface
public interface ContextContinuationGate
        extends ModelRequestGate.ContinuationGate {

    @Override
    boolean tryRun(Runnable action);

    static ContextContinuationGate alwaysOpen() {
        return action -> {
            Objects.requireNonNull(action, "提交动作不能为空").run();
            return true;
        };
    }
}
