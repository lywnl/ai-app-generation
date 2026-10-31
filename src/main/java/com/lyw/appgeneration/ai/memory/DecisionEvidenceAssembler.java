package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.core.handler.VueDecisionSourceCollector;
import dev.langchain4j.data.message.AiMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.lyw.appgeneration.ai.memory.DecisionEvidence.*;

/** 只保存原始表达与可明确关联的事实，把关键决策的提炼留给统一摘要。 */
public final class DecisionEvidenceAssembler {
    public List<Entry> collect(long userHistoryId, L0Snapshot source, long through,
                               VueDecisionSourceCollector.Snapshot observed, DecisionEvidence previous) {
        var entries = new LinkedHashMap<String, Entry>();
        if (previous != null) previous.entries().forEach(entry -> entries.put(entry.sourceId(), entry));
        String userSourceId = "user:" + userHistoryId;
        entries.putIfAbsent(userSourceId, entry(userSourceId, SourceKind.USER, "", 0, List.of(), List.of()));
        for (var item : source.entries()) {
            if (item.userHistoryId() != userHistoryId || item.sequence() > through
                    || !(item.payload() instanceof AiMessage ai) || ai.text() == null || ai.text().isBlank()) continue;
            var reference = observed.sources().get(source.memoryEpoch() + ":" + item.sequence());
            if (reference == null) throw new IllegalStateException("模型原文尚未登记采集来源");
            // 工具调用和本条正文具有明确关联，但不会把同代次的其他建议一并标成已实施。
            var paths = declaredPaths(ai);
            String id = "ai:" + reference.id();
            entries.putIfAbsent(id, entry(id, SourceKind.MODEL_TEXT, ai.text(), reference.generation(), paths, reference.declaredToolIds()));
        }
        for (var receipt : observed.tools().values().stream()
                .sorted(java.util.Comparator.comparingLong(VueDecisionSourceCollector.ToolReceipt::generation)
                        .thenComparing(VueDecisionSourceCollector.ToolReceipt::toolRequestId)).toList()) {
            var ref = resultSource(source, through, observed, receipt.toolRequestId());
            if (receipt.plan() == null || ref == null) continue;
            var plan = receipt.plan();
            String prefix = "plan:" + receipt.toolRequestId();
            List<String> ids = List.of(receipt.toolRequestId());
            var paths = plan.files().stream().map(file -> file.path()).toList();
            entries.putIfAbsent(prefix + ":summary", entry(prefix + ":summary", SourceKind.PLAN_SUMMARY,
                    plan.summary(), receipt.generation(), paths, ids));
            if (plan.reason() != null && !plan.reason().isBlank()) entries.putIfAbsent(prefix + ":reason",
                    entry(prefix + ":reason", SourceKind.PLAN_REASON, plan.reason(), receipt.generation(), paths, ids));
            for (var file : plan.files()) {
                String id = prefix + ":purpose:" + file.path();
                entries.putIfAbsent(id, entry(id, SourceKind.PLAN_FILE_PURPOSE, file.purpose(), receipt.generation(), List.of(file.path()), ids));
            }
        }
        var result = new ArrayList<Entry>();
        for (Entry entry : entries.values()) {
            var changes = new ArrayList<ChangeFact>();
            var builds = new ArrayList<BuildFact>();
            if (entry.kind() != SourceKind.USER) {
                for (var receipt : observed.tools().values()) {
                    var fact = receipt.fact();
                    var ref = resultSource(source, through, observed, receipt.toolRequestId());
                    if (fact == null || ref == null || receipt.generation() < entry.generation()) continue;
                    boolean explicit = entry.kind() == SourceKind.MODEL_TEXT
                            ? entry.sourceToolIds().contains(receipt.toolRequestId())
                            : fact.changedRelativePath() != null && entry.relatedFiles().contains(fact.changedRelativePath());
                    if (explicit && fact.changedRelativePath() != null) changes.add(new ChangeFact(receipt.toolRequestId(),
                            receipt.generation(), ref.sequence(), fact.changedRelativePath(), fact.toolName()));
                    if ("buildProject".equals(fact.toolName()) && fact.buildAttempt() != null && fact.buildAttempt() > 0) {
                        ValidationStatus status = switch (fact.status()) {
                            case SUCCEEDED -> ValidationStatus.SUCCEEDED;
                            case FAILED -> ValidationStatus.FAILED;
                            case TIMED_OUT -> ValidationStatus.TIMED_OUT;
                            default -> ValidationStatus.UNVERIFIED;
                        };
                        if (status != ValidationStatus.UNVERIFIED) builds.add(new BuildFact(receipt.toolRequestId(),
                                receipt.generation(), ref.sequence(), fact.buildAttempt(), status));
                    }
                }
            }
            changes.sort(java.util.Comparator.comparingLong(ChangeFact::generation).thenComparingLong(ChangeFact::sequence));
            builds.sort(java.util.Comparator.comparingLong(BuildFact::generation).thenComparingLong(BuildFact::sequence));
            result.add(new Entry(entry.sourceId(), entry.kind(), entry.text(), entry.generation(), entry.relatedFiles(),
                    entry.sourceToolIds(), changes, builds, 0));
        }
        return List.copyOf(result);
    }

    private VueDecisionSourceCollector.SourceReference resultSource(L0Snapshot source, long through,
                                                                    VueDecisionSourceCollector.Snapshot observed, String toolId) {
        return observed.sources().values().stream().filter(ref -> Objects.equals(ref.toolResultId(), toolId)
                && (!Objects.equals(ref.memoryEpoch(), source.memoryEpoch()) || ref.sequence() <= through)).findFirst().orElse(null);
    }

    private List<String> declaredPaths(AiMessage ai) {
        if (!ai.hasToolExecutionRequests()) return List.of();
        var paths = new ArrayList<String>();
        for (var request : ai.toolExecutionRequests()) {
            if (!List.of("writeFile", "modifyFile", "deleteFile").contains(request.name())) continue;
            try {
                var args = cn.hutool.json.JSONUtil.parseObj(request.arguments());
                for (String key : List.of("relativeFilePath", "relativePath", "path")) {
                    String value = args.getStr(key);
                    if (value != null && !value.isBlank() && value.length() <= 512) { paths.add(value); break; }
                }
            } catch (RuntimeException ignored) {
                // 无法识别的参数不构成实施依据，原工具结果仍由其可信协议负责。
            }
        }
        return paths.stream().distinct().toList();
    }

    private Entry entry(String id, SourceKind kind, String text, long generation, List<String> files, List<String> tools) {
        return new Entry(id, kind, text, generation, files, tools, List.of(), List.of(), 0);
    }
}
