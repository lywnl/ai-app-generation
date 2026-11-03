package com.lyw.appgeneration.service.rag;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.ai.plan.PlanStoragePathResolver;
import com.lyw.appgeneration.core.builder.VueProjectBuilder;
import com.lyw.appgeneration.service.rag.model.CurrentVueProject;
import com.lyw.appgeneration.utils.SecureFileAccess;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** 使用已有安全目录句柄，只读本应用 package.json，不解析脚本或执行工程代码。 */
@Component
public final class CurrentVueProjectReader {
    public static final int MAX_PACKAGE_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlanStoragePathResolver paths;

    public CurrentVueProjectReader(PlanStoragePathResolver paths) { this.paths = paths; }

    public CurrentVueProject read(long appId) { return read(appId, () -> true); }

    public CurrentVueProject read(long appId, BooleanSupplier active) {
        if (!active.getAsBoolean()) return CurrentVueProject.unavailable("DEADLINE_OR_CANCELLED");
        try {
            return paths.withExistingProjectDirectory(appId, directory -> {
                if (!active.getAsBoolean()) return CurrentVueProject.unavailable("DEADLINE_OR_CANCELLED");
                try (var channel = SecureFileAccess.openRegularFile(directory, Path.of("package.json"))) {
                    if (channel.size() > MAX_PACKAGE_BYTES) return CurrentVueProject.unavailable("PACKAGE_TOO_LARGE");
                    ByteBuffer bytes = ByteBuffer.allocate(MAX_PACKAGE_BYTES + 1);
                    while (bytes.hasRemaining() && channel.read(bytes) != -1) {
                        if (!active.getAsBoolean()) return CurrentVueProject.unavailable("DEADLINE_OR_CANCELLED");
                    }
                    if (bytes.position() > MAX_PACKAGE_BYTES) return CurrentVueProject.unavailable("PACKAGE_TOO_LARGE");
                    bytes.flip();
                    String json = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT).decode(bytes).toString();
                    JsonNode root = JSON.readTree(json);
                    if (root == null || !root.isObject()) return CurrentVueProject.unavailable("INVALID_PACKAGE");
                    if (VueProjectBuilder.dependencyContractViolation(root) != null) {
                        return CurrentVueProject.unavailable("UNTRUSTED_DEPENDENCY_CONTRACT");
                    }
                    for (String name : VueProjectBuilder.forbiddenDependencyResolutionFiles()) {
                        if (!active.getAsBoolean()) return CurrentVueProject.unavailable("DEADLINE_OR_CANCELLED");
                        try {
                            directory.getFileAttributeView(Path.of(name), java.nio.file.attribute.BasicFileAttributeView.class,
                                    java.nio.file.LinkOption.NOFOLLOW_LINKS).readAttributes();
                            return CurrentVueProject.unavailable("UNTRUSTED_DEPENDENCY_RESOLUTION");
                        } catch (NoSuchFileException absent) { /* 只检查存在性，不读取文件内容。 */ }
                    }
                    Map<String, String> runtime = dependencies(root.get("dependencies"),
                            VueProjectBuilder.trustedRuntimeDependencies());
                    Map<String, String> development = dependencies(root.get("devDependencies"),
                            VueProjectBuilder.trustedDevelopmentDependencies());
                    if (runtime == null || development == null || !runtime.containsKey("vue")
                            || !development.containsKey("vite")) return CurrentVueProject.unavailable("UNKNOWN_DEPENDENCIES");
                    if (!active.getAsBoolean()) return CurrentVueProject.unavailable("DEADLINE_OR_CANCELLED");
                    return new CurrentVueProject(CurrentVueProject.Status.AVAILABLE, runtime, development, "NONE");
                } catch (NoSuchFileException missing) {
                    if (!active.getAsBoolean()) return CurrentVueProject.unavailable("DEADLINE_OR_CANCELLED");
                    // 计划初始化会创建空目录；只有安全句柄确认完全为空时才仍视作新工程。
                    // 任意已有文件/子目录（含计划）都保持保守保护，不能因清单缺失重建。
                    if (!directory.iterator().hasNext()) {
                        return new CurrentVueProject(CurrentVueProject.Status.ABSENT,
                                Map.of(), Map.of(), "EMPTY_PROJECT_DIRECTORY");
                    }
                    return CurrentVueProject.unavailable("PACKAGE_MISSING");
                } catch (Exception invalid) {
                    return CurrentVueProject.unavailable("INVALID_OR_UNSAFE_PACKAGE");
                }
            }).orElseGet(() -> new CurrentVueProject(CurrentVueProject.Status.ABSENT, Map.of(), Map.of(), "PROJECT_ABSENT"));
        } catch (RuntimeException unsafe) {
            return CurrentVueProject.unavailable("UNSAFE_PROJECT_PATH");
        }
    }

    private Map<String, String> dependencies(JsonNode values, Map<String, String> allowed) {
        if (values == null || !values.isObject()) return null;
        Map<String, String> result = new LinkedHashMap<>();
        var fields = values.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!allowed.containsKey(field.getKey()) || !field.getValue().isTextual()
                    || field.getValue().textValue().isBlank()) return null;
            result.put(field.getKey(), field.getValue().textValue());
        }
        return result;
    }
}
