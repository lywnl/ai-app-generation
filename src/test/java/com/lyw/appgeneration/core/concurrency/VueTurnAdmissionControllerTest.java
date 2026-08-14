package com.lyw.appgeneration.core.concurrency;

import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueTurnAdmissionControllerTest {

    @Test
    void 第六十五个活跃回合必须立即拒绝() {
        var controller = controller();
        List<VueTurnAdmissionController.AdmissionPermit> permits =
                new ArrayList<>();
        try {
            for (int index = 0;
                    index < VueTurnAdmissionController.MAX_ACTIVE_TURNS;
                    index++) {
                controller.tryAcquire().ifPresent(permits::add);
            }

            assertEquals(VueTurnAdmissionController.MAX_ACTIVE_TURNS,
                    permits.size());
            assertTrue(controller.tryAcquire().isEmpty());
        } finally {
            permits.forEach(VueTurnAdmissionController.AdmissionPermit::close);
        }
    }

    @Test
    void 许可关闭后可重新准入且重复关闭不得超发() {
        var controller = controller();
        List<VueTurnAdmissionController.AdmissionPermit> permits =
                acquireAll(controller);
        VueTurnAdmissionController.AdmissionPermit released = permits.removeFirst();

        released.close();
        released.close();
        VueTurnAdmissionController.AdmissionPermit replacement = controller
                .tryAcquire().orElseThrow();
        try {
            assertTrue(controller.tryAcquire().isEmpty(),
                    "重复关闭同一许可不得额外增加全局容量");
        } finally {
            replacement.close();
            permits.forEach(VueTurnAdmissionController.AdmissionPermit::close);
        }
    }

    @Test
    void 一百二十八个并发竞争者只能有六十四个成功() throws Exception {
        var controller = controller();
        int competitors = VueTurnAdmissionController.MAX_ACTIVE_TURNS * 2;
        CountDownLatch ready = new CountDownLatch(competitors);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<VueTurnAdmissionController.AdmissionPermit>> futures =
                new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < competitors; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return controller.tryAcquire().orElse(null);
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            List<VueTurnAdmissionController.AdmissionPermit> acquired =
                    new ArrayList<>();
            for (Future<VueTurnAdmissionController.AdmissionPermit> future
                    : futures) {
                VueTurnAdmissionController.AdmissionPermit permit =
                        future.get(2, TimeUnit.SECONDS);
                if (permit != null) {
                    acquired.add(permit);
                }
            }
            assertEquals(VueTurnAdmissionController.MAX_ACTIVE_TURNS,
                    acquired.size());
            acquired.forEach(VueTurnAdmissionController.AdmissionPermit::close);
        } finally {
            start.countDown();
        }
    }

    @Test
    void 控制器不得保存任何业务标识() {
        Set<Class<?>> forbiddenTypes = Set.of(String.class, Long.class,
                long.class, Integer.class, int.class);

        assertTrue(Arrays.stream(VueTurnAdmissionController.class
                        .getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .noneMatch(field -> forbiddenTypes.contains(field.getType())),
                "全局准入控制器只能保存容量与指标依赖，不得保存业务 ID");
    }

    @Test
    void 成功拒绝与首次释放必须各记录一次真实边界指标() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var controller = new VueTurnAdmissionController(
                new VueBuildRepairMetricsCollector(registry));
        List<VueTurnAdmissionController.AdmissionPermit> permits =
                acquireAll(controller);

        assertTrue(controller.tryAcquire().isEmpty());
        VueTurnAdmissionController.AdmissionPermit first = permits.removeFirst();
        first.close();
        first.close();

        assertEquals(VueTurnAdmissionController.MAX_ACTIVE_TURNS,
                registry.get("vue_turn_admissions_total")
                        .tag("result", "acquired").counter().count());
        assertEquals(1.0, registry.get("vue_turn_admissions_total")
                .tag("result", "rejected").counter().count());
        assertEquals(1.0, registry.get("vue_turn_admissions_total")
                .tag("result", "released").counter().count());
        permits.forEach(VueTurnAdmissionController.AdmissionPermit::close);
    }

    private VueTurnAdmissionController controller() {
        return new VueTurnAdmissionController(
                new VueBuildRepairMetricsCollector(new SimpleMeterRegistry()));
    }

    private List<VueTurnAdmissionController.AdmissionPermit> acquireAll(
            VueTurnAdmissionController controller) {
        List<VueTurnAdmissionController.AdmissionPermit> permits =
                new ArrayList<>();
        for (int index = 0;
                index < VueTurnAdmissionController.MAX_ACTIVE_TURNS;
                index++) {
            permits.add(controller.tryAcquire().orElseThrow());
        }
        return permits;
    }
}
