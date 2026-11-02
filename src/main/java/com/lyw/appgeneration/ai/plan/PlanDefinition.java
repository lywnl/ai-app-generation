package com.lyw.appgeneration.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 计划定义的稳定身份；执行状态和活动回合绑定不属于定义。 */
public record PlanDefinition(String planId, int version, String summary, List<FileDefinition> files) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public PlanDefinition {
        Objects.requireNonNull(planId);
        Objects.requireNonNull(summary);
        files = List.copyOf(files);
    }

    public static PlanDefinition of(AppPlan plan) {
        return of(plan.planId(), plan.version(), plan.summary(), plan.files());
    }

    public static PlanDefinition of(String planId, int version, String summary, List<PlanFile> files) {
        return new PlanDefinition(planId, version, summary, files.stream()
                .map(file -> new FileDefinition(file.path(), file.purpose(), file.action(), file.dependsOn()))
                .sorted(Comparator.comparing(FileDefinition::path)).toList());
    }

    public Identity identity() {
        try {
            byte[] bytes = JSON.writeValueAsString(this).getBytes(StandardCharsets.UTF_8);
            return new Identity(planId, version, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (Exception failure) {
            throw new IllegalStateException("计划定义指纹计算失败", failure);
        }
    }

    public record FileDefinition(String path, String purpose, PlanFileAction action, List<String> dependsOn) {
        public FileDefinition { dependsOn = dependsOn.stream().sorted().toList(); }
    }

    public record Identity(String planId, int version, String fingerprint) {
        public Identity {
            if (planId == null || planId.isBlank() || version <= 0 || fingerprint == null
                    || !fingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("计划定义身份无效");
            }
        }
    }
}
