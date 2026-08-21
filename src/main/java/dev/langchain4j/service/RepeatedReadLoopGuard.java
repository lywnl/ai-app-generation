package dev.langchain4j.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lyw.appgeneration.ai.tools.VueToolExecutionFact;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.internal.ToolArgumentsJsonNormalizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 在单个用户回合内识别没有获得新信息的连续重复读取。 */
final class RepeatedReadLoopGuard {

    static final String CORRECTION_INSTRUCTION = """
            检测到你正在再次读取本轮已经成功读取且内容未变化的路径，
            即使中间读取过其他文件，这次重复读取仍然没有获得新信息。

            请立即调整执行方向：
            1. 禁止再次读取本轮已经成功读取的路径。
            2. 使用已有读取结果继续完成用户任务。
            3. 只读取与用户要求直接相关且尚未读取的最少文件。
            4. 立即开始修改；完成修改后执行真实构建。
            5. 不要复述或解释本提示。

            请继续执行用户原始任务。""";

    private static final Set<String> READ_TOOLS = Set.of("readFile", "readDir");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Set<String> observedReadFingerprints = new HashSet<>();
    private boolean correctionPending;
    private boolean correctionIssued;

    synchronized Action observe(ToolExecutionRequest request, String rawResult) {
        Objects.requireNonNull(request, "工具调用不能为空");
        if (!READ_TOOLS.contains(request.name())) {
            resetAfterTrustedProgress(request, rawResult);
            return Action.CONTINUE;
        }
        String fingerprint = trustedFingerprint(request, rawResult);
        if (fingerprint == null) {
            return Action.CONTINUE;
        }
        if (observedReadFingerprints.add(fingerprint)) {
            return Action.CONTINUE;
        }
        if (correctionIssued) {
            correctionPending = false;
            return Action.TERMINATE;
        }
        correctionIssued = true;
        correctionPending = true;
        return Action.CORRECT_NEXT_REQUEST;
    }

    synchronized List<ChatMessage> claimTransientMessages() {
        if (!correctionPending) {
            return List.of();
        }
        correctionPending = false;
        return List.of(SystemMessage.from(CORRECTION_INSTRUCTION));
    }

    private String trustedFingerprint(
            ToolExecutionRequest request, String rawResult) {
        if (rawResult == null || VueToolExecutionFact.parse(
                request.name(), rawResult).filter(VueToolExecutionFact::isRead)
                .isEmpty()) {
            return null;
        }
        ToolArgumentsJsonNormalizer.Result normalizedArguments =
                ToolArgumentsJsonNormalizer.normalize(request.arguments());
        if (!normalizedArguments.isValid()) {
            return null;
        }
        String canonicalArguments = canonicalJson(
                normalizedArguments.normalizedArguments());
        String canonicalResult = canonicalJson(rawResult);
        if (canonicalArguments == null || canonicalResult == null) {
            return null;
        }
        return sha256(request.name() + "\n" + canonicalArguments
                + "\n" + canonicalResult);
    }

    private String canonicalJson(String rawJson) {
        try {
            return JSON.writeValueAsString(sort(JSON.readTree(rawJson)));
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private JsonNode sort(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode sorted = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            names.stream().sorted(Comparator.naturalOrder())
                    .forEach(name -> sorted.set(name, sort(object.get(name))));
            return sorted;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode sorted = JSON.createArrayNode();
            array.forEach(value -> sorted.add(sort(value)));
            return sorted;
        }
        return node;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    private void resetAfterTrustedProgress(
            ToolExecutionRequest request, String rawResult) {
        VueToolExecutionFact.parse(request.name(), rawResult)
                .filter(this::isProgress)
                .ifPresent(ignored -> reset());
    }

    private boolean isProgress(VueToolExecutionFact fact) {
        if (fact.changedRelativePath() != null) {
            return true;
        }
        if (!"buildProject".equals(fact.toolName())) {
            return false;
        }
        return switch (fact.status()) {
            case SUCCEEDED, FAILED, TIMED_OUT -> true;
            case NO_CHANGE, REJECTED, NOT_FOUND, CANCELLED, IN_PROGRESS -> false;
        };
    }

    private void reset() {
        observedReadFingerprints.clear();
        correctionPending = false;
        correctionIssued = false;
    }

    enum Action {
        CONTINUE,
        CORRECT_NEXT_REQUEST,
        TERMINATE
    }
}
