package com.lyw.appgeneration.ai;

import com.lyw.appgeneration.core.handler.VueTurnMode;
import dev.langchain4j.service.SystemMessage;

/** 使用轻量模型为已通过本地资格门的 Vue 回合做二次确认。 */
public interface VueTurnModeRoutingService {

    @SystemMessage(fromResource = "prompt/vue-turn-mode-routing-system-prompt.txt")
    VueTurnMode route(String userPrompt);
}
