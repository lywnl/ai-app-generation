package com.lyw.appgeneration.ai;

import dev.langchain4j.service.SystemMessage;

/** Vue 专属三字段联合判断；不修改共用模型的全局响应格式。 */
public interface VueTurnModeRoutingService {

    @SystemMessage(fromResource = "prompt/vue-turn-mode-routing-system-prompt.txt")
    String route(String userPrompt);
}
