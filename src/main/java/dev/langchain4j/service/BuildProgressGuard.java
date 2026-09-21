package dev.langchain4j.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.ai.plan.BuildBlockDiagnostic;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import dev.langchain4j.data.message.SystemMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 当前用户回合的构建拒绝保护，只消费已经提交的可信工具观察。 */
public final class BuildProgressGuard implements AutoCloseable {
    public static final int MAX_TERMINAL_CODE_POINTS = 1_200;
    public static final String DEFAULT_TERMINAL_MESSAGE = "构建条件仍未满足，本轮已停止。已完成的文件修改和计划会保留。";
    private static final Logger LOG = LoggerFactory.getLogger(BuildProgressGuard.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final long appId;
    private final String turnId;
    private final Set<CallKey> observedCalls = new HashSet<>();
    private final Map<Long, Long> feedbackRequests = new HashMap<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<LogEvent> logs = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private BuildBlockDiagnostic lastDiagnostic;
    private long blockedMutationRevision;
    private int blockedCount;
    private long feedbackSequence;
    private Feedback feedback;
    private BuildBlockDiagnostic feedbackDiagnostic;
    private boolean delivered;
    private boolean stopped;
    private boolean closed;
    private String terminalMessage;

    public BuildProgressGuard(long appId, String turnId) {
        this.appId = appId;
        this.turnId = Objects.requireNonNull(turnId);
    }

    public synchronized Action observe(long generation, String toolId, Observation observation) {
        if (closed || stopped || observation == null || !turnId.equals(observation.turnId())
                || !observedCalls.add(new CallKey(generation, toolId))) return Action.CONTINUE;
        if (observation.realBuild() || isProgress(observation)) {
            resetStage();
            log("progress", generation, toolId);
        }
        if (observation.trustedProgress() && observation.after() != null && observation.after().recoverable()
                && feedback != null && !delivered
                && !observation.after().equals(feedbackDiagnostic)) {
            refreshFeedback(observation.after());
        }
        BuildBlockDiagnostic rejected = observation.rejection();
        if (rejected == null || !rejected.recoverable()) return Action.CONTINUE;
        lastDiagnostic = rejected;
        blockedMutationRevision = observation.mutationRevision();
        blockedCount++;
        if (delivered) {
            stopped = true;
            terminalMessage = FileToolBudgetGuard.prefixByCodePoints(
                    (rejected.reason() == BuildBlockDiagnostic.Reason.CODE_MUTATION_REQUIRED
                            ? "构建错误尚未修复，本轮已停止。" : "计划仍未完成，本轮已停止。")
                            + "已完成的文件修改和计划会保留。\n" + rejected.message(), MAX_TERMINAL_CODE_POINTS);
            log("termination-requested", generation, toolId);
            return Action.TERMINATE;
        }
        if (blockedCount >= 2 && feedback == null) {
            refreshFeedback(rejected);
            log("correction-pending", generation, toolId);
            return Action.CORRECT_NEXT_REQUEST;
        }
        if (feedback != null && !rejected.equals(feedbackDiagnostic)) refreshFeedback(rejected);
        log("blocked", generation, toolId);
        return Action.CONTINUE;
    }

    private boolean isProgress(Observation observation) {
        if (!observation.trustedProgress() || lastDiagnostic == null) return false;
        if (lastDiagnostic.reason() == BuildBlockDiagnostic.Reason.CODE_MUTATION_REQUIRED) {
            return observation.mutationRevision() > blockedMutationRevision;
        }
        if (observation.before() == null || observation.after() == null) return false;
        if (lastDiagnostic.reason() == BuildBlockDiagnostic.Reason.NO_PLAN
                && observation.before().reason() == BuildBlockDiagnostic.Reason.NO_PLAN
                && observation.after().hasNonEmptyPlan()) return true;
        Set<BuildBlockDiagnostic.Blocker> before = new HashSet<>(observation.before().blockerSet());
        Set<BuildBlockDiagnostic.Blocker> after = new HashSet<>(observation.after().blockerSet());
        // fail-open 移除的标记不是模型提交的进展；同次真正解除文件阻塞仍可被识别。
        if (observation.failOpenTransition()) {
            before.removeIf(block -> block.kind() == BuildBlockDiagnostic.Reason.REPLAN_PENDING);
            after.removeIf(block -> block.kind() == BuildBlockDiagnostic.Reason.REPLAN_PENDING);
        }
        Set<BuildBlockDiagnostic.Blocker> baseline = lastDiagnostic.blockerSet();
        Set<BuildBlockDiagnostic.Blocker> current = observation.after().blockerSet();
        return before.size() > after.size() && before.containsAll(after)
                && baseline.size() > current.size() && baseline.containsAll(current);
    }

    /** 非破坏性领取：准备失败、压缩或恢复不能把尚未送达的反馈吞掉。 */
    public synchronized Feedback pendingFeedback() {
        return closed || stopped || delivered ? null : feedback;
    }

    public synchronized void requestStarted(long generation, Feedback ticket) {
        if (!closed && !stopped && !delivered && feedback != null && ticket != null
                && feedback.id() == ticket.id()) {
            feedbackRequests.put(generation, ticket.id());
            log("correction-request-started", generation, null);
        }
    }

    public synchronized void responseAccepted(long generation) {
        if (!closed && !stopped && !delivered && feedback != null
                && Objects.equals(feedbackRequests.get(generation), feedback.id())) {
            delivered = true;
            feedbackRequests.clear();
            log("correction-delivered", generation, null);
        }
    }

    public synchronized int blockedCount() { return blockedCount; }
    public synchronized boolean correctionDelivered() { return delivered; }
    public synchronized String terminalMessage() { return terminalMessage == null ? DEFAULT_TERMINAL_MESSAGE : terminalMessage; }

    private void resetStage() {
        lastDiagnostic = null;
        blockedMutationRevision = 0;
        blockedCount = 0;
        feedback = null;
        feedbackDiagnostic = null;
        delivered = false;
        feedbackRequests.clear();
    }

    private void refreshFeedback(BuildBlockDiagnostic diagnostic) {
        feedbackDiagnostic = diagnostic;
        feedbackRequests.clear();
        feedback = new Feedback(++feedbackSequence, SystemMessage.from(
                "检测到构建请求被反复拒绝，期间没有解除阻塞。\n" + diagnostic.message()
                        + "\n请根据实际原因完成创建计划、必要修订或代码修复后再构建。"
                        + "不要复述或解释本提示。"));
    }

    @Override
    public synchronized void close() {
        closed = true;
        resetStage();
        observedCalls.clear();
        terminalMessage = null;
        logs.clear();
    }

    private void log(String event, long generation, String toolId) {
        logs.add(new LogEvent(event, generation, toolId, lastDiagnostic, blockedCount,
                feedback == null ? null : feedback.id()));
    }

    /** 哈希计算与日志IO不得占用请求控制器的取消锁。 */
    void flushLogs() {
        LogEvent event;
        while ((event = logs.poll()) != null) {
            LOG.info("构建阻塞保护,event={},appId={},turnId={},generation={},toolId={},reason={},fingerprint={},count={},feedbackId={}",
                    event.event(), appId, turnId, event.generation(), event.toolId(),
                    event.diagnostic() == null ? null : event.diagnostic().reason(), fingerprint(event.diagnostic()),
                    event.count(), event.feedbackId());
        }
    }

    private String fingerprint(BuildBlockDiagnostic diagnostic) {
        if (diagnostic == null) return "none";
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes(diagnostic.blockers())));
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("无法生成构建阻塞指纹", exception);
        }
    }

    public enum Action { CONTINUE, CORRECT_NEXT_REQUEST, TERMINATE }
    private record CallKey(long generation, String toolId) { }
    private record LogEvent(String event, long generation, String toolId, BuildBlockDiagnostic diagnostic, int count, Long feedbackId) { }
    public record Feedback(long id, SystemMessage message) { }

    public record Observation(String turnId, BuildBlockDiagnostic before, BuildBlockDiagnostic after,
                              BuildBlockDiagnostic rejection, boolean trustedProgress, boolean realBuild,
                              long mutationRevision, boolean failOpenTransition) {
        public static Observation rejected(String turnId, BuildBlockDiagnostic diagnostic, long revision) {
            return new Observation(turnId, diagnostic, diagnostic, diagnostic, false, false, revision, false);
        }
    }
}
