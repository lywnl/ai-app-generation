package com.lyw.appgeneration.core.handler;

import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.memory.L0Snapshot;
import com.lyw.appgeneration.ai.plan.PlanFile;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.ai.tools.PlanToolProtocolSupport;
import com.lyw.appgeneration.ai.tools.PlanToolResult;
import com.lyw.appgeneration.ai.tools.VueToolExecutionFact;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolExecution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 共享现有转录与可信事实，只额外保存有界来源引用及成功计划的原始说明。
 * 普通 AI 正文仍从对应 L0 原消息或已有转录读取，不复制源码／日志转录。
 */
public final class VueDecisionSourceCollector {
    private VueTurnTranscriptAccumulator transcript;
    private final FileToolBudgetGuard.Session budget;
    private final long limit;
    private long retainedCodePoints;
    private final Map<String, SourceReference> sources = new LinkedHashMap<>();
    private final Map<String, ToolReceipt> receipts = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<VueToolExecutionFact> facts = new CopyOnWriteArrayList<>();

    public VueDecisionSourceCollector(FileToolBudgetGuard.Session budget, VueTurnTranscriptAccumulator transcript) {
        this.budget = Objects.requireNonNull(budget);
        this.transcript = transcript;
        limit = budget.canonicalLimitCodePoints();
    }

    public synchronized void sourceCommitted(long generation, String epoch, L0Snapshot.Message message) {
        if (generation <= 0 || epoch == null || message.userHistoryId() <= 0) {
            throw new IllegalArgumentException("决策来源缺少执行代次或持久身份");
        }
        List<String> declared = message.payload() instanceof AiMessage ai && ai.hasToolExecutionRequests()
                ? ai.toolExecutionRequests().stream().map(request -> request.id()).toList() : List.of();
        String resultId = message.payload() instanceof ToolExecutionResultMessage result ? result.id() : null;
        var source = new SourceReference(epoch, message.sequence(), message.userHistoryId(), generation, declared, resultId);
        String id = source.id();
        SourceReference previous = sources.get(id);
        if (previous != null) {
            if (!previous.equals(source)) throw new IllegalStateException("同一来源不能关联不同执行代次");
            return;
        }
        reserve(id + generation + declared + Objects.toString(resultId, ""));
        sources.put(id, source);
    }

    /** 在工具结果提交后、展示队列消费前执行；迟到的展示观察只会幂等命中。 */
    public synchronized void toolCommitted(long generation, ToolExecution execution) {
        String id = execution.request().id();
        String hash = digest(execution.result());
        ToolReceipt old = receipts.get(id);
        if (old != null) {
            if (old.generation() != generation || !old.resultFingerprint().equals(hash)) {
                throw new IllegalStateException("工具来源被不相容的执行结果覆盖");
            }
            return;
        }
        var fact = VueToolExecutionFact.parse(execution.request().name(), execution.result()).orElse(null);
        PlanMaterial plan = null;
        if (fact != null && fact.planVersion() != null && "APPLIED".equals(fact.planStatus())) {
            PlanToolResult result = PlanToolProtocolSupport.parse(execution.result());
            var arguments = JSONUtil.parseObj(execution.request().arguments());
            String reason = "updatePlan".equals(execution.request().name()) ? arguments.getStr("reason") : null;
            plan = new PlanMaterial(result.planId(), result.version(), result.summary(), reason, result.files());
        }
        String material = id + generation + (plan == null ? "" : plan.toString());
        reserve(material);
        receipts.put(id, new ToolReceipt(id, generation, hash, fact, plan));
        if (fact != null) facts.add(fact);
    }

    public synchronized boolean collectedThrough(L0Snapshot snapshot, long userHistoryId, long throughSequence) {
        for (var entry : snapshot.entries()) {
            if (entry.userHistoryId() != userHistoryId || entry.sequence() > throughSequence
                    || entry.payload() instanceof UserMessage) continue;
            var source = sources.get(snapshot.memoryEpoch() + ":" + entry.sequence());
            if (source == null || source.userHistoryId() != userHistoryId) return false;
            if (source.toolResultId() != null && !receipts.containsKey(source.toolResultId())) return false;
        }
        return true;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(Map.copyOf(sources), Map.copyOf(receipts));
    }

    public synchronized VueTurnTranscriptAccumulator transcript() {
        if (transcript == null) transcript = new VueTurnTranscriptAccumulator(budget, VueTurnFinalizer.terminalReserveCodePoints());
        return transcript;
    }
    public List<VueToolExecutionFact> facts() { return facts; }

    private void reserve(String material) {
        long amount = material.codePointCount(0, material.length());
        if (amount > limit - retainedCodePoints) throw new SourceLimitExceededException();
        retainedCodePoints += amount;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256", exception);
        }
    }

    public record SourceReference(String memoryEpoch, long sequence, long userHistoryId, long generation,
                                  List<String> declaredToolIds, String toolResultId) {
        public SourceReference { declaredToolIds = List.copyOf(declaredToolIds); }
        public String id() { return memoryEpoch + ":" + sequence; }
    }
    public record PlanMaterial(String planId, int version, String summary, String reason, List<PlanFile> files) {
        public PlanMaterial { files = List.copyOf(files); }
    }
    public record ToolReceipt(String toolRequestId, long generation, String resultFingerprint,
                               VueToolExecutionFact fact, PlanMaterial plan) { }
    public record Snapshot(Map<String, SourceReference> sources, Map<String, ToolReceipt> tools) {
        public Snapshot { sources = Map.copyOf(sources); tools = Map.copyOf(tools); }
    }
    public static final class SourceLimitExceededException extends IllegalStateException {
        public SourceLimitExceededException() { super("决策来源引用达到现有转录资源上限"); }
    }
}
