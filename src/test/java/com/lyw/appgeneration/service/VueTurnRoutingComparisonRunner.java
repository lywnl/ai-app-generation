package com.lyw.appgeneration.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.ai.VueTurnModeRoutingService;
import com.lyw.appgeneration.core.handler.VueTurnDecision;
import com.lyw.appgeneration.core.handler.VueTurnMode;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** 显式授权后单独运行；不作为常规测试执行，不启动 Spring 或其他外部服务。 */
public final class VueTurnRoutingComparisonRunner {
    public static final int MAX_CALLS = 240;
    private static final ObjectMapper JSON = new ObjectMapper();

    public interface BaselineRouting {
        @SystemMessage(fromResource = "vue-turn-routing/baseline-system-prompt.txt")
        VueTurnMode route(String userPrompt);
    }

    public record Sample(String id, String category, String input, VueTurnMode mode,
                         boolean needRag, boolean needImageSearch, boolean criticalNoMutation) { }
    public record Observation(String id, int round, String variant, VueTurnMode expected,
                              VueTurnMode predicted, Boolean needRag, Boolean needImageSearch,
                              boolean invalidOutput, String failureType, long elapsedMs,
                              String responseHash, String providerModel) { }

    private VueTurnRoutingComparisonRunner() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !"--authorized-240".equals(args[2])) {
            throw new IllegalArgumentException("需要工作区、全新输出目录和显式 --authorized-240；先取得用户费用授权");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        if (!output.startsWith(root.resolve(".codex/enable-multiturn-resource-recall"))) {
            throw new IllegalArgumentException("评测输出必须位于本变更 .codex 目录");
        }
        Files.createDirectory(output);
        var env = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("routing-evaluation",
                new FileSystemResource(root.resolve("src/main/resources/application.yml")))) {
            env.getPropertySources().addLast(source);
        }
        String prefix = "langchain4j.open-ai.routing-chat-model.";
        String model = env.getRequiredProperty(prefix + "model-name");
        Integer maxTokens = env.getRequiredProperty(prefix + "max-tokens", Integer.class);
        Double temperature = env.getRequiredProperty(prefix + "temperature", Double.class);
        ChatModel delegate = OpenAiChatModel.builder()
                .baseUrl(env.getRequiredProperty(prefix + "base-url"))
                .apiKey(env.getRequiredProperty(prefix + "api-key"))
                .modelName(model).maxTokens(maxTokens).temperature(temperature)
                .customHeaders(Map.of("Accept-Encoding", "gzip, deflate"))
                .timeout(Duration.ofSeconds(60)).maxRetries(0)
                .logRequests(false).logResponses(false).build();
        Path casesPath = root.resolve("src/test/resources/vue-turn-routing/cases.json");
        List<Sample> samples = JSON.readValue(Files.readString(casesPath), new TypeReference<>() { });
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("startedAt", Instant.now().toString());
        metadata.put("model", model);
        metadata.put("temperature", temperature);
        metadata.put("maxTokens", maxTokens);
        metadata.put("maxHttpCalls", MAX_CALLS);
        metadata.put("retries", 0);
        metadata.put("casesHash", hash(Files.readString(casesPath)));
        metadata.put("baselinePromptHash", hash(Files.readString(root.resolve(
                "src/test/resources/vue-turn-routing/baseline-system-prompt.txt"))));
        metadata.put("jointPromptHash", hash(Files.readString(root.resolve(
                "src/main/resources/prompt/vue-turn-mode-routing-system-prompt.txt"))));
        Files.writeString(output.resolve("metadata.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(metadata));
        List<Observation> observed = evaluate(delegate, samples, observation -> {
            try {
                Files.writeString(output.resolve("observations.jsonl"), JSON.writeValueAsString(observation) + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception failure) {
                throw new IllegalStateException("评测证据写入失败", failure);
            }
        });
        Map<String, Object> report = summarize(samples, observed);
        Files.writeString(output.resolve("summary.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println(JSON.writeValueAsString(report));
        if (!Boolean.TRUE.equals(report.get("passed"))) System.exit(2);
    }

    public static List<Observation> evaluate(ChatModel delegate, List<Sample> samples,
                                              Consumer<Observation> observer) {
        if (samples.size() != 60 || samples.stream().map(Sample::id).distinct().count() != 60) {
            throw new IllegalArgumentException("必须恰好60条不同ID的固定样例");
        }
        RecordingModel model = new RecordingModel(delegate);
        BaselineRouting baseline = AiServices.builder(BaselineRouting.class).chatModel(model).build();
        VueTurnModeRoutingService joint = AiServices.builder(VueTurnModeRoutingService.class).chatModel(model).build();
        List<Observation> results = new ArrayList<>();
        for (int round = 1; round <= 2; round++) {
            for (Sample sample : samples) {
                for (String variant : List.of("baseline", "joint")) {
                    model.response = null;
                    model.transportFailure = false;
                    long start = System.nanoTime();
                    VueTurnMode predicted = null;
                    VueTurnDecision decision = null;
                    boolean invalid = false;
                    String failureType = "NONE";
                    try {
                        if ("baseline".equals(variant)) predicted = baseline.route(sample.input());
                        else {
                            var parsed = VueTurnDecisionParser.parse(joint.route(sample.input()), false);
                            decision = parsed.decision();
                            predicted = decision.mode();
                            invalid = !parsed.degradationReason().isEmpty();
                        }
                    } catch (RuntimeException failure) {
                        invalid = true;
                        failureType = failure.getClass().getSimpleName();
                    }
                    ChatResponse response = model.response;
                    Observation value = new Observation(sample.id(), round, variant, sample.mode(), predicted,
                            decision == null ? null : decision.needRag(), decision == null ? null : decision.needImageSearch(),
                            invalid, failureType, Math.max(0, (System.nanoTime() - start) / 1_000_000L),
                            response == null ? "" : hash(response.aiMessage().text()),
                            response == null ? "" : Objects.requireNonNullElse(response.metadata().modelName(), ""));
                    results.add(value);
                    observer.accept(value);
                    // 连接或账户失败时停止，不浪费剩余请求；不自动补跑、重试或掩盖不完整证据。
                    if (model.transportFailure) return List.copyOf(results);
                }
            }
        }
        return List.copyOf(results);
    }

    public static Map<String, Object> summarize(List<Sample> samples, List<Observation> rows) {
        Map<String, Sample> byId = new HashMap<>();
        samples.forEach(sample -> byId.put(sample.id(), sample));
        Map<String, Object> report = new LinkedHashMap<>();
        Map<String, Map<VueTurnMode, Long>> modeErrors = new HashMap<>();
        for (String variant : List.of("baseline", "joint")) {
            List<Observation> part = rows.stream().filter(o -> variant.equals(o.variant())).toList();
            Map<VueTurnMode, Long> errors = new EnumMap<>(VueTurnMode.class);
            for (VueTurnMode mode : VueTurnMode.values()) errors.put(mode, part.stream()
                    .filter(o -> o.expected() == mode && o.predicted() != mode).count());
            modeErrors.put(variant, errors);
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("calls", part.size());
            stats.put("modeErrors", errors);
            stats.put("invalidOutputs", part.stream().filter(Observation::invalidOutput).count());
            stats.put("averageMs", part.stream().mapToLong(Observation::elapsedMs).average().orElse(0));
            List<Long> sorted = part.stream().map(Observation::elapsedMs).sorted().toList();
            stats.put("p95Ms", sorted.isEmpty() ? 0 : sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1)));
            if ("joint".equals(variant)) {
                stats.put("ragErrors", part.stream().filter(o -> !Objects.equals(o.needRag(), byId.get(o.id()).needRag())).count());
                stats.put("imageErrors", part.stream().filter(o -> !Objects.equals(o.needImageSearch(), byId.get(o.id()).needImageSearch())).count());
            }
            report.put(variant, stats);
        }
        long criticalRegressions = samples.stream().filter(Sample::criticalNoMutation).filter(sample -> {
            long oldErrors = rows.stream().filter(o -> o.id().equals(sample.id()) && o.variant().equals("baseline")
                    && o.predicted() != sample.mode()).count();
            long newErrors = rows.stream().filter(o -> o.id().equals(sample.id()) && o.variant().equals("joint")
                    && o.predicted() != sample.mode()).count();
            return newErrors > oldErrors;
        }).count();
        Set<String> versions = new TreeSet<>();
        rows.forEach(o -> { if (!o.providerModel().isBlank()) versions.add(o.providerModel()); });
        boolean complete = rows.size() == MAX_CALLS;
        boolean passed = complete && criticalRegressions == 0 && versions.size() == 1
                && Arrays.stream(VueTurnMode.values()).allMatch(mode -> modeErrors.get("joint").get(mode)
                <= modeErrors.get("baseline").get(mode));
        report.put("actualCalls", rows.size());
        report.put("complete", complete);
        report.put("providerModels", versions);
        report.put("criticalRegressions", criticalRegressions);
        report.put("passed", passed);
        return report;
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNullElse(text, "").getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static final class RecordingModel implements ChatModel {
        private final ChatModel delegate;
        private int calls;
        private ChatResponse response;
        private boolean transportFailure;
        private RecordingModel(ChatModel delegate) { this.delegate = delegate; }
        @Override public ChatResponse doChat(ChatRequest request) {
            if (++calls > MAX_CALLS) throw new IllegalStateException("超过已登记模型调用上限");
            try { return response = delegate.chat(request); }
            catch (RuntimeException failure) { transportFailure = true; throw failure; }
        }
    }
}
