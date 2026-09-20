package com.lyw.appgeneration.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.ReplanContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplanDetectorTest {

    @Test
    void 计划外变更会产生一次性偏差() throws Exception {
        Path root = Files.createTempDirectory("replan-detector-");
        AppPlanStateManager manager = new AppPlanStateManager(
                new ObjectMapper(), new PlanStoragePathResolver(root));
        AppPlan plan = new AppPlan(
                "plan-1", "turn-1", "turn-1", 1, 1, PlanMode.FULL,
                "创建页面", List.of(new PlanFile(
                "src/App.vue", "入口", PlanFileAction.MODIFY,
                List.of(), PlanFileState.PENDING)),
                List.of(), PlanStatus.PLANNED);
        manager.save(77, plan, 0, "turn-1");
        ReplanDetector detector = new ReplanDetector(manager, 77, "turn-1");

        String result = "{\"protocol\":\"file-tool/v1\","
                + "\"operation\":\"writeFile\",\"status\":\"APPLIED\","
                + "\"relativePath\":\"src/Other.vue\",\"changed\":true,"
                + "\"message\":\"已写入\",\"failureReason\":null,\"content\":null}";

        ReplanContext.PlanDeviation deviation = detector
                .detect("writeFile", result).orElseThrow();

        assertEquals("out-of-plan:src/Other.vue", deviation.triggerId());
        assertTrue(deviation.reason().contains("计划之外"));
    }

    @Test
    void 成功变更会清除同一路径的连续失败计数() throws Exception {
        Path root = Files.createTempDirectory("replan-failure-reset-");
        AppPlanStateManager manager = new AppPlanStateManager(
                new ObjectMapper(), new PlanStoragePathResolver(root));
        manager.save(78, new AppPlan(
                "plan-1", "turn-1", "turn-1", 1, 1, PlanMode.FULL,
                "创建页面", List.of(new PlanFile(
                "src/App.vue", "入口", PlanFileAction.MODIFY,
                List.of(), PlanFileState.PENDING)), List.of(),
                PlanStatus.PLANNED), 0, "turn-1");
        ReplanDetector detector = new ReplanDetector(manager, 78, "turn-1");
        String failure = "{"
                + "\"protocol\":\"file-tool/v1\",\"operation\":\"writeFile\","
                + "\"status\":\"FAILED\",\"relativePath\":\"src/App.vue\","
                + "\"changed\":false,\"message\":\"失败\",\"failureReason\":null,"
                + "\"content\":null}";
        String success = "{"
                + "\"protocol\":\"file-tool/v1\",\"operation\":\"writeFile\","
                + "\"status\":\"APPLIED\",\"relativePath\":\"src/App.vue\","
                + "\"changed\":true,\"message\":\"成功\",\"failureReason\":null,"
                + "\"content\":null}";

        assertTrue(detector.detect("writeFile", failure).isEmpty());
        assertTrue(detector.detect("writeFile", failure).isEmpty());
        assertTrue(detector.detect("writeFile", failure).isPresent());
        assertTrue(detector.detect("writeFile", success).isEmpty());
        assertTrue(detector.detect("writeFile", failure).isEmpty());
    }
}
