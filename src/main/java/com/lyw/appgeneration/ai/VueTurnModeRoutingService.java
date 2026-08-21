package com.lyw.appgeneration.ai;

import com.lyw.appgeneration.core.handler.VueTurnMode;
import dev.langchain4j.service.SystemMessage;

/** 使用轻量模型判断当前 Vue 回合是否要求工程变更。 */
public interface VueTurnModeRoutingService {

    @SystemMessage(fromResource = "prompt/vue-turn-mode-routing-system-prompt.txt")
    VueTurnMode route(String userPrompt);
}
