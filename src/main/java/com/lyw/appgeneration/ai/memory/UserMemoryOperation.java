package com.lyw.appgeneration.ai.memory;

import java.util.List;

/** 模型提交的操作请求；最终状态、用户归属和数据库 ID 由后端决定。 */
public record UserMemoryOperation(
        Action action, Long targetId, String category, String attribute,
        String content, Scope scope, String evidenceType, List<Long> turnIds) {

    public UserMemoryOperation {
        turnIds = List.copyOf(turnIds);
    }

    public enum Action { ADD, REPLACE, REINFORCE, RETRACT }
    public enum Scope { USER, APP, THREAD }
}
