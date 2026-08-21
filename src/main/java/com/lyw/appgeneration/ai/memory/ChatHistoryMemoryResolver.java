package com.lyw.appgeneration.ai.memory;

import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatHistoryMessageTypeEnum;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 将聊天展示记录解析为允许进入模型上下文的文本。
 */
@Component
public class ChatHistoryMemoryResolver {

    /**
     * 用户行读取原话；AI 行只读取具备合法结果类型的可信投影。
     */
    public Optional<String> resolveModelText(ChatHistory history) {
        if (history == null) {
            return Optional.empty();
        }
        if (ChatHistoryMessageTypeEnum.USER.getValue()
                .equals(history.getMessageType())) {
            return Optional.ofNullable(history.getMessage());
        }
        if (!ChatHistoryMessageTypeEnum.AI.getValue()
                .equals(history.getMessageType())
                || StrUtil.isBlank(history.getMemoryMessage())
                || history.getMemoryOutcome() == null) {
            return Optional.empty();
        }
        return Optional.of(history.getMemoryMessage());
    }

    /**
     * 判断 AI 行能否通过长期偏好处理的排除门。
     */
    public boolean isEligibleForLongTermPreference(ChatHistory aiHistory) {
        if (aiHistory == null
                || !ChatHistoryMessageTypeEnum.AI.getValue()
                .equals(aiHistory.getMessageType())
                || StrUtil.isBlank(aiHistory.getMemoryMessage())) {
            return false;
        }
        ChatMemoryOutcome outcome = aiHistory.getMemoryOutcome();
        return outcome != null
                && outcome != ChatMemoryOutcome.PROTOCOL_ERROR
                && outcome != ChatMemoryOutcome.INCOMPLETE_TOOL_CHAIN
                && outcome != ChatMemoryOutcome.LEGACY_UNVERIFIED;
    }
}
