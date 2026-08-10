package com.lyw.appgeneration.rag.build;

import com.lyw.appgeneration.core.builder.BuildResult;

import java.util.List;

/**
 * 一条真实生成与构建结果。
 */
public record VueGenerationBuildRow(
        VueGenerationBuildCase testCase,
        boolean generationCompleted,
        String selectedSkeletonId,
        List<String> selectedFeatureIds,
        BuildResult buildResult,
        String error
) {

    public VueGenerationBuildRow {
        selectedFeatureIds = selectedFeatureIds == null
                ? List.of()
                : List.copyOf(selectedFeatureIds);
    }

    public boolean successful() {
        return generationCompleted && buildResult != null && buildResult.success();
    }
}
