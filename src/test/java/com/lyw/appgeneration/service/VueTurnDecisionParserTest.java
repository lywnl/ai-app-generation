package com.lyw.appgeneration.service;

import com.lyw.appgeneration.ai.VueTurnModeRoutingService;
import com.lyw.appgeneration.ai.VueTurnModeRoutingServiceFactory;
import com.lyw.appgeneration.core.handler.VueTurnDecision;
import com.lyw.appgeneration.core.handler.VueTurnMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VueTurnDecisionParserTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "READ_ONLY", "{}", "[]", "null", "{\"mode\":null}",
            "{\"mode\":\"UNKNOWN\"}", "{\"mode\":true}", "{\"mode\":\"READ_ONLY\"} extra",
            "{\"mode\":\"READ_ONLY\"} {}", "{\"mode\":\"READ_ONLY\"", "```json\n{}\n```",
            "{\"mode\":\"READ_ONLY\",\"mode\":\"MUTATION_REQUIRED\"}"})
    void 无可靠完整模式不能猜测(String raw) {
        assertThrows(VueTurnModeRoutingException.class, () -> VueTurnDecisionParser.parse(raw, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"true\"", "1", "[]", "{}"})
    void 资源错误彼此独立(String bad) {
        var rag = VueTurnDecisionParser.parse("{\"mode\":\"MUTATION_REQUIRED\",\"needRag\":"
                + bad + ",\"needImageSearch\":true}", false);
        assertEquals(new VueTurnDecision(VueTurnMode.MUTATION_REQUIRED, false, true), rag.decision());
        assertTrue(rag.degradationReason().contains("needRag="));
        var image = VueTurnDecisionParser.parse("{\"mode\":\"MUTATION_REQUIRED\",\"needRag\":true,"
                + "\"needImageSearch\":" + bad + "}", false);
        assertEquals(new VueTurnDecision(VueTurnMode.MUTATION_REQUIRED, true, false), image.decision());
        assertTrue(image.degradationReason().contains("needImageSearch="));
    }

    @Test
    void 缺失和额外字段不取得权限() {
        var parsed = VueTurnDecisionParser.parse(
                "{\"mode\":\"MUTATION_REQUIRED\",\"needLogoGeneration\":true}", false);
        assertEquals(new VueTurnDecision(VueTurnMode.MUTATION_REQUIRED, false, false), parsed.decision());
        assertTrue(parsed.degradationReason().contains("MISSING"));
    }

    @Test
    void 只读归一但选中元素固定模式优先() {
        String raw = "{\"mode\":\"READ_ONLY\",\"needRag\":true,\"needImageSearch\":true}";
        assertEquals(new VueTurnDecision(VueTurnMode.READ_ONLY, false, false),
                VueTurnDecisionParser.parse(raw, false).decision());
        assertEquals(new VueTurnDecision(VueTurnMode.MUTATION_REQUIRED, true, true),
                VueTurnDecisionParser.parse(raw, true).decision());
    }

    @Test
    void 模型失败保留Cause选中元素仅降级资源且不重试() {
        var factory = mock(VueTurnModeRoutingServiceFactory.class);
        var model = mock(VueTurnModeRoutingService.class);
        when(factory.create()).thenReturn(model);
        var cause = new IllegalStateException("Insufficient Balance");
        when(model.route(anyString())).thenThrow(cause);
        var router = new VueTurnModeRouter(factory);
        assertSame(cause, assertThrows(VueTurnModeRoutingException.class,
                () -> router.decide("创建网站")).getCause());
        assertEquals(new VueTurnDecision(VueTurnMode.MUTATION_REQUIRED, false, false),
                router.decide("选中元素信息：button"));
        verify(model).route("创建网站");
        verify(model).route("选中元素信息：button");
        verifyNoMoreInteractions(model);
    }
}
