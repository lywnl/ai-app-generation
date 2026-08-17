package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.ai.model.message.ContextCompressionMessage;
import dev.langchain4j.service.ModelRequestGate;

import java.util.Objects;

/**
 * 将上下文门禁的关键提交与回合终态认领排成可判定的先后顺序。
 *
 * <p>实现必须保证：返回 {@code true} 时 {@code action} 已完整执行，且该执行
 * 原子地先于终态关门；返回 {@code false} 时不得执行 action。调用方可把
 * {@code VueTurnContext.tryRunCallback} 作为实现传入。</p>
 *
 * <p>action 通常只承载快速的启动或结果提交线性化点。唯一例外是最终 L0
 * 原子裁剪：它会在同一个 admission 绝对截止内有界等待本地锁与 Redis，
 * 以保证取消关门、删除 writer permit 和裁剪提交具有明确先后顺序。</p>
 *
 * <p>一次成功提交只证明本次动作先于终态获胜，不是永久通行证。任务 5 的
 * 实际模型请求与任务 6 的完成事件仍必须再次通过同一个真实回调门。</p>
 */
@FunctionalInterface
public interface ContextContinuationGate
        extends ModelRequestGate.ContinuationGate {

    @Override
    boolean tryRun(Runnable action);

    /** 非应用回合调用方保持无操作，真实回合上下文覆盖此提交点。 */
    default void publishContextCompression(
            ContextCompressionMessage message) {
        Objects.requireNonNull(message, "上下文压缩进度不能为空");
    }

    static ContextContinuationGate from(
            ModelRequestGate.ContinuationGate continuationGate) {
        Objects.requireNonNull(continuationGate, "回合原子提交门不能为空");
        if (continuationGate instanceof ContextContinuationGate contextGate) {
            return contextGate;
        }
        return continuationGate::tryRun;
    }

    static ContextContinuationGate alwaysOpen() {
        return action -> {
            Objects.requireNonNull(action, "提交动作不能为空").run();
            return true;
        };
    }
}
