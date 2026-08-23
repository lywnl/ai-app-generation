package dev.langchain4j.service;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolArgumentLeakScannerTest {

    private static final String PREFIX = "[[internal.";
    private static final String MARKER = "<internal-ack>";

    @Test
    void 工具扫描嵌套对象数组的全部字符串值但不扫描键() {
        ToolArgumentLeakScanner scanner = scanner();

        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.complete("request-a", "{\"[[internal.key\":\"安全\",\"count\":1}").status());
        assertEquals(ToolArgumentLeakScanner.Status.VIOLATION,
                scanner.complete("nested", "{\"items\":[\"安全\",{\"text\":\"<internal-ack>\"}]}" ).status());
    }

    @Test
    void 不同字符串值不同请求不能拼接成违规() {
        ToolArgumentLeakScanner scanner = scanner();

        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.complete("values", "{\"first\":\"[[inte\",\"second\":\"rnal.\"}").status());
        assertEquals(ToolArgumentLeakScanner.Status.BUFFERING,
                scanner.accept("first-request", "{\"text\":\"[[inte").status());
        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.accept("second-request", "{\"text\":\"rnal.\"").status());
    }

    @Test
    void 完整请求与部分请求累计内容严格一致() {
        ToolArgumentLeakScanner scanner = scanner();
        String complete = "{\"text\":\"安全\"}";

        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.accept("same", "{\"text\":\"安全\"").status());
        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.accept("same", "}").status());
        assertEquals(ToolArgumentLeakScanner.Status.SAFE, scanner.complete("same", complete).status());

        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.accept("different", "{\"text\":\"安全").status());
        assertEquals(ToolArgumentLeakScanner.Status.MISMATCH,
                scanner.complete("different", complete).status());

        assertEquals(ToolArgumentLeakScanner.Status.SAFE, scanner.complete("same", complete).status());
    }

    @Test
    void 部分JSON可在键和值边界切分且多个请求交错完成() {
        ToolArgumentLeakScanner scanner = scanner();
        String first = "{\"first\":\"安全\"}";
        String second = "{\"second\":\"正常\"}";

        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.accept("first", "{\"fir").status());
        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.accept("second", "{\"second\":\"正").status());
        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.accept("first", "st\":\"安").status());
        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.accept("second", "常\"}").status());
        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.accept("first", "全\"}").status());
        assertEquals(ToolArgumentLeakScanner.Status.SAFE, scanner.complete("second", second).status());
        assertEquals(ToolArgumentLeakScanner.Status.SAFE, scanner.complete("first", first).status());
    }

    @Test
    void 控制转义和Unicode转义按解码文本扫描且允许任意切分() {
        ToolArgumentLeakScanner scanner = scanner();

        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.complete("escaped", "{\"text\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"}").status());
        assertEquals(ToolArgumentLeakScanner.Status.BUFFERING,
                scanner.accept("unicode", "{\"text\":\"\\u005b\\u005binte").status());
        assertEquals(ToolArgumentLeakScanner.Status.VIOLATION,
                scanner.accept("unicode", "rnal.\"}").status());
        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.accept("split-escape", "{\"text\":\"\\").status());
        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.accept("split-escape", "u263A\"}").status());
        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.complete("split-escape", "{\"text\":\"\\u263A\"}").status());
        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.accept("split-pair", "{\"text\":\"\\uD83D").status());
        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.accept("split-pair", "\\uDE42\"}").status());
        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.complete("split-pair", "{\"text\":\"\\uD83D\\uDE42\"}").status());
    }

    @Test
    void 未闭合字符串值也立即检测完整泄漏标记且合法字面量前缀继续缓冲() {
        ToolArgumentLeakScanner scanner = scanner();

        assertEquals(ToolArgumentLeakScanner.Status.VIOLATION,
                scanner.accept("leak", "{\"text\":\"<internal-ack>").status());
        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.accept("literal", "{\"ready\":tru").status());
        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.accept("number", "{\"count\":1e").status());
    }

    @Test
    void 仅禁止模式候选要求缓冲且失配后立即恢复安全状态() {
        ToolArgumentLeakScanner scanner = scanner();

        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.accept("safe", "{\"text\":\"普通").status());
        assertEquals(ToolArgumentLeakScanner.Status.BUFFERING,
                scanner.accept("candidate", "{\"text\":\"[[inte").status());
        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.accept("candidate", "rx").status());
    }

    @Test
    void 完成校验拒绝未闭合非法转义和孤立代理项并支持合法代理对() {
        ToolArgumentLeakScanner scanner = scanner();

        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.complete("pair", "{\"text\":\"\\uD83D\\uDE42\"}").status());
        assertEquals(ToolArgumentLeakScanner.Status.INVALID,
                scanner.complete("high", "{\"text\":\"\\uD800\"}").status());
        assertEquals(ToolArgumentLeakScanner.Status.INVALID,
                scanner.complete("low", "{\"text\":\"\\uDC00\"}").status());
        assertEquals(ToolArgumentLeakScanner.Status.INVALID,
                scanner.complete("unclosed", "{\"text\":\"安全").status());
        assertEquals(ToolArgumentLeakScanner.Status.INVALID,
                scanner.complete("escape", "{\"text\":\"\\x\"}").status());
        assertEquals(ToolArgumentLeakScanner.Status.INVALID,
                scanner.complete("number", "{\"count\":1e+-2}").status());
    }

    @Test
    void Unicode转义仅接受ASCII十六进制字符() {
        ToolArgumentLeakScanner scanner = scanner();

        assertEquals(ToolArgumentLeakScanner.Status.INVALID,
                scanner.complete("fullwidth-digit", "{\"text\":\"\\u００５Ｂ\"}").status());
        assertEquals(ToolArgumentLeakScanner.Status.INVALID,
                scanner.complete("fullwidth-letter", "{\"text\":\"\\u00ＡＦ\"}").status());
        assertEquals(ToolArgumentLeakScanner.Status.INVALID,
                scanner.complete("mixed-fullwidth", "{\"text\":\"\\u0Ａ0B\"}").status());
    }

    @Test
    void 丢弃请求后不保留跨请求状态() {
        ToolArgumentLeakScanner scanner = scanner();
        assertEquals(ToolArgumentLeakScanner.Status.BUFFERING,
                scanner.accept("discarded", "{\"text\":\"[[inte").status());
        scanner.discard("discarded");
        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.complete("discarded", "{\"text\":\"安全\"}").status());
    }

    @Test
    void 拒绝空白请求标识和空参数() {
        ToolArgumentLeakScanner scanner = scanner();

        assertEquals(ToolArgumentLeakScanner.Status.INVALID, scanner.accept(null, "{}").status());
        assertEquals(ToolArgumentLeakScanner.Status.INVALID, scanner.accept(" ", "{}").status());
        assertEquals(ToolArgumentLeakScanner.Status.INVALID, scanner.accept("request", null).status());
        assertEquals(ToolArgumentLeakScanner.Status.INVALID, scanner.complete(null, "{}").status());
        assertEquals(ToolArgumentLeakScanner.Status.INVALID, scanner.complete("\t", "{}").status());
        assertEquals(ToolArgumentLeakScanner.Status.INVALID, scanner.complete("request", null).status());
    }

    @Test
    void 完成参数为空时也清理有效请求的累计状态() {
        ToolArgumentLeakScanner scanner = scanner();
        String complete = "{\"text\":\"安全\"}";

        assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                scanner.accept("request", "{\"text\":\"安全").status());
        assertEquals(ToolArgumentLeakScanner.Status.INVALID, scanner.complete("request", null).status());
        assertEquals(ToolArgumentLeakScanner.Status.SAFE, scanner.complete("request", complete).status());
    }

    @Test
    void 并发回调按请求隔离且不发生状态竞争() throws Exception {
        ToolArgumentLeakScanner scanner = scanner();
        ExecutorService executor = Executors.newFixedThreadPool(24);
        CountDownLatch ready = new CountDownLatch(24);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.List<Future<ToolArgumentLeakScanner.Status>> results = new java.util.ArrayList<>();
            for (int index = 0; index < 24; index++) {
                int requestNumber = index;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    String arguments = "{\"text\":\"安全" + requestNumber + "\"}";
                    assertEquals(ToolArgumentLeakScanner.Status.SAFE,
                            scanner.accept("request-" + requestNumber, arguments).status());
                    return scanner.complete("request-" + requestNumber, arguments).status();
                }));
            }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            for (Future<ToolArgumentLeakScanner.Status> result : results) {
                assertEquals(ToolArgumentLeakScanner.Status.SAFE, result.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private ToolArgumentLeakScanner scanner() {
        return new ToolArgumentLeakScanner(PREFIX, Set.of(MARKER));
    }
}
