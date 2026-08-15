package com.lyw.appgeneration.ai.memory;

import java.util.List;
import java.util.Objects;

/** 完整回合按 Token 反向选择后的保留与压缩边界。 */
public record RetentionSelection(List<ConversationTurn> retained,
                                 List<ConversationTurn> compressible,
                                 long summarizeThroughId,
                                 int retainedTokens) {

    public RetentionSelection {
        retained = List.copyOf(Objects.requireNonNull(
                retained, "保留回合不能为空"));
        compressible = List.copyOf(Objects.requireNonNull(
                compressible, "可压缩回合不能为空"));
        if (summarizeThroughId < 0L || retainedTokens < 0) {
            throw new IllegalArgumentException("回合选择结果不能为负数");
        }
    }
}
