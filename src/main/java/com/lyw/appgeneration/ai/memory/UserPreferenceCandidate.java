package com.lyw.appgeneration.ai.memory;

import java.util.List;

/** 偏好模型输出经服务端白名单校验后的候选。 */
public record UserPreferenceCandidate(
        String name,
        String content,
        String evidenceType,
        List<Long> turnIds) {

    public UserPreferenceCandidate {
        turnIds = List.copyOf(turnIds);
    }
}
