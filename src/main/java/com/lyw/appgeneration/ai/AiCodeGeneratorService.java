package com.lyw.appgeneration.ai;


import com.lyw.appgeneration.ai.model.HtmlCodeResult;
import com.lyw.appgeneration.ai.model.MultiFileCodeResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import reactor.core.publisher.Flux;

/**
 * AI 代码生成服务
 * <p>
 * 注意:方法级 @UserMessage("{{content}}") + 参数 @V("content") 的写法,
 * 是为了绕开 LangChain4j 把 user message 当模板解析的默认行为。
 * 如果直接写 String userMessage,LangChain4j 会把用户输入里的 {{xxx}}
 * 当占位符去找对应变量,Vue/Angular 模板语法会直接报错。
 * @V 映射是字面量替换,不会二次解析内容。
 */
public interface AiCodeGeneratorService {

    /**
     * 生成 HTML 代码
     *
     * @param userMessage 用户消息
     * @return 生成的代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    @UserMessage("{{content}}")
    HtmlCodeResult generateHtmlCode(@V("content") String userMessage);

    /**
     * 生成多文件代码
     *
     * @param userMessage 用户消息
     * @return 生成的代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    @UserMessage("{{content}}")
    MultiFileCodeResult generateMultiFileCode(@V("content") String userMessage);

    /**
     * 生成 HTML 代码（流式）
     *
     * @param userMessage 用户消息
     * @return 生成的代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    @UserMessage("{{content}}")
    Flux<String> generateHtmlCodeStream(@V("content") String userMessage);

    /**
     * 生成多文件代码（流式）
     *
     * @param userMessage 用户消息
     * @return 生成的代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    @UserMessage("{{content}}")
    Flux<String> generateMultiFileCodeStream(@V("content") String userMessage);

    /**
     * 生成 Vue 项目代码（流式）
     *
     * @param userMessage 用户消息
     * @return 生成过程的流式响应
     */
    @SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
    @UserMessage("{{content}}")
    TokenStream generateVueProjectCodeStream(@MemoryId long appId, @V("content") String userMessage);

}
