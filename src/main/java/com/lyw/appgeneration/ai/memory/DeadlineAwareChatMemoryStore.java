package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.data.message.ChatMessage;

import java.time.Duration;
import java.util.List;

/** 仅向 L0 最终提交暴露的截止感知原子 CAS。 */
public interface DeadlineAwareChatMemoryStore {

    Duration worstCaseCommitDuration();

    DeadlineAwareReplaceResult replaceMessagesIfMatches(
            Object memoryId,
            List<ChatMessage> expected,
            List<ChatMessage> replacement,
            AdmissionDeadline deadline);
}
