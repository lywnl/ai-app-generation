package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.model.message.ContextCompressionMessage;
import com.lyw.appgeneration.ai.model.message.IncompleteToolChainRecoveryMessage;
import com.lyw.appgeneration.ai.model.message.ToolProtocolRecoveryMessage;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnProgressChannelTest {

    @Test
    void 合并时先订阅进度且同步压缩事件排在正文前() {
        TurnProgressChannel channel = new TurnProgressChannel();
        AtomicInteger businessSubscriptions = new AtomicInteger();
        Flux<GenerationStreamEvent> business = Flux.defer(() -> {
            businessSubscriptions.incrementAndGet();
            assertTrue(channel.publish(ContextCompressionMessage.started()));
            assertTrue(channel.publish(ContextCompressionMessage.completed()));
            return Flux.just(GenerationStreamEvent.content("正文"));
        });

        StepVerifier.create(channel.mergeWith(business))
                .assertNext(event -> assertCompressionPhase(
                        event, ContextCompressionMessage.Phase.STARTED))
                .assertNext(event -> assertCompressionPhase(
                        event, ContextCompressionMessage.Phase.COMPLETED))
                .assertNext(event -> assertEquals("正文",
                        ((GenerationStreamEvent.Content) event).text()))
                .verifyComplete();

        assertEquals(1, businessSubscriptions.get(), "业务 Flux 不得被二次订阅");
        assertFalse(channel.publish(ContextCompressionMessage.started()),
                "业务完成后进度通道必须关闭");
    }

    @Test
    void 业务错误必须原样终止并关闭进度通道() {
        TurnProgressChannel channel = new TurnProgressChannel();
        IllegalStateException failure = new IllegalStateException("生成失败");

        StepVerifier.create(channel.mergeWith(Flux.error(failure)))
                .expectErrorMatches(error -> error == failure)
                .verify();

        assertFalse(channel.publish(ContextCompressionMessage.completed()));
    }

    @Test
    void 恢复事件必须作为受信控制事件合流而非普通正文() {
        TurnProgressChannel channel = new TurnProgressChannel();
        Flux<GenerationStreamEvent> business = Flux.defer(() -> {
            assertTrue(channel.publish(ToolProtocolRecoveryMessage.started()));
            assertTrue(channel.publish(ToolProtocolRecoveryMessage.recovered()));
            return Flux.just(GenerationStreamEvent.content("正文"));
        });

        StepVerifier.create(channel.mergeWith(business))
                .assertNext(event -> assertEquals(
                        ToolProtocolRecoveryMessage.Phase.STARTED,
                        ((GenerationStreamEvent.ToolProtocolRecovery) event)
                                .message().phase()))
                .assertNext(event -> assertEquals(
                        ToolProtocolRecoveryMessage.Phase.RECOVERED,
                        ((GenerationStreamEvent.ToolProtocolRecovery) event)
                                .message().phase()))
                .expectNext(GenerationStreamEvent.content("正文"))
                .verifyComplete();
    }

    @Test
    void 未完成工具链续行事件必须独立合流且保持顺序() {
        TurnProgressChannel channel = new TurnProgressChannel();
        Flux<GenerationStreamEvent> business = Flux.defer(() -> {
            assertTrue(channel.publish(
                    IncompleteToolChainRecoveryMessage.started()));
            assertTrue(channel.publish(
                    IncompleteToolChainRecoveryMessage.recovered()));
            return Flux.just(GenerationStreamEvent.content("可信正文"));
        });

        StepVerifier.create(channel.mergeWith(business))
                .assertNext(event -> assertEquals(
                        IncompleteToolChainRecoveryMessage.Phase.STARTED,
                        ((GenerationStreamEvent.IncompleteToolChainRecovery)
                                event).message().phase()))
                .assertNext(event -> assertEquals(
                        IncompleteToolChainRecoveryMessage.Phase.RECOVERED,
                        ((GenerationStreamEvent.IncompleteToolChainRecovery)
                                event).message().phase()))
                .expectNext(GenerationStreamEvent.content("可信正文"))
                .verifyComplete();
    }

    @Test
    void 下游取消必须取消业务并关闭进度通道() {
        TurnProgressChannel channel = new TurnProgressChannel();
        AtomicBoolean businessCancelled = new AtomicBoolean();

        StepVerifier.create(channel.mergeWith(
                        Flux.concat(
                                        Flux.<GenerationStreamEvent>just(
                                                GenerationStreamEvent
                                                .content("业务已订阅")),
                                        Flux.never())
                                .doOnCancel(() -> businessCancelled.set(true))))
                .expectNextCount(1)
                .thenCancel()
                .verify();

        assertTrue(businessCancelled.get());
        assertFalse(channel.publish(ContextCompressionMessage.completed()));
    }

    private void assertCompressionPhase(
            GenerationStreamEvent event,
            ContextCompressionMessage.Phase expected) {
        var compression = (GenerationStreamEvent.ContextCompression) event;
        assertEquals(expected, compression.message().phase());
    }
}
