package com.lyw.appgeneration.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/** 仅根据首次需求命名，不接入会话记忆或工具。 */
public interface AiAppNameService {

    @SystemMessage(fromResource = "prompt/app-name-system-prompt.txt")
    String generateAppName(@UserMessage String initPrompt);
}
