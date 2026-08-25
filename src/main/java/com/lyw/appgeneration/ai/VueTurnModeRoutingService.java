package com.lyw.appgeneration.ai;

import com.lyw.appgeneration.core.handler.VueTurnMode;
import dev.langchain4j.service.SystemMessage;

/** 使用独立 ChatModel 对 Vue 回合执行只读或修改模式分类。 */
public interface VueTurnModeRoutingService {

    @SystemMessage(fromResource = "prompt/vue-turn-mode-routing-system-prompt.txt")
    VueTurnMode route(String userPrompt);
}
