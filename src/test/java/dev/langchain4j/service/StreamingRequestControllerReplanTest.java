package dev.langchain4j.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamingRequestControllerReplanTest {

    @Test
    void controller按一次请求边界领取计划反馈() {
        StreamingRequestController controller = new StreamingRequestController();
        ReplanContext context = new ReplanContext();
        controller.bindReplanContext(context);
        controller.observePlanDeviation(new ReplanContext.PlanDeviation(
                "trigger-1", "计划偏差", "证据"));

        assertEquals(1, controller.claimPlanFeedback().size());
        assertEquals(0, controller.claimPlanFeedback().size());
    }
}
