package com.lyw.appgeneration.ai.tools;

import java.util.Optional;
import java.util.Set;

/** 从真实工具返回协议中提取的最小可信 Vue 回合事实。 */
public final class VueToolExecutionFact {

    private static final Set<String> FILE_TOOLS = Set.of(
            "readFile", "readDir", "writeFile", "modifyFile", "deleteFile", "exit");

    private final String toolName;
    private final String changedRelativePath;
    private final Integer buildAttempt;

    private VueToolExecutionFact(
            String toolName,
            String changedRelativePath,
            Integer buildAttempt) {
        this.toolName = toolName;
        this.changedRelativePath = changedRelativePath;
        this.buildAttempt = buildAttempt;
    }

    public static Optional<VueToolExecutionFact> parse(
            String toolName, String rawResult) {
        try {
            if ("buildProject".equals(toolName)) {
                return Optional.of(fromBuildResult(
                        BuildProjectProtocolSupport.parse(rawResult)));
            }
            if (!FILE_TOOLS.contains(toolName)) {
                return Optional.empty();
            }
            return Optional.of(fromFileResult(
                    FileToolProtocolSupport.parseTrustedResult(rawResult, toolName)));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static VueToolExecutionFact fromFileResult(FileToolResult result) {
        String changedPath = result.changed() ? result.relativePath() : null;
        return new VueToolExecutionFact(result.operation(), changedPath, null);
    }

    private static VueToolExecutionFact fromBuildResult(
            BuildProjectToolResult result) {
        return new VueToolExecutionFact("buildProject", null, result.attempt());
    }

    public String toolName() {
        return toolName;
    }

    public String changedRelativePath() {
        return changedRelativePath;
    }

    public Integer buildAttempt() {
        return buildAttempt;
    }
}
