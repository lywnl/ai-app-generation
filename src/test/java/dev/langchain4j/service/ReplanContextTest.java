package dev.langchain4j.service;

import dev.langchain4j.data.message.SystemMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplanContextTest {

    @Test
    void 偏差反馈只能领取一次并在修订后清除待处理状态() {
        ReplanContext context = new ReplanContext(2);
        ReplanContext.PlanDeviation deviation = new ReplanContext.PlanDeviation(
                "trigger-1", "文件路径与计划不一致", "src/router/index.js 不存在");

        assertTrue(context.observe(deviation));
        List<?> feedback = context.claimFeedback();
        assertEquals(1, feedback.size());
        assertTrue(feedback.getFirst() instanceof SystemMessage);
        String feedbackText = ((SystemMessage) feedback.getFirst()).text();
        assertTrue(feedbackText.contains("updatePlan"));
        assertTrue(!feedbackText.contains("[[server."));
        assertFalse(context.replanPending());
        assertTrue(context.claimFeedback().isEmpty());
        context.acknowledgePlanUpdate();
        assertEquals(1, context.feedbackCount());
    }

    @Test
    void 重复触发被去重并在反馈超限后failOpen() {
        ReplanContext context = new ReplanContext(1);
        ReplanContext.PlanDeviation first = new ReplanContext.PlanDeviation(
                "trigger-1", "计划偏差", "证据一");
        ReplanContext.PlanDeviation second = new ReplanContext.PlanDeviation(
                "trigger-2", "第二次偏差", "证据二");

        assertTrue(context.observe(first));
        context.claimFeedback();
        assertFalse(context.observe(first));
        assertFalse(context.observe(second));
        assertTrue(context.failOpen());
        assertFalse(context.replanPending());
    }

    @Test
    void failOpen会通知持久状态清理且修订后允许同一触发标识再次出现() {
        ReplanContext context = new ReplanContext(1);
        java.util.concurrent.atomic.AtomicInteger failOpen =
                new java.util.concurrent.atomic.AtomicInteger();
        context.setFailOpenHandler(failOpen::incrementAndGet);
        ReplanContext.PlanDeviation deviation = new ReplanContext.PlanDeviation(
                "same-trigger", "计划偏差", "证据");

        assertTrue(context.observe(deviation));
        context.claimFeedback();
        assertFalse(context.observe(new ReplanContext.PlanDeviation(
                "second-trigger", "第二次偏差", "证据")));
        assertEquals(1, failOpen.get());

        ReplanContext fresh = new ReplanContext(2);
        assertTrue(fresh.observe(deviation));
        fresh.claimFeedback();
        fresh.acknowledgePlanUpdate();
        assertTrue(fresh.observe(deviation));
    }
}
