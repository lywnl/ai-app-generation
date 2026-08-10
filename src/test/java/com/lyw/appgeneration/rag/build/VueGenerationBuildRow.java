package com.lyw.appgeneration.rag.build;

import com.lyw.appgeneration.core.builder.BuildResult;

import java.util.List;

/**
 * 一条真实生成与构建结果。
 */
public record VueGenerationBuildRow(
        VueGenerationBuildCase testCase,
        long appId,
        boolean generationCompleted,
        String selectedSkeletonId,
        List<String> selectedFeatureIds,
        BuildResult buildResult,
        String error
) {

    public VueGenerationBuildRow {
        if (appId <= 0) {
            throw new IllegalArgumentException("appId 必须是正数");
        }
        selectedFeatureIds = selectedFeatureIds == null
                ? List.of()
                : List.copyOf(selectedFeatureIds);
    }

    public boolean successful() {
        return generationCompleted && buildResult != null && buildResult.success();
    }
}
