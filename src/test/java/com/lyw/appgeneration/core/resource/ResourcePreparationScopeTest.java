package com.lyw.appgeneration.core.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ResourcePreparationScopeTest {
    @Test
    void 同一截止冻结已完成部分拒绝迟到且不因第一个慢漏掉后面() {
        AtomicLong time = new AtomicLong();
        try (var scope = new ResourcePreparationScope(Duration.ofSeconds(8), () -> true, time::get, "test")) {
            var slow = scope.slot("rag");
            var fast = scope.slot("images");
            assertTrue(fast.publish("已完成的图片", 3, 1));
            fast.finish(ResourcePreparationScope.Outcome.SUCCEEDED);
            time.set(Duration.ofSeconds(8).toNanos());
            var result = scope.awaitAndFreeze();
            assertEquals(1, result.size());
            assertEquals("images", result.getFirst().stage());
            assertFalse(slow.publish("迟到RAG", 2, 0));
            assertFalse(fast.publish("迟到图片", 6, 2));
            assertEquals("已完成的图片", result.getFirst().text());
        }
    }
    @Test
    void 取消唤醒等待且注销的旧回调不执行() throws Exception {
        try (var scope = new ResourcePreparationScope(Duration.ofSeconds(8), () -> true, System::nanoTime, "test");
             var waiter = Executors.newSingleThreadExecutor()) {
            scope.slot("images").publish("已完成部分", 1, 1);
            AtomicInteger closed = new AtomicInteger();
            var registration = scope.onClose(closed::incrementAndGet);
            registration.close();
            var result = waiter.submit(scope::awaitAndFreeze);
            scope.cancel();
            assertTrue(result.get(1, TimeUnit.SECONDS).isEmpty());
            assertEquals(0, closed.get());
        }
    }
    @Test
    void 排队耗尽同一预算并清理排队任务且饱和不扩池() throws Exception {
        AtomicLong time = new AtomicLong();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        try (pool; var scope = new ResourcePreparationScope(Duration.ofSeconds(8), () -> true, time::get, "test")) {
            pool.execute(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            AtomicInteger calls = new AtomicInteger();
            var queued = scope.submit(pool, "queued", calls::incrementAndGet);
            var rejected = scope.submit(pool, "rejected", calls::incrementAndGet);
            assertTrue(rejected.isCompletedExceptionally());
            time.set(Duration.ofSeconds(8).toNanos());
            scope.close();
            assertTrue(queued.isCancelled());
            assertEquals(0, pool.getQueue().size());
            release.countDown();
            assertEquals(0, calls.get());
            assertEquals(1, pool.getMaximumPoolSize());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
