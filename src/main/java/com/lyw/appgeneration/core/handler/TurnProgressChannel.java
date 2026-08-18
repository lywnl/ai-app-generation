package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.model.message.ContextCompressionMessage;
import com.lyw.appgeneration.ai.model.message.ToolProtocolRecoveryMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Objects;

/** 将单个生成回合的受信进度事件合并进业务流。 */
public final class TurnProgressChannel {

    private final Sinks.Many<GenerationStreamEvent> sink =
            Sinks.many().unicast().onBackpressureBuffer();
    private boolean closed;

    synchronized boolean publish(ContextCompressionMessage message) {
        Objects.requireNonNull(message, "上下文压缩进度不能为空");
        if (closed) {
            return false;
        }
        return publishEvent(GenerationStreamEvent.contextCompression(message));
    }

    synchronized boolean publish(ToolProtocolRecoveryMessage message) {
        Objects.requireNonNull(message, "工具协议恢复进度不能为空");
        return publishEvent(GenerationStreamEvent.toolProtocolRecovery(message));
    }

    private boolean publishEvent(GenerationStreamEvent event) {
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isSuccess()) {
            return true;
        }
        if (result == Sinks.EmitResult.FAIL_CANCELLED
                || result == Sinks.EmitResult.FAIL_TERMINATED) {
            closed = true;
            return false;
        }
        throw new IllegalStateException("上下文压缩进度提交失败: " + result);
    }

    Flux<GenerationStreamEvent> mergeWith(
            Flux<GenerationStreamEvent> business) {
        Objects.requireNonNull(business, "生成业务流不能为空");
        return Flux.defer(() -> {
            Flux<GenerationStreamEvent> progress = sink.asFlux();
            Flux<GenerationStreamEvent> guardedBusiness = Flux.defer(
                            () -> business)
                    .doFinally(ignored -> close());
            return Flux.merge(progress, guardedBusiness);
        });
    }

    synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        Sinks.EmitResult result = sink.tryEmitComplete();
        if (result.isSuccess()
                || result == Sinks.EmitResult.FAIL_CANCELLED
                || result == Sinks.EmitResult.FAIL_TERMINATED) {
            return;
        }
        throw new IllegalStateException("上下文压缩进度通道关闭失败: " + result);
    }
}
