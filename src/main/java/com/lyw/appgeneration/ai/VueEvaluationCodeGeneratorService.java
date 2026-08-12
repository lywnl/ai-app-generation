package com.lyw.appgeneration.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/** 仅供离线 Vue 构建质量评测使用的独立 AI 服务。 */
public interface VueEvaluationCodeGeneratorService {

    @SystemMessage(fromResource = "prompt/codegen-vue-project-evaluation-system-prompt.txt")
    @UserMessage("{{content}}")
    TokenStream generate(@MemoryId long appId, @V("content") String userMessage);
}
